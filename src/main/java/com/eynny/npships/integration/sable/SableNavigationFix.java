package com.eynny.npships.integration.sable;

import com.eynny.npships.config.NPShipsRuntimeConfig;

import dev.ryanhcode.sable.Sable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.function.Supplier;

/** Server-thread entry points. Neither correction changes goals, tracking, collision or targets. */
public final class SableNavigationFix {
    private SableNavigationFix() {}

    private record Request(PathNavigation navigation, Entity target) {}

    private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();

    /**
     * A call-local target survives native coordinate normalization; nested calls restore ownership.
     */
    public static <T> T requested(PathNavigation navigation, Entity target, Supplier<T> original) {
        discardOtherPlanning(navigation, target);
        if (!NPShipsRuntimeConfig.mobMovementFixEnabled()
                && !NPShipsRuntimeConfig.mobCrossStructureFixEnabled()) return original.get();
        var previous = REQUEST.get();
        if (previous != null && previous.navigation == navigation && previous.target == target)
            return publish(navigation, completedRequest(navigation, target, original.get()));
        REQUEST.set(new Request(navigation, target));
        try {
            return publish(navigation, completedRequest(navigation, target, original.get()));
        } finally {
            if (previous == null) REQUEST.remove();
            else REQUEST.set(previous);
        }
    }

