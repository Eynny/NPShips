package com.eynny.npships.diagnostics;

import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Field;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Frame;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Marker;
import com.eynny.npships.naturalspawn.NPShipsNaturalSpawnData;
import com.eynny.npships.naturalspawn.NPShipsNaturalSpawnManager;
import com.eynny.npships.naturalspawn.NaturalSpawnAttemptContext;
import com.eynny.npships.naturalspawn.NaturalSpawnLimits;
import com.eynny.npships.naturalspawn.NaturalSpawnRarity;
import com.eynny.npships.network.SpawnVisualizationNetworking;
import com.eynny.npships.network.SpawnVisualizationNetworking.State;
import com.eynny.npships.network.SpawnVisualizationNetworking.World;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-thread transient observer owner. No subscriptions means no recurring sampling or pruning.
 */
@EventBusSubscriber(modid = "npships")
public final class SpawnVisualizationServer {
    public static final int MAX_OBSERVERS = 16;

    private record ClientWorld(ServerPlayer player, World identity) {}

    private static final class Observer {
        final ClientWorld world;
        final long epoch;
        long sequence;

        Observer(ClientWorld world, long epoch) {
            this.world = world;
            this.epoch = epoch;
        }
    }

    private record Located(ResourceLocation dimension, Marker marker) {}

    private static final Map<UUID, ClientWorld> WORLDS = new HashMap<>();
    private static final Map<UUID, Observer> OBSERVERS = new LinkedHashMap<>();
    private static final ArrayDeque<Located> MARKERS = new ArrayDeque<>();
    private static java.util.function.BiConsumer<ServerPlayer, State> sender =
            (player, packet) -> PacketDistributor.sendToPlayer(player, packet);
    private static MinecraftServer owner;
    private static long epoch, markerId, nextUpdate;
    private static boolean dirty;

    private SpawnVisualizationServer() {}

    private static void attach(MinecraftServer server) {
        if (owner == server) return;
        reset();
        owner = server;
    }

    private static void reset() {
        WORLDS.clear();
        OBSERVERS.clear();
        MARKERS.clear();
        owner = null;
        epoch = markerId = nextUpdate = 0;
        dirty = false;
    }

