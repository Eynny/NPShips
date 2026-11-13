package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchAssembly;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Set;

/** Internal opt-in on the same registered assembly provider; not part of the public SDK. */
public interface DetachedPatchAssembly extends PatchAssembly {
    /** Only providers whose transfer callback has no side effects may snapshot membership. */
    default boolean detachedTransferIsNoop() {
        return false;
    }

    /** Source-world coordinates in a callback-scoped detached Level, with no native allocation. */
    Plan planDetached(Level source, BlockPos point, Set<BlockPos> permitted) throws Exception;
}
