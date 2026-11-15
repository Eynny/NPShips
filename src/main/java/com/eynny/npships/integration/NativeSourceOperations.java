package com.eynny.npships.integration;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.PatchSourceOperations;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.cannons.CannonWelds;
import com.eynny.npships.integration.computercraft.ComputerPatch;
import com.eynny.npships.integration.create.MechanicalArmPatch;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;

/** Detached native grammar. No world access, source mutation or execution-policy decision. */
public final class NativeSourceOperations implements PatchSourceOperations {
    private final String type;

    private NativeSourceOperations(String type) {
        this.type = type;
    }

    @Override
    public boolean selects(
            com.eynny.npships.authoring.document.SchematicDocument source,
            JsonObject record,
            net.minecraft.core.BlockPos selection) {
        return type.equals("simulated_spring")
                && record.get("kind").getAsString().equals("spring")
                && com.eynny.npships.authoring.patches.PatchDevices.targets(record)
                        .contains(selection);
    }

    @Override
    public boolean acceptsOwner(
            com.eynny.npships.authoring.document.SchematicDocument source,
            JsonObject record,
            net.minecraft.core.BlockPos selection) {
        if (!type.equals("simulated_spring") || !record.get("kind").getAsString().equals("spring"))
            return false;
        var owner =
                com.eynny.npships.authoring.patches.SchematicPatches.position(
                        record.getAsJsonArray("pos"));
        return com.eynny.npships.authoring.patches.PatchDevices.incoming(source, selection).stream()
                .anyMatch(
                        p ->
                                p.get("kind").getAsString().equals("spring")
                                        && com.eynny.npships.authoring.patches.SchematicPatches
                                                .position(p.getAsJsonArray("pos"))
                                                .equals(owner));
    }

    @Override
    public int selectionRank(JsonObject record) {
        return type.equals("simulated_spring")
                        && record.get("kind").getAsString().equals("spring")
                        && record.has("target")
                ? 0
                : 1;
    }

    @Override
    public SchematicDocument blockDataEdited(
            SchematicDocument before, SchematicDocument after, BlockPos position) {
        boolean radar = type.equals("radars_network");
        boolean computer =
                type.equals("computercraft_computer") || type.equals("computercraft_peripheral");
        boolean arm = type.equals("create_arm");
        if (!radar && !computer && !arm) return after;
        String kind = NativeDeviceDescriptions.kind(type, after.blockName(position));
        if (kind.isEmpty()) return after;
        var data = after.blockEntityData(position);
        var original = before.blockEntityData(position);
        if (original.equals(data)) return after;
        if (arm) {
            var records =
                    SchematicPatches.read(SchematicPatches.authoringConfig(after)).stream()
                            .filter(
                                    p ->
                                            !ExternalPatches.external(p)
                                                    && p.get("kind").getAsString().equals("arm")
                                                    && SchematicPatches.position(
                                                                    p.getAsJsonArray("pos"))
                                                            .equals(position))
                            .toList();
            if (records.isEmpty()) return after;
            if (records.size() != 1)
                throw new IllegalArgumentException("Select a native arm patch instance.");
            var record = records.getFirst();
            var nativeFields = MechanicalArmPatch.capture(after, position, data);
            for (String field : java.util.List.of("ScrollValue", "InteractionPoints")) {
                if (java.util.Objects.equals(original.get(field), data.get(field))) continue;
                String key = field.equals("ScrollValue") ? "selection_mode" : "points";
                record.add(key, nativeFields.get(key).deepCopy());
            }
            return PatchDevices.put(after, record);
        }
        boolean retained =
                SchematicPatches.read(SchematicPatches.authoringConfig(after)).stream()
                        .anyMatch(
                                p ->
                                        !ExternalPatches.external(p)
                                                && p.get("kind").getAsString().equals(kind)
                                                && SchematicPatches.position(
                                                                p.getAsJsonArray("pos"))
                                                        .equals(position));
        // First configuration of a pristine editor block needs an empty native record. Never
        // bless imported NBT, pasted world addresses or media IDs whose captured files are absent.
        if (!retained && original.isEmpty() && !data.isEmpty() && freshSettings(radar, data))
            after = PatchDevices.put(after, PatchDevices.create(after, position));
        return radar && after.blockName(position).equals("create_radar:network_filterer")
                ? com.eynny.npships.integration.radars.RadarFilters.synchronizeNative(
                        after, position)
                : after;
    }

