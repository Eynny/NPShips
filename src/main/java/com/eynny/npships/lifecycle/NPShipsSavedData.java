package com.eynny.npships.lifecycle;

import com.eynny.npships.block.ClaimBlockEntity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class NPShipsSavedData extends SavedData {

    private static final String DATA_NAME = "npships_ships";

    private static final String SHIPS_TAG = "ships";
    private static final String UUID_TAG = "uuid";
    private static final String DEADLINE_TAG = "deadline";

    /**
     * UUID -> absolute unload deletion deadline.
     *
     * <p>-1 = currently loaded >0 = unloaded and waiting for automatic deletion
     */
    private final Map<UUID, Long> trackedShips = new HashMap<>();

    // Last unloaded bounds remain available without opening Sable storage or loading chunks.
    private final Map<UUID, net.minecraft.world.phys.AABB> unloadedBounds = new HashMap<>();

    @Nullable
    public net.minecraft.world.phys.AABB unloadedBounds(UUID uuid) {
        return unloadedBounds.get(uuid);
    }

    public void unloadedBounds(UUID uuid, net.minecraft.world.phys.AABB bounds) {
        if (isTracked(uuid) && bounds != null && !bounds.equals(unloadedBounds.put(uuid, bounds)))
            setDirty();
    }

    // Session-only identity prevents a removal snapshot from deleting a newly tracked UUID.
    private final Map<UUID, Long> trackingGenerations = new HashMap<>();
    private long trackingSequence;

    public int trackedCount() {
        return trackedShips.size();
    }

    public long trackingGeneration(UUID uuid) {
        if (!isTracked(uuid)) return 0;
        return trackingGenerations.computeIfAbsent(uuid, ignored -> ++trackingSequence);
    }

    private final Map<UUID, String> sources = new HashMap<>();

    public String source(UUID uuid) {
        return sources.getOrDefault(uuid, "");
    }

    public void source(UUID uuid, String name) {
        if (isTracked(uuid)) {
            sources.put(uuid, name);
            setDirty();
        }
    }

    private final java.util.Set<UUID> fragments = new java.util.HashSet<>();

    public boolean fragment(UUID uuid) {
        return fragments.contains(uuid);
    }

    public void fragment(UUID uuid, boolean value) {
        if (isTracked(uuid) && (value ? fragments.add(uuid) : fragments.remove(uuid))) setDirty();
    }

    private final Map<UUID, Long> createdAt = new HashMap<>();

    public NPShipsSavedData() {}

    /** Epoch milliseconds; zero means an old record with unknown creation time. */
    public long createdAt(UUID uuid) {
        return createdAt.getOrDefault(uuid, 0L);
    }

    @Nullable
    public static NPShipsSavedData peek(final ServerLevel level) {
        return level.getDataStorage()
                .get(
                        new SavedData.Factory<>(NPShipsSavedData::new, NPShipsSavedData::load),
                        DATA_NAME);
    }

    public static NPShipsSavedData get(final ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(
                        new SavedData.Factory<>(NPShipsSavedData::new, NPShipsSavedData::load),
                        DATA_NAME);
    }

    private static NPShipsSavedData load(
            final CompoundTag tag, final HolderLookup.Provider provider) {
        final NPShipsSavedData data = new NPShipsSavedData();

        final ListTag ships = tag.getList(SHIPS_TAG, Tag.TAG_COMPOUND);

        for (int i = 0; i < ships.size(); i++) {
            final CompoundTag shipTag = ships.getCompound(i);

            if (!shipTag.hasUUID(UUID_TAG)) {
                continue;
            }

            final UUID uuid = shipTag.getUUID(UUID_TAG);

            final long deadline = shipTag.getLong(DEADLINE_TAG);

            data.trackedShips.put(uuid, deadline);
            var bounds = shipTag.getList("unloaded_bounds", Tag.TAG_DOUBLE);
            if (bounds.size() == 6) {
                double[] v = new double[6];
                for (int j = 0; j < 6; j++) v[j] = bounds.getDouble(j);
                if (java.util.Arrays.stream(v).allMatch(Double::isFinite)
                        && v[0] <= v[3]
                        && v[1] <= v[4]
                        && v[2] <= v[5])
                    data.unloadedBounds.put(
                            uuid,
                            new net.minecraft.world.phys.AABB(v[0], v[1], v[2], v[3], v[4], v[5]));
            }
            data.createdAt.put(uuid, shipTag.getLong("created_at"));
            data.sources.put(uuid, shipTag.getString("source"));
            if (shipTag.getBoolean("fragment")) data.fragments.add(uuid);
        }

        return data;
    }

    public boolean isTracked(final UUID uuid) {
        return this.trackedShips.containsKey(uuid);
    }

    /**
     * Adds a structure to NPShips tracking.
     *
     * <p>Newly tracked structures are considered loaded.
     */
    public void track(final UUID uuid) {
        if (this.trackedShips.containsKey(uuid)) {
            return;
        }

        this.trackedShips.put(uuid, -1L);
        this.createdAt.put(uuid, System.currentTimeMillis());

        this.setDirty();
        ClaimBlockEntity.retentionChanged(this, uuid);
    }

    /**
     * Marks a structure as loaded.
     *
     * <p>-1 explicitly means "loaded".
     */
    public void markLoaded(final UUID uuid) {
        if (!this.trackedShips.containsKey(uuid)) {
            return;
        }

        final Long previous = this.trackedShips.get(uuid);

        if (previous != -1L) {
            this.trackedShips.put(uuid, -1L);

            this.setDirty();
        }
    }

    /** Stores an absolute deletion deadline. */
    public void markUnloaded(final UUID uuid, final long deadline) {
        if (!this.trackedShips.containsKey(uuid)) {
            return;
        }

        this.trackedShips.put(uuid, deadline);

        this.setDirty();
    }

    /** Stops tracking the structure completely. */
    public void remove(final UUID uuid) {
        trackingGenerations.remove(uuid);
        if (this.trackedShips.remove(uuid) != null) {
            this.unloadedBounds.remove(uuid);
            this.createdAt.remove(uuid);
            this.sources.remove(uuid);
            this.fragments.remove(uuid);
            this.setDirty();
            ClaimBlockEntity.retentionChanged(this, uuid);
        }
    }

    /**
     * Returns the absolute unload deletion deadline.
     *
     * <p>-1 means loaded.
     */
    public long getUnloadDeadline(final UUID uuid) {
        return this.trackedShips.getOrDefault(uuid, -1L);
    }

    /** Immutable snapshot. */
    public Map<UUID, Long> getTrackedShips() {
        return Map.copyOf(this.trackedShips);
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
        final ListTag ships = new ListTag();

        for (final Map.Entry<UUID, Long> entry : this.trackedShips.entrySet()) {

            final CompoundTag shipTag = new CompoundTag();

            shipTag.putUUID(UUID_TAG, entry.getKey());

            shipTag.putLong(DEADLINE_TAG, entry.getValue());
            shipTag.putLong("created_at", createdAt(entry.getKey()));
            shipTag.putString("source", source(entry.getKey()));
            if (fragment(entry.getKey())) shipTag.putBoolean("fragment", true);
            var bounds = unloadedBounds(entry.getKey());
            if (bounds != null) {
                var list = new ListTag();
                for (double v :
                        new double[] {
                            bounds.minX,
                            bounds.minY,
                            bounds.minZ,
                            bounds.maxX,
                            bounds.maxY,
                            bounds.maxZ
                        }) list.add(net.minecraft.nbt.DoubleTag.valueOf(v));
                shipTag.put("unloaded_bounds", list);
            }

            ships.add(shipTag);
        }

        tag.put(SHIPS_TAG, ships);

        return tag;
    }
}
