package com.eynny.npships.authoring.patches;

/** Frozen projection only; there is no separate format registration or native capture owner. */
public final class PatchImports {
    private PatchImports() {}

    public static PatchImportFormat require(String boundary) {
        PatchImportFormat result = null;
        for (var codec : PatchCode.sourceFormats(PatchImportFormat.class).values())
            if (codec.boundary().equals(boundary)) {
                if (result != null)
                    throw new IllegalArgumentException("Conflicting import codecs: " + boundary);
                result = codec;
            }
        if (result == null) throw new IllegalArgumentException("Missing import codec: " + boundary);
        return result;
    }
}
