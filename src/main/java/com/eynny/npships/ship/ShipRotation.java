package com.eynny.npships.ship;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;

/** Applies one world-space rigid transform to all assembled bodies. */
public final class ShipRotation {
    private ShipRotation() {}

    public static void rotate(ServerLevel level, Ship ship, Vec3 center, double radians) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Rotation requires server thread.");
        final var container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("Missing Sable container.");
        final var pipeline = container.physicsSystem().getPipeline();
        final var rotation = new Quaterniond().rotationY(radians);
        record Target(ServerSubLevel body, Vector3d position, Quaterniond orientation) {}
        final var targets = new ArrayList<Target>();
        for (var id : ship.getPhysicalIds()) {
            var body = container.getSubLevel(id);
            if (!(body instanceof ServerSubLevel serverBody) || body.isRemoved()) {
                throw new IllegalStateException("Assembled physical body is missing: " + id);
            }
            var pose = body.logicalPose();
            var position = rotatePosition(new Vector3d(pose.position()), center, radians);
            var orientation = new Quaterniond(rotation).mul(pose.orientation());
            targets.add(new Target(serverBody, position, orientation));
        }
        for (var target : targets) {
            pipeline.teleport(target.body(), target.position(), target.orientation());
            // Native link factories project through logicalPose, not the backend pose. Keep them
            // identical immediately; waiting for the next physics tick creates stale attachments.
            target.body().logicalPose().position().set(target.position());
            target.body().logicalPose().orientation().set(target.orientation());
            target.body().updateLastPose();
            refreshBounds(target.body());
        }
        for (var id : ship.getSpawnedMobs()) {
            var mob = level.getEntity(id);
            if (mob == null || mob.isRemoved() || mob.isPassenger()) continue;
            var position =
                    rotatePosition(
                            new Vector3d(mob.getX(), mob.getY(), mob.getZ()), center, radians);
            // Minecraft yaw starts at south (+Z) and has the opposite sign to the JOML Y transform.
            float yaw = rotateYaw(mob.getYRot(), radians);
            float headYaw = rotateYaw(mob.getYHeadRot(), radians);
            float bodyYaw =
                    mob instanceof LivingEntity living ? rotateYaw(living.yBodyRot, radians) : yaw;
            mob.moveTo(position.x, position.y, position.z, yaw, mob.getXRot());
            mob.setYHeadRot(headYaw);
            mob.setYBodyRot(bodyYaw);
            mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }
    }

    /** Publish teleport bounds before attachment and placement consumers run in this tick. */
    static void refreshBounds(ServerSubLevel body) {
        var bounds = body.getPlot().getBoundingBox();
        // Native child mechanisms may own an empty plot. Do not transform its sentinel box.
        if (bounds == null
                || bounds.minX() > bounds.maxX()
                || bounds.minY() > bounds.maxY()
                || bounds.minZ() > bounds.maxZ()) return;
        body.updateBoundingBox();
        body.forceUpdateGlobalBounds();
    }

    public static float rotateYaw(float yaw, double radians) {
        return yaw - (float) Math.toDegrees(radians);
    }

    public static Vector3d rotatePosition(Vector3d position, Vec3 center, double radians) {
        return position.sub(center.x, center.y, center.z)
                .rotateY(radians)
                .add(center.x, center.y, center.z);
    }
}
