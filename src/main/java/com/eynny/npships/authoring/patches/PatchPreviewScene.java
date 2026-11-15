package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.Function;

/**
 * Internal detached-native visual preparation, also usable by dedicated-server reference tests. It
 * exposes neither GL nor editor/world ownership. BE/registry/state-function arguments are
 * callback-borrowed; only the preview-owned session's detached document indexes may persist.
 */
public interface PatchPreviewScene {
    /** Pure constructor type/state predicate; never depends on position or mutable runtime data. */
    boolean applies(BlockEntity entity, String name);

    Session open(SchematicDocument document);

    interface Session {
        default Set<BlockPos> refresh(SchematicDocument document) {
            return Set.of();
        }

        default boolean loadNative(BlockEntity entity) {
            return true;
        }

        default void prepare(
                BlockEntity entity,
                BlockPos pos,
                CompoundTag saved,
                Function<BlockPos, BlockState> states) {}

        default void afterNativeLoad(
                BlockEntity entity, BlockPos pos, HolderLookup.Provider registry) {}

        default void afterVirtual(
                BlockEntity entity,
                BlockPos pos,
                CompoundTag saved,
                HolderLookup.Provider registry) {}

        default Component describe(BlockEntity entity, String name) {
            return null;
        }
    }
}