    public static void world(ServerPlayer player, World identity) {
        if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || !identity.dimension().equals(player.serverLevel().dimension().location()))
            return;
        attach(player.getServer());
        var previous = WORLDS.get(player.getUUID());
        if (previous != null
                && previous.player == player
                && identity.generation() <= previous.identity.generation()) return;
        revoke(player.getUUID(), true);
        WORLDS.put(player.getUUID(), new ClientWorld(player, identity));
    }

    /**
     * Returns a localized failure key, or null on success. Command ownership checks permission too.
     */
    public static String enable(ServerPlayer player) {
        attach(player.getServer());
        if (!player.hasPermissions(2)) return "permission";
        if (!NaturalSpawnLimits.dev_diagnostics()) return "diagnostics";
        var world = WORLDS.get(player.getUUID());
        if (world == null
                || world.player != player
                || !world.identity.dimension().equals(player.serverLevel().dimension().location()))
            return "not_ready";
        if (!OBSERVERS.containsKey(player.getUUID()) && OBSERVERS.size() >= MAX_OBSERVERS)
            return "observers";
        var observer = new Observer(world, ++epoch);
        OBSERVERS.put(player.getUUID(), observer);
        send(observer, SpawnVisualizationNetworking.START, capture(player.serverLevel()));
        return null;
    }

    public static void disable(ServerPlayer player) {
        attach(player.getServer());
        revoke(player.getUUID(), true);
    }

    private static void revoke(UUID id, boolean notify) {
        var observer = OBSERVERS.remove(id);
        if (observer != null && notify)
            sender.accept(
                    observer.world.player,
                    new State(
                            observer.world.identity.token(),
                            ++epoch,
                            0,
                            observer.world.identity.dimension(),
                            SpawnVisualizationNetworking.CLEAR,
                            Frame.empty()));
    }

    /** Called only by the owner after the genuine automatic point and its single H are pinned. */
    public static void initialPoint(
            MinecraftServer server, ServerLevel level, BlockPos point, double frozenPressure) {
        if (!NaturalSpawnLimits.dev_diagnostics()) return;
        attach(server);
        long now = server.overworld().getGameTime();
        prune(now);
        if (MARKERS.size() == SpawnVisualizationData.MAX_MARKERS) MARKERS.removeFirst();
        MARKERS.addLast(
                new Located(
                        level.dimension().location(),
                        new Marker(++markerId, point, now, frozenPressure)));
        dirty = true;
    }

    private static void prune(long now) {
        MARKERS.removeIf(m -> now >= m.marker.expiresAt());
    }

    /**
     * Read only: does not prune histories, advance their clock, consume RNG or set SavedData dirty.
     */
    public static Frame capture(ServerLevel level) {
        long now = level.getServer().overworld().getGameTime();
        prune(now);
        var markers = new ArrayList<Marker>();
        for (var m : MARKERS)
            if (m.dimension.equals(level.dimension().location())) markers.add(m.marker);
        var fields = new ArrayList<Field>();
        var data = NPShipsNaturalSpawnData.peek(level);
        var players = level.players();
        boolean overflow = players.size() > SpawnVisualizationData.MAX_ROSTER;
        var parameters = NPShipsRuntimeConfig.rarity();
        if (!overflow) {
            double radius =
                    NaturalSpawnRarity.fieldRadius(
                            level.getServer().getPlayerList().getSimulationDistance(),
                            NaturalSpawnLimits.min_effective_simulation_distance_chunks(),
                            parameters.extraRadius());
            var history = data == null ? null : data.rarityHistory();
            String dimension = level.dimension().location().toString();
            for (var p : players) {
                if (p.serverLevel() != level || !NaturalSpawnAttemptContext.isEligiblePlayer(p))
                    continue;
                if (fields.size() == SpawnVisualizationData.MAX_FIELDS) {
                    overflow = true;
                    fields.clear();
                    break;
                }
                try {
                    fields.add(
                            new Field(
                                    p.getUUID(),
                                    NaturalSpawnAttemptContext.worldPosition(p),
                                    history == null
                                            ? 0
                                            : history.readPressure(
                                                    p.getUUID(),
                                                    dimension,
                                                    now,
                                                    parameters.maximumPressure()),
                                    radius));
                } catch (RuntimeException invalidProjection) {
                    // A diagnostic may hide unavailable data, but must not invent a partial MAX or
                    // fail gameplay ticks.
                    fields.clear();
                    overflow = true;
                    break;
                }
            }
        }
        return new Frame(
                now,
                overflow,
                fields,
                markers,
                NPShipsNaturalSpawnManager.diagnosticSchedule(data),
                parameters.maximumPressure());
    }

    private static void send(Observer observer, int kind, Frame frame) {
        sender.accept(
                observer.world.player,
                new State(
                        observer.world.identity.token(),
                        observer.epoch,
                        ++observer.sequence,
                        observer.world.identity.dimension(),
                        kind,
                        frame));
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (OBSERVERS.isEmpty()) return;
        var server = event.getServer();
        if (owner != server) return;
        var it = OBSERVERS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var observer = entry.getValue();
            var p = observer.world.player;
            if (!NaturalSpawnLimits.dev_diagnostics()
                    || !p.hasPermissions(2)
                    || server.getPlayerList().getPlayer(entry.getKey()) != p
                    || !p.serverLevel()
                            .dimension()
                            .location()
                            .equals(observer.world.identity.dimension())) {
                it.remove();
                if (server.getPlayerList().getPlayer(entry.getKey()) == p) {
                    sender.accept(
                            p,
                            new State(
                                    observer.world.identity.token(),
                                    ++epoch,
                                    0,
                                    observer.world.identity.dimension(),
                                    SpawnVisualizationNetworking.CLEAR,
                                    Frame.empty()));
                    p.sendSystemMessage(
                            Component.translatable("npships.spawnvisualization.revoked"));
                }
            }
        }
        if (OBSERVERS.isEmpty()) return;
        long now = server.overworld().getGameTime();
        if (!dirty && now < nextUpdate) return;
        dirty = false;
        nextUpdate = now + SpawnVisualizationData.UPDATE_TICKS;
        var frames = new HashMap<ResourceLocation, Frame>();
        for (var observer : OBSERVERS.values()) {
            var level = observer.world.player.serverLevel();
            var frame = frames.computeIfAbsent(level.dimension().location(), k -> capture(level));
            send(observer, SpawnVisualizationNetworking.UPDATE, frame);
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            revoke(p.getUUID(), false);
            WORLDS.remove(p.getUUID());
        }
    }

    @SubscribeEvent
    public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            revoke(p.getUUID(), true);
            WORLDS.remove(p.getUUID());
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || owner != level.getServer()) return;
        var ids =
                OBSERVERS.entrySet().stream()
                        .filter(
                                e ->
                                        e.getValue()
                                                .world
                                                .identity
                                                .dimension()
                                                .equals(level.dimension().location()))
                        .map(Map.Entry::getKey)
                        .toList();
        for (var id : ids) {
            revoke(id, true);
            WORLDS.remove(id);
        }
        MARKERS.removeIf(m -> m.dimension.equals(level.dimension().location()));
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        if (owner == event.getServer()) reset();
    }
}
