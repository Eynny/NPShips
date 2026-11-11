package com.eynny.npships.config;

import java.util.List;

/**
 * Immutable configuration of one NPShips structure.
 *
 * <p>The actual structure dimensions are NOT stored here. They are obtained from the corresponding
 * NBT schematic.
 */
public record NPShipsStructureConfig(
        double selectionWeight,
        EnvironmentConfig environment,
        RestrictionsConfig restrictions,
        PlacementConfig placement,
        List<PatchConfig> patches,
        List<com.eynny.npships.authoring.LootAssignments.Entry> lootContainers,
        List<com.eynny.npships.authoring.StructureSegments.Segment> segments,
        com.google.gson.JsonObject authoring,
        RarityRule rarity) {

    public static final int DEFAULT_ENVIRONMENT_DEPTH = 8;

    public NPShipsStructureConfig {
        rarity = rarity == null ? RarityRule.automaticRule() : rarity;
        authoring = authoring == null ? new com.google.gson.JsonObject() : authoring.deepCopy();
        segments = segments == null ? List.of() : List.copyOf(segments);
        lootContainers = lootContainers == null ? List.of() : List.copyOf(lootContainers);
        if (!Double.isFinite(selectionWeight) || selectionWeight <= 0)
            throw new IllegalArgumentException(
                    "selection_weight must be a positive finite number.");

        environment = environment == null ? EnvironmentConfig.defaults() : environment;

        restrictions = restrictions == null ? RestrictionsConfig.defaults() : restrictions;

        placement = placement == null ? PlacementConfig.defaults() : placement;

        patches = patches == null ? List.of() : List.copyOf(patches);
    }

    public NPShipsStructureConfig(
            double selectionWeight,
            EnvironmentConfig environment,
            RestrictionsConfig restrictions,
            PlacementConfig placement,
            List<PatchConfig> patches,
            List<com.eynny.npships.authoring.LootAssignments.Entry> loot,
            List<com.eynny.npships.authoring.StructureSegments.Segment> segments,
            com.google.gson.JsonObject authoring) {
        this(
                selectionWeight,
                environment,
                restrictions,
                placement,
                patches,
                loot,
                segments,
                authoring,
                RarityRule.automaticRule());
    }

    public NPShipsStructureConfig(
            EnvironmentConfig environment,
            RestrictionsConfig restrictions,
            PlacementConfig placement,
            List<PatchConfig> patches) {
        this(environment, restrictions, placement, patches, List.of());
    }

    public NPShipsStructureConfig(
            EnvironmentConfig environment,
            RestrictionsConfig restrictions,
            PlacementConfig placement,
            List<PatchConfig> patches,
            List<com.eynny.npships.authoring.LootAssignments.Entry> loot) {
        this(environment, restrictions, placement, patches, loot, List.of());
    }

    public NPShipsStructureConfig(
            EnvironmentConfig environment,
            RestrictionsConfig restrictions,
            PlacementConfig placement,
            List<PatchConfig> patches,
            List<com.eynny.npships.authoring.LootAssignments.Entry> loot,
            List<com.eynny.npships.authoring.StructureSegments.Segment> segments) {
        this(
                1.0,
                environment,
                restrictions,
                placement,
                patches,
                loot,
                segments,
                new com.google.gson.JsonObject());
    }

    @Override
    public com.google.gson.JsonObject authoring() {
        return authoring.deepCopy();
    }

    public static NPShipsStructureConfig defaults() {
        return new NPShipsStructureConfig(
                EnvironmentConfig.defaults(),
                RestrictionsConfig.defaults(),
                PlacementConfig.defaults(),
                List.of());
    }

    /**
     * Environment requirements around the schematic.
     *
     * <p>Missing "under" / "above" sections mean:
     *
     * <p>Type.NONE
     *
     * <p>and therefore that side is not checked at all.
     *
     * <p>Missing "inside" means:
     *
     * <p>CLEAR
     *
     * <p>and therefore the schematic volume must be empty.
     */
    public record EnvironmentConfig(
            EnvironmentRule under, EnvironmentRule above, InsideRule inside) {

        public EnvironmentConfig {
            /*
             * Omitted under/above must disable the corresponding
             * environment check completely.
             */
            under = under == null ? EnvironmentRule.none() : under;

            above = above == null ? EnvironmentRule.none() : above;

            /*
             * By default nothing may already occupy the
             * schematic volume.
             */
            inside = inside == null ? InsideRule.clear() : inside;
        }

        public boolean requiresSnapshot() {
            return under.type() != EnvironmentRule.Type.NONE
                    || above.type() != EnvironmentRule.Type.NONE
                    || inside.type() != InsideRule.Type.IGNORE;
        }

        public static EnvironmentConfig defaults() {
            return new EnvironmentConfig(
                    EnvironmentRule.none(), EnvironmentRule.none(), InsideRule.clear());
        }
    }

    /**
     * Reference-point restrictions are checked before the expensive environment scan. Height ranges
     * are checked against each candidate origin during placement search.
     *
     * <p>This order is intentional:
     *
     * <p>difficulty dimension biome time of day weather real date real time environment
     *
     * <p>Environment validation should only happen after all restrictions have passed.
     */
    public record RestrictionsConfig(
            DifficultyRule difficulty,
            RegistryFilter dimensions,
            BiomeFilter biomes,
            TimeOfDayRule timeOfDay,
            WeatherRule weather,
            RealDateRule realDate,
            RealTimeRule realTime,
            List<HeightRange> heightRanges) {

        public RestrictionsConfig {
            heightRanges = heightRanges == null ? List.of() : List.copyOf(heightRanges);
            difficulty = difficulty == null ? DifficultyRule.defaults() : difficulty;

            dimensions = dimensions == null ? RegistryFilter.allowAll() : dimensions;

            biomes = biomes == null ? BiomeFilter.allowAll() : biomes;

            timeOfDay = timeOfDay == null ? TimeOfDayRule.allowAll() : timeOfDay;

            weather = weather == null ? WeatherRule.allowAll() : weather;

            realDate = realDate == null ? RealDateRule.allowAll() : realDate;

            realTime = realTime == null ? RealTimeRule.allowAll() : realTime;
        }

        /** Compatibility constructor: omitted height ranges allow all origin heights. */
        public RestrictionsConfig(
                DifficultyRule difficulty,
                RegistryFilter dimensions,
                BiomeFilter biomes,
                TimeOfDayRule timeOfDay,
                WeatherRule weather,
                RealDateRule realDate,
                RealTimeRule realTime) {
            this(difficulty, dimensions, biomes, timeOfDay, weather, realDate, realTime, List.of());
        }

        public boolean allowsOriginY(final int y) {
            return heightRanges.isEmpty()
                    || heightRanges.stream().anyMatch(range -> range.contains(y));
        }

        public static RestrictionsConfig defaults() {
            return new RestrictionsConfig(
                    DifficultyRule.defaults(),
                    RegistryFilter.allowAll(),
                    BiomeFilter.allowAll(),
                    TimeOfDayRule.allowAll(),
                    WeatherRule.allowAll(),
                    RealDateRule.allowAll(),
                    RealTimeRule.allowAll());
        }
    }

    /**
     * Location-related restrictions.
     *
     * <p>The actual schematic dimensions are not stored here. They are obtained from the loaded NBT
     * structure.
     */
    public record PlacementConfig(
            boolean avoidPhysicalStructures,
            double rotationSpreadDegrees,
            boolean requireRotationSpace,
            boolean disableAutoRotation) {
        public PlacementConfig {
            if (!Double.isFinite(rotationSpreadDegrees)
                    || rotationSpreadDegrees < 0
                    || rotationSpreadDegrees > 180) {
                throw new IllegalArgumentException(
                        "placement.rotation_spread_degrees must be between 0 and 180.");
            }
        }

        public PlacementConfig(boolean avoidPhysicalStructures) {
            this(avoidPhysicalStructures, 45.0D, false, false);
        }

        public static PlacementConfig defaults() {
            return new PlacementConfig(true);
        }
    }

    /**
     * Legacy typed slot retained for constructor compatibility. Actual patch payloads are preserved
     * as JSON/NBT and interpreted by authoring.patches and integration adapters.
     */
    public record PatchConfig() {}
}
