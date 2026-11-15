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

    /**
     * One server-thread graph cursor. Each step visits at most one native graph node; the caller
     * checks its shared work budget between steps. No cursor owns a ticker.
     */
    interface Traversal extends AutoCloseable {
        boolean step() throws Exception;

        Plan result();

        @Override
        void close();
    }

    /** Supports are seeded before attached/brittle blocks; providers keep their own semantics. */
    default boolean deferSeed(net.minecraft.world.level.block.state.BlockState state) {
        return false;
    }

    /** Bounded native placement requirements; membership/connectivity alone is not support. */
    record PlacementRequirements(Set<BlockPos> supports, Set<BlockPos> peers) {
        public static final PlacementRequirements NONE =
                new PlacementRequirements(Set.of(), Set.of());

        public PlacementRequirements {
            supports = Set.copyOf(supports);
            peers = Set.copyOf(peers);
            if (supports.size() + peers.size() > 6)
                throw new IllegalArgumentException("Placement requirements exceed adjacent cells");
        }
    }

    /**
     * At most six adjacent dependency probes. Supports precede this block; mutually required native
     * peers share one placement unit with ordinary updates and no yield between peers. The host
     * owns ordering, admission and budget checks, not the provider.
     */
    default PlacementRequirements placementRequirements(Level source, BlockPos local) {
        return PlacementRequirements.NONE;
    }

    /** Open a resumable selection without publishing native bodies or touching live blocks. */
    default Traversal beginDetached(Level source, BlockPos point, Set<BlockPos> permitted)
            throws Exception {
        throw new IllegalStateException("Assembly provider has no resumable detached traversal");
    }

    /** Source-world coordinates in a callback-scoped detached Level, with no native allocation. */
    Plan planDetached(Level source, BlockPos point, Set<BlockPos> permitted) throws Exception;
}
