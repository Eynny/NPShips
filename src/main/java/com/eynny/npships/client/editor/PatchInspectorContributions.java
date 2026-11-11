package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.patches.PatchCode;
import com.google.gson.JsonObject;

/** Stateless inspector fan-out through the existing frozen provider/policy owner. */
final class PatchInspectorContributions {
    private PatchInspectorContributions() {}

    static void header(EditorToolsPanel panel, JsonObject selected) {
        for (var entry :
                PatchCode.authorControls(
                        PatchInspectorContribution.class,
                        code -> code.headerApplies(panel, selected)))
            entry.getValue().header(panel, selected);
    }

    static void fields(EditorToolsPanel panel, JsonObject selected) {
        for (var entry :
                PatchCode.authorControls(
                        PatchInspectorContribution.class,
                        code -> code.fieldsApply(panel, selected)))
            entry.getValue().fields(panel, selected);
    }
}
