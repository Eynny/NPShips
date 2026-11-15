package com.eynny.npships.config;

import static com.eynny.npships.config.ConfigJson.*;
import static com.eynny.npships.config.EnvironmentConfigParser.*;
import static com.eynny.npships.config.RestrictionsConfigParser.*;

import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Loads NPShips structure configuration from JSON.
 *
 * <p>Spawn rules and authoring sections retain separate validation boundaries.
 */
public final class NPShipsStructureConfigLoader {
    private NPShipsStructureConfigLoader() {}

    public static NPShipsStructureConfig load(final JsonObject json) {
        JsonStructure.validate(json);
        double weight = 1.0;
        if (json.has("selection_weight")) {
            var value = json.get("selection_weight");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException(
                        "selection_weight must be a positive finite number.");
            weight = value.getAsDouble();
        }
        final NPShipsStructureConfig.EnvironmentConfig environment =
                parseEnvironment(getObject(json, "environment"));
        final NPShipsStructureConfig.RestrictionsConfig restrictions =
                parseRestrictions(getObject(json, "restrictions"));
        final NPShipsStructureConfig.PlacementConfig placement =
                parsePlacement(getObject(json, "placement"));
        // Validate authoring sections independently, preserving their JSON in the loaded config.
        com.eynny.npships.authoring.EmbeddedLoot.read(json);
        com.eynny.npships.authoring.StructureCrew.read(json);
        com.eynny.npships.authoring.patches.SchematicPatches.read(json);
        EntityPatches.read(json);
        var authoring = new JsonObject();
        for (String key : List.of("loot_tables", "crew", "patches", "entity_patches"))
            if (json.has(key)) authoring.add(key, json.get(key).deepCopy());
        return new NPShipsStructureConfig(
                weight,
                environment,
                restrictions,
                placement,
                List.of(),
                com.eynny.npships.authoring.LootAssignments.read(json),
                com.eynny.npships.authoring.StructureSegments.read(json),
                authoring,
                RarityRule.read(json));
    }

    /** Missing placement rules use the same defaults as the structure editor. */
    private static NPShipsStructureConfig.PlacementConfig parsePlacement(final JsonObject json) {
        if (json == null) {
            return NPShipsStructureConfig.PlacementConfig.defaults();
        }
        return new NPShipsStructureConfig.PlacementConfig(
                getBoolean(json, "avoid_physical_structures", true),
                parseRotationSpread(json),
                getBoolean(json, "require_rotation_space", false),
                getBoolean(json, "disable_auto_rotation", false));
    }

    private static double parseRotationSpread(JsonObject json) {
        if (!json.has("rotation_spread_degrees")) return 45.0D;
        var value = json.get("rotation_spread_degrees");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                    "placement.rotation_spread_degrees must be a number.");
        }
        return value.getAsDouble();
    }
}
