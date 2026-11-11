package com.eynny.npships.authoring.validation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Shape-only admission before recursive Gson copies/codecs; foreign field meanings stay opaque. */
public final class JsonStructure {
    public static final int MAX_DEPTH = 512;

    private JsonStructure() {}

    /** Parse with the existing Gson grammar, then bound nesting before any recursive consumer. */
    public static JsonElement parse(String text) {
        var result = JsonParser.parseString(text);
        validate(result);
        return result;
    }

    /** Bounded-depth iteration uses no recursion and keeps only one cursor per container level. */
    public static void validate(JsonElement value) {
        if (value == null || !container(value)) return;
        var pending = new ArrayDeque<Iterator<JsonElement>>();
        pending.push(children(value));
        while (!pending.isEmpty()) {
            var cursor = pending.peek();
            if (!cursor.hasNext()) {
                pending.pop();
                continue;
            }
            var child = cursor.next();
            if (!container(child)) continue;
            if (pending.size() >= MAX_DEPTH)
                throw new JsonParseException(
                        "JSON nesting exceeds " + MAX_DEPTH + " container levels.");
            pending.push(children(child));
        }
    }

    /** External mutable configuration is checked before the host takes its detached copy. */
    public static JsonObject copy(JsonObject value) {
        validate(value);
        return value == null ? null : value.deepCopy();
    }

    private static boolean container(JsonElement value) {
        return value.isJsonArray() || value.isJsonObject();
    }

    private static Iterator<JsonElement> children(JsonElement value) {
        return value.isJsonArray()
                ? value.getAsJsonArray().iterator()
                : value.getAsJsonObject().asMap().values().iterator();
    }
}
