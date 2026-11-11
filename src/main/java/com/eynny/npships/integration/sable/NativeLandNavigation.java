package com.eynny.npships.integration.sable;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Entity-local evidence that the active native land navigator actually received a request, not an
 * AI/controller replacement.
 */
public interface NativeLandNavigation {
    Path npships$waterRequest();

    void npships$waterRequest(Path path);

    /** Unpublished query state; it never grants the native AI an executable route. */
    Path npships$planningRequest();

    void npships$planningRequest(Path path);

    interface Navigator {
        Mob npships$navigationMob();
    }

    void npships$landNavigationUsed();

    boolean npships$hasLandNavigation();
}
