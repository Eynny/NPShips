package com.eynny.npships.api;

import com.eynny.npships.config.WeatherRule;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Optional integrations register their actual server-side weather detector during setup. Names
 * alone cannot infer another mod's weather system. Predicates must be read-only, fast, and must not
 * load chunks. Several custom conditions can be active together.
 */
public final class SpawnWeather {
    private static final Map<String, BiPredicate<ServerLevel, BlockPos>> CUSTOM =
            new ConcurrentHashMap<>();

    private SpawnWeather() {}

    /**
     * Registers one custom detector during setup.
     *
     * @param id unique custom condition ID, at most 256 UTF-16 units
     * @param detector read-only server detector; must not load chunks
     * @throws IllegalArgumentException for duplicate IDs or exceeded limits
     */
    public static synchronized void register(
            ResourceLocation id, BiPredicate<ServerLevel, BlockPos> detector) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(detector);
        if (id.toString().length() > 256 || CUSTOM.size() >= 1024)
            throw new IllegalArgumentException("Weather registry limit exceeded");
        if (CUSTOM.putIfAbsent(id.toString(), detector) != null)
            throw new IllegalArgumentException("Duplicate weather: " + id);
    }

    /**
     * Lists the custom and vanilla conditions.
     *
     * @return sorted immutable names, including clear, rain and thunder
     */
    public static List<String> ids() {
        var result = new TreeSet<>(CUSTOM.keySet());
        result.addAll(List.of("clear", "rain", "thunder"));
        return List.copyOf(result);
    }

    /**
     * Evaluates one rule in a fresh synchronous server context.
     *
     * @param rule allow/forbid conditions
     * @param level target server level
     * @param origin native world position sampled by custom detectors
     * @return whether forbids are absent and the allow requirement is met
     */
    public static boolean allows(WeatherRule rule, ServerLevel level, BlockPos origin) {
        return context(level, origin).allows(rule);
    }

    /**
     * Read-only detector results shared only within one synchronous restriction evaluation batch.
     *
     * @param level target server level
     * @param origin native world position, copied on construction
     * @return batch context retaining the level only for this synchronous evaluation
     */
    public static Context context(ServerLevel level, BlockPos origin) {
        return new Context(level, origin);
    }

    /** Server-thread batch cache; do not retain it across evaluations or world lifetimes. */
    public static final class Context {
        private final ServerLevel level;
        private final BlockPos origin;
        private final Map<String, Boolean> checked = new HashMap<>();
        private String vanilla;

        private Context(ServerLevel level, BlockPos origin) {
            this.level = level;
            this.origin = origin.immutable();
        }

        /**
         * Evaluates forbids before allows, memoizing encountered detectors.
         *
         * @param rule conditions to evaluate
         * @return whether this rule permits placement
         */
        public boolean allows(WeatherRule rule) {
            if (rule.allow().isEmpty() && rule.forbid().isEmpty()) return true;
            if (vanilla == null)
                vanilla = level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear";
            if (rule.forbid().stream().anyMatch(this::active)) return false;
            return rule.allow().isEmpty() || rule.allow().stream().anyMatch(this::active);
        }

        private boolean active(String id) {
            return checked.computeIfAbsent(
                    id,
                    key -> {
                        if (key.equals(vanilla)) return true;
                        var detector = CUSTOM.get(key);
                        return detector != null && detector.test(level, origin);
                    });
        }
    }
}