    private static boolean freshSettings(boolean radar, CompoundTag data) {
        if (radar) {
            for (String key :
                    java.util.List.of(
                            "TargetOffset", "TargetOffsetShip", "LinkedShipId", "Controller"))
                if (data.contains(key)) return false;
            var custom = data.getCompound("components").getCompound("minecraft:custom_data");
            return !custom.contains("SelectedFiltererPos") && !custom.contains("SelectedMountPos");
        }
        if (data.contains("ComputerId") && data.getInt("ComputerId") >= 0) return false;
        for (String key : data.getAllKeys())
            if (key.startsWith("PeripheralId") && data.getInt(key) >= 0) return false;
        return com.eynny.npships.integration.computercraft.ComputerMedia.ids(data).isEmpty();
    }

    @Override
    public void inspect(JsonObject patch) {
        if (type.equals("computercraft_computer")
                && patch.get("kind").getAsString().equals("computer"))
            ComputerPatch.validateReferences(patch);
    }

    public static <T> T capability(String type, Class<T> contract) {
        if (type.equals("aeronautics_lifting_gas")
                && contract == com.eynny.npships.authoring.patches.DetachedAssemblyGuard.class)
            return contract.cast(
                    new com.eynny.npships.integration.aeronautics
                            .AeronauticsDetachedAssemblyGuard());
        if (contract == com.eynny.npships.authoring.patches.PatchSourcePass.class
                && type.equals("computercraft_peripheral"))
            return contract.cast(
                    (com.eynny.npships.authoring.patches.PatchSourcePass)
                            (stage, execution) ->
                                    stage != com.eynny.npships.api.patches.PatchStage.PREPARE_WIRES
                                            || execution
                                                    .requireCapability(
                                                            com.eynny.npships.authoring.patches
                                                                    .BuiltinPatchState.class)
                                                    .hasWires());
        if (contract == com.eynny.npships.authoring.patches.PatchImportFormat.class) {
            if (type.equals("simulated_swivel"))
                return contract.cast(
                        new com.eynny.npships.authoring.patches.PatchImportFormat() {
                            public String boundary() {
                                return "physical_source";
                            }

                            public com.eynny.npships.authoring.document.SchematicDocument read(
                                    net.minecraft.nbt.CompoundTag source, JsonObject config) {
                                return com.eynny.npships.integration.contraptions
                                        .NativeSubLevelImport.read(source, config);
                            }
                        });
            if (type.equals("create_devices"))
                return contract.cast(
                        new com.eynny.npships.authoring.patches.PatchImportFormat() {
                            public String boundary() {
                                return "contraption_source";
                            }

                            public com.eynny.npships.authoring.document.SchematicDocument capture(
                                    net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.core.BlockPos origin,
                                    com.eynny.npships.authoring.document.SchematicDocument source)
                                    throws Exception {
                                return com.eynny.npships.integration.contraptions
                                        .NativeContraptionImport.capture(level, origin, source);
                            }

                            public String diagnostic(JsonObject report) {
                                return com.eynny.npships.integration.contraptions
                                        .NativeContraptionImport.diagnostic(report);
                            }

                            public com.eynny.npships.authoring.document.SchematicDocument convert(
                                    com.eynny.npships.authoring.document.SchematicDocument source) {
                                return com.eynny.npships.integration.contraptions
                                        .NativeContraptionImport.convert(source);
                            }
                        });
        }
        return contract == PatchSourceOperations.class
                ? contract.cast(new NativeSourceOperations(type))
                : null;
    }

    @Override
    public void envelope(JsonObject patch) {
        String kind = patch.get("kind").getAsString();
        if (type.equals("computercraft_computer") && kind.equals("computer"))
            ComputerPatch.validatePatch(patch);
        if (type.equals("create_arm") && kind.equals("arm")) MechanicalArmPatch.validate(patch);
    }

    @Override
    public void embed(JsonObject patch, net.minecraft.nbt.CompoundTag block) {
        if (type.equals("create_arm") && patch.get("kind").getAsString().equals("arm")) {
            var data = block.getCompound("nbt");
            MechanicalArmPatch.write(patch, data, java.util.function.Function.identity());
            block.put("nbt", data);
        }
    }

    @Override
    public void copied(JsonObject copy) {
        String kind = copy.get("kind").getAsString();
        if (type.equals("simulated_" + kind)
                && java.util.Set.of("rope", "docking", "spring").contains(kind)) {
            copy.remove("target");
            copy.remove("extension");
        }
        if (type.equals("computercraft_" + kind)
                && java.util.Set.of("computer", "peripheral", "media").contains(kind)) {
            copy.addProperty("fresh_ids", true);
            copy.remove("aliases");
            if (kind.equals("computer")) copy.addProperty("old_id", -1);
        }
    }

