package com.eynny.npships.integration.sable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.PathType;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * One weak route hint per existing shared-budget tick. Geometry is never shared between mobs:
 * another actor must prove the complete corridor again against its own fresh Scene and rules.
 */
final class SurfaceRouteHints {
    private SurfaceRouteHints() {}

    static void remember(
            SableSurfaceNavigation.SurfacePath path, SableSurfaceNavigation.Slot slot) {
        if (path.searchFinished && path.canReach() && path.anchors.size() <= 16)
            slot.routeHint = new WeakReference<>(path);
    }

    static SableSurfaceNavigation.SurfacePath reuse(
            SableSurfaceNavigation.Scene scene, Entity target, float range, int accuracy) {
        var reference = scene.budget.slot.routeHint;
        var hint = reference == null ? null : reference.get();
        if (hint == null
                || hint.waterExit != scene.waterExit
                || hint.retired
                || !hint.searchFinished
                || !hint.canReach()
                || hint.level != scene.level
                || hint.mob == scene.mob
                || hint.goal.get() != target
                || hint.mob.getClass() != scene.mob.getClass()
                || hint.anchors.size() > 16
                || hint.mob.getBbWidth() != scene.mob.getBbWidth()
                || hint.mob.getBbHeight() != scene.mob.getBbHeight()
                || SableSurfaceNavigation.jumpRise(hint.mob, scene.waterExit) != scene.maxRise
                || hint.mob.getMaxFallDistance() != scene.mob.getMaxFallDistance()
                || !hint.matchesGoal(target)
                || hint.bodies.stream().anyMatch(body -> body.isRemoved())) return null;
        for (var type : PathType.values())
            if (hint.mob.getPathfindingMalus(type) != scene.mob.getPathfindingMalus(type))
                return null;
        var start = scene.mob.position();
        if (start.distanceToSqr(hint.anchors.getFirst().point(scene.mob)) > 9) return null;
        SableSurfaceNavigation.Anchor startAnchor = null;
        if (scene.waterExit && scene.mob.isInWaterOrBubble() && scene.waterAtFeet(start)) {
            if (!scene.waterSupport(start)) return null;
            startAnchor =
                    SableSurfaceNavigation.Anchor.water(
                            start, scene.mob.getPathfindingMalus(PathType.WATER));
        } else {
            if (!scene.mob.onGround()) return null;
            for (var floor : scene.floors(start.x, start.z, start.y)) {
                if (Math.abs(floor.planned.y - start.y) <= Math.max(.125, scene.mob.maxUpStep())
                        && scene.clear(floor.planned, floor.planned)) {
                    startAnchor = floor;
                    start = floor.planned;
                    break;
                }
            }
            if (startAnchor == null) return null;
        }
        var last = hint.anchors.getLast().point(scene.mob);
        var goal = target.position();
        if (Math.abs(last.y - goal.y) >= 1
                || Math.hypot(last.x - goal.x, last.z - goal.z) > Math.max(.55, accuracy + .5))
            return null;
        var anchors = new ArrayList<SableSurfaceNavigation.Anchor>();
        anchors.add(startAnchor);
        var previous = start;
        try {
            int first = 0;
            boolean joined = false;
            int nearest = 0;
            double distance = Double.POSITIVE_INFINITY;
            for (int index = 1; index < hint.anchors.size(); index++) {
                var candidate = hint.anchors.get(index);
                if (candidate.liftLanding != null
                        || candidate.water != hint.anchors.getFirst().water) break;
                var point = candidate.point(scene.mob);
                if (point.subtract(start).dot(goal.subtract(start)) < -SableSurfaceNavigation.SKIN)
                    continue;
                double nextDistance = start.distanceToSqr(point);
                if (nextDistance <= distance) {
                    nearest = index;
                    distance = nextDistance;
                }
            }
            if (nearest > 0
                    && distance <= 9
                    && scene.edge(start, hint.anchors.get(nearest).point(scene.mob))) {
                // Join the nearest same-medium contact only after a fresh complete edge check.
                // Never skip a lift or carry another actor's obsolete starting prefix forward.
                first = nearest;
                joined = true;
            }
            for (int index = first; index < hint.anchors.size(); index++) {
                var anchor = hint.anchors.get(index);
                var point = anchor.point(scene.mob);
                if (Math.hypot(point.x - start.x, point.z - start.z) > range) return null;
                boolean support =
                        anchor.water
                                ? scene.waterSupport(point)
                                : scene.floors(point.x, point.z, point.y).stream()
                                        .anyMatch(
                                                current ->
                                                        !current.water
                                                                && current.planned.distanceToSqr(
                                                                                point)
                                                                        < .025);
                if (!support || !(joined && index == first) && !scene.edge(previous, point))
                    return null;
                anchors.add(anchor);
                previous = point;
            }
        } catch (SableSurfaceNavigation.AllowanceExhausted deferred) {
            throw deferred;
        } catch (SableSurfaceNavigation.Exhausted invalidHint) {
            // A changed corridor is only a failed hint; normal bounded search remains available.
            return null;
        }
        var result =
                new SableSurfaceNavigation.SurfacePath(
                        scene.mob,
                        anchors,
                        target.blockPosition(),
                        target.getUUID(),
                        true,
                        range,
                        accuracy,
                        target);
        result.waterExit = scene.waterExit;
        result.searchFinished = true;
        result.checkedTick = Long.MIN_VALUE;
        return result;
    }
}
