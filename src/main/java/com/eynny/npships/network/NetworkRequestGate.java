package com.eynny.npships.network;

import com.eynny.npships.NPShips;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.WeakHashMap;

/** Small admission gate before network requests can enqueue server-thread work. */
@EventBusSubscriber(modid = NPShips.MOD_ID)
final class NetworkRequestGate {
    enum Kind {
        SETTINGS(4, 50_000_000L),
        SETTINGS_PAGE(4, 50_000_000L),
        PATCH_POLICY(8, 25_000_000L),
        CATALOGUE_REFRESH(1, 5_000_000_000L),
        WEATHER(2, 1_000_000_000L),
        INFO(4, 250_000_000L);
        final int capacity;
        final long refill;

        Kind(int capacity, long refill) {
            this.capacity = capacity;
            this.refill = refill;
        }
    }

    private static final int MAX_PENDING = 16;
    private static final WeakHashMap<Object, Peer> PEERS = new WeakHashMap<>();
    private static int pending;
    private static long epoch;

    private static final class Peer {
        final long[] last = new long[Kind.values().length];
        final int[] tokens = new int[Kind.values().length];
        boolean pending, worldQueued;
        long worldTicket;
        Runnable latestWorld;

        Peer(long now) {
            for (var kind : Kind.values()) {
                last[kind.ordinal()] = now;
                tokens[kind.ordinal()] = kind.capacity;
            }
        }
    }

    static final class Lease implements AutoCloseable {
        private final Peer peer;
        private final long ownerEpoch;
        private boolean closed;

        Lease(Peer peer) {
            this.peer = peer;
            ownerEpoch = epoch;
        }

        boolean active() {
            synchronized (PEERS) {
                return !closed && ownerEpoch == epoch;
            }
        }

        boolean currentEpoch() {
            synchronized (PEERS) {
                return ownerEpoch == epoch;
            }
        }

        @Override
        public void close() {
            synchronized (PEERS) {
                if (closed) return;
                closed = true;
                if (ownerEpoch != epoch) return;
                peer.pending = false;
                pending--;
            }
        }
    }

    // The clock is injected only at this local arithmetic boundary; wire data never supplies it.
    static Lease acquire(Object connection, Kind kind, long now) {
        synchronized (PEERS) {
            var peer = PEERS.computeIfAbsent(connection, unused -> new Peer(now));
            int slot = kind.ordinal();
            long elapsed = now - peer.last[slot];
            if (elapsed >= kind.refill) {
                long refill = elapsed / kind.refill;
                peer.tokens[slot] = (int) Math.min(kind.capacity, peer.tokens[slot] + refill);
                peer.last[slot] = now - elapsed % kind.refill;
            }
            if (peer.pending || pending >= MAX_PENDING || peer.tokens[slot] == 0) return null;
            peer.tokens[slot]--;
            peer.pending = true;
            pending++;
            return new Lease(peer);
        }
    }

    static boolean submit(IPayloadContext context, Kind kind, Runnable action) {
        if (!context.connection().isConnected()) return false;
        var lease = acquire(context.connection(), kind, System.nanoTime());
        if (lease == null) return false;
        try {
            context.enqueueWork(
                            () -> {
                                try {
                                    if (lease.active() && context.connection().isConnected())
                                        action.run();
                                } finally {
                                    lease.close();
                                }
                            })
                    .whenComplete((unused, error) -> completed(lease, kind, error));
            return true;
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
    }

    /**
     * Release admission after producing the reply, before a fast peer can request its next page.
     */
    static boolean reply(
            IPayloadContext context,
            Kind kind,
            java.util.function.Supplier<
                            ? extends
                                    net.minecraft.network.protocol.common.custom
                                            .CustomPacketPayload>
                    response) {
        if (!context.connection().isConnected()) return false;
        var lease = acquire(context.connection(), kind, System.nanoTime());
        if (lease == null) return false;
        try {
            context.enqueueWork(
                            () -> {
                                try {
                                    if (!lease.active() || !context.connection().isConnected())
                                        return;
                                    var packet = response.get();
                                    lease.close();
                                    if (packet != null
                                            && lease.currentEpoch()
                                            && context.connection().isConnected())
                                        context.reply(packet);
                                } finally {
                                    lease.close();
                                }
                            })
                    .whenComplete((unused, error) -> completed(lease, kind, error));
            return true;
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
    }

    private static void completed(Lease lease, Kind kind, Throwable error) {
        lease.close();
        // Native ServerPayloadContext.guard logs and normalizes its own failures. Contexts that
        // expose an exceptional future still need cleanup and a visible failure diagnostic.
        if (error != null)
            com.mojang.logging.LogUtils.getLogger()
                    .warn("[NPShips network] Request {} failed", kind, error);
    }

    /**
     * O(1) world-identity notices are latest-wins, not expensive requests. One queued delivery per
     * actual play connection prevents a rapid legitimate world change from losing its last nonce.
     */
    static boolean submitLatestWorld(IPayloadContext context, Runnable action) {
        if (!context.connection().isConnected()) return false;
        final Peer peer;
        final long ownerEpoch, ticket;
        synchronized (PEERS) {
            peer =
                    PEERS.computeIfAbsent(
                            context.connection(), unused -> new Peer(System.nanoTime()));
            peer.latestWorld = action;
            if (peer.worldQueued) return true;
            peer.worldQueued = true;
            ticket = ++peer.worldTicket;
            ownerEpoch = epoch;
        }
        try {
            context.enqueueWork(
                            () -> {
                                final Runnable latest;
                                synchronized (PEERS) {
                                    if (ownerEpoch != epoch
                                            || ticket != peer.worldTicket
                                            || !peer.worldQueued) return;
                                    latest = peer.latestWorld;
                                    peer.latestWorld = null;
                                    // Clear before execution: a concurrent NETWORK arrival owns
                                    // its next delivery; there is no rescheduling loop or ticker.
                                    peer.worldQueued = false;
                                }
                                if (context.connection().isConnected() && latest != null)
                                    latest.run();
                            })
                    .whenComplete(
                            (unused, error) -> {
                                clearWorld(peer, ownerEpoch, ticket);
                                if (error != null)
                                    com.mojang.logging.LogUtils.getLogger()
                                            .warn(
                                                    "[NPShips network] World identity notice failed",
                                                    error);
                            });
            return true;
        } catch (RuntimeException error) {
            clearWorld(peer, ownerEpoch, ticket);
            throw error;
        }
    }

    private static void clearWorld(Peer peer, long ownerEpoch, long ticket) {
        synchronized (PEERS) {
            if (ownerEpoch != epoch || ticket != peer.worldTicket) return;
            peer.latestWorld = null;
            peer.worldQueued = false;
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        clear();
    }

    static void clear() {
        synchronized (PEERS) {
            epoch++;
            pending = 0;
            for (var peer : PEERS.values()) {
                peer.latestWorld = null;
                peer.worldQueued = false;
            }
            PEERS.clear();
        }
    }
}
