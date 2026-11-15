package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchOrdering;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeSet;

/** Provider-neutral accepted-document normalization boundary. No history or world mutation. */
public final class PatchDocuments {
    private PatchDocuments() {}

    /** Admission into an editor/import operation is distinct from passive, lossless file reads. */
    public static SchematicDocument admitEditor(SchematicDocument document) {
        SchematicPatches.validatePatches(document, false);
        return document;
    }

    public static SchematicDocument normalize(SchematicDocument document, Level context) {
        var code = PatchTypes.capabilities(PatchDocumentNormalization.class);
        var active = new TreeSet<net.minecraft.resources.ResourceLocation>();
        for (var entry : code.entrySet())
            if (entry.getValue().applies(document)) active.add(entry.getKey());
        if (active.isEmpty()) return document;
        var policy = NPShipsRuntimeConfig.patchPolicy();
        PatchRuntimePolicy.validate(policy, active);
        var defaults = new ArrayList<>(active);
        defaults.sort(Comparator.comparingInt(id -> code.get(id).defaultRank()));
        for (var id :
                PatchOrdering.discoveryOrder(
                        PatchStage.AUTHOR_PREPARATION, active, policy, defaults))
            document = code.get(id).normalize(document, context);
        return document;
    }
}
