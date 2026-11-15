package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.patches.PatchSourceFormat;
import com.google.gson.JsonObject;

/**
 * Detached provider text workflow; host fields, input, validation and accepted history stay shared.
 */
interface PatchTextWorkspace extends PatchSourceFormat {
    void open(
            EditorToolsPanel panel,
            JsonObject record,
            String container,
            String path,
            String initial);
}
