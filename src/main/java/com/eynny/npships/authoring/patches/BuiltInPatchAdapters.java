package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchAdapter;
import com.eynny.npships.api.patches.PatchBinding;
import com.eynny.npships.api.patches.PatchExecutionContext;
import com.eynny.npships.api.patches.PatchHandler;
import com.eynny.npships.api.patches.PatchRegistration;
import com.eynny.npships.api.patches.PatchStage;

import net.minecraft.resources.ResourceLocation;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real native adapter factories. Registration contains code/metadata, never a world or batch. */
public final class BuiltInPatchAdapters {
    private BuiltInPatchAdapters() {}

    @FunctionalInterface
    public interface Registrar {
        void register(ResourceLocation id, PatchAdapter adapter, PatchRegistration metadata);
    }

    @FunctionalInterface
    private interface Action {
        boolean run(BuiltinPatchState state) throws Exception;
    }

    @FunctionalInterface
    private interface Work {
        void run(BuiltinPatchState state) throws Exception;
    }

    private static Action once(Work work) {
        return state -> {
            work.run(state);
            return true;
        };
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("npships", path);
    }

    public static void register(Registrar registrar) {
        register(
                registrar,
                "create_arm",
                "create",
                Map.of(
                        PatchStage.PREPARE_BLOCK, once(BuiltinPatchState::prepareArm),
                        PatchStage.RESTORE_REFERENCES, once(BuiltinPatchState::restoreArm)));
        register(
                registrar,
                "create_logistics",
                "create",
                Map.of(
                        PatchStage.PREPARE_BLOCK, once(BuiltinPatchState::prepareLogistics),
                        PatchStage.AFTER_BODY_PLACEMENT,
                                once(BuiltinPatchState::restoreLogistics)));
        register(
                registrar,
                "simulated_spring",
                "simulated",
                Map.of(PatchStage.RESTORE_REFERENCES, once(BuiltinPatchState::restoreSpring)));
        register(
                registrar,
                "simulated_docking",
                "simulated",
                Map.of(PatchStage.AFTER_BODY_PLACEMENT, once(BuiltinPatchState::restoreDocking)));
        register(
                registrar,
                "simulated_rope",
                "simulated",
                Map.of(PatchStage.RESTORE_REFERENCES, once(BuiltinPatchState::restoreRope)));
        register(
                registrar,
                "aeronautics_lifting_gas",
                "aeronautics",
                Map.of(PatchStage.BEFORE_NATIVE_ASSEMBLY, once(BuiltinPatchState::restoreGas)));
        register(
                registrar,
                "simulated_swivel",
                "simulated",
                Map.of(
                        PatchStage.BEFORE_BODY_PLACEMENT,
                        once(BuiltinPatchState::restoreMechanism)));
        register(
                registrar,
                "computercraft_computer",
                "computercraft",
                Map.of(
                        PatchStage.PREPARE_BLOCK, once(BuiltinPatchState::prepareComputer),
                        PatchStage.PREPARE_REFERENCES, once(BuiltinPatchState::prepareReferences),
                        PatchStage.PREPARE_FINALIZE,
                                once(BuiltinPatchState::finalizeComputerFiles)));
        register(
                registrar,
                "computercraft_peripheral",
                "computercraft",
                Map.of(
                        PatchStage.PREPARE_BLOCK, once(BuiltinPatchState::preparePeripheral),
                        PatchStage.PREPARE_WIRES, once(BuiltinPatchState::wire),
                        PatchStage.PREPARE_REFERENCES, once(BuiltinPatchState::prepareAliases)));
        register(
                registrar,
                "computercraft_media",
                "computercraft",
                Map.of(PatchStage.PREPARE_BLOCK, once(BuiltinPatchState::prepareMedia)));
        register(
                registrar,
                "radars_network",
                "create_radar",
                Map.of(
                        PatchStage.INPUT_VALIDATION, once(BuiltinPatchState::preflightRadar),
                        PatchStage.PREPARE_BLOCK, once(BuiltinPatchState::prepareRadar),
                        PatchStage.RESTORE_LOCAL, once(BuiltinPatchState::restoreRadarLocal),
                        PatchStage.RESTORE_REFERENCES,
                                once(BuiltinPatchState::restoreRadarReferences)));
        register(
                registrar,
                "radars_guided_fuzes",
                "create_radar",
                Map.of(
                        PatchStage.PREPARE_BLOCK, once(BuiltinPatchState::prepareFuzes),
                        PatchStage.RESTORE_LOCAL, once(BuiltinPatchState::restoreFuzesLocal),
                        PatchStage.RESTORE_REFERENCES,
                                once(BuiltinPatchState::restoreFuzesReferences)));
        register(
                registrar,
                "cbc_welds",
                "createbigcannons",
                Map.of(
                        PatchStage.INPUT_VALIDATION,
                        BuiltinPatchState::validateWeldStep,
                        PatchStage.BEFORE_NATIVE_ASSEMBLY,
                        once(BuiltinPatchState::restoreWelds)));
        register(
                registrar,
                "cbc_default_connections",
                "createbigcannons",
                Map.of(
                        PatchStage.BEFORE_NATIVE_ASSEMBLY,
                        once(BuiltinPatchState::restoreDefaultConnections)));
        register(
                registrar,
                "create_mounted_storage",
                "create",
                Map.of(
                        PatchStage.INPUT_VALIDATION, once(BuiltinPatchState::prepareStorage),
                        PatchStage.BEFORE_NATIVE_ASSEMBLY,
                                once(BuiltinPatchState::restoreStorage)));
    }

