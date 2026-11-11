package com.eynny.npships.naturalspawn;

/**
 * Centralized runtime limits for the natural-spawn system.
 *
 * <p>Constants below are fallback defaults; production callers use the config-backed accessors.
 * Server settings are exposed through npships-server.toml.
 */
public final class NaturalSpawnLimits {

    private NaturalSpawnLimits() {}

    /**
     * Enables verbose diagnostics for the natural-spawn system.
     *
     * <p>false = normal logging true = detailed candidate filtering diagnostics
     */
    public static final boolean DEV_DIAGNOSTICS = false;

    /**
     * Successful-spawn cooldown, independent of the opportunity interval.
     *
     * <p>3 minutes.
     */
    public static final long RECENT_SPAWN_COOLDOWN_TICKS = 3L * 60L * 20L;

    public static final int RECENT_SPAWN_PLAYER_RADIUS = 1000;

    public static final double SPAWN_YAW_SPREAD_DEGREES = 45.0D;

    public static final int MAX_STRUCTURES_PER_ATTEMPT = 8;

    public static final int MAX_PHYSICAL_OVERLAP_RETRIES = 3;

    public static final int MAX_SEARCH_OPERATIONS_PER_TICK = 24000;

    public static final int MAX_SCAN_CHECKS_PER_TICK = 24000;

    public static final int MAX_CHUNK_OPERATIONS_PER_TICK = 16;

    /** Minimum horizontal search radius. */
    public static final int MIN_HORIZONTAL_RADIUS = 24;

    /** Maximum horizontal search radius. */
    public static final int MAX_HORIZONTAL_RADIUS = 128;

    /** Minimum vertical search radius. */
    public static final int MIN_VERTICAL_RADIUS = 16;

    /** Maximum vertical search radius. */
    public static final int MAX_VERTICAL_RADIUS = 96;

    /** Horizontal expansion amount. */
    public static final int HORIZONTAL_EXPANSION = 24;

    /** Vertical expansion amount. */
    public static final int VERTICAL_EXPANSION = 16;

    /**
     * Minimum horizontal distance from the selected player.
     *
     * <p>This keeps the structure from appearing immediately next to the player even when
     * simulation-distance is small.
     */
    public static final int MIN_PLAYER_HORIZONTAL_DISTANCE = 64;

    /**
     * Explicit maximum horizontal distance.
     *
     * <p>{@code <= 0} means:
     *
     * <p>derive from server simulation-distance.
     */
    public static final int MAX_PLAYER_HORIZONTAL_DISTANCE = 0;

    /**
     * Keep the initial point this many blocks inside the outer edge of the simulation area.
     *
     * <p>0 means exactly on the theoretical edge.
     */
    public static final int PLAYER_SIMULATION_DISTANCE_EDGE_MARGIN = 16;

    /** Minimum sane simulation-distance used for calculations. */
    public static final int MIN_EFFECTIVE_SIMULATION_DISTANCE_CHUNKS = 2;

    /**
     * Search-time chunk margin.
     *
     * <p>The search itself does not need the post-spawn safety ring.
     */
    public static final int SEARCH_CHUNK_MARGIN = 0;

    /** Post-spawn physical structure safety ring. */
    public static final int PHYSICAL_CHUNK_MARGIN = 1;

    /** Keep naturally spawned physical structures protected from chunk unloading for one minute. */
    public static final long NATURAL_PHYSICAL_LOAD_DURATION_TICKS = 60L * 20L;

    /** Compatibility accessor for the single server developer mode; no independent switch. */
    public static boolean dev_diagnostics() {
        return com.eynny.npships.config.NPShipsRuntimeConfig.developerModeEnabled();
    }

