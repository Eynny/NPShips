package com.eynny.npships.integration;

import com.eynny.npships.integration.cannons.*;
import com.eynny.npships.integration.contraptions.MountedStorageSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Pure native transformation schemas behind their existing registered owners. */
final class NativeDocumentTransforms {
    private NativeDocumentTransforms() {}

    static void rotateRecord(CompoundTag b, Rotation rotation) {
        String controllerOffset = MountedStorageSnapshot.CONTROLLER_OFFSET;
        NbtUtils.readBlockPos(b, controllerOffset)
                .ifPresent(
                        offset ->
                                b.put(
                                        controllerOffset,
                                        NbtUtils.writeBlockPos(offset.rotate(rotation))));
    }

    static void rotateData(
            BlockState state, CompoundTag original, CompoundTag data, Rotation rotation) {
        if (state.getBlock()
                .builtInRegistryHolder()
                .key()
                .location()
                .getNamespace()
                .equals("createbigcannons"))
            CannonWelds.rotate(original, data, p -> p.rotate(rotation));
    }

    static boolean mirror(BlockState state, CompoundTag data, int axes) {
        if (CannonWelding.supports(state)) {
            // The installed behavior has additional contained-block / layered-cannon payloads.
            // Their nested schemas are not the simple native face lists handled here.
            if (data.contains("LayerConnections")
                    || data.contains("Layers")
                    || data.contains("State")
                    || data.contains("Data"))
                throw com.eynny.npships.authoring.validation.ValidationProblem.invalid(
                        "mirror_payload",
                        "Cannot mirror this saved data safely: CBC contained/layered block data. Move it without symmetry or mirror supported content separately.",
                        "CBC contained/layered block data");
            CannonWelds.validateNativeFaces(data);
            CannonWelds.rotate(data.copy(), data, p -> vector(p, axes));
            return true;
        }

        return false;
    }

    private static BlockPos vector(BlockPos p, int axes) {
        return new BlockPos(
                (axes & 1) == 0 ? p.getX() : -p.getX(),
                (axes & 2) == 0 ? p.getY() : -p.getY(),
                (axes & 4) == 0 ? p.getZ() : -p.getZ());
    }

    static void importedBlock(
            CompoundTag state, CompoundTag block, java.util.Set<BlockPos> positions) {
        if (!MountedStorageSnapshot.controllerOwner(state)) return;
        var data = block.getCompound("nbt");
        var controller = NbtUtils.readBlockPos(data, "Controller");
        var last = NbtUtils.readBlockPos(data, "LastKnownPos");
        if (controller.isPresent() && last.isPresent()) {
            var local = com.eynny.npships.authoring.patches.SchematicPatches.blockPosition(block);
            if (!positions.contains(local.offset(controller.get().subtract(last.get()))))
                throw new IllegalArgumentException(
                        "Sublevel contains a stale or external multiblock Controller at "
                                + local
                                + ". Reconnect the multiblock in the source world before exporting.");
            block.put(
                    com.eynny.npships.integration.contraptions.MountedStorageSnapshot
                            .CONTROLLER_OFFSET,
                    NbtUtils.writeBlockPos(controller.get().subtract(last.get())));
            data.put(
                    "Controller",
                    NbtUtils.writeBlockPos(local.offset(controller.get().subtract(last.get()))));
            data.put("LastKnownPos", NbtUtils.writeBlockPos(local));
        }
    }
}
