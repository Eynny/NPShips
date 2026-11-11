package com.eynny.npships.config;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public record EnvironmentRule(
        Type type,
        int depth,
        List<ResourceLocation> blocks,
        List<ResourceLocation> tags,
        List<ResourceLocation> excludeBlocks,
        List<ResourceLocation> excludeTags,
        boolean allowAir,
        boolean allowNonSupport,
        @Nullable ResourceLocation fluid) {

    public EnvironmentRule {
        if (type == null) {
            throw new IllegalArgumentException("Environment rule type cannot be null.");
        }

        /*
         * NONE means that this particular environment side
         * is not checked at all.
         */
        if (type != Type.NONE && depth <= 0) {

            throw new IllegalArgumentException(
                    "Environment depth must be greater than zero: " + depth);
        }

        blocks = List.copyOf(blocks == null ? List.of() : blocks);

        tags = List.copyOf(tags == null ? List.of() : tags);

        excludeBlocks = List.copyOf(excludeBlocks == null ? List.of() : excludeBlocks);

        excludeTags = List.copyOf(excludeTags == null ? List.of() : excludeTags);

        /*
         * A fluid restriction only makes sense for FLUID.
         */
        if (type != Type.FLUID && fluid != null) {

            throw new IllegalArgumentException(
                    "Fluid can only be specified for " + "environment type FLUID.");
        }

        /*
         * NONE must not carry active environment options.
         */
        if (type == Type.NONE) {

            if (!blocks.isEmpty()
                    || !tags.isEmpty()
                    || !excludeBlocks.isEmpty()
                    || !excludeTags.isEmpty()
                    || allowAir
                    || allowNonSupport
                    || fluid != null) {

                throw new IllegalArgumentException(
                        "Environment type NONE cannot have "
                                + "blocks, tags, exclusions, "
                                + "allowAir, allowNonSupport or fluid.");
            }
        }

        /*
         * AIR does not use surface filters or fluid.
         */
        if (type == Type.AIR && fluid != null) {

            throw new IllegalArgumentException("AIR environment rule cannot specify a fluid.");
        }

        /*
         * ALL and SURFACE may use block/tag filters and
         * exclusions. FLUID uses only the fluid selector.
         *
         * We intentionally do not reject additional fields here,
         * because the parser/config format may evolve and the
         * validator itself defines how those fields behave.
         */
    }

    /**
     * No environment restriction.
     *
     * <p>This means:
     *
     * <p>under -> not checked above -> not checked
     *
     * <p>It is the implicit value for omitted under/above sections.
     */
    public static EnvironmentRule none() {
        return new EnvironmentRule(
                Type.NONE, 0, List.of(), List.of(), List.of(), List.of(), false, false, null);
    }

    /**
     * Default surface rule.
     *
     * <p>This is an explicit/default preset and is NOT the same as {@link #none()}.
     *
     * <p>It means:
     *
     * <p>type = SURFACE depth = 8
     *
     * <p>with no positive or negative block filters.
     */
    public static EnvironmentRule surfaceDefaults() {
        return new EnvironmentRule(
                Type.SURFACE,
                NPShipsStructureConfig.DEFAULT_ENVIRONMENT_DEPTH,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                null);
    }

    public enum Type {
        NONE,
        AIR,
        FLUID,
        ALL,
        SURFACE
    }
}
