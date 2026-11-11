package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.google.gson.JsonObject;

import net.minecraft.nbt.CompoundTag;

/**
 * Internal native source-container codec. read/convert are pure; capture borrows the authorized
 * server context only for that callback. Mandatory source ownership remains host-enforced.
 */
public interface PatchImportFormat extends PatchSourceFormat {
    String boundary();

    default String diagnostic(JsonObject report) {
        throw new IllegalArgumentException("Unsupported import report");
    }

    default SchematicDocument read(CompoundTag source, JsonObject config) {
        return new SchematicDocument(source, config);
    }

    default SchematicDocument convert(SchematicDocument source) {
        return source;
    }

    default SchematicDocument capture(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos origin,
            SchematicDocument source)
            throws Exception {
        throw new IllegalArgumentException("Unsupported native capture boundary");
    }
}
