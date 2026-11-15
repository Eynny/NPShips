package com.eynny.npships.api.patches;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Detached registry metadata. Provider dependencies refer to code capabilities, not a requirement
 * that every document contain an authored instance. Capture and transform work requires the
 * corresponding provider callbacks; declaring a capability does not supply their implementation.
 *
 * @param ownerMod provider owner mod ID
 * @param multiplicity per-target authored multiplicity
 * @param requires hard provider code dependencies
 * @param conflicts mutually exclusive active providers
 * @param ordering fixed-stage precedence metadata
 * @param disabledBehavior disabled-provider behavior
 * @param capabilities declared provider feature set
 */
public record PatchRegistration(
        String ownerMod,
        Multiplicity multiplicity,
        Set<ResourceLocation> requires,
        Set<ResourceLocation> conflicts,
        Map<PatchStage, Order> ordering,
        DisabledBehavior disabledBehavior,
        Set<Capability> capabilities) {
    /** Allowed authored instance count for one provider at a target. */
    public enum Multiplicity {
        /** At most one authored provider instance at a target. */
        SINGLE_PER_TARGET,
        /** Multiple explicitly identified authored instances at a target. */
        MULTIPLE
    }

    /** Host behavior for disabled provider work. */
    public enum DisabledBehavior {
        /** Disabled required work refuses the operation. */
        REFUSE,
        /** Explicitly nonessential work may be omitted without deleting authored data. */
        SKIP
    }

    /** Declared feature metadata; declaration alone installs no callback. */
    public enum Capability {
        /** Detached validation capability metadata. */
        VALIDATION,
        /** Capture. */
        CAPTURE,
        /** Authored transform. */
        AUTHORED_TRANSFORM,
        /** Preparation. */
        PREPARATION,
        /** Legacy callback before native assembly. */
        BEFORE_ASSEMBLY,
        /** Global boundary after native reference restoration. */
        AFTER_PLACEMENT,
        /** Client presentation. */
        CLIENT_PRESENTATION
    }

    /**
     * Soft precedence within one fixed stage; unknown/absent providers do not create instances.
     *
     * @param before providers placed after this provider in the stage
     * @param after providers placed before this provider in the stage
     */
    public record Order(Set<ResourceLocation> before, Set<ResourceLocation> after) {
        /**
         * Copies precedence sets and rejects contradictory declarations.
         *
         * @param before providers placed after this provider in the stage
         * @param after providers placed before this provider in the stage
         */
        public Order {
            before = ids(before);
            after = ids(after);
            if (!Collections.disjoint(before, after))
                throw new IllegalArgumentException(
                        "Patch precedence names a provider both before and after");
        }
    }

    /**
     * Validates owner metadata and copies dependency/order/capability collections.
     *
     * @param ownerMod provider owner mod ID
     * @param multiplicity per-target authored multiplicity
     * @param requires hard provider code dependencies
     * @param conflicts mutually exclusive active providers
     * @param ordering fixed-stage precedence metadata
     * @param disabledBehavior disabled-provider behavior
     * @param capabilities declared provider feature set
     */
    public PatchRegistration {
        Objects.requireNonNull(ownerMod);
        if (!ownerMod.matches("[a-z][a-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid patch owner mod ID: " + ownerMod);
        Objects.requireNonNull(multiplicity);
        requires = ids(requires);
        conflicts = ids(conflicts);
        var copy = new EnumMap<PatchStage, Order>(PatchStage.class);
        Objects.requireNonNull(ordering)
                .forEach(
                        (stage, order) ->
                                copy.put(
                                        Objects.requireNonNull(stage),
                                        Objects.requireNonNull(order)));
        ordering = Collections.unmodifiableMap(copy);
        Objects.requireNonNull(disabledBehavior);
        capabilities = Set.copyOf(capabilities);
    }

    /**
     * Previous registrations remain required, one instance of each provider kind per target.
     *
     * @param ownerMod provider owner mod ID
     * @return required single-instance legacy target metadata
     */
    public static PatchRegistration legacy(String ownerMod) {
        return new PatchRegistration(
                ownerMod,
                Multiplicity.SINGLE_PER_TARGET,
                Set.of(),
                Set.of(),
                Map.of(),
                DisabledBehavior.REFUSE,
                Set.of(
                        Capability.VALIDATION,
                        Capability.PREPARATION,
                        Capability.BEFORE_ASSEMBLY,
                        Capability.AFTER_PLACEMENT));
    }

    /**
     * Copies metadata with a different per-target multiplicity.
     *
     * @param value replacement metadata value
     * @return new immutable metadata with the requested replacement
     */
    public PatchRegistration withMultiplicity(Multiplicity value) {
        return new PatchRegistration(
                ownerMod, value, requires, conflicts, ordering, disabledBehavior, capabilities);
    }

    /**
     * Copies metadata with different hard provider dependencies.
     *
     * @param value replacement metadata value
     * @return new immutable metadata with the requested replacement
     */
    public PatchRegistration withRequires(Set<ResourceLocation> value) {
        return new PatchRegistration(
                ownerMod, multiplicity, value, conflicts, ordering, disabledBehavior, capabilities);
    }

    /**
     * Copies metadata with different conflicting provider IDs.
     *
     * @param value replacement metadata value
     * @return new immutable metadata with the requested replacement
     */
    public PatchRegistration withConflicts(Set<ResourceLocation> value) {
        return new PatchRegistration(
                ownerMod, multiplicity, requires, value, ordering, disabledBehavior, capabilities);
    }

    /**
     * Copies metadata with precedence for one fixed stage.
     *
     * @param stage fixed host callback boundary
     * @param before providers placed after this provider in the stage
     * @param after providers placed before this provider in the stage
     * @return new immutable metadata with the requested replacement
     */
    public PatchRegistration withOrder(
            PatchStage stage, Set<ResourceLocation> before, Set<ResourceLocation> after) {
        var copy = new EnumMap<PatchStage, Order>(PatchStage.class);
        copy.putAll(ordering);
        copy.put(Objects.requireNonNull(stage), new Order(before, after));
        return new PatchRegistration(
                ownerMod, multiplicity, requires, conflicts, copy, disabledBehavior, capabilities);
    }

    /**
     * Copies metadata with a different disabled-provider behavior.
     *
     * @param value replacement metadata value
     * @return new immutable metadata with the requested replacement
     */
    public PatchRegistration withDisabledBehavior(DisabledBehavior value) {
        return new PatchRegistration(
                ownerMod, multiplicity, requires, conflicts, ordering, value, capabilities);
    }

    /**
     * Copies metadata with different capability declarations.
     *
     * @param value replacement metadata value
     * @return new immutable metadata with the requested replacement
     */
    public PatchRegistration withCapabilities(Set<Capability> value) {
        return new PatchRegistration(
                ownerMod, multiplicity, requires, conflicts, ordering, disabledBehavior, value);
    }

    private static Set<ResourceLocation> ids(Set<ResourceLocation> values) {
        var copy = new TreeSet<>(Objects.requireNonNull(values));
        for (var id : copy)
            if (id.toString().length() > 256)
                throw new IllegalArgumentException("Patch reference ID exceeds 256 characters");
        return Collections.unmodifiableSet(copy);
    }
}
