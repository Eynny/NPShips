package com.eynny.npships.mixin;

import com.eynny.npships.integration.sable.NativeLandNavigation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Native land-use evidence and one bounded handoff request die with this mob, never a global cache.
 */
@Mixin(Mob.class)
public abstract class NPShipsMobNavigationCapabilityMixin implements NativeLandNavigation {
    @Unique private boolean npships$landNavigation;
    @Unique private Path npships$waterRequest;
    @Unique private Path npships$planningRequest;

    public Path npships$waterRequest() {
        return npships$waterRequest;
    }

    public void npships$waterRequest(Path path) {
        npships$waterRequest = path;
    }

    public Path npships$planningRequest() {
        return npships$planningRequest;
    }

    public void npships$planningRequest(Path path) {
        npships$planningRequest = path;
    }

    public void npships$landNavigationUsed() {
        npships$landNavigation = true;
    }

    public boolean npships$hasLandNavigation() {
        return npships$landNavigation;
    }
}
