package com.eynny.npships.authoring.validation;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

/** Exact scalar parsing for host-owned integer fields; never traverses opaque provider payloads. */
public final class JsonNumbers {
    private JsonNumbers() {}

    public static int integer(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new JsonParseException("Expected an integer for '" + field + "'.");
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new JsonParseException("Expected a 32-bit integer for '" + field + "'.", error);
        }
    }
}
