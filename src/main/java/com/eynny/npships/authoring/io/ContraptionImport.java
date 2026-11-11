package com.eynny.npships.authoring.io;

/** Native source-container transaction entry; registered code owns format semantics. */
public final class ContraptionImport {
    public static final String REPORT = "npships_import_objects";

    private ContraptionImport() {}

    public static String diagnostic(com.google.gson.JsonObject report) {
        return com.eynny.npships.authoring.patches.PatchImports.require("contraption_source")
                .diagnostic(report);
    }

    public static com.eynny.npships.authoring.document.SchematicDocument convert(
            com.eynny.npships.authoring.document.SchematicDocument input) {
        return com.eynny.npships.authoring.patches.PatchImports.require("contraption_source")
                .convert(input);
    }
}