    private static NativeLandNavigation planningOwner(PathNavigation navigation) {
        if (!(navigation instanceof NativeLandNavigation.Navigator owner)
                || navigation != owner.npships$navigationMob().getNavigation()
                || !(owner.npships$navigationMob().level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) return null;
        return (NativeLandNavigation) owner.npships$navigationMob();
    }

    private static boolean validPlanning(
            PathNavigation navigation, SableSurfaceNavigation.SurfacePath path) {
        var goal = path.goal.get();
        return !path.retired
                && path.mob.isAlive()
                && goal != null
                && goal.isAlive()
                && path.mob.getNavigation() == navigation
                && path.mob.level() == path.level
                && goal.level() == path.level
                && path.level.getServer().isSameThread()
                && NPShipsRuntimeConfig.mobCrossStructureFixEnabled()
                && SableSurfaceNavigation.nativeTraversal(path.mob)
                && !SableSurfaceNavigation.independent(navigation.getPath())
                && path.matchesGoal(goal)
                && path.bodies.stream().noneMatch(dev.ryanhcode.sable.sublevel.SubLevel::isRemoved);
    }

    private static void discardOtherPlanning(PathNavigation navigation, Entity target) {
        var owner = planningOwner(navigation);
        if (owner != null
                && owner.npships$planningRequest()
                        instanceof SableSurfaceNavigation.SurfacePath path
                && (path.goal.get() != target || !validPlanning(navigation, path))) path.retire();
    }

    /** Only finished routes cross the query boundary; native goals still own moveTo. */
    private static <T> T publish(PathNavigation navigation, T result) {
        var owner = planningOwner(navigation);
        if (owner == null) return result;
        if (result instanceof SableSurfaceNavigation.SurfacePath path) {
            if (path.retired) return null;
            if (!path.searchFinished) {
                if (owner.npships$planningRequest() != path) {
                    SableSurfaceNavigation.cancel(owner.npships$planningRequest());
                    owner.npships$planningRequest(path);
                }
                if (owner.npships$waterRequest() == path) owner.npships$waterRequest(null);
                return null;
            }
            if (owner.npships$planningRequest() == path) owner.npships$planningRequest(null);
            else SableSurfaceNavigation.cancel(owner.npships$planningRequest());
        } else if (result instanceof Path) {
            SableSurfaceNavigation.cancel(owner.npships$planningRequest());
        }
        return result;
    }

    /** Resume query work in the existing navigation tick, without installing or driving a Path. */
    public static void preparePlanning(PathNavigation navigation) {
        var owner = planningOwner(navigation);
        if (owner == null
                || !(owner.npships$planningRequest()
                        instanceof SableSurfaceNavigation.SurfacePath path)) return;
        if (!validPlanning(navigation, path)
                || path.level.getGameTime() - path.requestTick > 100
                        && (path.searchFinished
                                || !path.mob.onGround()
                                        && !(path.waterExit && path.mob.isInWaterOrBubble()))) {
            path.retire();
            return;
        }
        SableSurfaceNavigation.prepare(path);
    }

    public static void cancelPlanning(PathNavigation navigation) {
        var owner = planningOwner(navigation);
        if (owner != null) SableSurfaceNavigation.cancel(owner.npships$planningRequest());
    }

    /**
     * Reuse only the exact admitted native query; foreign requests still call native PathFinder.
     */
    public static Path plannedPath(Mob mob, Set<BlockPos> targets, float range, int accuracy) {
        if (!(mob instanceof NativeLandNavigation owner)
                || !(owner.npships$planningRequest()
                        instanceof SableSurfaceNavigation.SurfacePath path)) return null;
        if (path.nativeTargets == null) rememberQuery(path, targets);
        if (!ownsRequest(mob.getNavigation(), path, accuracy, range)
                || !validPlanning(mob.getNavigation(), path)
                || !targets.equals(path.nativeTargets)) {
            path.retire();
            return null;
        }
        SableSurfaceNavigation.prepare(path);
        // A just-refused query returns its retired receipt to publish(), which exposes null.
        return path;
    }

    public static Path rememberQuery(Path result, Set<BlockPos> targets) {
        if (result instanceof SableSurfaceNavigation.SurfacePath path && path.nativeTargets == null)
            path.nativeTargets =
                    targets.stream()
                            .map(BlockPos::immutable)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return result;
    }

    /** Cache reuse belongs to the same entity request, not merely the same target cell. */
    public static boolean ownsRequest(
            PathNavigation navigation, Path result, int accuracy, float range) {
        if (!(result instanceof SableSurfaceNavigation.SurfacePath path)) return false;
        return path.accuracy == accuracy
                && path.range == range
                && sameRequestOwner(navigation, path);
    }

    private static boolean sameRequestOwner(
            PathNavigation navigation, SableSurfaceNavigation.SurfacePath path) {
        var request = REQUEST.get();
        return request != null
                && request.navigation == navigation
                && path.mob.getNavigation() == navigation
                && path.level.getServer().isSameThread()
                && !path.retired
                && NPShipsRuntimeConfig.mobCrossStructureFixEnabled()
                && SableSurfaceNavigation.nativeTraversal(path.mob)
                && request.target != null
                && path.goal.get() == request.target
                && request.target.isAlive()
                && request.target.level() == path.level
                && path.matchesGoal(request.target);
    }

    /** Retire an incompatible owned request before native code considers its cached path. */
    public static void prepareRequest(PathNavigation navigation, int accuracy, float range) {
        var owner = planningOwner(navigation);
        if (owner != null
                && owner.npships$planningRequest()
                        instanceof SableSurfaceNavigation.SurfacePath pending
                && !ownsRequest(navigation, pending, accuracy, range)) pending.retire();
        if (navigation.getPath() instanceof SableSurfaceNavigation.SurfacePath path
                && path.mob.getNavigation() == navigation
                && path.level.getServer().isSameThread()
                && !ownsRequest(navigation, path, accuracy, range)) {
            // Native AI commonly asks for accuracy 0, then moves with accuracy 1. A finished
            // route valid under the stricter tolerance remains valid for the broader request,
            // but its new contract receives an independent value and native cache key.
            if (owner != null
                    && sameRequestOwner(navigation, path)
                    && path.searchFinished
                    && !path.isDone()
                    && accuracy >= path.accuracy
                    && Float.isFinite(range)
                    && range >= path.range
                    && range <= 128) {
                var candidate = path.copyForRequest(range, accuracy);
                candidate.nativeTargets = null;
                SableSurfaceNavigation.cancel(owner.npships$planningRequest());
                owner.npships$planningRequest(candidate);
            }
            path.retire();
        }
    }

    /**
     * A cached native Path can bypass PathFinder entirely. Reuse only the same accepted entity
     * request; the native caller still decides whether to install/move along it.
     */
    @SuppressWarnings("unchecked")
    private static <T> T completedRequest(PathNavigation navigation, Entity target, T result) {
        if (!(result instanceof Path nativePath)
                || !(navigation instanceof NativeLandNavigation.Navigator owner)) return result;
        var mob = owner.npships$navigationMob();
        if (!(mob instanceof NativeLandNavigation capability)
                || !(capability.npships$waterRequest()
                        instanceof SableSurfaceNavigation.SurfacePath previous)
                || navigation != mob.getNavigation()) return result;
        if (SableSurfaceNavigation.independent(nativePath)
                || !SableSurfaceNavigation.nativeTraversal(mob)) {
            previous.retire();
            return result;
        }
        if (previous.goal.get() != target
                || target == null
                || !target.isAlive()
                || target.level() != mob.level()
                || !NPShipsRuntimeConfig.mobCrossStructureFixEnabled()
                || !(navigation instanceof GroundPathNavigation
                        || navigation instanceof WaterBoundPathNavigation)
                || !(mob.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) {
            previous.retire();
            return result;
        }
        if (!previous.matchesGoal(target)) {
            previous.retire();
            return result;
        }
        // This accepted crossing still owns its remaining land nodes after native navigation
        // switches out of swimming. Being dry is success, not cancellation of that request.
        if (previous == nativePath) return result;
        if (SableSurfaceNavigation.healthy(level, mob, target, nativePath, previous.accuracy)) {
            previous.retire();
            return result;
        }
        return previous.isDone() ? result : (T) previous;
    }

    /** Follow the native-installed value, which may be an independent copy of the candidate. */
    public static void installed(PathNavigation navigation, Path installed) {
        if (!(navigation instanceof NativeLandNavigation.Navigator owner)) return;
        var mob = owner.npships$navigationMob();
        if (navigation != mob.getNavigation()
                || !(mob.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()
                || !NPShipsRuntimeConfig.mobCrossStructureFixEnabled()
                || !SableSurfaceNavigation.nativeTraversal(mob)
                || !(mob instanceof NativeLandNavigation capability)
                || !(installed instanceof SableSurfaceNavigation.SurfacePath path)
                || path.mob != mob
                || path.retired
                || !path.waterExit) return;
        capability.npships$waterRequest(path);
    }

    /** Only the current native controller tick may refresh an accepted water-route intent. */
    public static boolean acceptWaterIntent(Mob mob) {
        if (!mob.isInWaterOrBubble()) return false;
        var request = REQUEST.get();
        return request != null
                && request.navigation == mob.getNavigation()
                && NPShipsRuntimeConfig.mobCrossStructureFixEnabled()
                && request.navigation.getPath() instanceof SableSurfaceNavigation.SurfacePath path
                && path.waterExit
                && path.ready();
    }

    /** A physical region must not cache plot AIR under an ordinary-world coordinate. */
    public static boolean isolatedPathTypes(Mob mob) {
        var request = REQUEST.get();
        return request != null
                && request.target != null
                && request.navigation == mob.getNavigation()
                && mob.level() instanceof ServerLevel level
                && level.getServer().isSameThread()
                && Sable.HELPER.getTrackingSubLevel(mob)
                        != Sable.HELPER.getTrackingSubLevel(request.target);
    }

    public static Path retry(Mob mob, float range, int accuracy, Path nativePath) {
        var request = REQUEST.get();
        if (!NPShipsRuntimeConfig.mobCrossStructureFixEnabled()
                || request == null
                || request.target == null
                || request.navigation != mob.getNavigation()
                || !(mob.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()
                || request.target.level() != level
                || request.target.isRemoved()) return nativePath;
        // A real active land request is capability evidence; a water-only mob is untouched.
        if (request.navigation instanceof GroundPathNavigation
                && mob instanceof NativeLandNavigation capability)
            capability.npships$landNavigationUsed();
        return SableSurfaceNavigation.retry(
                level, mob, request.target, nativePath, range, accuracy);
    }

    /** Arrival and the native controller must compare the same precise world point. */
    public static Vec3 preciseWaypoint(Mob mob, Path path) {
        if (!(mob.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()
                || !(mob.getNavigation() instanceof GroundPathNavigation
                        || mob.getNavigation() instanceof WaterBoundPathNavigation
                                && SableSurfaceNavigation.owned(path))
                || path == null
                || SableSurfaceNavigation.independent(path)
                || path.isDone()) return null;
        if (!SableSurfaceNavigation.owned(path)) {
            if (!NPShipsRuntimeConfig.mobMovementFixEnabled()) return null;
            var body = Sable.HELPER.getTrackingSubLevel(mob);
            if (body == null
                    || Sable.HELPER.getContaining(level, path.getNextNode().asBlockPos()) != body)
                return null;
        } else if (!NPShipsRuntimeConfig.mobCrossStructureFixEnabled()) return null;
        var point = path.getNextEntityPos(mob);
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z)
                ? point
                : null;
    }

    /** A local native path's projected contact must not be snapped to world terrain below it. */
    public static boolean preciseGroundHeight(Mob mob, Path path, Vec3 point) {
        if (SableSurfaceNavigation.owned(path)) return false;
        var exact = preciseWaypoint(mob, path);
        return exact != null && exact.distanceToSqr(point) < 1e-12;
    }

    /** Skip unfamiliar upstream comparisons; an already precise comparison is idempotent. */
    public static double arrivalDistance(
            Mob mob, Path path, Vec3 point, int axis, double original) {
        if (point == null || path == null || path.isDone()) return original;
        var rounded = path.getNextNodePos();
        double offset = ((int) (mob.getBbWidth() + 1)) / 2.;
        double here = axis == 0 ? mob.getX() : axis == 1 ? mob.getY() : mob.getZ();
        double old =
                axis == 0
                        ? rounded.getX() + offset
                        : axis == 1 ? rounded.getY() : rounded.getZ() + offset;
        double exact = Math.abs(here - (axis == 0 ? point.x : axis == 1 ? point.y : point.z));
        double arrival =
                axis == 1 ? SableSurfaceNavigation.shoreHeightDistance(path, exact) : exact;
        return Math.abs(original - Math.abs(here - old)) < 1e-7 || Math.abs(original - exact) < 1e-7
                ? arrival
                : original;
    }

    public static PathType worldPathType(BlockGetter world, BlockPos pos) {
        return NativeTypes.read(world, pos);
    }

    // Only expose the protected read-only classifier, without replacing the native evaluator.
    private static final class NativeTypes extends WalkNodeEvaluator {
        static PathType read(BlockGetter world, BlockPos pos) {
            return getPathTypeFromState(world, pos);
        }
    }
}
