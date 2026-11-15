package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.validation.ValidationProblem;
import com.google.gson.JsonParseException;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/** Translate identified NPShips failures at their existing UI boundary; keep the native detail. */
public final class EditorErrorText {
    private EditorErrorText() {}

    public static String message(Throwable failure) {
        String technical = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        var detail = ValidationProblem.detail(failure);
        String key;
        Object[] arguments;
        if (detail != null) {
            key = "npships.error." + detail.code();
            arguments = detail.arguments().toArray();
        } else if (failure instanceof JsonParseException) {
            key = "npships.error.json_syntax";
            arguments = new Object[0];
        } else return technical;
        if (!I18n.exists(key)) return technical;
        String explanation = Component.translatable(key, arguments).getString();
        return explanation.equals(technical)
                ? technical
                : explanation
                        + "\n"
                        + Component.translatable("npships.error.technical", technical).getString();
    }

    static void computerPath(Runnable validation, String path) {
        try {
            validation.run();
        } catch (IllegalArgumentException failure) {
            throw ValidationProblem.describe(failure, "computer_path", path);
        }
    }
}
