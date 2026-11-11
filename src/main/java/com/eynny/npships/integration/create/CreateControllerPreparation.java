package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.integration.contraptions.MountedStorageSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;

/** Actual registered multiblock preparation/restoration; no formation, ticking or extra scan. */
public final class CreateControllerPreparation implements PatchNativePreparation {
    private static final class Controllers {
        final Map<BlockPos, BlockPos> entries = new LinkedHashMap<>();
    }

    private static Map<BlockPos, BlockPos> controllers(BuiltinPatchState state) {
        return state.nativeData(Controllers.class, Controllers::new).entries;
    }

    @Override
    public boolean applies(BlockEntity entity, CompoundTag block) {
        return entity
                instanceof com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
    }

    @Override
    public void prepare(
            BuiltinPatchState state, BlockEntity entity, CompoundTag block, CompoundTag data) {
        var controllers = controllers(state);
        if (entity
                instanceof com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer) {
            var target = state.placementOrigin().offset(SchematicPatches.blockPosition(block));
            data.put("LastKnownPos", NbtUtils.writeBlockPos(target));
            var local = SchematicPatches.blockPosition(block);
            if (!data.contains("Controller")) {
                controllers.put(local, local);
                state.track(local);
            }
            if (block.contains(MountedStorageSnapshot.CONTROLLER_OFFSET)) {
                var offset =
                        NbtUtils.readBlockPos(block, MountedStorageSnapshot.CONTROLLER_OFFSET)
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Invalid multiblock controller"
                                                                + " offset"));
                data.put("Controller", NbtUtils.writeBlockPos(target.offset(offset)));
                controllers.put(local, local.offset(offset));
                state.track(local);
                state.track(local.offset(offset));
            }
        }
    }

    @Override
    public void afterBodies(BuiltinPatchState state) {
        var controllers = controllers(state);
        var level = state.nativeLevel();
        // Sable has moved these block entities again since initial template placement.
        // Restore both the controller reference and the position cache before their next tick.
        for (var entry : controllers.entrySet()) {
            var pos = state.finalPosition(entry.getKey());
            var controller = state.finalPosition(entry.getValue());
            var be = level.getBlockEntity(pos);
            if (!(be
                    instanceof
                    com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer))
                throw new IllegalArgumentException("Missing multiblock member at " + pos);
            var data = be.saveWithoutMetadata(level.registryAccess());
            data.put("Controller", NbtUtils.writeBlockPos(controller));
            data.put("LastKnownPos", NbtUtils.writeBlockPos(pos));
            be.loadWithComponents(data, level.registryAccess());
            be.setChanged();
            level.sendBlockUpdated(pos, be.getBlockState(), be.getBlockState(), 3);
        }
    }
}
