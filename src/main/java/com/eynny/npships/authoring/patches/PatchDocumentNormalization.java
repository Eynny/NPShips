package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.world.level.Level;

/** Internal native-format capability; never grants an addon mutable editor/history ownership. */
public interface PatchDocumentNormalization {
    /** Pure applicability check on detached source, before policy validation or native access. */
    boolean applies(SchematicDocument document);

    /** Detached normalization; the existing editor accepts the combined result once. */
    SchematicDocument normalize(SchematicDocument document, Level context);

    /** Fallback rank only. Mandatory provider edges and SERVER preferences remain authoritative. */
    int defaultRank();
}
