package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.*;

/** Read-only native qualification at catalogue/panel admission, one bounded cache per operation. */
public final class PatchAvailability {
    private final Map<ResourceLocation, String> checked = new HashMap<>();

    public String unavailable(List<JsonObject> patches) {
        var validators = PatchCode.sourceFormats(PatchNativeValidation.class);
        for (var patch : patches) {
            if (ExternalPatches.external(patch)) {
                var reason = ExternalPatches.unavailable(patch);
                if (!reason.isEmpty()) return reason;
                continue;
            }
            for (var entry : validators.entrySet()) {
                var code = entry.getValue();
                if (!code.requires(patch)) continue;
                var key = Objects.requireNonNull(code.availabilityKey());
                if (!key.getNamespace().equals(entry.getKey().getNamespace()))
                    throw new IllegalArgumentException(
                            "Availability key has foreign owner: " + key);
                var reason = checked.computeIfAbsent(key, ignored -> code.unavailable());
                if (!reason.isEmpty()) return reason;
            }
        }
        return "";
    }

    public static void validateDocument(SchematicDocument document, Level context) {
        for (var code : PatchCode.sourceFormats(PatchNativeValidation.class).values())
            code.validateDocument(document, context);
    }
}