    @Override
    public void copied(JsonObject patch, CopyMode mode) {
        if (mode == CopyMode.DEVICE) {
            copied(patch);
            return;
        }
        String kind = patch.get("kind").getAsString();
        if (type.equals("computercraft_" + kind)
                && java.util.Set.of("computer", "peripheral", "media").contains(kind)
                && !(mode == CopyMode.REGION && kind.equals("media"))) {
            patch.addProperty("fresh_ids", true);
            patch.remove("aliases");
            if (kind.equals("computer")) patch.addProperty("old_id", -1);
        }
    }

    @Override
    public void validate(
            com.eynny.npships.authoring.document.SchematicDocument doc,
            JsonObject patch,
            boolean complete,
            java.util.Set<net.minecraft.core.BlockPos> occupied) {
        NativeSourceValidation.validate(type, doc, patch, occupied);
        if (type.equals("computercraft_computer")
                && patch.get("kind").getAsString().equals("computer")) {
            ComputerPatch.validatePatch(patch);
            if (complete) ComputerPatch.validateReferences(patch);
        }
        if (type.equals("create_arm") && patch.get("kind").getAsString().equals("arm"))
            MechanicalArmPatch.validate(patch);
    }

    @Override
    public java.util.List<net.minecraft.core.BlockPos> captureTargets(JsonObject patch) {
        String kind = patch.get("kind").getAsString();
        return type.equals("simulated_" + kind) && java.util.Set.of("rope", "spring").contains(kind)
                ? com.eynny.npships.authoring.patches.PatchDevices.targets(patch)
                : java.util.List.of();
    }

    @Override
    public void references(
            com.eynny.npships.authoring.document.SchematicDocument doc,
            java.util.List<JsonObject> patches) {
        if (type.equals("computercraft_computer")) NativeSourceValidation.references(doc, patches);
    }

    @Override
    public boolean validatesCapture() {
        return NativeCaptureRequirements.ORDER.contains(type);
    }

    @Override
    public int captureValidationRank() {
        return NativeCaptureRequirements.ORDER.indexOf(type);
    }

    @Override
    public void requireCapture(Capture source) {
        NativeCaptureRequirements.validate(type, source);
    }

    @Override
    public boolean ownsKind(String kind) {
        return NativeGraphData.owns(type, kind);
    }

    @Override
    public boolean tracks(JsonObject patch) {
        if (com.eynny.npships.authoring.patches.ExternalPatches.external(patch)) return false;
        if (type.equals("radars_guided_fuzes")) return patch.has("fuze_bindings");
        return ownsKind(patch.get("kind").getAsString())
                && java.util.Set.of(
                                "radar",
                                "rope",
                                "balloon",
                                "arm",
                                "docking",
                                "spring",
                                "mechanism",
                                "logistics")
                        .contains(patch.get("kind").getAsString());
    }

    @Override
    public void replacing(JsonObject accepted, java.util.List<JsonObject> records) {
        NativeGraphData.replacing(type, accepted, records);
    }

    @Override
    public void removeTarget(
            JsonObject patch,
            net.minecraft.core.BlockPos owner,
            net.minecraft.core.BlockPos target) {
        if (ownsKind(patch.get("kind").getAsString()))
            NativeGraphData.removeTarget(patch, owner, target);
    }

    @Override
    public void clearAddresses(String sourceBlock, net.minecraft.nbt.CompoundTag data) {
        NativeGraphData.clear(type, sourceBlock, data);
    }

    @Override
    public boolean brush(String block, net.minecraft.nbt.CompoundTag data) {
        return NativeGraphData.brush(type, block, data);
    }

    @Override
    public boolean sanitize(
            String block, net.minecraft.nbt.CompoundTag data, boolean unpatched, boolean ropeEnd) {
        return NativeIdentityData.sanitize(type, block, data, unpatched, ropeEnd);
    }

    @Override
    public void sanitizeEntity(
            net.minecraft.world.level.block.entity.BlockEntity entity,
            net.minecraft.nbt.CompoundTag data)
            throws Exception {
        if (type.equals("create_devices")) NativeIdentityData.kinetics(entity, data);
    }

