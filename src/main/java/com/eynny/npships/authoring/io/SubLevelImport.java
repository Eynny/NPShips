package com.eynny.npships.authoring.io;

/** Native source-container transaction entry; registered code owns format semantics. */
public final class SubLevelImport {
    private SubLevelImport() {}

    public static final String GROUP = "npships:physical_group";

    public static com.eynny.npships.authoring.document.SchematicDocument read(
            net.minecraft.nbt.CompoundTag input, com.google.gson.JsonObject config) {
        return com.eynny.npships.authoring.patches.PatchImports.require("physical_source")
                .read(input, config);
    }
}
