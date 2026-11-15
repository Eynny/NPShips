package com.eynny.npships.client.editor;

import com.google.gson.JsonObject;

/** Native inspector code. A callback borrows the current panel; it never owns history or Save. */
interface PatchInspectorContribution extends com.eynny.npships.authoring.patches.PatchSourceFormat {
    boolean headerApplies(EditorToolsPanel panel, JsonObject selected);

    boolean fieldsApply(EditorToolsPanel panel, JsonObject patch);

    void header(EditorToolsPanel panel, JsonObject selected);

    void fields(EditorToolsPanel panel, JsonObject patch);
}