    private static void register(
            Registrar registrar, String path, String mod, Map<PatchStage, Action> operations) {
        var identity = id(path);
        var actions = Map.copyOf(operations);
        var metadata =
                PatchRegistration.legacy("npships")
                        .withCapabilities(capabilities(path, actions.keySet()));
        if (path.equals("cbc_default_connections"))
            metadata =
                    metadata.withOrder(
                            PatchStage.BEFORE_NATIVE_ASSEMBLY, Set.of(), Set.of(id("cbc_welds")));
        if (path.equals("radars_guided_fuzes"))
            metadata =
                    metadata.withOrder(
                            PatchStage.RESTORE_LOCAL, Set.of(), Set.of(id("radars_network")));
        if (path.equals("create_mounted_storage")) {
            var features = EnumSet.copyOf(metadata.capabilities());
            features.add(PatchRegistration.Capability.AFTER_PLACEMENT);
            metadata =
                    metadata.withCapabilities(features)
                            .withOrder(PatchStage.PREPARE_NATIVE_DATA, Set.of(), Set.of())
                            .withOrder(PatchStage.RESTORE_NATIVE_CONTEXT, Set.of(), Set.of());
        }
        var captureOrder = com.eynny.npships.integration.NativeCaptureSteps.ORDER;
        int captureRank = captureOrder.indexOf(path);
        if (captureRank >= 0)
            metadata =
                    metadata.withOrder(
                            PatchStage.CAPTURE_BLOCK,
                            Set.of(),
                            captureOrder.subList(0, captureRank).stream()
                                    .map(BuiltInPatchAdapters::id)
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        registrar.register(
                identity,
                new PatchAdapter() {
                    @Override
                    public <T> java.util.Optional<T> capability(Class<T> contract) {
                        if (contract == com.eynny.npships.api.patches.PatchMetadata.class)
                            return java.util.Optional.of(
                                    contract.cast(BuiltInPatchMetadata.describe(path)));

                        if (path.equals("cbc_default_connections")
                                && contract
                                        == com.eynny.npships.api.patches.PatchItemPresentation
                                                .class)
                            return java.util.Optional.of(
                                    contract.cast(
                                            new com.eynny.npships.integration.cannons
                                                    .CannonItemPresentation()));
                        if (path.equals("cbc_default_connections")
                                && contract
                                        == com.eynny.npships.api.patches.PatchBlockPlacement.class)
                            return java.util.Optional.of(
                                    contract.cast(
                                            new com.eynny.npships.integration.cannons
                                                    .CannonBlockPlacement()));
                        T client =
                                com.eynny.npships.integration.NativeClientCode.capability(
                                        path, contract);
                        if (client != null) return java.util.Optional.of(client);
                        if (path.equals("cbc_default_connections")
                                && contract == PatchInventoryEditor.class)
                            return java.util.Optional.of(
                                    contract.cast(
                                            new com.eynny.npships.integration.cannons
                                                    .CannonInventoryEditor()));
                        if (path.equals("create_mounted_storage")
                                && contract == PatchDetachedData.class)
                            return java.util.Optional.of(
                                    contract.cast(
                                            new com.eynny.npships.integration.contraptions
                                                    .MountedResourceAuthoring()));
                        T inventory =
                                com.eynny.npships.integration.NativeInventoryEditors.capability(
                                        path, contract);
                        if (inventory != null) return java.util.Optional.of(inventory);
                        if (path.equals("computercraft_peripheral")
                                && (contract
                                                == com.eynny.npships.api.patches.PatchBlockPlacement
                                                        .class
                                        || contract
                                                == com.eynny.npships.api.patches
                                                        .PatchItemPresentation.class))
                            return java.util.Optional.of(
                                    contract.cast(
                                            new com.eynny.npships.integration.computercraft
                                                    .ComputerBlockPlacement()));
                        if (path.equals("create_mounted_storage")
                                && contract == PatchNativePreparation.class)
                            return java.util.Optional.of(
                                    contract.cast(
                                            new com.eynny.npships.integration.create
                                                    .CreateControllerPreparation()));
                        if (path.equals("cbc_default_connections")
                                && contract == PatchFieldTemplates.class)
                            return java.util.Optional.of(
                                    contract.cast(
                                            new com.eynny.npships.integration.cannons
                                                    .CannonFieldTemplates()));
                        T qualification =
                                com.eynny.npships.integration.NativeValidation.capability(
                                        path, contract);
                        if (qualification != null) return java.util.Optional.of(qualification);
                        T grammar =
                                com.eynny.npships.integration.NativeSourceOperations.capability(
                                        path, contract);
                        if (grammar != null) return java.util.Optional.of(grammar);
                        T result =
                                com.eynny.npships.integration.NativeDocumentNormalizations
                                        .capability(path, contract);
                        if (result == null)
                            result =
                                    com.eynny.npships.integration.NativeDeviceDescriptions
                                            .capability(path, contract);
                        if (result == null)
                            result =
                                    com.eynny.npships.integration.NativeCaptureSteps.capability(
                                            path, contract);
                        if (result == null)
                            result =
                                    com.eynny.npships.integration.NativeCaptureAspects.capability(
                                            path, contract);
                        if (result == null)
                            result =
                                    com.eynny.npships.integration.NativePreviewData.capability(
                                            path, contract);
                        if (result == null)
                            result =
                                    com.eynny.npships.integration.NativeScenePreviews.capability(
                                            path, contract);
                        return java.util.Optional.ofNullable(result);
                    }

                    @Override
                    public Set<String> requiredMods() {
                        return Set.of(mod);
                    }

                    @Override
                    public Set<PatchStage> stages() {
                        return actions.keySet();
                    }

                    @Override
                    public List<PatchBinding> bind(PatchExecutionContext context) {
                        return context.requireCapability(BuiltinPatchState.class)
                                .bindings(identity);
                    }

                    @Override
                    public PatchHandler create(
                            PatchBinding binding, PatchExecutionContext context) {
                        var state = context.requireCapability(BuiltinPatchState.class);
                        if (!identity.equals(binding.identity().type()))
                            throw new IllegalArgumentException(
                                    "Native adapter binding type mismatch");
                        return new PatchHandler() {
                            @Override
                            public boolean step(PatchStage stage, PatchExecutionContext execution)
                                    throws Exception {
                                var action = actions.get(stage);
                                return action == null || action.run(state);
                            }
                        };
                    }
                },
                metadata);
    }

    private static Set<PatchRegistration.Capability> capabilities(
            String path, Set<PatchStage> stages) {
        var result = EnumSet.of(PatchRegistration.Capability.VALIDATION);
        if (stages.stream()
                .anyMatch(
                        stage ->
                                stage == PatchStage.INPUT_VALIDATION
                                        || stage == PatchStage.PREPARE_BLOCK
                                        || stage == PatchStage.PREPARE_WIRES
                                        || stage == PatchStage.PREPARE_REFERENCES
                                        || stage == PatchStage.PREPARE_FINALIZE))
            result.add(PatchRegistration.Capability.PREPARATION);
        if (stages.contains(PatchStage.BEFORE_NATIVE_ASSEMBLY))
            result.add(PatchRegistration.Capability.BEFORE_ASSEMBLY);
        if (stages.stream()
                .anyMatch(
                        stage ->
                                stage == PatchStage.BEFORE_BODY_PLACEMENT
                                        || stage == PatchStage.AFTER_BODY_PLACEMENT
                                        || stage == PatchStage.RESTORE_LOCAL
                                        || stage == PatchStage.RESTORE_REFERENCES))
            result.add(PatchRegistration.Capability.AFTER_PLACEMENT);
        if (com.eynny.npships.integration.NativeCaptureSteps.ORDER.contains(path))
            result.add(PatchRegistration.Capability.CAPTURE);
        if (Set.of(
                        "cbc_default_connections",
                        "cbc_welds",
                        "radars_network",
                        "computercraft_peripheral",
                        "simulated_rope")
                .contains(path)) result.add(PatchRegistration.Capability.CLIENT_PRESENTATION);
        if (Set.of(
                        "create_arm",
                        "create_logistics",
                        "simulated_spring",
                        "simulated_docking",
                        "simulated_rope",
                        "radars_network",
                        "radars_guided_fuzes",
                        "cbc_welds",
                        "create_mounted_storage")
                .contains(path)) result.add(PatchRegistration.Capability.AUTHORED_TRANSFORM);
        return Set.copyOf(result);
    }

    /** Default ranks only; mandatory phase boundaries and declared edges remain authoritative. */
    public static List<ResourceLocation> defaultOrder(PatchStage stage) {
        var paths =
                switch (stage) {
                    case INPUT_VALIDATION ->
                            List.of("create_mounted_storage", "radars_network", "cbc_welds");
                    case PREPARE_BLOCK ->
                            List.of(
                                    "computercraft_media",
                                    "computercraft_computer",
                                    "computercraft_peripheral",
                                    "radars_network",
                                    "radars_guided_fuzes",
                                    "create_logistics",
                                    "create_arm");
                    case BEFORE_NATIVE_ASSEMBLY ->
                            List.of(
                                    "create_mounted_storage",
                                    "cbc_welds",
                                    "cbc_default_connections",
                                    "aeronautics_lifting_gas");
                    case AFTER_BODY_PLACEMENT -> List.of("simulated_docking", "create_logistics");
                    case RESTORE_LOCAL -> List.of("radars_network", "radars_guided_fuzes");
                    case RESTORE_REFERENCES ->
                            List.of(
                                    "radars_guided_fuzes",
                                    "radars_network",
                                    "create_arm",
                                    "simulated_spring",
                                    "simulated_rope");
                    default -> List.<String>of();
                };
        return paths.stream().map(BuiltInPatchAdapters::id).toList();
    }
}