    @Override
    public void rotateRecord(
            net.minecraft.nbt.CompoundTag record,
            net.minecraft.world.level.block.Rotation rotation) {
        if (type.equals("create_mounted_storage"))
            NativeDocumentTransforms.rotateRecord(record, rotation);
    }

    @Override
    public boolean rotatesData(net.minecraft.world.level.block.state.BlockState state) {
        return type.equals("cbc_welds")
                && state.getBlock()
                        .builtInRegistryHolder()
                        .key()
                        .location()
                        .getNamespace()
                        .equals("createbigcannons");
    }

    @Override
    public void rotateData(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.nbt.CompoundTag original,
            net.minecraft.nbt.CompoundTag data,
            net.minecraft.world.level.block.Rotation rotation) {
        if (type.equals("cbc_welds"))
            NativeDocumentTransforms.rotateData(state, original, data, rotation);
    }

    @Override
    public boolean mirror(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.nbt.CompoundTag data,
            int axes) {
        return type.equals("cbc_welds") && NativeDocumentTransforms.mirror(state, data, axes);
    }

    @Override
    public void blockReplaced(
            SchematicDocument source, BlockPos at, Map<BlockPos, CompoundTag> changes) {
        if (type.equals("cbc_welds")) CannonWelds.blockReplaced(source, at, changes);
        if (type.equals("create_mounted_storage") && changes.get(at) != null) {
            changes.get(at)
                    .remove(com.eynny.npships.integration.contraptions.MountedStorageSnapshot.KEY);
            changes.get(at)
                    .remove(
                            com.eynny.npships.integration.contraptions.MountedStorageSnapshot
                                    .CONTROLLER_OFFSET);
        }
    }

    @Override
    public void validateDocument(com.eynny.npships.authoring.document.SchematicDocument document) {
        if (type.equals("cbc_welds"))
            com.eynny.npships.integration.cannons.CannonWelds.pairs(document);
    }

    @Override
    public void remap(
            net.minecraft.nbt.CompoundTag result,
            com.eynny.npships.authoring.document.SchematicDocument source,
            java.util.Map<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos> mapping,
            java.util.function.Function<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos>
                    vectors) {
        if (type.equals("cbc_welds"))
            com.eynny.npships.integration.cannons.CannonWelds.remap(
                    result, source, mapping, vectors);
    }

    @Override
    public void validatePrepared(net.minecraft.nbt.CompoundTag data, JsonObject patch) {
        if (type.equals("computercraft_media")
                && patch != null
                && !patch.has("media")
                && !patch.get("kind").getAsString().equals("media"))
            com.eynny.npships.integration.computercraft.ComputerMedia.validateReferences(
                    data, patch);
    }

    @Override
    public void inventoryChanged(JsonObject patch, net.minecraft.nbt.CompoundTag data) {
        if (type.equals("radars_guided_fuzes") && patch.has("fuze_bindings")) {
            var bindings = patch.getAsJsonArray("fuze_bindings");
            for (int i = bindings.size() - 1; i >= 0; i--)
                try {
                    com.eynny.npships.integration.radars.RadarFuzes.stack(
                            data, bindings.get(i).getAsJsonObject().getAsJsonArray("path"));
                } catch (IllegalArgumentException e) {
                    bindings.remove(i);
                }
        }
    }

    @Override
    public void importedBlock(
            net.minecraft.nbt.CompoundTag state,
            net.minecraft.nbt.CompoundTag block,
            java.util.Set<net.minecraft.core.BlockPos> positions) {
        if (type.equals("create_mounted_storage"))
            NativeDocumentTransforms.importedBlock(state, block, positions);
    }

    @Override
    public void entityDefaults(net.minecraft.world.entity.Entity entity) {
        if (type.equals("create_devices")) NativeIdentityData.entityDefaults(entity);
    }

    @Override
    public boolean authoredBlock(
            String block,
            net.minecraft.nbt.CompoundTag data,
            net.minecraft.nbt.CompoundTag visual) {
        return type.equals("create_devices")
                && NativeIdentityData.authoredBlock(block, data, visual);
    }

    @Override
    public net.minecraft.nbt.CompoundTag savedVisual(net.minecraft.nbt.CompoundTag data) {
        return type.equals("create_devices") ? data.getCompound("Material").copy() : null;
    }

    @Override
    public void blockSettings(
            String block,
            net.minecraft.nbt.CompoundTag before,
            net.minecraft.nbt.CompoundTag after,
            net.minecraft.nbt.CompoundTag data) {
        if (type.equals("create_devices"))
            NativeIdentityData.blockSettings(block, before, after, data);
    }
}
