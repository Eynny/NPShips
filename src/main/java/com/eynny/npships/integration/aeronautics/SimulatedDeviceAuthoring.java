package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.api.patches.PatchAuthoring;

import java.util.Optional;
import java.util.Set;

/** Scoped native visual serializers; no fake target, runtime binding or automatic source pass. */
public final class SimulatedDeviceAuthoring implements PatchAuthoring {
    @Override
    public boolean automaticCallbacks() {
        return false;
    }

    @Override
    public Set<String> requiredMods() {
        return Set.of("simulated");
    }

    @Override
    public <T> Optional<T> capability(Class<T> contract) {
        if (contract == com.eynny.npships.api.patches.PatchAssembly.class)
            return Optional.of(contract.cast(new SimulatedAssembly()));
        if (contract == com.eynny.npships.api.patches.PatchConnectionFields.class
                || contract == com.eynny.npships.authoring.patches.PatchSourceOperations.class)
            return Optional.of(contract.cast(new SimulatedConnectionFields()));
        if (contract == com.eynny.npships.api.patches.PatchMetadata.class)
            return java.util.Optional.of(
                    contract.cast(
                            com.eynny.npships.authoring.patches.BuiltInPatchMetadata.describe(
                                    "simulated_devices")));

        if (contract == com.eynny.npships.authoring.patches.PatchVolumeFormat.class)
            return Optional.of(
                    contract.cast(new com.eynny.npships.integration.NativeGlueFormat(true)));
        T client =
                com.eynny.npships.integration.NativeClientCode.capability(
                        "simulated_devices", contract);
        if (client != null) return Optional.of(client);
        T result =
                com.eynny.npships.integration.NativePreviewData.capability(
                        "simulated_devices", contract);
        if (result == null)
            result =
                    com.eynny.npships.integration.NativeScenePreviews.capability(
                            "simulated_devices", contract);
        return Optional.ofNullable(result);
    }
}
