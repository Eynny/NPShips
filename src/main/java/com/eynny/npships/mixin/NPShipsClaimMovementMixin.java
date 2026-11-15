package com.eynny.npships.mixin;

import com.eynny.npships.block.ClaimBlockEntity;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe committed movement; no entity tick handler or periodic world/entity query. */
@Mixin(value = Entity.class, priority = 900)
public abstract class NPShipsClaimMovementMixin {
    @Unique private Vec3 npships$beforeMove, npships$beforePosition;
    @Unique private SubLevel npships$moveBody, npships$positionBody;

    @Unique
    private Entity npships$hostile() {
        var entity = (Entity) (Object) this;
        return entity instanceof Mob
                        && entity instanceof Enemy
                        && entity.level() instanceof ServerLevel
                        && ClaimBlockEntity.hasLoadedClaims()
                ? entity
                : null;
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void npships$beforeMove(MoverType type, Vec3 movement, CallbackInfo ci) {
        var entity = npships$hostile();
        if (entity == null) return;
        npships$beforeMove = entity.position();
        npships$moveBody = Sable.HELPER.getTrackingOrVehicleSubLevel(entity);
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void npships$afterMove(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (npships$beforeMove == null) return;
        ClaimBlockEntity.hostileMoved((Entity) (Object) this, npships$beforeMove, npships$moveBody);
        npships$beforeMove = null;
        npships$moveBody = null;
    }

    @Inject(method = "setPosRaw", at = @At("HEAD"))
    private void npships$beforePosition(double x, double y, double z, CallbackInfo ci) {
        var entity = npships$hostile();
        if (entity == null || entity.position() == null) return;
        npships$beforePosition = entity.position();
        npships$positionBody = Sable.HELPER.getTrackingOrVehicleSubLevel(entity);
    }

    @Inject(method = "setPosRaw", at = @At("RETURN"))
    private void npships$afterPosition(double x, double y, double z, CallbackInfo ci) {
        if (npships$beforePosition == null) return;
        ClaimBlockEntity.hostileMoved(
                (Entity) (Object) this, npships$beforePosition, npships$positionBody);
        npships$beforePosition = null;
        npships$positionBody = null;
    }

    // Sable's mixin (priority 1100) supplies this public movement-extension method.
    @Inject(method = "sable$setTrackingSubLevel", at = @At("HEAD"), remap = false)
    private void npships$trackingChanged(SubLevel next, CallbackInfo ci) {
        var entity = npships$hostile();
        if (entity != null)
            ClaimBlockEntity.hostileTrackingChanged(
                    entity, Sable.HELPER.getTrackingOrVehicleSubLevel(entity), next);
    }
}