    public static long recent_spawn_cooldown_ticks() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "RECENT_SPAWN_COOLDOWN_TICKS", RECENT_SPAWN_COOLDOWN_TICKS))
                .longValue();
    }

    public static int recent_spawn_player_radius() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "RECENT_SPAWN_PLAYER_RADIUS", RECENT_SPAWN_PLAYER_RADIUS))
                .intValue();
    }

    public static double spawn_yaw_spread_degrees() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "SPAWN_YAW_SPREAD_DEGREES", SPAWN_YAW_SPREAD_DEGREES))
                .doubleValue();
    }

    public static int max_structures_per_attempt() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MAX_STRUCTURES_PER_ATTEMPT", MAX_STRUCTURES_PER_ATTEMPT))
                .intValue();
    }

    public static int max_physical_overlap_retries() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MAX_PHYSICAL_OVERLAP_RETRIES", MAX_PHYSICAL_OVERLAP_RETRIES))
                .intValue();
    }

    public static int max_search_operations_per_tick() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MAX_SEARCH_OPERATIONS_PER_TICK", MAX_SEARCH_OPERATIONS_PER_TICK))
                .intValue();
    }

    public static int max_scan_checks_per_tick() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MAX_SCAN_CHECKS_PER_TICK", MAX_SCAN_CHECKS_PER_TICK))
                .intValue();
    }

    public static int max_chunk_operations_per_tick() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MAX_CHUNK_OPERATIONS_PER_TICK", MAX_CHUNK_OPERATIONS_PER_TICK))
                .intValue();
    }

    public static int min_horizontal_radius() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MIN_HORIZONTAL_RADIUS", MIN_HORIZONTAL_RADIUS))
                .intValue();
    }

    public static int max_horizontal_radius() {
        return Math.max(
                min_horizontal_radius(),
                ((Number)
                                com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                        "MAX_HORIZONTAL_RADIUS", MAX_HORIZONTAL_RADIUS))
                        .intValue());
    }

    public static int min_vertical_radius() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MIN_VERTICAL_RADIUS", MIN_VERTICAL_RADIUS))
                .intValue();
    }

    public static int max_vertical_radius() {
        return Math.max(
                min_vertical_radius(),
                ((Number)
                                com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                        "MAX_VERTICAL_RADIUS", MAX_VERTICAL_RADIUS))
                        .intValue());
    }

    public static int horizontal_expansion() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "HORIZONTAL_EXPANSION", HORIZONTAL_EXPANSION))
                .intValue();
    }

    public static int vertical_expansion() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "VERTICAL_EXPANSION", VERTICAL_EXPANSION))
                .intValue();
    }

    public static int min_player_horizontal_distance() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MIN_PLAYER_HORIZONTAL_DISTANCE", MIN_PLAYER_HORIZONTAL_DISTANCE))
                .intValue();
    }

    public static int max_player_horizontal_distance() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MAX_PLAYER_HORIZONTAL_DISTANCE", MAX_PLAYER_HORIZONTAL_DISTANCE))
                .intValue();
    }

    public static int player_simulation_distance_edge_margin() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "PLAYER_SIMULATION_DISTANCE_EDGE_MARGIN",
                                PLAYER_SIMULATION_DISTANCE_EDGE_MARGIN))
                .intValue();
    }

    public static int min_effective_simulation_distance_chunks() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "MIN_EFFECTIVE_SIMULATION_DISTANCE_CHUNKS",
                                MIN_EFFECTIVE_SIMULATION_DISTANCE_CHUNKS))
                .intValue();
    }

    public static int search_chunk_margin() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "SEARCH_CHUNK_MARGIN", SEARCH_CHUNK_MARGIN))
                .intValue();
    }

    public static int physical_chunk_margin() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "PHYSICAL_CHUNK_MARGIN", PHYSICAL_CHUNK_MARGIN))
                .intValue();
    }

    public static long natural_physical_load_duration_ticks() {
        return ((Number)
                        com.eynny.npships.config.NPShipsRuntimeConfig.value(
                                "NATURAL_PHYSICAL_LOAD_DURATION_TICKS",
                                NATURAL_PHYSICAL_LOAD_DURATION_TICKS))
                .longValue();
    }
}
