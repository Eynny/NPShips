package com.eynny.npships.config;

import com.google.gson.JsonObject;

/** Author-selected relative rarity mode. Automatic values come from SERVER rarity parameters. */
public record RarityRule(boolean automatic, double manualProbability) {
    public RarityRule {
        if (!Double.isFinite(manualProbability) || manualProbability <= 0 || manualProbability > 1)
            throw new IllegalArgumentException(
                    "rarity.probability must be a finite number in (0, 1].");
    }

    public static RarityRule automaticRule() {
        return new RarityRule(true, 1);
    }

    public static RarityRule read(JsonObject root) {
        if (root == null || !root.has("rarity")) return automaticRule();
        var value = root.get("rarity");
        if (!value.isJsonObject()) throw new IllegalArgumentException("rarity must be an object.");
        var rule = value.getAsJsonObject();
        String mode = "automatic";
        if (rule.has("mode")) {
            var m = rule.get("mode");
            if (!m.isJsonPrimitive() || !m.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("rarity.mode must be automatic or manual.");
            mode = m.getAsString();
        }
        if (mode.equals("automatic")) {
            if (rule.has("probability"))
                throw new IllegalArgumentException("Automatic rarity must omit probability.");
            return automaticRule();
        }
        if (!mode.equals("manual"))
            throw new IllegalArgumentException("rarity.mode must be automatic or manual.");
        var probability = rule.get("probability");
        if (probability == null
                || !probability.isJsonPrimitive()
                || !probability.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(
                    "rarity.probability must be a finite number in (0, 1].");
        return new RarityRule(false, probability.getAsDouble());
    }
}
