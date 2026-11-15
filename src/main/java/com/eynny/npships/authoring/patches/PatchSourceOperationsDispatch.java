package com.eynny.npships.authoring.patches;

import java.util.List;

/** Immutable grammar projection of the existing frozen registry; no registration or world state. */
public final class PatchSourceOperationsDispatch {
    private PatchSourceOperationsDispatch() {}

    private static final class Code {
        static final List<PatchSourceOperations> VALUES =
                List.copyOf(PatchCode.sourceFormats(PatchSourceOperations.class).values());
        static final List<PatchSourceOperations> GUARDS =
                VALUES.stream()
                        .filter(PatchSourceOperations::validatesCapture)
                        .sorted(
                                java.util.Comparator.comparingInt(
                                        PatchSourceOperations::captureValidationRank))
                        .toList();
    }

    public static void copied(
            com.google.gson.JsonObject patch, PatchSourceOperations.CopyMode mode) {
        if (ExternalPatches.external(patch)) return;
        for (var code : Code.VALUES) code.copied(patch, mode);
    }

    public static List<PatchSourceOperations> all() {
        return Code.VALUES;
    }

    public static List<PatchSourceOperations> captureGuards() {
        return Code.GUARDS;
    }
}
