package com.eynny.npships.naturalspawn;

import com.mojang.serialization.Codec;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelTicketsSavedData;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

/** Temporary physical-body protection, separate from NPShips persistent despawn policy. */
public final class NaturalSpawnPhysicalChunkManager {
    // Key is an absolute dimension game-time deadline, persisted by Sable's own SavedData.
    public static final SubLevelLoadingTicketType<Long> TICKET_TYPE =
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath("npships", "spawn_protection"),
                    Codec.LONG);
    private static final Map<UUID, NaturalSpawnPhysicalChunkTracker> TRACKERS = new HashMap<>();
    private static final Set<ServerLevel> PENDING =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private NaturalSpawnPhysicalChunkManager() {}

    /** Register the ticket codec before levels deserialize Sable tickets. */
    public static void initialize() {}

    public static void track(ServerSubLevel body) {
        if (body == null || body.isRemoved()) return;
        untrack(body.getUniqueId());
        TRACKERS.put(
                body.getUniqueId(),
                new NaturalSpawnPhysicalChunkTracker(
                        body,
                        body.getLevel().getGameTime()
                                + NaturalSpawnLimits.natural_physical_load_duration_ticks()));
    }

    public static long protectedUntil(UUID id) {
        var tracker = TRACKERS.get(id);
        return tracker == null || tracker.isReleased() ? 0 : tracker.expirationGameTime();
    }

    public static void protectUntil(ServerSubLevel body, long deadline) {
        untrack(body.getUniqueId());
        TRACKERS.put(body.getUniqueId(), new NaturalSpawnPhysicalChunkTracker(body, deadline));
    }

    public static void untrack(UUID id) {
        var old = TRACKERS.remove(id);
        if (old != null) old.release();
    }

    /** Release this level's NPShips ticket, including a persisted owner without a live tracker. */
    public static void untrack(ServerLevel level, UUID id) {
        var tracker = TRACKERS.get(id);
        if (tracker != null
                && tracker.subLevel().getLevel() == level
                && TRACKERS.remove(id, tracker)) tracker.release();
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        var holder = container.getAllTickets().get(id);
        if (holder == null) return;
        var body = container.getSubLevel(id);
        for (var ticket : List.copyOf(holder.tickets())) {
            if (!ticket.type().equals(TICKET_TYPE)) continue;
            if (body instanceof ServerSubLevel serverBody)
                container.removeForceLoadTicket(serverBody, TICKET_TYPE, (Long) ticket.key());
            else if (body == null && holder.tickets().remove(ticket))
                SubLevelTicketsSavedData.getOrLoad(level).setDirty();
        }
    }

    public static NaturalSpawnPhysicalChunkTracker get(UUID id) {
        return TRACKERS.get(id);
    }

    /** Rebuild runtime expiry checks after Sable has restored its force-loaded bodies. */
    public static boolean restore(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null || container.getHoldingChunkMap() == null) return false;
        var bodies = new HashMap<UUID, ServerSubLevel>();
        for (var body : container.getAllSubLevels()) bodies.put(body.getUniqueId(), body);
        for (var entry : List.copyOf(container.getAllTickets().entrySet())) {
            var body = bodies.get(entry.getKey());
            long deadline = Long.MIN_VALUE;
            for (var ticket : List.copyOf(entry.getValue().tickets())) {
                if (!ticket.type().equals(TICKET_TYPE)) continue;
                long end = (Long) ticket.key();
                if (body != null) container.removeForceLoadTicket(body, TICKET_TYPE, end);
                else {
                    entry.getValue().tickets().remove(ticket);
                    SubLevelTicketsSavedData.getOrLoad(level).setDirty();
                }
                deadline = Math.max(deadline, end);
            }
            if (body != null && deadline > level.getGameTime()) {
                untrack(body.getUniqueId());
                TRACKERS.put(
                        body.getUniqueId(), new NaturalSpawnPhysicalChunkTracker(body, deadline));
            }
        }
        return true;
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) PENDING.add(level);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        for (var level : event.getServer().getAllLevels())
            if (restore(level)) PENDING.remove(level);
            else PENDING.add(level);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (var level : List.copyOf(PENDING))
            if (level.getServer() == event.getServer() && restore(level)) PENDING.remove(level);
        var it = TRACKERS.values().iterator();
        while (it.hasNext()) {
            var tracker = it.next();
            if (tracker.subLevel().getLevel().getServer() != event.getServer()) continue;
            if (tracker.isReleased()
                    || tracker.subLevel().isRemoved()
                    || tracker.isExpired(tracker.subLevel().getLevel().getGameTime())) {
                tracker.release();
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PENDING.remove(level);
            TRACKERS.values().removeIf(t -> t.subLevel().getLevel() == level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Do not erase saved tickets: a restart resumes only their remaining game-time lifetime.
        TRACKERS.values().removeIf(t -> t.subLevel().getLevel().getServer() == event.getServer());
        PENDING.removeIf(l -> l.getServer() == event.getServer());
    }
}
