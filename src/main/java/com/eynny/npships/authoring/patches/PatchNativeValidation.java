package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Known native qualification code from the frozen provider owner. Discovery is detached; explicit
 * ABI probes/native document checks run only at the host's read-only admission boundary.
 */
public interface PatchNativeValidation extends PatchSourceFormat {
    default boolean requires(JsonObject patch) {
        return false;
    }

    default ResourceLocation availabilityKey() {
        return null;
    }

    default String unavailable() {
        return "";
    }

    default void validateDocument(SchematicDocument document, Level context) {}
}
