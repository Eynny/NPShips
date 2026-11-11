package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.*;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.HashSet;
import java.util.Set;

/** Per-preparation capability checks. No worlds, handlers or policy are cached globally. */
final class PatchRuntimePolicy {
    private PatchRuntimePolicy() {}

    /** Connected common executor: every selected execution kind shares the same preflight. */
    static void validate(PatchPolicy policy, Set<ResourceLocation> authored) {
        validate(policy, authored, true);
    }

    static void validate(
            PatchPolicy policy, Set<ResourceLocation> authored, boolean nativeRequired) {
        var active = new java.util.TreeSet<ResourceLocation>();
        for (var id : authored) {
            var provider = PatchTypes.provider(id);
            if (provider == null)
                throw new IllegalArgumentException("Patch provider is absent: " + id);
            if (!policy.enabled(id)
                    && provider.registration().disabledBehavior()
                            == PatchRegistration.DisabledBehavior.SKIP
                    && provider.execution() != PatchTypes.Execution.LEGACY_DISPATCH) continue;
            active.add(id);
        }
        var visited = new HashSet<ResourceLocation>();
        for (var id : active) {
            available(id, id, policy, visited, nativeRequired);
            for (var conflict : PatchTypes.provider(id).registration().conflicts())
                if (active.contains(conflict))
                    throw new IllegalArgumentException(
                            "Conflicting enabled patch providers: " + id + " and " + conflict);
        }
    }

    /** Creation menus use the same native/dependency admission as execution. */
    static boolean permitsCreation(PatchPolicy policy, ResourceLocation id) {
        try {
            available(id, id, policy, new HashSet<>(), true);
            return true;
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
    }

    private static void available(
            ResourceLocation id,
            ResourceLocation requester,
            PatchPolicy policy,
            Set<ResourceLocation> visited,
            boolean nativeRequired) {
        if (!visited.add(id)) return;
        var provider = PatchTypes.provider(id);
        if (provider == null)
            throw new IllegalArgumentException(
                    "Patch provider is absent: " + id + " (required by " + requester + ")");
        if (!policy.enabled(id))
            throw new IllegalArgumentException(
                    "Required patch provider is disabled: "
                            + id
                            + "; affected operation refused before preparation; authored payload retained");
        if (provider.execution() == PatchTypes.Execution.LEGACY_DISPATCH)
            throw new IllegalArgumentException(
                    "Patch provider has no unified executable adapter: " + id);
        if (nativeRequired)
            for (String mod : provider.requiredMods())
                if (!ModList.get().isLoaded(mod))
                    throw new IllegalArgumentException(
                            "Patch " + id + " requires unavailable mod " + mod);
        for (var required : provider.registration().requires())
            available(required, requester, policy, visited, nativeRequired);
    }
}
