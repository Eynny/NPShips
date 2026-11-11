package com.eynny.npships.api.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * Native schematic-local target. No document, catalogue or mutable template escapes the API.
 *
 * @param position local block cell; null for entity targets
 * @param state native block state, or null for entity targets
 * @param blockEntityType native block-entity type, or null
 * @param data detached sanitized native target data
 * @param entity authored entity target, or null for block targets
 */
public record PatchTarget(
        @Nullable BlockPos position,
        @Nullable BlockState state,
        @Nullable ResourceLocation blockEntityType,
        CompoundTag data,
        @Nullable EntityTarget entity) {
    /**
     * Existing block/BE constructor remains source and binary compatible.
     *
     * @param position local block cell; null for entity targets
     * @param state native block state, or null for entity targets
     * @param blockEntityType native block-entity type, or null
     * @param data detached sanitized native target data
     */
    public PatchTarget(
            BlockPos position,
            BlockState state,
            ResourceLocation blockEntityType,
            CompoundTag data) {
        this(position, state, blockEntityType, data, null);
    }

    /**
     * Entity targets have no block position/state/BE; data() is a detached sanitized native copy.
     *
     * @param entity authored entity target, or null for block targets
     * @param data detached sanitized native target data
     * @return detached native entity target
     */
    public static PatchTarget forEntity(EntityTarget entity, CompoundTag data) {
        return new PatchTarget(null, null, null, data, java.util.Objects.requireNonNull(entity));
    }

    /**
     * token is null only for a new, unaccepted authoring presentation; factories have real IDs.
     *
     * @param version supported entity-target description version; independent of provider payload
     * @param token existing stable authored root token
     * @param type native entity type ID
     * @param position finite schematic-local entity position
     */
    public record EntityTarget(
            int version,
            @Nullable java.util.UUID token,
            ResourceLocation type,
            net.minecraft.world.phys.Vec3 position) {
        /**
         * Validates the supported version and finite local entity position.
         *
         * @param version supported entity-target description version; independent of provider
         *     payload
         * @param token existing stable authored root token
         * @param type native entity type ID
         * @param position finite schematic-local entity position
         */
        public EntityTarget {
            if (version != 1
                    || type == null
                    || position == null
                    || !Double.isFinite(position.lengthSqr()))
                throw new IllegalArgumentException("Invalid entity target");
        }
    }

    /**
     * Checks exclusive block/entity shape and copies mutable target data.
     *
     * @param position local block cell; null for entity targets
     * @param state native block state, or null for entity targets
     * @param blockEntityType native block-entity type, or null
     * @param data detached sanitized native target data
     * @param entity authored entity target, or null for block targets
     */
    public PatchTarget {
        if (entity == null) {
            java.util.Objects.requireNonNull(position);
            java.util.Objects.requireNonNull(state);
        } else if (position != null || state != null || blockEntityType != null)
            throw new IllegalArgumentException("Entity and block targets are exclusive");
        position = position == null ? null : position.immutable();
        data = data.copy();
    }

    /**
     * Copies sanitized native target data.
     *
     * @return fresh detached native target data
     */
    @Override
    public CompoundTag data() {
        return data.copy();
    }
}
