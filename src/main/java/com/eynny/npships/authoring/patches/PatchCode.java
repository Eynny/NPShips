package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchOrdering;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Shared policy/ordering for scoped code capabilities; no second registration or state owner. */
public final class PatchCode {
    private PatchCode() {}

    private static final ClassValue<Map<ResourceLocation, ?>> SOURCE_FORMATS =
            new ClassValue<>() {
                @Override
                protected Map<ResourceLocation, ?> computeValue(Class<?> contract) {
                    var result = new java.util.TreeMap<ResourceLocation, Object>();
                    // The same entry owner, deliberately no installed-mod filter for PURE source
                    // grammar.
                    for (var provider : PatchTypes.providers().values()) {
                        // Reserved NPShips entries are installed only by the private builtin
                        // bootstrap.
                        // Their known source grammars remain readable without an optional native
                        // mod.
                        // Never query an unavailable external provider merely to discover
                        // capabilities.
                        if (!provider.id().getNamespace().equals("npships")
                                && provider.requiredMods().stream()
                                        .anyMatch(
                                                mod ->
                                                        !net.neoforged.fml.ModList.get()
                                                                .isLoaded(mod))) continue;
                        var adapter = PatchTypes.adapter(provider.id());
                        var author = PatchTypes.authoring(provider.id());
                        Object a =
                                adapter == null ? null : adapter.capability(contract).orElse(null);
                        Object b = author == null ? null : author.capability(contract).orElse(null);
                        if (a != null && b != null && a != b)
                            throw new IllegalArgumentException(
                                    "Competing source grammar: " + provider.id());
                        Object code = a == null ? b : a;
                        if (code != null) result.put(provider.id(), contract.cast(code));
                    }
                    return java.util.Collections.unmodifiableMap(result);
                }
            };

    @SuppressWarnings("unchecked")
    public static <T extends PatchSourceFormat> Map<ResourceLocation, T> sourceFormats(
            Class<T> contract) {
        return (Map<ResourceLocation, T>) (Map<?, ?>) SOURCE_FORMATS.get(contract);
    }

    /** Installed, enabled creation choices; detached source grammars retain their wider view. */
    public static <T extends PatchSourceFormat> Map<ResourceLocation, T> creationFormats(
            Class<T> contract) {
        return creationFormats(contract, NPShipsRuntimeConfig.patchPolicy());
    }

    /** Evaluate an already captured policy without reading a mutable configuration draft. */
    public static <T extends PatchSourceFormat> Map<ResourceLocation, T> creationFormats(
            Class<T> contract, com.eynny.npships.api.patches.PatchPolicy policy) {
        var result = new java.util.TreeMap<ResourceLocation, T>();
        for (var entry : PatchTypes.capabilities(contract).entrySet())
            if (PatchRuntimePolicy.permitsCreation(policy, entry.getKey()))
                result.put(entry.getKey(), entry.getValue());
        return java.util.Collections.unmodifiableMap(result);
    }

    public static <T> List<Map.Entry<ResourceLocation, T>> applicable(
            Class<T> contract, Predicate<T> applies, PatchStage stage) {
        return applicable(contract, applies, stage, NPShipsRuntimeConfig.patchPolicy());
    }

    public static <T> List<Map.Entry<ResourceLocation, T>> applicable(
            Class<T> contract,
            Predicate<T> applies,
            PatchStage stage,
            com.eynny.npships.api.patches.PatchPolicy policy) {
        var code = PatchTypes.capabilities(contract);
        var active = new TreeSet<ResourceLocation>();
        for (var entry : code.entrySet())
            if (applies.test(entry.getValue())) active.add(entry.getKey());
        if (active.isEmpty()) return List.of();
        PatchRuntimePolicy.validate(policy, active);
        return PatchOrdering.discoveryOrder(stage, active, policy).stream()
                .map(id -> Map.entry(id, code.get(id)))
                .toList();
    }

    public static void validate(
            com.eynny.npships.api.patches.PatchPolicy policy,
            java.util.Set<ResourceLocation> active) {
        PatchRuntimePolicy.validate(policy, active);
    }

    /**
     * Known detached author controls may remain usable without native execution being available.
     */
    public static <T extends PatchSourceFormat> List<Map.Entry<ResourceLocation, T>> authorControls(
            Class<T> contract, Predicate<T> applies) {
        var policy = NPShipsRuntimeConfig.patchPolicy();
        var code = sourceFormats(contract);
        var active = new TreeSet<ResourceLocation>();
        for (var entry : code.entrySet())
            if (applies.test(entry.getValue())) active.add(entry.getKey());
        PatchRuntimePolicy.validate(policy, active, false);
        return PatchOrdering.discoveryOrder(PatchStage.AUTHOR_PREPARATION, active, policy).stream()
                .map(id -> Map.entry(id, code.get(id)))
                .toList();
    }

    public static <T extends PatchSourceFormat> T authorControl(
            Class<T> contract, ResourceLocation id) {
        var code = sourceFormats(contract).get(id);
        var policy = NPShipsRuntimeConfig.patchPolicy();
        if (code == null) throw new IllegalArgumentException("Author control unavailable: " + id);
        if (!policy.enabled(id))
            throw new IllegalArgumentException(
                    "Author control disabled: " + id + "; source retained");
        PatchRuntimePolicy.validate(policy, java.util.Set.of(id), false);
        return code;
    }
}
