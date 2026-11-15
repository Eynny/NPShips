package com.eynny.npships.naturalspawn;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import org.jetbrains.annotations.Nullable;

/** Global opportunity deadline and independent player/dimension rarity histories. No point bank. */
public final class NPShipsNaturalSpawnData extends SavedData {
    private static final String DATA_NAME = "npships_natural_spawn_rarity";
    private boolean initialized;
    private long lastSelectionGameTime, nextSelectionGameTime;
    private final RarityHistory rarityHistory = new RarityHistory();

    public RarityHistory rarityHistory() {
        return rarityHistory;
    }

    public void recordRaritySuccess(RarityHistory.Event event, long now) {
        recordRaritySuccess(
                event,
                now,
                com.eynny.npships.config.NPShipsRuntimeConfig.rarity().maximumPressure());
    }

    public void recordRaritySuccess(RarityHistory.Event event, long now, double maximum) {
        // A rejected event can still prune or rescale retained history.
        rarityHistory.append(event, now, maximum);
        setDirty();
    }

    private final java.util.List<RecentSpawn> recentSpawns = new java.util.ArrayList<>();

    private record RecentSpawn(
            String dimension,
            net.minecraft.core.BlockPos playerPosition,
            java.util.UUID playerId,
            long expiresAt) {}

    public record BlockerPosition(String dimension, net.minecraft.world.phys.Vec3 position) {}

    public boolean isSpawnAreaBlocked(
            ServerLevel level, net.minecraft.core.BlockPos point, long now) {
        return isSpawnAreaBlocked(
                level.dimension().location().toString(),
                point,
                now,
                id -> {
                    var player = level.getServer().getPlayerList().getPlayer(id);
                    return player == null || !NaturalSpawnAttemptContext.isEligiblePlayer(player)
                            ? null
                            : new BlockerPosition(
                                    player.serverLevel().dimension().location().toString(),
                                    NaturalSpawnAttemptContext.worldPosition(player));
                });
    }

    /** Explicit fixed-region cooldowns are independent of live-player UUID cooldowns. */
    public boolean isSpawnAreaBlocked(
            String dimension, net.minecraft.core.BlockPos point, long now) {
        return isSpawnAreaBlocked(dimension, point, now, id -> null);
    }

    public boolean isSpawnAreaBlocked(
            String dimension,
            net.minecraft.core.BlockPos point,
            long now,
            java.util.function.Function<java.util.UUID, BlockerPosition> resolvePlayer) {
        pruneRecentSpawns(now);
        double radius = NaturalSpawnLimits.recent_spawn_player_radius();
        for (RecentSpawn spawn : recentSpawns) {
            var blocker =
                    spawn.playerId == null
                            ? new BlockerPosition(
                                    spawn.dimension,
                                    net.minecraft.world.phys.Vec3.atLowerCornerOf(
                                            spawn.playerPosition))
                            : resolvePlayer.apply(spawn.playerId);
            if (blocker == null || !blocker.dimension.equals(dimension)) continue;
            double dx = point.getX() - blocker.position.x;
            double dz = point.getZ() - blocker.position.z;
            if (dx * dx + dz * dz <= radius * radius) return true;
        }
        return false;
    }

    public void recordSuccessfulSpawn(
            String dimension, net.minecraft.core.BlockPos playerPosition, long now) {
        pruneRecentSpawns(now);
        recentSpawns.add(
                new RecentSpawn(
                        dimension,
                        playerPosition.immutable(),
                        null,
                        now + NaturalSpawnLimits.recent_spawn_cooldown_ticks()));
        setDirty();
    }

    public void recordSuccessfulSpawn(java.util.UUID playerId, long now) {
        recordSuccessfulSpawn(playerId, now, NaturalSpawnLimits.recent_spawn_cooldown_ticks());
    }

