package com.eynny.npships.integration.create;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.patches.PatchBlockDefaults;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;
import java.util.Set;

/** Registered Create document code. It owns native serializers, never the editor or history. */
public final class CreateDeviceAuthoring
        implements PatchAuthoring,
                PatchBlockDefaults,
                com.eynny.npships.authoring.patches.PatchDetachedData {
    @Override
    public boolean automaticCallbacks() {
        return false;
    }

    @Override
    public Set<String> requiredMods() {
        return Set.of("create");
    }

    @Override
    public <T> Optional<T> capability(Class<T> contract) {
        if (contract == com.eynny.npships.api.patches.PatchAssembly.class)
            return Optional.of(contract.cast(new CreateAssembly()));
        if (contract == com.eynny.npships.api.patches.PatchConnectionFields.class)
            return Optional.of(contract.cast(new CreateConnectionFields()));
        if (contract == com.eynny.npships.api.patches.PatchMetadata.class)
            return java.util.Optional.of(
                    contract.cast(
                            com.eynny.npships.authoring.patches.BuiltInPatchMetadata.describe(
                                    "create_devices")));

        T grammar =
                com.eynny.npships.integration.NativeSourceOperations.capability(
                        "create_devices", contract);
        if (grammar != null) return Optional.of(grammar);
        T inventory =
                com.eynny.npships.integration.NativeInventoryEditors.capability(
                        "create_devices", contract);
        if (inventory != null) return Optional.of(inventory);
        if (contract == com.eynny.npships.authoring.patches.PatchVolumeFormat.class)
            return Optional.of(
                    contract.cast(new com.eynny.npships.integration.NativeGlueFormat(false)));
        T client =
                com.eynny.npships.integration.NativeClientCode.capability(
                        "create_devices", contract);
        if (client != null) return Optional.of(client);
        if (contract == com.eynny.npships.authoring.patches.PatchFieldTemplates.class)
            return Optional.of(contract.cast(new CreateFieldTemplates()));
        if (contract == com.eynny.npships.authoring.patches.PatchNativePreparation.class)
            return Optional.of(contract.cast(new CreateKineticMaintenance()));

        if (contract == com.eynny.npships.authoring.patches.PatchDetachedData.class)
            return Optional.of(contract.cast(this));
        if (contract == com.eynny.npships.authoring.patches.PatchFluidEditor.class)
            return Optional.of(
                    contract.cast(
                            new com.eynny.npships.authoring.patches.PatchFluidEditor() {
                                public boolean applies(
                                        com.eynny.npships.authoring.document.SchematicDocument doc,
                                        net.minecraft.core.BlockPos pos,
                                        HolderLookup.Provider registry,
                                        java.util.function.Supplier<BlockEntity> target) {
                                    var index = doc.blocks().get(pos);
                                    if (index == null) return false;
                                    var state =
                                            NbtUtils.readBlockState(
                                                    registry.lookupOrThrow(
                                                            net.minecraft.core.registries.Registries
                                                                    .BLOCK),
                                                    doc.paletteCopy().getCompound(index));
                                    if (!(state.getBlock()
                                            instanceof
                                            net.minecraft.world.level.block.EntityBlock factory))
                                        return false;
                                    var fresh = factory.newBlockEntity(pos, state);
                                    if (fresh
                                            instanceof
                                            com.simibubi.create.content.fluids.tank
                                                    .FluidTankBlockEntity) return true;
                                    if (!(fresh
                                                    instanceof
                                                    com.simibubi
                                                            .create
                                                            .foundation
                                                            .blockEntity
                                                            .SmartBlockEntity)
                                            || com.eynny.npships.authoring.patches
                                                    .SchematicResources.unavailableItems(
                                                    doc.blockEntityData(pos))) return false;
                                    var detached = target.get();
                                    for (var type :
                                            java.util.List.of(
                                                    com.simibubi
                                                            .create
                                                            .foundation
                                                            .blockEntity
                                                            .behaviour
                                                            .fluid
                                                            .SmartFluidTankBehaviour
                                                            .TYPE,
                                                    com.simibubi
                                                            .create
                                                            .foundation
                                                            .blockEntity
                                                            .behaviour
                                                            .fluid
                                                            .SmartFluidTankBehaviour
                                                            .INPUT,
                                                    com.simibubi
                                                            .create
                                                            .foundation
                                                            .blockEntity
                                                            .behaviour
                                                            .fluid
                                                            .SmartFluidTankBehaviour
                                                            .OUTPUT))
                                        if (com.simibubi.create.foundation.blockEntity.behaviour
                                                        .BlockEntityBehaviour.get(detached, type)
                                                != null) return true;
                                    return false;
                                }

                                public Session open(
                                        com.eynny.npships.authoring.document.SchematicDocument doc,
                                        net.minecraft.core.BlockPos pos,
                                        net.minecraft.world.level.Level context,
                                        java.util.function.Supplier<BlockEntity> target) {
                                    return new CreateFluidEditor(doc, pos, context, target);
                                }
                            }));

        if (contract == com.eynny.npships.authoring.patches.PatchPreviewTopology.class)
            return Optional.of(
                    contract.cast(
                            new com.eynny.npships.authoring.patches.PatchPreviewTopology() {
                                public boolean applies(
                                        net.minecraft.world.level.block.state.BlockState state) {
                                    return state.is(com.simibubi.create.AllBlocks.FLUID_TANK.get());
                                }

                                public Session open(
                                        com.eynny.npships.authoring.document.SchematicDocument
                                                document,
                                        java.util.Map<
                                                        net.minecraft.core.BlockPos,
                                                        net.minecraft.world.level.block.state
                                                                .BlockState>
                                                blocks) {
                                    return new CreatePreviewTankTopology(document, blocks);
                                }
                            }));
        T preview =
                com.eynny.npships.integration.NativePreviewData.capability(
                        "create_devices", contract);
        if (preview != null) return Optional.of(preview);
        preview =
                com.eynny.npships.integration.NativeScenePreviews.capability(
                        "create_devices", contract);
        if (preview != null) return Optional.of(preview);
        if (contract == com.eynny.npships.authoring.patches.PatchBlockControls.class)
            return Optional.of(contract.cast(new CreateBlockControls()));
        return contract == PatchBlockDefaults.class
                ? Optional.of(contract.cast(this))
                : Optional.empty();
    }

    @Override
    public boolean applies(String id, BlockEntity entity) {
        return Set.of(
                                "create:redstone_link",
                                "create:belt",
                                "create:display_link",
                                "create:basin",
                                "create:display_board")
                        .contains(id)
                || id.endsWith("nixie_tube")
                || id.endsWith("_toolbox")
                || id.endsWith("_backtank")
                || entity
                        instanceof
                        com.simibubi.create.content.equipment.clipboard.ClipboardBlockEntity
                || entity
                        instanceof
                        com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
    }

    @Override
    public void fill(
            com.eynny.npships.authoring.document.SchematicDocument doc,
            net.minecraft.core.BlockPos pos,
            boolean stored,
            String id,
            BlockEntity be,
            CompoundTag result,
            HolderLookup.Provider registries) {
        if (id.equals("create:redstone_link")) {
            missing(result, "FrequencyFirst", new CompoundTag());
            missing(result, "FrequencyLast", new CompoundTag());
        }
        if (id.endsWith("nixie_tube")) {
            missing(result, "CustomText", StringTag.valueOf("\"\""));
            missing(result, "RawCustomText", StringTag.valueOf("\"\""));
            missing(result, "CustomTextIndex", IntTag.valueOf(0));
        }
        if (id.equals("create:belt")) missing(result, "Dye", StringTag.valueOf("WHITE"));
        if (id.equals("create:display_link")) {
            var source = new CompoundTag();
            source.putString("Id", "create:item_names");
            source.putString("Label", "");
            source.putString("Filter", "*");
            source.putString("Objective", "");
            for (String key :
                    java.util.List.of("Mode", "Directional", "Cycle", "Format", "Interval"))
                source.putInt(key, 0);
            source.putInt("NameColumn", 50);
            source.putInt("PlatformColumn", 3);
            missing(result, "Source", source);
        }
        if (id.equals("create:basin"))
            missing(result, "PreferredSpoutput", StringTag.valueOf("DOWN"));
        if (id.endsWith("_toolbox") || id.endsWith("_backtank"))
            missing(result, "CustomName", StringTag.valueOf("\"\""));
        if (id.equals("create:display_board")) {
            for (int i = 0;
                    i
                            < Math.clamp(
                                            stored
                                                    ? doc.blockEntityData(pos).getInt("YSize")
                                                    : result.getInt("YSize"),
                                            1,
                                            256)
                                    * 2;
                    i++) {
                missing(result, "Dye" + i, StringTag.valueOf("WHITE"));
                missing(result, "CustomLine" + i, ByteTag.ZERO);
                missing(result, "GlowingLine" + i, ByteTag.ZERO);
            }
        }
        if (be instanceof com.simibubi.create.content.equipment.clipboard.ClipboardBlockEntity) {
            var components = result.getCompound("components");
            missing(
                    components,
                    "create:clipboard_content",
                    com.simibubi.create.content.equipment.clipboard.ClipboardContent.CODEC
                            .encodeStart(
                                    registries.createSerializationContext(NbtOps.INSTANCE),
                                    com.simibubi.create.content.equipment.clipboard.ClipboardContent
                                            .EMPTY)
                            .getOrThrow());
            result.put("components", components);
        }
        if (be
                instanceof
                com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity board) {
            var templates = new CompoundTag();
            for (var panel : board.panels.values()) {
                panel.active = true;
                panel.write(templates, registries, false);
            }
            for (var key : templates.getAllKeys()) missing(result, key, templates.get(key));
        }
    }

    @Override
    public boolean applies(BlockEntity entity) {
        return entity instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
    }

    @Override
    public void prepare(
            com.eynny.npships.authoring.document.SchematicDocument doc,
            net.minecraft.core.BlockPos pos,
            BlockEntity be,
            CompoundTag data) {
        if (be instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity) {
            data.putInt("Size", Math.max(1, data.getInt("Size")));
            data.putInt("Height", Math.max(1, data.getInt("Height")));
            var controller = NbtUtils.readBlockPos(data, "Controller");
            if (controller.isPresent()
                    && !doc.blockName(controller.get()).equals(doc.blockName(pos))) {
                data.remove("Controller");
                data.remove("LastKnownPos");
                data.putBoolean("Uninitialized", true);
            }
        }
    }

    private static void missing(CompoundTag tag, String key, Tag value) {
        if (!tag.contains(key)) tag.put(key, value);
    }
}
