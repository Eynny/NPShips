package com.eynny.npships.config;

import static com.eynny.npships.config.ConfigJson.*;

import com.eynny.npships.authoring.validation.ValidationProblem;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.ResourceLocation;

/** Internal parser; shared validation and error semantics stay unchanged. */
final class EnvironmentConfigParser {
    private EnvironmentConfigParser() {}

    static NPShipsStructureConfig.EnvironmentConfig parseEnvironment(final JsonObject json) {
        if (json == null) {
            return NPShipsStructureConfig.EnvironmentConfig.defaults();
        }
        final EnvironmentRule under = parseEnvironmentRule(getObject(json, "under"));
        final EnvironmentRule above = parseEnvironmentRule(getObject(json, "above"));
        final InsideRule inside = parseInsideRule(getObject(json, "inside"));
        return new NPShipsStructureConfig.EnvironmentConfig(under, above, inside);
    }

    static EnvironmentRule parseEnvironmentRule(final JsonObject json) {
        if (json == null) {
            return EnvironmentRule.none();
        }
        final EnvironmentRule.Type type =
                parseEnum(EnvironmentRule.Type.class, getString(json, "type", "none"));
        final int depth =
                type == EnvironmentRule.Type.NONE
                        ? 0
                        : getInt(json, "depth", NPShipsStructureConfig.DEFAULT_ENVIRONMENT_DEPTH);
        final ResourceLocation fluid;
        if (type == EnvironmentRule.Type.FLUID) {
            /*
             * Fluid is optional for under/above.
             *
             * No fluid specified means:
             * any source fluid is accepted.
             */
            if (json.has("fluid")) {
                fluid = ResourceLocation.parse(getRequiredString(json, "fluid"));
            } else {
                fluid = null;
            }
        } else {
            if (json.has("fluid")) {
                throw new JsonParseException(
                        "Property 'fluid' is only valid " + "for environment type 'fluid'.");
            }
            fluid = null;
        }
        return new EnvironmentRule(
                type,
                depth,
                parseResourceLocations(json, "blocks"),
                parseResourceLocations(json, "tags"),
                parseResourceLocations(json, "exclude_blocks"),
                parseResourceLocations(json, "exclude_tags"),
                getBoolean(json, "allow_air", false),
                getBoolean(json, "allow_non_support", false),
                fluid);
    }

    static InsideRule parseInsideRule(final JsonObject json) {
        if (json == null) {
            return InsideRule.air();
        }
        final String name = getString(json, "type", "air");
        final InsideRule.Type type = parseEnum(InsideRule.Type.class, name);
        final ResourceLocation fluid;
        if (type == InsideRule.Type.FLUID) {
            if (!json.has("fluid")) {
                throw ValidationProblem.invalid(
                        "inside_fluid_required", "Inside fluid rule requires a source fluid id.");
            }
            var value = json.get("fluid");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw ValidationProblem.invalid(
                        "inside_fluid_invalid",
                        "Inside fluid id must be a resource-location string.",
                        value);
            }
            String id = value.getAsString();
            fluid = id.isBlank() ? null : ResourceLocation.tryParse(id);
            if (fluid == null) {
                throw ValidationProblem.invalid(
                        "inside_fluid_invalid", "Invalid inside fluid id: " + id, id);
            }
        } else {
            if (json.has("fluid")) {
                throw new JsonParseException(
                        "Property 'fluid' is only valid " + "for inside type 'fluid'.");
            }
            fluid = null;
        }
        return new InsideRule(type, fluid);
    }
}
