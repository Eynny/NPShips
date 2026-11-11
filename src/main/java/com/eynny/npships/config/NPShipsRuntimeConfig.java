package com.eynny.npships.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.*;

public final class NPShipsRuntimeConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue DEV_MODE,
            CLAIM_WAVES,
            MOB_MOVEMENT_FIX,
            MOB_CROSS_STRUCTURE_FIX,
            MOB_MOUNTED_ATTACK_FIX;
    private static final Map<String, ModConfigSpec.ConfigValue<?>> VALUES = new HashMap<>();
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.EnumValue<StructureSpacing> STRUCTURE_SPACING;

    public enum StructureSpacing implements net.neoforged.neoforge.common.TranslatableEnum {
        OFF(0),
        HALF(.5),
        ONE(1),
        TWO(2);

        private final double multiplier;

        StructureSpacing(double multiplier) {
            this.multiplier = multiplier;
        }

        public double multiplier() {
            return multiplier;
        }

        @Override
        public net.minecraft.network.chat.Component getTranslatedName() {
            return net.minecraft.network.chat.Component.translatable(
                    "npships.configuration.structure_spacing."
                            + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    public static final ModConfigSpec.DoubleValue RARITY_PIVOT_BLOCKS,
            RARITY_SCALE_BLOCKS,
            RARITY_SIZE_EXPONENT,
            RARITY_TAIL_EXPONENT,
            RARITY_PRESSURE_STRENGTH,
            RARITY_MAXIMUM_PRESSURE;
    public static final ModConfigSpec.LongValue RARITY_HALF_LIFE_TICKS;
    public static final ModConfigSpec.IntValue RARITY_FIELD_EXTRA_BLOCKS;
    public static final ModConfigSpec.LongValue OPPORTUNITY_INTERVAL_MIN_TICKS,
            OPPORTUNITY_INTERVAL_MAX_TICKS;
    public static final ModConfigSpec.IntValue OPPORTUNITY_PLAYER_CAP, COOLDOWN_PLAYER_CAP;
    public static final ModConfigSpec.DoubleValue MIN_PLAYER_STRUCTURE_DISTANCE;
    public static final ModConfigSpec.IntValue MAX_PLAYER_STRUCTURE_HORIZONTAL_DISTANCE,
            PLAYER_RELEVANCE_EXTRA_BLOCKS;
    public static final ModConfigSpec.LongValue UNLOAD_MS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_ADDONS,
            DISABLED_STRUCTURES,
            ENTITY_CLEANUP_WHITELIST,
            DISABLED_PATCH_TYPES,
            PATCH_TYPE_ORDER;

    static {
        var b = new ModConfigSpec.Builder();
        DEV_MODE =
                b.comment(
                                "Enable developer conveniences, OP-only spawn diagnostics and verbose NPShips diagnostics. Does not grant operator permissions. Live setting; disabled by default.")
                        .define("developer_mode", false);
        VALUES.put("DEVELOPER_MODE", DEV_MODE);
        CLAIM_WAVES =
                b.comment(
                                "Enable Claim surface waves for every player. Disabling stops new wave packets and clears active client waves on server configuration reload. Claim retention, block animation and sound are unchanged. Live setting.")
                        .define("claim_waves_enabled", true);
        MOB_MOVEMENT_FIX =
                b.comment(
                                "Use precise waypoint arrival within one physical structure. Preserves native tolerances, targeting, collision and flight. Live setting; enabled by default.")
                        .translation("npships.configuration.mob_movement_fix")
                        .define("mob_movement_fix", true);
        MOB_CROSS_STRUCTURE_FIX =
                b.comment(
                                "Find ground routes between physical structures and the world. Live setting; enabled by default.")
                        .translation("npships.configuration.mob_cross_structure_fix")
                        .define("mob_cross_structure_fix", true);
        MOB_MOUNTED_ATTACK_FIX =
                b.comment(
                                "Correct mixed world/physical-plot vehicle bounds in mounted mob melee attacks. Preserves native reach and damage. Live setting; enabled by default.")
                        .translation("npships.configuration.mob_mounted_attack_fix")
                        .define("mob_mounted_attack_fix", true);
        b.push("automatic");
        ENABLED = b.define("enabled", true);
        STRUCTURE_SPACING =
                b.comment(
                                "Minimum 3D distance from a new automatic search reference to tracked physical structure bounds, as a fraction of the effective server simulation distance.",
                                "OFF disables this restriction; HALF is the default. Rejected random references are retried before structure selection. Explicit manual/external spawn references are unchanged.")
                        .translation("npships.configuration.structure_spacing")
                        .defineEnum("structure_spacing", StructureSpacing.HALF);
        MIN_PLAYER_STRUCTURE_DISTANCE =
                b.comment(
                                "Minimum 3D distance from every non-spectator player's current world position to the automatic structure placement volume; exact boundary is allowed. 0 disables protection.",
                                "Distinct from limits.min_player_horizontal_distance, which only chooses the initial search reference. Manual/external/forced spawning is unchanged.")
                        .defineInRange("min_player_structure_distance", 64.0, 0.0, 1000000.0);
        MAX_PLAYER_STRUCTURE_HORIZONTAL_DISTANCE =
                b.comment(
                                "Maximum horizontal distance to the automatic structure's continuous footprint for at least one current non-spectator player in its dimension. Exact boundary is allowed; manual/external/forced spawning is unchanged.",
                                "-1: automatic max(16*max(effective simulation chunk floor, server simulation chunks)-simulation edge margin, minimum 3D structure distance+16, 1)+player_relevance_extra_blocks. 0: disabled. Positive: explicit blocks; no extra allowance is added.",
                                "For yaw, both original and rotated footprints need a witness (possibly different players). This conservative rule covers uncertain static/physical output. Distinct from limits.min/max_player_horizontal_distance, which only choose the initial search reference.")
                        .defineInRange("max_player_structure_horizontal_distance", -1, -1, 1000000);
        PLAYER_RELEVANCE_EXTRA_BLOCKS =
                b.comment(
                                "Additional blocks for the automatic (-1) maximum relevance distance only. Default 256 gives search displacement and player travel room after the initial reference is pinned.",
                                "Does not expand initial references, terrain search or rarity fields; explicit positive maxima and0 stay exact. Live checks still reject players who travel beyond the resulting limit. 0 restores the original simulation-derived maximum.")
                        .defineInRange("player_relevance_extra_blocks", 256, 0, 1000000);
        b.push("rarity");
        RARITY_PIVOT_BLOCKS =
                b.comment(
                                "Experimental RELATIVE size rarity, not an admission probability: logOdds=a*log(min(N,K)/S)+b*max(0,log(N/K)); p=logistic(-logOdds). K=pivot_blocks, S=scale_blocks, a=size_exponent, b=tail_exponent. Manual overrides remain available.")
                        .defineInRange("pivot_blocks", 20000.0, 1.0, 1.0e15);
        RARITY_SCALE_BLOCKS = b.defineInRange("scale_blocks", 6000.0, 1.0, 1.0e15);
        RARITY_SIZE_EXPONENT = b.defineInRange("size_exponent", 2.6, 0.01, 8.0);
        RARITY_TAIL_EXPONENT =
                b.comment(
                                "Tail exponent above the pivot. Every valid restriction-eligible ship participates. q=p^(1+strength*H), group=round(100*q); group weight=mean(q), member share=q*selection_weight/sum(q*selection_weight). Author weight only redistributes within the group. No rarity gate or gamma compensation.")
                        .defineInRange("tail_exponent", .1, .01, 8.0);
        RARITY_HALF_LIFE_TICKS =
                b.comment(
                                "Temporal pressure half-life for new successes. Existing events keep their saved half-life; expire after 12 half-lives. Default24000 ticks is20min; no offline real-time decay.")
                        .defineInRange("pressure_half_life_ticks", 24000L, 1L, 1000000000L);
        RARITY_PRESSURE_STRENGTH =
                b.comment(
                                "Effective relative rarity q=authorRarity^(1+strength*frozenPressure). Sampled once at INITIAL_POINT; no FOUND/final recheck. A lone valid ship is always selected even at very low rarity.")
                        .defineInRange("pressure_strength", 2.75, 0.0, 16.0);
        RARITY_MAXIMUM_PRESSURE =
                b.comment(
                                "Cap accumulated pressure for EACH owner/dimension. New successes fill only the remaining capacity; excess is discarded, never stored for later decay. Player fields combine with MAX, not sum.")
                        .defineInRange("maximum_pressure", 2.0, 0.0, 16.0);
        RARITY_FIELD_EXTRA_BLOCKS =
                b.comment(
                                "Constant-strength spherical player field radius =16*max(effective server simulation floor, SERVER simulation chunks)+extra. XYZ world distance, exact edge included; zero outside. No client view distance, falloff or hysteresis.")
                        .defineInRange("field_extra_blocks", 160, 0, 1000000);
        b.pop();
        OPPORTUNITY_INTERVAL_MIN_TICKS =
                b.comment(
                                "Minimum game ticks after a complete automatic spawn opportunity before the next. One inclusive base draw, default 8400..20400 ticks (7..17 minutes at 20 TPS), divided by sqrt(min(eligible players, opportunity_player_cap)). Floor: min(configured minimum,2400 ticks). Opportunities are not guaranteed spawns.",
                                "One active attempt, no missed-opportunity backlog or exponential backoff. Changes affect the next sampled deadline, not an already saved deadline.")
                        .defineInRange("opportunity_interval_min_ticks", 8400L, 1L, 1000000L);
        OPPORTUNITY_INTERVAL_MAX_TICKS =
                b.comment(
                                "Maximum ticks in the inclusive automatic opportunity interval. If configured below the minimum, the effective maximum is the minimum (a fixed delay). Equal bounds also give a fixed delay; zero is invalid. Successful-spawn cooldown is configured separately in limits.recent_spawn_cooldown_ticks.")
                        .defineInRange("opportunity_interval_max_ticks", 20400L, 1L, 1000000L);
        OPPORTUNITY_PLAYER_CAP =
                b.comment(
                                "Maximum eligible players counted when scaling the next opportunity interval. 1 disables player-count scaling. Default 8. Live: existing deadlines remain unchanged.")
                        .defineInRange("opportunity_player_cap", 8, 1, Integer.MAX_VALUE);
        DISABLED_ADDONS =
                b.comment(
                                "Resource namespaces excluded from automatic spawning; commands remain available.")
                        .worldRestart()
                        .defineListAllowEmpty(
                                "disabled_addons",
                                List.of(),
                                () -> "example",
                                v -> v instanceof String);
        DISABLED_STRUCTURES =
                b.comment(
                                "Exact schematic ids excluded from automatic spawning; manual commands remain available.")
                        .defineListAllowEmpty(
                                "disabled_structures",
                                List.of(),
                                () -> "example:ship",
                                v -> v instanceof String);
        b.pop();
        b.push("lifecycle");
        ENTITY_CLEANUP_WHITELIST =
                b.comment(
                                "Exact entity type IDs preserved by NPShips area-based physical-structure cleanup, for loaded and holding bodies. Players are always preserved.",
                                "Empty by default: existing cleanup behavior is unchanged. No wildcards; optional-mod IDs may be listed before that mod is installed. Changes affect the next cleanup; no restart required.")
                        .defineListAllowEmpty(
                                "entity_cleanup_whitelist",
                                List.of(),
                                () -> "minecraft:pig",
                                v -> {
                                    if (!(v instanceof String text)) return false;
                                    var id =
                                            net.minecraft.resources.ResourceLocation.tryParse(text);
                                    return id != null && id.toString().equals(text);
                                });
        UNLOAD_MS =
                b.comment(
                                "Real elapsed unload grace. Stored in milliseconds for compatibility; the settings screen displays seconds. Default 300000 = five minutes. Requires world/server restart.")
                        .worldRestart()
                        .defineInRange("unload_timeout_ms", 300000L, 0L, Long.MAX_VALUE);
        b.pop();
        b.push("limits");
        VALUES.put(
                "RECENT_SPAWN_COOLDOWN_TICKS",
                b.comment(
                                "Base successful automatic-spawn cooldown. At success, multiply by sqrt(min(eligible players, recent_spawn_player_cap)); default base 3600 ticks and cap 4 give 3..6 minutes. Existing expiry times remain unchanged; independent of opportunity interval bounds. The radius follows the reference player, not the spawned ship.")
                        .worldRestart()
                        .defineInRange(
                                "recent_spawn_cooldown_ticks",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .RECENT_SPAWN_COOLDOWN_TICKS,
                                1L,
                                1000000L));
        COOLDOWN_PLAYER_CAP =
                b.comment(
                                "Maximum eligible players counted when scaling a new successful-spawn cooldown. 1 disables player-count scaling. Default 4. World/server restart required; existing expiry times remain unchanged.")
                        .worldRestart()
                        .defineInRange("recent_spawn_player_cap", 4, 1, Integer.MAX_VALUE);
        VALUES.put(
                "RECENT_SPAWN_PLAYER_RADIUS",
                b.worldRestart()
                        .defineInRange(
                                "recent_spawn_player_radius",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .RECENT_SPAWN_PLAYER_RADIUS,
                                0,
                                4096));
        VALUES.put(
                "MAX_STRUCTURES_PER_ATTEMPT",
                b.comment(
                                "Independent weighted draw slots per opportunity (1..8). A duplicate consumes its slot without another search or redraw.")
                        .worldRestart()
                        .defineInRange(
                                "max_structures_per_attempt",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MAX_STRUCTURES_PER_ATTEMPT,
                                1,
                                8));
        VALUES.put(
                "MAX_PHYSICAL_OVERLAP_RETRIES",
                b.worldRestart()
                        .defineInRange(
                                "max_physical_overlap_retries",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MAX_PHYSICAL_OVERLAP_RETRIES,
                                0,
                                1000000));
        VALUES.put(
                "MAX_SEARCH_OPERATIONS_PER_TICK",
                b.worldRestart()
                        .defineInRange(
                                "max_search_operations_per_tick",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MAX_SEARCH_OPERATIONS_PER_TICK,
                                1,
                                1000000));
        VALUES.put(
                "MAX_SCAN_CHECKS_PER_TICK",
                b.worldRestart()
                        .defineInRange(
                                "max_scan_checks_per_tick",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MAX_SCAN_CHECKS_PER_TICK,
                                1,
                                1000000));
        VALUES.put(
                "MAX_CHUNK_OPERATIONS_PER_TICK",
                b.worldRestart()
                        .defineInRange(
                                "max_chunk_operations_per_tick",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MAX_CHUNK_OPERATIONS_PER_TICK,
                                1,
                                1000000));
        VALUES.put(
                "MIN_HORIZONTAL_RADIUS",
                b.worldRestart()
                        .defineInRange(
                                "min_horizontal_radius",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MIN_HORIZONTAL_RADIUS,
                                0,
                                4096));
        VALUES.put(
                "MAX_HORIZONTAL_RADIUS",
                b.worldRestart()
                        .defineInRange(
                                "max_horizontal_radius",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MAX_HORIZONTAL_RADIUS,
                                0,
                                4096));
        VALUES.put(
                "MIN_VERTICAL_RADIUS",
                b.worldRestart()
                        .defineInRange(
                                "min_vertical_radius",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MIN_VERTICAL_RADIUS,
                                0,
                                4096));
        VALUES.put(
                "MAX_VERTICAL_RADIUS",
                b.worldRestart()
                        .defineInRange(
                                "max_vertical_radius",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MAX_VERTICAL_RADIUS,
                                0,
                                4096));
        VALUES.put(
                "MIN_PLAYER_HORIZONTAL_DISTANCE",
                b.worldRestart()
                        .defineInRange(
                                "min_player_horizontal_distance",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MIN_PLAYER_HORIZONTAL_DISTANCE,
                                0,
                                1000000));
        VALUES.put(
                "MAX_PLAYER_HORIZONTAL_DISTANCE",
                b.worldRestart()
                        .defineInRange(
                                "max_player_horizontal_distance",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MAX_PLAYER_HORIZONTAL_DISTANCE,
                                0,
                                1000000));
        VALUES.put(
                "PLAYER_SIMULATION_DISTANCE_EDGE_MARGIN",
                b.worldRestart()
                        .defineInRange(
                                "player_simulation_distance_edge_margin",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .PLAYER_SIMULATION_DISTANCE_EDGE_MARGIN,
                                0,
                                1000000));
        VALUES.put(
                "MIN_EFFECTIVE_SIMULATION_DISTANCE_CHUNKS",
                b.worldRestart()
                        .defineInRange(
                                "min_effective_simulation_distance_chunks",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .MIN_EFFECTIVE_SIMULATION_DISTANCE_CHUNKS,
                                0,
                                1000000));
        VALUES.put(
                "SEARCH_CHUNK_MARGIN",
                b.comment(
                                "Extra chunk ring for dense placement preload, including rotation clearance. Sparse terrain search requests exact chunks without this ring.",
                                "Default 0. Separate from native chunk dependencies and post-spawn physical load protection. World/server restart required.")
                        .worldRestart()
                        .defineInRange(
                                "search_chunk_margin",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .SEARCH_CHUNK_MARGIN,
                                0,
                                1000000));
        VALUES.put(
                "NATURAL_PHYSICAL_LOAD_DURATION_TICKS",
                b.comment(
                                "Post-spawn physical chunk load protection. Default 1200 ticks (one minute at 20 TPS); 0 disables extra protection. World/server restart required. Existing saved ticket expiry times remain unchanged.")
                        .worldRestart()
                        .defineInRange(
                                "natural_physical_load_duration_ticks",
                                com.eynny.npships.naturalspawn.NaturalSpawnLimits
                                        .NATURAL_PHYSICAL_LOAD_DURATION_TICKS,
                                0L,
                                1000000L));
        b.pop();
        com.eynny.npships.resources.ServerResourcePolicy.define(b);
        b.push("patches");
        DISABLED_PATCH_TYPES =
                b.comment(
                                "Provider type IDs disabled for new placements. Required restoration refuses before preparation; only providers explicitly declaring safe skipping may be skipped. Native and addon handlers share the same policy. Unknown IDs are retained. World/server restart required.")
                        .worldRestart()
                        .translation("npships.configuration.patches.disabled_types")
                        .defineListAllowEmpty(
                                "disabled_types",
                                List.of(),
                                () -> "example:device",
                                NPShipsRuntimeConfig::patchTypeId);
        PATCH_TYPE_ORDER =
                b.comment(
                                "Preferred provider type IDs, first to last, within each fixed lifecycle stage or source unit. Mandatory dependencies always take precedence. Native assembly and cleanup cannot be reordered. Unknown IDs are retained. Duplicate IDs are invalid. World/server restart required.")
                        .worldRestart()
                        .translation("npships.configuration.patches.preferred_order")
                        .defineListAllowEmpty(
                                "preferred_order",
                                List.of(),
                                () -> "example:device",
                                NPShipsRuntimeConfig::patchTypeId);
        b.pop();
        SPEC = b.build();
    }

    private static boolean patchTypeId(Object value) {
        return value instanceof String text
                && text.length() <= 256
                && net.minecraft.resources.ResourceLocation.tryParse(text) != null
                && net.minecraft.resources.ResourceLocation.parse(text).toString().equals(text);
    }

    /** A placement owns one detached policy; config reload cannot reorder an in-flight handler. */
    public static com.eynny.npships.api.patches.PatchPolicy patchPolicy() {
        if (!SPEC.isLoaded()) return com.eynny.npships.api.patches.PatchPolicy.defaults();
        var disabled = new java.util.LinkedHashSet<net.minecraft.resources.ResourceLocation>();
        for (String id : DISABLED_PATCH_TYPES.get())
            disabled.add(net.minecraft.resources.ResourceLocation.parse(id));
        var order =
                PATCH_TYPE_ORDER.get().stream()
                        .map(net.minecraft.resources.ResourceLocation::parse)
                        .toList();
        return new com.eynny.npships.api.patches.PatchPolicy(disabled, order);
    }

    public static Object value(String key, Object fallback) {
        return SPEC.isLoaded() && VALUES.containsKey(key) ? VALUES.get(key).get() : fallback;
    }

    public static boolean developerModeEnabled() {
        return SPEC.isLoaded() && DEV_MODE.get();
    }

    public static boolean enabled() {
        return !SPEC.isLoaded() || ENABLED.get();
    }

    public static double structureSpacingMultiplier() {
        return (SPEC.isLoaded() ? STRUCTURE_SPACING.get() : StructureSpacing.HALF).multiplier();
    }

    public static double minimumPlayerStructureDistance() {
        return SPEC.isLoaded() ? MIN_PLAYER_STRUCTURE_DISTANCE.get() : 64.0;
    }

    public static int maximumPlayerStructureHorizontalDistance() {
        return SPEC.isLoaded() ? MAX_PLAYER_STRUCTURE_HORIZONTAL_DISTANCE.get() : -1;
    }

    public static int playerRelevanceExtraBlocks() {
        return SPEC.isLoaded() ? PLAYER_RELEVANCE_EXTRA_BLOCKS.get() : 256;
    }

    public static RarityParameters rarity() {
        if (!SPEC.isLoaded()) return RarityParameters.DEFAULTS;
        return new RarityParameters(
                RARITY_PIVOT_BLOCKS.get(),
                RARITY_SCALE_BLOCKS.get(),
                RARITY_SIZE_EXPONENT.get(),
                RARITY_TAIL_EXPONENT.get(),
                RARITY_HALF_LIFE_TICKS.get(),
                RARITY_PRESSURE_STRENGTH.get(),
                RARITY_MAXIMUM_PRESSURE.get(),
                RARITY_FIELD_EXTRA_BLOCKS.get());
    }

    public static long opportunityIntervalMinTicks() {
        return SPEC.isLoaded() ? OPPORTUNITY_INTERVAL_MIN_TICKS.get() : 8400L;
    }

    public static long opportunityIntervalMaxTicks() {
        return Math.max(
                opportunityIntervalMinTicks(),
                SPEC.isLoaded() ? OPPORTUNITY_INTERVAL_MAX_TICKS.get() : 20400L);
    }

    public static int opportunityPlayerCap() {
        return SPEC.isLoaded() ? OPPORTUNITY_PLAYER_CAP.get() : 8;
    }

    public static int cooldownPlayerCap() {
        return SPEC.isLoaded() ? COOLDOWN_PLAYER_CAP.get() : 4;
    }

    public static long unloadMs() {
        return SPEC.isLoaded() ? UNLOAD_MS.get() : 300000L;
    }

    /** Capture once per cleanup, not once per entity or through a world-retaining cache. */
    public static Set<String> entityCleanupWhitelist() {
        return SPEC.isLoaded() ? Set.copyOf(ENTITY_CLEANUP_WHITELIST.get()) : Set.of();
    }

    public static boolean mobCrossStructureFixEnabled() {
        return SPEC.isLoaded() && MOB_CROSS_STRUCTURE_FIX.get();
    }

    public static boolean mobMountedAttackFixEnabled() {
        return SPEC.isLoaded() && MOB_MOUNTED_ATTACK_FIX.get();
    }

    public static boolean mobMovementFixEnabled() {
        return !SPEC.isLoaded() || MOB_MOVEMENT_FIX.get();
    }

    public static boolean claimWavesEnabled() {
        return !SPEC.isLoaded() || CLAIM_WAVES.get();
    }

    public static boolean addonEnabled(String id) {
        return !SPEC.isLoaded() || !DISABLED_ADDONS.get().contains(id);
    }

    public static boolean structureEnabled(String id) {
        return !SPEC.isLoaded() || !DISABLED_STRUCTURES.get().contains(id);
    }
}
