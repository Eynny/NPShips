package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.SchematicDocument;

/** Private native-module view, borrowed only in a reserved provider's current callback. */
interface NativeEditorDocument {
    SchematicDocument document();
}
