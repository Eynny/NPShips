package com.eynny.npships.config;

import static com.eynny.npships.config.ConfigJson.*;
import static com.eynny.npships.config.EnvironmentConfigParser.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.world.Difficulty;

import java.util.ArrayList;
import java.util.List;

/** Internal parser; shared validation and error semantics stay unchanged. */
final class RestrictionsConfigParser {
    private RestrictionsConfigParser() {}

    static NPShipsStructureConfig.RestrictionsConfig parseRestrictions(final JsonObject json) {
        if (json == null) {
            return NPShipsStructureConfig.RestrictionsConfig.defaults();
        }
        return new NPShipsStructureConfig.RestrictionsConfig(
                parseDifficulty(getObject(json, "difficulty")),
                parseRegistryFilter(getObject(json, "dimensions")),
                parseBiomeFilter(getObject(json, "biomes")),
                parseTimeOfDay(getObject(json, "time_of_day")),
                parseWeather(getObject(json, "weather")),
                parseRealDate(getObject(json, "real_date")),
                parseRealTime(getObject(json, "real_time")),
                parseHeightRanges(json));
    }

    static List<HeightRange> parseHeightRanges(final JsonObject json) {
        final List<HeightRange> result = new ArrayList<>();
        for (final JsonElement element : getArray(json, "height_ranges")) {
            if (!element.isJsonObject()) {
                throw new JsonParseException("Each height_ranges entry must be an object.");
            }
            final JsonObject range = element.getAsJsonObject();
            final int min = parseHeightBound(range, "min", Integer.MIN_VALUE);
            final int max = parseHeightBound(range, "max", Integer.MAX_VALUE);
            if (min > max) {
                throw new JsonParseException("height_ranges min must not exceed max.");
            }
            result.add(new HeightRange(min, max));
        }
        return List.copyOf(result);
    }

    static int parseHeightBound(final JsonObject range, final String key, final int fallback) {
        if (!range.has(key)) {
            return fallback;
        }
        final JsonElement value = range.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("Height bound '" + key + "' must be an integer.");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new JsonParseException(
                    "Height bound '" + key + "' must be a 32-bit integer.", exception);
        }
    }

    static DifficultyRule parseDifficulty(final JsonObject json) {
        if (json == null) {
            return DifficultyRule.defaults();
        }
        final Difficulty min = parseDifficultyValue(getString(json, "min", "peaceful"));
        final Difficulty max =
                json.has("max") ? parseDifficultyValue(json.get("max").getAsString()) : null;
        return new DifficultyRule(min, max);
    }

    static Difficulty parseDifficultyValue(final String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "peaceful" -> Difficulty.PEACEFUL;
            case "easy" -> Difficulty.EASY;
            case "normal" -> Difficulty.NORMAL;
            case "hard" -> Difficulty.HARD;
            default -> throw new JsonParseException("Unknown difficulty: " + value);
        };
    }

    static RegistryFilter parseRegistryFilter(final JsonObject json) {
        if (json == null) {
            return RegistryFilter.allowAll();
        }
        return new RegistryFilter(
                parseResourceLocations(json, "allow"), parseResourceLocations(json, "forbid"));
    }

    static BiomeFilter parseBiomeFilter(final JsonObject json) {
        if (json == null) {
            return BiomeFilter.allowAll();
        }
        return new BiomeFilter(
                parseResourceLocations(json, "allow"),
                parseResourceLocations(json, "allow_tags"),
                parseResourceLocations(json, "forbid"),
                parseResourceLocations(json, "forbid_tags"));
    }

    static TimeOfDayRule parseTimeOfDay(final JsonObject json) {
        if (json == null) {
            return TimeOfDayRule.allowAll();
        }
        final List<TimeOfDayRule.Interval> intervals = new ArrayList<>();
        for (JsonElement element : getArray(json, "intervals")) {
            final JsonObject object = element.getAsJsonObject();
            intervals.add(
                    new TimeOfDayRule.Interval(
                            getInt(object, "from", 0), getInt(object, "to", 23999)));
        }
        /*
         * Also support the compact form:
         *
         * {
         *     "from": 0,
         *     "to": 12000
         * }
         */
        if (json.has("from") || json.has("to")) {
            intervals.add(
                    new TimeOfDayRule.Interval(getInt(json, "from", 0), getInt(json, "to", 23999)));
        }
        return new TimeOfDayRule(intervals);
    }

    static WeatherRule parseWeather(final JsonObject json) {
        if (json == null) {
            return WeatherRule.allowAll();
        }
        return new WeatherRule(getStrings(json, "allow"), getStrings(json, "forbid"));
    }

    static RealDateRule parseRealDate(final JsonObject json) {
        if (json == null) {
            return RealDateRule.allowAll();
        }
        final List<RealDateRule.Interval> intervals = new ArrayList<>();
        for (JsonElement element : getArray(json, "intervals")) {
            final JsonObject object = element.getAsJsonObject();
            intervals.add(
                    new RealDateRule.Interval(
                            RealDateRule.parseMonthDay(getRequiredString(object, "from")),
                            RealDateRule.parseMonthDay(getRequiredString(object, "to"))));
        }
        /*
         * Compact form.
         */
        if (json.has("from") || json.has("to")) {
            intervals.add(
                    new RealDateRule.Interval(
                            RealDateRule.parseMonthDay(getRequiredString(json, "from")),
                            RealDateRule.parseMonthDay(getRequiredString(json, "to"))));
        }
        return new RealDateRule(intervals);
    }

    static RealTimeRule parseRealTime(final JsonObject json) {
        if (json == null) {
            return RealTimeRule.allowAll();
        }
        final List<RealTimeRule.Interval> intervals = new ArrayList<>();
        for (JsonElement element : getArray(json, "intervals")) {
            final JsonObject object = element.getAsJsonObject();
            intervals.add(
                    new RealTimeRule.Interval(
                            RealTimeRule.parseTime(getRequiredString(object, "from")),
                            RealTimeRule.parseTime(getRequiredString(object, "to"))));
        }
        /*
         * Compact form.
         */
        if (json.has("from") || json.has("to")) {
            intervals.add(
                    new RealTimeRule.Interval(
                            RealTimeRule.parseTime(getRequiredString(json, "from")),
                            RealTimeRule.parseTime(getRequiredString(json, "to"))));
        }
        return new RealTimeRule(intervals);
    }
}
