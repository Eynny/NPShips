package com.eynny.npships.integration.contraptions;

import com.eynny.npships.authoring.patches.SchematicPatches;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Native Create codecs retain inventory/fluid data without unmounting the original contraption. */
public final class MountedStorageSnapshot {
    public static final String KEY = "npships_mounted_storage";
    public static final String CONTROLLER_OFFSET = "npships_contraption_controller_offset";

    public record Prepared(
            BlockPos pos, BlockState state, MountedItemStorage items, MountedFluidStorage fluids) {
        public void restore(ServerLevel level, BlockPos origin) {
            var target = origin.offset(pos);
            var be = level.getBlockEntity(target);
            if (be == null || !level.getBlockState(target).is(state.getBlock()))
                throw new IllegalArgumentException("Missing mounted storage block at " + target);
            if (items != null) items.unmount(level, level.getBlockState(target), target, be);
            if (fluids != null) fluids.unmount(level, level.getBlockState(target), target, be);
            be.setChanged();
            level.sendBlockUpdated(target, be.getBlockState(), be.getBlockState(), 3);
        }
    }

    /** Resolve the registered native BE contract without constructing or loading source data. */
    public static boolean controllerOwner(CompoundTag state) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(state.getString("Name"));
        if (id == null) return false;
        var block =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        return block instanceof com.simibubi.create.foundation.block.IBE<?> nativeBlock
                && com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer.class
                        .isAssignableFrom(nativeBlock.getBlockEntityClass());
    }

    public static void attach(
            CompoundTag payload, ListTag palette, ListTag blocks, BlockPos minimum) {
        var byPos = new HashMap<BlockPos, CompoundTag>();
        for (var value : blocks) {
            var block = (CompoundTag) value;
            var original = SchematicPatches.blockPosition(block).offset(minimum);
            byPos.put(original, block);
            if (block.contains(CONTROLLER_OFFSET)
                    || !controllerOwner(palette.getCompound(block.getInt("state")))) continue;
            NbtUtils.readBlockPos(block.getCompound("nbt"), "Controller")
                    .ifPresent(
                            controller ->
                                    block.put(
                                            CONTROLLER_OFFSET,
                                            NbtUtils.writeBlockPos(controller.subtract(original))));
        }
        for (String key : List.of("items", "fluids")) {
            if (payload.contains(key)
                    && (!(payload.get(key) instanceof ListTag list)
                            || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND))
                throw new IllegalArgumentException("Invalid mounted storage list: " + key);
            for (var value : payload.getList(key, Tag.TAG_COMPOUND)) {
                var item = (CompoundTag) value;
                var pos =
                        NbtUtils.readBlockPos(item, "pos")
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Invalid mounted storage position"));
                var block = byPos.get(pos);
                if (block == null || !item.contains("storage", Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException(
                            "Mounted storage has no block payload at " + pos);
                var data = block.getCompound(KEY);
                if (data.contains(key))
                    throw new IllegalArgumentException("Duplicate mounted storage at " + pos);
                data.put(key, item.getCompound("storage").copy());
                block.put(KEY, data);
            }
        }
    }

    public static List<Prepared> prepare(ServerLevel level, CompoundTag template) {
        return prepare(level.registryAccess(), template);
    }

    public static List<Prepared> prepare(
            net.minecraft.core.HolderLookup.Provider registries, CompoundTag template) {
        var out = new ArrayList<Prepared>();
        var palette = template.getList("palette", Tag.TAG_COMPOUND);
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (var value : template.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            if (!block.contains(KEY, Tag.TAG_COMPOUND)) continue;
            var data = block.getCompound(KEY);
            var state =
                    NbtUtils.readBlockState(
                            registries.lookupOrThrow(Registries.BLOCK),
                            palette.getCompound(block.getInt("state")));
            var items =
                    data.contains("items")
                            ? MountedItemStorage.CODEC.parse(ops, data.get("items")).getOrThrow()
                            : null;
            var fluids =
                    data.contains("fluids")
                            ? MountedFluidStorage.CODEC.parse(ops, data.get("fluids")).getOrThrow()
                            : null;
            if (items != null && MountedItemStorageType.REGISTRY.get(state.getBlock()) != items.type
                    || fluids != null
                            && MountedFluidStorageType.REGISTRY.get(state.getBlock())
                                    != fluids.type)
                throw new IllegalArgumentException(
                        "Mounted storage type no longer matches " + state);
            out.add(new Prepared(SchematicPatches.blockPosition(block), state, items, fluids));
        }
        return List.copyOf(out);
    }
}