    public void recordSuccessfulSpawn(java.util.UUID playerId, long now, long cooldownTicks) {
        if (cooldownTicks < 1) throw new IllegalArgumentException("Positive cooldown required");
        long expiry = now > Long.MAX_VALUE - cooldownTicks ? Long.MAX_VALUE : now + cooldownTicks;
        pruneRecentSpawns(now);
        recentSpawns.removeIf(spawn -> playerId.equals(spawn.playerId));
        recentSpawns.add(new RecentSpawn("", net.minecraft.core.BlockPos.ZERO, playerId, expiry));
        setDirty();
    }

    private void pruneRecentSpawns(long now) {
        if (recentSpawns.removeIf(spawn -> now >= spawn.expiresAt)) setDirty();
    }

    public static NPShipsNaturalSpawnData get(final ServerLevel level) {

        final ServerLevel overworld = level.getServer().overworld();

        return overworld
                .getDataStorage()
                .computeIfAbsent(
                        new SavedData.Factory<>(
                                NPShipsNaturalSpawnData::new, NPShipsNaturalSpawnData::load),
                        DATA_NAME);
    }

    /** Returns the existing data without intentionally creating a new persistent entry. */
    @Nullable
    public static NPShipsNaturalSpawnData peek(final ServerLevel level) {

        final ServerLevel overworld = level.getServer().overworld();

        return overworld
                .getDataStorage()
                .get(
                        new SavedData.Factory<>(
                                NPShipsNaturalSpawnData::new, NPShipsNaturalSpawnData::load),
                        DATA_NAME);
    }

    public void initializeIfNeeded(long now, long delay) {
        if (initialized) return;
        initialized = true;
        lastSelectionGameTime = now;
        nextSelectionGameTime = now + Math.max(0, delay);
        setDirty();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public long getLastSelectionGameTime() {
        return lastSelectionGameTime;
    }

    public long getNextSelectionGameTime() {
        return nextSelectionGameTime;
    }

    public void setLastSelectionGameTime(long now) {
        if (lastSelectionGameTime != now) {
            lastSelectionGameTime = now;
            setDirty();
        }
    }

    public void setNextSelectionGameTime(long now) {
        if (nextSelectionGameTime != now) {
            nextSelectionGameTime = now;
            setDirty();
        }
    }

    private static NPShipsNaturalSpawnData load(CompoundTag tag, HolderLookup.Provider provider) {
        var data = new NPShipsNaturalSpawnData();
        if (tag.getInt("schema") != 1)
            throw new IllegalArgumentException("Unsupported rarity SavedData schema");
        data.initialized = tag.getBoolean("initialized");
        data.lastSelectionGameTime = tag.getLong("last_selection_game_time");
        data.nextSelectionGameTime = tag.getLong("next_selection_game_time");
        if (data.rarityHistory.load(
                tag.getList("rarity_events", Tag.TAG_COMPOUND),
                com.eynny.npships.config.NPShipsRuntimeConfig.rarity().maximumPressure()))
            data.setDirty();
        var recent = tag.getList("recent_spawns", Tag.TAG_COMPOUND);
        for (int i = 0; i < recent.size(); i++) {
            var entry = recent.getCompound(i);
            data.recentSpawns.add(
                    new RecentSpawn(
                            entry.getString("dimension"),
                            net.minecraft.core.BlockPos.of(entry.getLong("position")),
                            entry.hasUUID("player_id") ? entry.getUUID("player_id") : null,
                            entry.getLong("expires_at")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("schema", 1);
        tag.putBoolean("initialized", initialized);
        tag.putLong("last_selection_game_time", lastSelectionGameTime);
        tag.putLong("next_selection_game_time", nextSelectionGameTime);
        tag.put("rarity_events", rarityHistory.save());
        var recent = new net.minecraft.nbt.ListTag();
        for (var spawn : recentSpawns) {
            var e = new CompoundTag();
            if (spawn.playerId != null) e.putUUID("player_id", spawn.playerId);
            e.putString("dimension", spawn.dimension);
            e.putLong("position", spawn.playerPosition.asLong());
            e.putLong("expires_at", spawn.expiresAt);
            recent.add(e);
        }
        tag.put("recent_spawns", recent);
        return tag;
    }
}
