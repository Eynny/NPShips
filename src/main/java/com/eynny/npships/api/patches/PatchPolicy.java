package com.eynny.npships.api.patches;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable SERVER policy snapshot. Unknown IDs survive absent or temporarily removed addons.
 *
 * @param disabledTypes captured disabled provider IDs, retaining unknown IDs
 * @param preferredTypes captured independent-provider preferences
 */
public record PatchPolicy(
        Set<ResourceLocation> disabledTypes, List<ResourceLocation> preferredTypes) {
    private static final PatchPolicy DEFAULTS = new PatchPolicy(Set.of(), List.of());

    /**
     * Copies disabled/preferred IDs and rejects duplicate preferences.
     *
     * @param disabledTypes captured disabled provider IDs, retaining unknown IDs
     * @param preferredTypes captured independent-provider preferences
     */
    public PatchPolicy {
        Objects.requireNonNull(disabledTypes);
        Objects.requireNonNull(preferredTypes);
        disabledTypes = Collections.unmodifiableSet(new TreeSet<>(disabledTypes));
        preferredTypes = List.copyOf(preferredTypes);
        if (new HashSet<>(preferredTypes).size() != preferredTypes.size())
            throw new IllegalArgumentException("Duplicate patch provider preference");
    }

    /**
     * Reads the immutable policy with no disabled IDs or preferences.
     *
     * @return immutable enabled policy with no preferences
     */
    public static PatchPolicy defaults() {
        return DEFAULTS;
    }

    /**
     * Checks only the captured disabled-ID set.
     *
     * @param id namespaced identity
     * @return whether captured policy permits the provider
     */
    public boolean enabled(ResourceLocation id) {
        return !disabledTypes.contains(Objects.requireNonNull(id));
    }
}
