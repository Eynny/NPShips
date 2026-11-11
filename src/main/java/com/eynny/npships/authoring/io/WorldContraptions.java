package com.eynny.npships.authoring.io;

/** Explicit server capture entry; native source-container code belongs to its registered owner. */
public final class WorldContraptions {
    private WorldContraptions() {}

    public static com.eynny.npships.authoring.document.SchematicDocument capture(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos origin,
            com.eynny.npships.authoring.document.SchematicDocument source)
            throws Exception {
        return com.eynny.npships.authoring.patches.PatchImports.require("contraption_source")
                .capture(level, origin, source);
    }
}
