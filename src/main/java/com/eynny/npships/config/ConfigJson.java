package com.eynny.npships.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Shared configuration parser; numeric author fields reject lossy integer narrowing. */
final class ConfigJson {
    private ConfigJson() {}

    static JsonObject getObject(final JsonObject parent, final String key) {
        if (!parent.has(key)) {
            return null;
        }
        final JsonElement element = parent.get(key);
        if (!element.isJsonObject()) {
            throw new JsonParseException("Expected object for '" + key + "'.");
        }
        return element.getAsJsonObject();
    }

    static List<JsonElement> getArray(final JsonObject parent, final String key) {
        if (!parent.has(key)) {
            return List.of();
        }
        final JsonElement element = parent.get(key);
        if (!element.isJsonArray()) {
            throw new JsonParseException("Expected array for '" + key + "'.");
        }
        final List<JsonElement> result = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) {
            result.add(child);
        }
        return result;
    }

    static List<String> getStrings(final JsonObject parent, final String key) {
        if (!parent.has(key)) {
            return List.of();
        }
        final JsonElement element = parent.get(key);
        if (!element.isJsonArray()) {
            throw new JsonParseException("Expected array for '" + key + "'.");
        }
        final List<String> result = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) {
            if (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("Expected string in '" + key + "'.");
            }
            result.add(child.getAsString());
        }
        return result;
    }

    static int getInt(final JsonObject json, final String key, final int defaultValue) {
        if (!json.has(key)) {
            return defaultValue;
        }
        return com.eynny.npships.authoring.validation.JsonNumbers.integer(json.get(key), key);
    }

    static boolean getBoolean(final JsonObject json, final String key, final boolean defaultValue) {
        if (!json.has(key)) {
            return defaultValue;
        }
        var value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new JsonParseException("Expected boolean for '" + key + "'.");
        return value.getAsBoolean();
    }

    static String getString(final JsonObject json, final String key, final String defaultValue) {
        if (!json.has(key)) {
            return defaultValue;
        }
        return json.get(key).getAsString();
    }

    static String getRequiredString(final JsonObject json, final String key) {
        if (!json.has(key)) {
            throw new JsonParseException("Missing required property: " + key);
        }
        return json.get(key).getAsString();
    }

    static <T extends Enum<T>> T parseEnum(final Class<T> enumType, final String value) {
        try {
            return Enum.valueOf(enumType, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new JsonParseException(
                    "Unknown value '" + value + "' for " + enumType.getSimpleName(), e);
        }
    }

    static List<ResourceLocation> parseResourceLocations(final JsonObject json, final String key) {
        final List<ResourceLocation> result = new ArrayList<>();
        for (String value : getStrings(json, key)) {
            result.add(ResourceLocation.parse(value));
        }
        return result;
    }
}
