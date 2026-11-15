package com.eynny.npships.authoring.document;

import net.createmod.ponder.api.VirtualBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Load a detached visual snapshot without ticking, initializing or activating the block. */
public final class PreviewBlockData {
    private PreviewBlockData() {}

    public static void load(BlockEntity entity, CompoundTag saved, HolderLookup.Provider registry) {
        if (saved.contains("id")) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(saved.getString("id"));
            var actual =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(
                            entity.getType());
            if (id == null || !id.equals(actual))
                throw new IllegalArgumentException(
                        "Saved BlockEntity type " + saved.get("id") + " does not match " + actual);
        }
        if (saved.contains("components"))
            net.minecraft.core.component.DataComponentMap.CODEC
                    .parse(
                            registry.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                            saved.get("components"))
                    .getOrThrow();
        if (saved.contains("NeoForgeData")
                && !saved.contains("NeoForgeData", net.minecraft.nbt.Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("NeoForgeData must be a compound");
        if (entity instanceof VirtualBlockEntity virtual) virtual.markVirtual();
        if (saved.isEmpty()) {
            settleVisualState(entity, saved, true);
            return;
        }
        var data = entity.saveWithoutMetadata(registry);
        data.merge(saved.copy());
        entity.loadWithComponents(data, registry);
        settleVisualState(entity, data, false);
    }

    private static void settleVisualState(
            BlockEntity entity, CompoundTag data, boolean emptySource) {
        for (var code :
                com.eynny.npships.authoring.patches.PatchCode.applicable(
                        com.eynny.npships.authoring.patches.PatchPreviewData.class,
                        candidate -> candidate.applies(entity),
                        com.eynny.npships.api.patches.PatchStage.AUTHOR_PREPARATION))
            code.getValue().settle(entity, data, emptySource);
    }
}
