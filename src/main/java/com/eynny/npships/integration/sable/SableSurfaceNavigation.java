package com.eynny.npships.integration.sable;

import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.mixin.NPShipsMobJumpAccessor;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.*;
import net.minecraft.world.phys.*;

import java.util.*;

/**
 * Bounded server-thread surface search. Half-block X/Z samples use actual floating supports;
 * transformed native collision boxes are tested continuously, never rasterized into full blocks.
 * All shape caches are call-local. Finished paths retain only local contact anchors.
 */
public final class SableSurfaceNavigation {
    private SableSurfaceNavigation() {}

    static final long TICK_BUDGET_NANOS = 5_000_000L;
    static final double EPS = 1e-7, SKIN = 1e-4;
    static final Vec3 X = new Vec3(1, 0, 0), Y = new Vec3(0, 1, 0), Z = new Vec3(0, 0, 1);

    static class Exhausted extends RuntimeException {
        Exhausted() {}

        Exhausted(String reason) {
            super(reason);
        }

        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    static final class AllowanceExhausted extends Exhausted {}

    // One shared allowance on the existing server tick, not a per-mob timer or scheduler.
    // Clock checkpoints bound our work; a foreign collision-shape callback is not preemptible.
    static final class Slot {
        java.lang.ref.WeakReference<net.minecraft.server.MinecraftServer> server;
        int tick, cells, nodes, boxes;
        long spent;
        java.lang.ref.WeakReference<SurfacePath> routeHint;
    }

    static final ThreadLocal<Slot> SLOT = ThreadLocal.withInitial(Slot::new);

    static final class Budget {
        final Slot slot;
        final long begin = System.nanoTime(), deadline;
        int cells, nodes;

        Budget(ServerLevel level) {
            slot = SLOT.get();
            var server = level.getServer();
            int tick = server.getTickCount();
            if (slot.server == null || slot.server.get() != server || slot.tick != tick) {
                slot.server = new java.lang.ref.WeakReference<>(server);
                slot.tick = tick;
                slot.spent = 0;
                slot.routeHint = null;
                slot.cells = slot.nodes = slot.boxes = 0;
            }
            deadline = begin + Math.max(0, TICK_BUDGET_NANOS - slot.spent);
        }

        void check() {
            if (System.nanoTime() >= deadline || slot.cells >= 8192 || slot.nodes >= 512)
                throw new AllowanceExhausted();
        }

        void cell() {
            check();
            if (++cells > 8192 || ++slot.cells > 8192) throw new AllowanceExhausted();
        }

        void node() {
            check();
            if (++nodes > 512 || ++slot.nodes > 512) throw new AllowanceExhausted();
        }

        void box() {
            check();
            if (++slot.boxes > 4096) throw new AllowanceExhausted();
        }

        void finish() {
            slot.spent += System.nanoTime() - begin;
        }
    }

    /**
     * Preserve the entity request while native navigation recomputes an owned route by position.
     */
    public static Entity target(Path path) {
        return path instanceof SurfacePath surface ? surface.goal.get() : null;
    }

    public static void cancel(Path path) {
        if (path instanceof SurfacePath surface) surface.retire();
    }

    public static boolean owned(Path path) {
        return path instanceof SurfacePath;
    }

    /** A custom native Path may own extra traversal/state contracts that we cannot replace. */
    static boolean independent(Path path) {
        return path != null && path.getClass() != Path.class && !owned(path);
    }

    /** Custom evaluators may impose traversal rules that this surface graph does not know. */
    static boolean nativeTraversal(Mob mob) {
        var navigation = mob.getNavigation();
        if (navigation == null) return false;
        var evaluator = navigation.getNodeEvaluator();
        return evaluator != null
                && (evaluator.getClass() == WalkNodeEvaluator.class
                        || evaluator.getClass() == SwimNodeEvaluator.class);
    }

    /** Identify the accepted medium without running validation or changing its state. */
    public static boolean waterRoute(Path path) {
        return path instanceof SurfacePath surface && surface.waterExit && !surface.retired;
    }

    /** A shore route cannot use a loose arrival radius to skip its narrow landing contact. */
    public static float arrivalHorizontalTolerance(Path path, float nativeTolerance) {
        if (path instanceof SurfacePath surface
                && surface.refined
                && !surface.waterExit
                && !surface.isDone()) return Math.min(nativeTolerance, .125f);
        if (path instanceof SurfacePath surface && surface.waterExit && !surface.isDone()) {
            var anchor = surface.anchors.get(surface.getNextNodeIndex());
            // Interior swimming retains native arrival. Fine-lattice contacts otherwise make
            // distance-proportional native controllers crawl toward every quarter-block point.
            if (!anchor.water
                    || anchor.liftLanding != null
                    || !(surface.mob.getNavigation() instanceof WaterBoundPathNavigation))
                return Math.min(nativeTolerance, .125f);
        }
        return nativeTolerance;
    }

    /**
     * Continue precise arrival during a checked native breach instead of the rounded air fallback.
     */
    public static boolean updateShore(Path path, boolean nativeResult) {
        return nativeResult
                || path instanceof SurfacePath surface && surface.waterExit && surface.ready();
    }

    public static double shoreHeightDistance(Path path, double exact) {
        if (path instanceof SurfacePath surface && surface.waterExit && !surface.isDone()) {
            Vec3 point = surface.getNextEntityPos(surface.mob);
            if (surface.mob.getY() >= point.y - SKIN) return 0;
        }
        return exact;
    }

    /** Water stances must reach their swim height before a native corner can advance to shore. */
    public static double arrivalHeightTolerance(Path path, double nativeTolerance) {
        return path instanceof SurfacePath surface && !surface.isDone() && surface.waterExit
                ? Math.min(nativeTolerance, .125)
                : nativeTolerance;
    }

    /** Exhausting shared validation work pauses an owned route without discarding it. */
    public static boolean ready(Path path) {
        return !(path instanceof SurfacePath surface) || surface.ready();
    }

    /** Resume only this native navigation owner's bounded search; no independent ticker exists. */
    public static Path prepare(Path path) {
        if (!(path instanceof SurfacePath surface) || surface.retired || surface.searchFinished)
            return path;
        if (!nativeTraversal(surface.mob)) {
            surface.retire();
            return path;
        }
        // A suspended land search cannot resume while its owner is swimming. Retire it so
        // the existing aquatic controller can request its own path instead of waiting for land.
        if (!surface.waterExit && !surface.mob.onGround() && surface.mob.isInWaterOrBubble()) {
            surface.retire();
            return path;
        }
        var target = surface.goal.get();
        if (target == null || !target.isAlive() || target.level() != surface.level) {
            surface.retire();
            return path;
        }
        long tick = surface.level.getGameTime();
        if (!surface.mob.onGround() && !(surface.waterExit && surface.mob.isInWaterOrBubble()))
            return path;
        if (surface.extendedTick == tick) return path;
        surface.extendedTick = tick;
        Budget budget = new Budget(surface.level);
        try {
            // Finish bounded planning at the native stuck horizon, retaining useful approach.
            // Reconstruction is bounded by the same frontier and charged to the shared slot.
            if (tick - surface.requestTick > 100) {
                surface.completePartial(target);
                return path;
            }
            budget.check();
            if (surface.request == null) {
                surface.retire();
                return path;
            }
            var scene =
                    new Scene(
                            surface.level,
                            surface.mob,
                            budget,
                            surface.mob.getBoundingBox().inflate(surface.range + 4),
                            surface.waterExit);
            Path next = SurfaceRouteHints.reuse(scene, target, surface.range, surface.accuracy);
            if (next == null) next = surface.request.step(scene, target);
            if (next == null) surface.retire();
            else {
                surface.accept((SurfacePath) next);
                SurfaceRouteHints.remember(surface, budget.slot);
                return surface;
            }
        } catch (AllowanceExhausted defer) {
            // The request retains only a bounded frontier and local anchors, never shape caches.
        } catch (Exhausted invalid) {
            surface.retire();
        } finally {
            budget.finish();
        }
        return path;
    }

    /** Pause horizontal intent only for the validated lift phase of this native land request. */
    public static double shoreSpeed(Path path, double nativeSpeed) {
        return path instanceof SurfacePath surface
                        && surface.waterExit
                        && surface.ready()
                        && surface.anchors.get(surface.getNextNodeIndex()).liftLanding != null
                ? 0
                : nativeSpeed;
    }

    public static void jump(Path path) {
        if (!(path instanceof SurfacePath surface) || !surface.ready()) return;
        var mob = surface.mob;
        if ((!mob.onGround() || !mob.horizontalCollision)
                && !(surface.waterExit
                        && mob.isInWaterOrBubble()
                        && (mob.getNavigation() instanceof GroundPathNavigation
                                || surface.anchors.get(surface.getNextNodeIndex()).liftLanding
                                        != null))) return;
        Vec3 next = path.getNextEntityPos(mob);
        double dx = next.x - mob.getX(), dz = next.z - mob.getZ();
        if (next.y > mob.getY() + SKIN && dx * dx + dz * dz < Math.max(1, mob.getBbWidth()))
            mob.getJumpControl().jump();
    }

    public static Path retry(
            ServerLevel level, Mob mob, Entity target, Path original, float range, int accuracy) {
        if (!NPShipsRuntimeConfig.mobCrossStructureFixEnabled()
                || independent(original)
                || !nativeTraversal(mob)
                || !level.getServer().isSameThread()
                || !Float.isFinite(range)
                || accuracy < 0
                || !(mob.getNavigation() instanceof GroundPathNavigation
                        || mob.getNavigation() instanceof WaterBoundPathNavigation
                                && (mob instanceof NativeLandNavigation capability
                                                && capability.npships$hasLandNavigation()
                                        || target != null
                                                && mob.isInWaterOrBubble()
                                                && target.isInWaterOrBubble()))
                || target == null
                || !mob.isAlive()
                || range <= 0
                || range > 128
                || mob.distanceToSqr(target) > range * range) return original;
        if (Sable.HELPER.getTrackingSubLevel(mob) == Sable.HELPER.getTrackingSubLevel(target))
            return original;
        if (healthy(level, mob, target, original, accuracy)) return original;
        Path fallback = nativeApproach(level, mob, target, original);
        if (mob.getNavigation().getPath() instanceof SurfacePath existing) {
            try {
                if (!existing.isDone()
                        && existing.targetId.equals(target.getUUID())
                        && (existing.canReach()
                                        ? existing.anchors
                                                .getLast()
                                                .point(mob)
                                                .distanceToSqr(target.position())
                                        : Vec3.atCenterOf(existing.getTarget())
                                                .distanceToSqr(target.position()))
                                < (accuracy + 1.5) * (accuracy + 1.5)) return existing;
            } catch (Exhausted invalid) {
                existing.retire();
            }
        }
        boolean reverseWater =
                mob.isInWaterOrBubble()
                        && Sable.HELPER.getTrackingSubLevel(mob) == null
                        && Sable.HELPER.getTrackingSubLevel(target) != null
                        && !target.isInWater();
        boolean waterExit =
                reverseWater
                        || Sable.HELPER.getTrackingSubLevel(mob) != null
                                && Sable.HELPER.getTrackingSubLevel(target) == null
                                && target.isInWaterOrBubble()
                        // A submerged target on a body is still a water crossing. Keeping it
                        // outside both shore cases strands native swimmers at the hull.
                        || mob.isInWaterOrBubble() && target.isInWaterOrBubble();
        if (bottomWalker(mob)) {
            reverseWater = false;
            waterExit = false;
        }
        if (mob.getNavigation() instanceof WaterBoundPathNavigation && !waterExit
                || !mob.onGround() && mob.isInWaterOrBubble() && !waterExit) return fallback;
        Search request = new Search(range, accuracy, waterExit, reverseWater);
        Budget budget = new Budget(level);
        try {
            budget.check();
            request.admitted = true;
            Scene scene =
                    new Scene(
                            level, mob, budget, mob.getBoundingBox().inflate(range + 4), waterExit);
            if (scene.frames.size() == 1) return fallback;
            Path result = SurfaceRouteHints.reuse(scene, target, range, accuracy);
            if (result == null) result = request.step(scene, target);
            if (result instanceof SurfacePath complete)
                SurfaceRouteHints.remember(complete, budget.slot);
            if (result instanceof SurfacePath surface
                    && surface.waterExit
                    && mob instanceof NativeLandNavigation capability)
                capability.npships$waterRequest(surface);
            return result == null ? fallback : result;
        } catch (AllowanceExhausted defer) {
            if (waterExit && !request.admitted) return fallback;
            // No validated progress yet: wait at the current point rather than walk a stale
            // native partial path away from the target and restart its goal every 20 ticks.
            var pending =
                    new SurfacePath(
                            mob,
                            List.of(new Anchor(mob.position())),
                            BlockPos.containing(target.position()),
                            target.getUUID(),
                            false,
                            range,
                            accuracy,
                            target);
            pending.request = request;
            pending.waterExit = waterExit;
            if (waterExit && mob instanceof NativeLandNavigation capability)
                capability.npships$waterRequest(pending);
            pending.checkedTick = Long.MIN_VALUE;
            return pending;
        } catch (Exhausted refusal) {
            return fallback;
        } finally {
            budget.finish();
        }
    }

    /** A non-floating ground navigator keeps its native bottom-walking medium. */
    static boolean bottomWalker(Mob mob) {
        return mob.isInWaterOrBubble()
                && mob.getNavigation() instanceof GroundPathNavigation
                && !mob.getNavigation().getNodeEvaluator().canFloat()
                && mob.getMoveControl().getClass()
                        == net.minecraft.world.entity.ai.control.MoveControl.class;
    }

    /** Do not reinstall a known invalid or zero-progress native fallback after a failed retry. */
    static Path nativeApproach(ServerLevel level, Mob mob, Entity target, Path path) {
        if (path == null
                || owned(path)
                || path.canReach()
                || path.getNodeCount() == 0
                || path.isDone()) return null;
        var first = Sable.HELPER.projectOutOfSubLevel(level, path.getEntityPosAtNode(mob, 0));
        var last =
                Sable.HELPER.projectOutOfSubLevel(
                        level, path.getEntityPosAtNode(mob, path.getNodeCount() - 1));
        double startRadius = Math.max(3, mob.getBbWidth() * .5 + 1);
        return finite(first)
                        && finite(last)
                        && first.distanceToSqr(mob.position()) < startRadius * startRadius
                        && last.distanceTo(target.position()) + .25 < mob.distanceTo(target)
                ? path
                : null;
    }

    static boolean healthy(ServerLevel l, Mob m, Entity t, Path p, int accuracy) {
        boolean admitted =
                p != null
                        && !owned(p)
                        && p.canReach()
                        && p.getNodeCount() > 0
                        && Sable.HELPER
                                        .projectOutOfSubLevel(
                                                l, Vec3.atBottomCenterOf(p.getNode(0).asBlockPos()))
                                        .distanceToSqr(m.position())
                                < 9
                        && Sable.HELPER
                                        .projectOutOfSubLevel(
                                                l,
                                                Vec3.atBottomCenterOf(p.getEndNode().asBlockPos()))
                                        .distanceToSqr(t.position())
                                < (accuracy + 1.5) * (accuracy + 1.5);
        if (!admitted || p.isDone()) return false;
        // Native canReach describes its own grid. A nearby physical block can still occupy
        // the next world-space step, so verify that short corridor before keeping the route.
        int index = Math.min(p.getNextNodeIndex() + 1, p.getNodeCount() - 1);
        Vec3 next = Sable.HELPER.projectOutOfSubLevel(l, p.getEntityPosAtNode(m, index));
        if (!finite(next) || next.distanceToSqr(m.position()) > 9) return false;
        Budget budget = new Budget(l);
        try {
            budget.check();
            var bounds =
                    m.getBoundingBox()
                            .minmax(m.getBoundingBox().move(next.subtract(m.position())))
                            .inflate(1);
            var scene =
                    new Scene(
                            l,
                            m,
                            budget,
                            bounds,
                            m.getNavigation() instanceof WaterBoundPathNavigation);
            return scene.frames.size() == 1
                    || scene.clear(m.position().add(0, SKIN, 0), next.add(0, SKIN, 0));
        } catch (Exhausted refused) {
            return false;
        } finally {
            budget.finish();
        }
    }

    record Key(int x, int z, long y) {
        static Key at(Vec3 point) {
            int resolution = 4;
            return new Key(
                    (int) Math.round(point.x * resolution),
                    (int) Math.round(point.z * resolution),
                    Math.round(point.y * 16));
        }
    }

    static final class Vertex {
        final Key key;
        final Vec3 p;
        final Anchor anchor;
        final double g, h;
        final Vertex previous;
        Anchor lift;

        Vertex(Vec3 p, Anchor a, double g, double h, Vertex previous) {
            this.p = p;
            this.anchor = a;
            this.g = g;
            this.h = h;
            this.previous = previous;
            key = Key.at(p);
        }
    }

    static Path search(Scene s, Entity target, float range, int accuracy) {
        return new Search(range, accuracy).step(s, target);
    }

    /** The frontier belongs to one pending Path and is released with native goal cancellation. */
    static final class Search {
        final float range;
        final int accuracy;
        final boolean waterExit, reverseWater;
        boolean admitted, refined;
        // Match native PathFinder's 1.5 goal bias instead of exhausting equally promising
        // side branches. Only ordering changes; every admitted edge is still checked.
        final PriorityQueue<Vertex> open =
                new PriorityQueue<>(
                        Comparator.comparingDouble((Vertex v) -> v.g + v.h * 1.5)
                                .thenComparingDouble(v -> v.h));
        final Map<Key, Vertex> best = new HashMap<>();
        final Set<Key> closed = new HashSet<>();
        Vertex first, expanding, closest;
        int neighbour;
        boolean waterJoinTried;
        long startedTick;
        Vec3 origin;
        GoalStances goalStances;

        Search(float range, int accuracy) {
            this(range, accuracy, false);
        }

        Search(float range, int accuracy, boolean waterExit) {
            this(range, accuracy, waterExit, waterExit);
        }

        Search(float range, int accuracy, boolean waterExit, boolean reverseWater) {
            this.range = range;
            this.accuracy = accuracy;
            this.waterExit = waterExit;
            this.reverseWater = reverseWater;
        }

        Search copy() {
            // Vertices/local anchors are immutable; only the owner's queue and progress differ.
            var result = new Search(range, accuracy, waterExit, reverseWater);
            result.admitted = admitted;
            result.refined = refined;
            result.open.addAll(open);
            result.best.putAll(best);
            result.closed.addAll(closed);
            result.first = first;
            result.closest = closest;
            result.expanding = expanding;
            result.neighbour = neighbour;
            result.waterJoinTried = waterJoinTried;
            result.startedTick = startedTick;
            result.origin = origin;
            result.goalStances = goalStances == null ? null : goalStances.copy();
            return result;
        }

        Path step(Scene s, Entity target) {
            Path result = advance(s, target);
            if (result instanceof SurfacePath surface) surface.refined = refined;
            return result;
        }

        private Path advance(Scene s, Entity target) {
            Vec3 targetPosition = target.position();
            if (reverseWater && first == null) {
                if (origin == null) origin = s.mob.position();
                if (goalStances == null) goalStances = new GoalStances(targetPosition);
                goalStances.probe(s, targetPosition);
                if (!goalStances.done && !goalStances.exact) throw new AllowanceExhausted();
                if (!s.waterSupport(origin) || !s.clear(origin, origin)) return null;
                // Search the same directed edges from proven dry stances toward the swimmer.
                // This avoids spending the bounded frontier on open water below a closed hull.
                for (Vec3 column : goalStances.nearest) {
                    for (Anchor anchor : s.readFloors(column.x, column.z, column.y, .1, .1)) {
                        if (anchor.water
                                || anchor.planned.distanceToSqr(column) > .025
                                || !s.clear(anchor.planned, anchor.planned)) continue;
                        var v =
                                new Vertex(
                                        anchor.planned,
                                        anchor,
                                        0,
                                        anchor.planned.distanceTo(origin),
                                        null);
                        if (first == null) first = v;
                        if (best.putIfAbsent(v.key, v) == null) open.add(v);
                    }
                }
                if (first == null) return null;
                startedTick = s.level.getGameTime();
            }
            Vec3 goal = reverseWater ? origin : targetPosition;
            if (first == null) {
                Anchor support = null;
                Vec3 start = s.mob.position();
                if (waterExit
                        && s.mob.isInWaterOrBubble()
                        && s.waterAtFeet(start)
                        && s.waterSupport(start)
                        && s.clear(start, start))
                    support = Anchor.water(start, s.mob.getPathfindingMalus(PathType.WATER));
                for (Anchor a :
                        support == null ? s.floors(start.x, start.z, start.y) : List.<Anchor>of()) {
                    Vec3 p = a.point(s.mob);
                    if (Math.abs(p.y - start.y) <= Math.max(.125, s.mob.maxUpStep())
                            && s.clear(p, p)) {
                        support = a;
                        start = p;
                        break;
                    }
                }
                if (support == null) {
                    Vec3 nativeStart = s.mob.position().add(0, SKIN, 0);
                    if (!s.mob.onGround()
                            || !s.clear(nativeStart, nativeStart)
                            || s.clear(
                                    nativeStart,
                                    nativeStart.add(0, -Math.max(.125, s.mob.maxUpStep()), 0)))
                        return null;
                    start = nativeStart;
                }
                first = new Vertex(start, support, 0, start.distanceTo(goal), null);
                startedTick = s.level.getGameTime();
                open.add(first);
                best.put(first.key, first);
            }
            // A native push or a fast moving goal invalidates the pending request, not the AI.
            Vec3 source = reverseWater ? origin : first.p;
            if (source.distanceToSqr(s.mob.position()) > 1
                    || source.distanceToSqr(targetPosition) > range * range) return null;
            if (!waterExit
                    && accuracy == 0
                    && (Math.abs(first.p.y - goal.y) >= 1
                            || Math.hypot(first.p.x - goal.x, first.p.z - goal.z) > .55)) {
                if (goalStances == null) goalStances = new GoalStances(goal, refined ? 4 : 2);
                goalStances.probe(s, goal);
            }
            while (expanding != null || !open.isEmpty()) {
                if (expanding == null) {
                    Vertex v = open.peek();
                    if (best.get(v.key) != v || closed.contains(v.key)) {
                        open.remove();
                        continue;
                    }
                    s.budget.node();
                    open.remove();
                    closed.add(v.key);
                    expanding = v;
                    if (closest == null || v.p.distanceToSqr(goal) < closest.p.distanceToSqr(goal))
                        closest = v;
                    neighbour = 0;
                    waterJoinTried = false;
                }
                Vertex v = expanding;
                Vec3 at = v.anchor == null ? v.p : v.anchor.point(s.mob);
                // Native motion continues while a request is suspended. Join its current feet,
                // not a historical swim depth; the same drift bound and edge checks still apply.
                Vec3 entry = s.mob.position();
                if (reverseWater && !waterJoinTried && plainWater(v.anchor)) {
                    // Try a fully swept open-water connection once per expanded contact. If its
                    // geometry spends this tick's allowance, resume the original graph next time.
                    waterJoinTried = true;
                    Vertex join = waterConnection(s, v, entry, at);
                    if (join != null)
                        return reverseRoute(
                                s.mob, join, target, goalStances.exact, range, accuracy);
                }
                if (reverseWater
                        && Math.abs(at.y - entry.y) <= .5
                        && Math.hypot(at.x - entry.x, at.z - entry.z) <= .5
                        && s.edge(entry, at)) {
                    var start = Anchor.water(entry, s.mob.getPathfindingMalus(PathType.WATER));
                    var end = new Vertex(entry, start, v.g + entry.distanceTo(at), 0, v);
                    boolean connected = true;
                    if (at.y > entry.y + SKIN) {
                        var high = new Vec3(entry.x, at.y + SKIN, entry.z);
                        connected = s.clear(entry, high) && s.clear(high, at);
                        if (connected) end.lift = Anchor.lift(entry, v.anchor);
                    }
                    if (connected)
                        return reverseRoute(s.mob, end, target, goalStances.exact, range, accuracy);
                }
                if (waterExit && !reverseWater && !waterJoinTried) {
                    waterJoinTried = true;
                    Vertex end = waterContinuation(s, v, at, goal);
                    if (end != null) {
                        var path = (SurfacePath) route(s.mob, end, target, true, range, accuracy);
                        path.checkedTick = Long.MIN_VALUE;
                        return path;
                    }
                }
                if (!reverseWater
                        && (waterExit
                                ? v.anchor != null && v.anchor.water && s.waterSupport(goal)
                                : v.anchor == null || !v.anchor.water)
                        && Math.abs(at.y - goal.y) < 1
                        && Math.hypot(at.x - goal.x, at.z - goal.z)
                                <= Math.max(.55, accuracy + .5)) {
                    var path = (SurfacePath) route(s.mob, v, target, true, range, accuracy);
                    if (startedTick != s.level.getGameTime() || waterExit)
                        path.checkedTick = Long.MIN_VALUE;
                    return path;
                }
                if (!waterExit
                        && goalStances != null
                        && (v.anchor == null || !v.anchor.water)
                        && goalStances.approach < 4
                        && at.distanceTo(goal) <= goalStances.approach + .03)
                    return partial(s.mob, target, waterExit ? v : closest);
                for (; neighbour < 8; neighbour++) {
                    int n = neighbour < 4 ? neighbour : neighbour + 1;
                    int dx = n / 3 - 1, dz = n % 3 - 1;
                    int resolution = waterExit || refined ? 4 : 2;
                    double x = (Math.round(at.x * resolution) + dx) / (double) resolution;
                    double z = (Math.round(at.z * resolution) + dz) / (double) resolution;
                    if (Math.hypot(x - source.x, z - source.z) > range) continue;
                    // An interrupted neighbour is retried. Already admitted candidates retain
                    // their best cost, so no edge or frontier is silently lost at a budget cut.
                    var candidates = s.floors(x, z, at.y);
                    double waterGoalY = reverseWater ? origin.y : goal.y;
                    if (waterExit && at.y > waterGoalY + SKIN) {
                        var lower = new Vec3(x, Math.max(waterGoalY, at.y - .5), z);
                        if (s.waterSupport(lower)) {
                            candidates = new ArrayList<>(candidates);
                            candidates.add(
                                    Anchor.water(lower, s.mob.getPathfindingMalus(PathType.WATER)));
                        }
                    }
                    for (Anchor anchor : candidates) {
                        Vec3 point = anchor.planned;
                        Key key = Key.at(point);
                        if (closed.contains(key)) continue;
                        Vec3 edgeStart = reverseWater ? point : at;
                        Vec3 edgeEnd = reverseWater ? at : point;
                        Anchor from = reverseWater ? anchor : v.anchor;
                        Anchor into = reverseWater ? v.anchor : anchor;
                        double rise = edgeEnd.y - edgeStart.y;
                        boolean lifting =
                                waterExit
                                        && !s.waterOnly
                                        && from != null
                                        && from.water
                                        && rise > SKIN;
                        Vec3 high =
                                lifting
                                        ? new Vec3(edgeStart.x, edgeEnd.y + SKIN, edgeStart.z)
                                        : null;
                        double distance =
                                lifting
                                        ? edgeStart.distanceTo(high) + high.distanceTo(edgeEnd)
                                        : at.distanceTo(point);
                        double g = v.g + distance + (reverseWater ? v.anchor.cost : anchor.cost);
                        Vertex old = best.get(key);
                        // A resumed neighbour can contain candidates already admitted before the
                        // budget cut. Their unchanged costs cannot improve, so do no geometry
                        // twice.
                        if (old != null && old.g <= g) continue;
                        if (rise > s.maxRise + SKIN
                                        && !(waterExit
                                                && into != null
                                                && into.water
                                                && s.waterSupport(edgeStart))
                                || rise < -s.mob.getMaxFallDistance() - .03) continue;
                        if (lifting && (!s.clear(edgeStart, high) || !s.clear(high, edgeEnd)))
                            continue;
                        if (!s.edge(edgeStart, edgeEnd)) continue;
                        Vertex next =
                                new Vertex(
                                        point,
                                        anchor,
                                        g,
                                        waterExit || goalStances == null
                                                ? point.distanceTo(goal)
                                                : goalStances.distance(point, goal),
                                        v);
                        if (lifting) next.lift = Anchor.lift(edgeStart, into);
                        if (old == null && best.size() >= 512) return partial(s.mob, target);
                        best.put(next.key, next);
                        if (open.size() >= 512)
                            open.removeIf(candidate -> best.get(candidate.key) != candidate);
                        if (open.size() >= 512) return partial(s.mob, target);
                        open.add(next);
                    }
                }
                expanding = null;
            }
            Path partial = partial(s.mob, target);
            if (partial != null || waterExit || refined || best.isEmpty()) return partial;
            // A half-block lattice can miss a real narrow passage after a fractional transform.
            // Refine only after the coarse frontier has failed, reusing its checked contacts.
            refined = true;
            goalStances = null;
            closed.clear();
            open.addAll(best.values());
            closest = null;
            expanding = null;
            neighbour = 0;
            return step(s, target);
        }

        Vertex waterConnection(Scene scene, Vertex end, Vec3 entry, Vec3 at) {
            double distance = entry.distanceTo(at);
            if (distance <= .5
                    || distance > 16
                    || !scene.waterSupport(entry)
                    || !scene.clear(entry, at)
                    || !scene.supportedEdge(entry, at)) return null;
            int segments = (int) Math.ceil(distance / 2);
            Vertex join = end;
            for (int i = segments - 1; i >= 0; i--) {
                Vec3 point = entry.lerp(at, i / (double) segments);
                if (!scene.waterSupport(point)) return null;
                var anchor = Anchor.water(point, scene.mob.getPathfindingMalus(PathType.WATER));
                if (point.y - join.p.y < -scene.mob.getMaxFallDistance() - .03) return null;
                join = new Vertex(point, anchor, join.g + point.distanceTo(join.p), 0, join);
            }
            return join;
        }

        Vertex waterContinuation(Scene scene, Vertex start, Vec3 at, Vec3 goal) {
            double distance = at.distanceTo(goal);
            // A checked native drop can leave a dry edge directly for nearby world water.
            // Longer shortcuts remain wholly aquatic and keep the two-block intent horizon.
            if (!plainWater(start.anchor)) {
                if (distance > 2
                        || goal.y > at.y
                        || !scene.waterSupport(goal)
                        || !scene.edge(at, goal)) return null;
                var landing = Anchor.water(goal, scene.mob.getPathfindingMalus(PathType.WATER));
                return new Vertex(goal, landing, start.g + distance + landing.cost, 0, start);
            }
            if (distance > 16
                    || !scene.waterSupport(goal)
                    || !scene.clear(at, goal)
                    || !scene.supportedEdge(at, goal)) return null;
            int segments = Math.max(1, (int) Math.ceil(distance / 2));
            Vertex end = start;
            for (int i = 1; i <= segments; i++) {
                Vec3 point = at.lerp(goal, i / (double) segments);
                if (!scene.waterSupport(point)) return null;
                var anchor = Anchor.water(point, scene.mob.getPathfindingMalus(PathType.WATER));
                if (point.y - end.p.y < -scene.mob.getMaxFallDistance() - .03) return null;
                end = new Vertex(point, anchor, end.g + point.distanceTo(end.p), 0, end);
            }
            return end;
        }

        Path partial(Mob mob, Entity target) {
            return partial(mob, target, closest);
        }

        Path partial(Mob mob, Entity target, Vertex end) {
            // A reverse prefix at the destination is not a usable approach from the swimmer.
            if (reverseWater) return null;
            // Like native PathFinder, keep useful approach when the exact goal is unreachable.
            // Only expanded vertices have a fully checked route; a pending horizon is not one.
            if (end == null
                    || end.previous == null
                    || first.p.distanceToSqr(mob.position()) > 1
                    || first.p.distanceToSqr(target.position()) > range * range
                    || end.p.distanceTo(target.position()) + .25
                            >= first.p.distanceTo(target.position())) return null;
            var result = (SurfacePath) route(mob, end, target, false, range, accuracy);
            result.searchFinished = true;
            result.refined = refined;
            result.checkedTick = Long.MIN_VALUE;
            return result;
        }
    }

    /**
     * Check the actual terminal lattice before exhausting a search for an unsupported target. A
     * valid exact stance preserves full search and detours. Otherwise only a checked partial
     * approach may finish early. State is bounded and owns no world, frame or shape cache.
     */
    static final class GoalStances {
        final Vec3 goal;
        final List<Vec3> columns;
        final List<Vec3> nearest = new ArrayList<>();
        int cursor, deferredColumn = -1;
        double approach = Double.POSITIVE_INFINITY;
        boolean done, exact;

        GoalStances(Vec3 goal) {
            this(goal, 2);
        }

        GoalStances(Vec3 goal, int resolution) {
            this.goal = goal;
            var positions = new ArrayList<Vec3>();
            double centerX = Math.rint(goal.x * resolution) / resolution;
            double centerZ = Math.rint(goal.z * resolution) / resolution;
            int reach = 4 * resolution;
            for (int dx = -reach; dx <= reach; dx++)
                for (int dz = -reach; dz <= reach; dz++) {
                    var p =
                            new Vec3(
                                    centerX + dx / (double) resolution,
                                    goal.y,
                                    centerZ + dz / (double) resolution);
                    if (p.distanceToSqr(goal) <= 16) positions.add(p);
                }
            positions.sort(Comparator.comparingDouble(p -> p.distanceToSqr(goal)));
            columns = List.copyOf(positions);
        }

        private GoalStances(GoalStances source) {
            goal = source.goal;
            columns = source.columns;
            nearest.addAll(source.nearest);
            cursor = source.cursor;
            deferredColumn = source.deferredColumn;
            approach = source.approach;
            done = source.done;
            exact = source.exact;
        }

        GoalStances copy() {
            return new GoalStances(this);
        }

        double distance(Vec3 point, Vec3 originalGoal) {
            if (nearest.isEmpty()) return point.distanceTo(originalGoal);
            double distance = Double.POSITIVE_INFINITY;
            for (Vec3 stand : nearest) distance = Math.min(distance, point.distanceToSqr(stand));
            return Math.sqrt(distance);
        }

        private void abandon() {
            approach = Double.POSITIVE_INFINITY;
            nearest.clear();
            exact = false;
            done = true;
        }

        void probe(Scene scene, Vec3 current) {
            if (current.distanceToSqr(goal) > 1. / 256) {
                abandon();
            }
            if (done) return;
            try {
                for (; cursor < columns.size(); cursor++) {
                    var column = columns.get(cursor);
                    if (exact && Math.hypot(column.x - goal.x, column.z - goal.z) > .55) {
                        done = true;
                        return;
                    }
                    for (var a : scene.readFloors(column.x, column.z, goal.y, 4, 4)) {
                        if (a.water) continue;
                        Vec3 point = a.planned;
                        if (!scene.clear(point, point)) continue;
                        if (Math.abs(point.y - goal.y) < 1
                                && Math.hypot(point.x - goal.x, point.z - goal.z) <= .55) {
                            // Aim at actual terminal stances rather than the entity's feet in
                            // the air. This only changes ordering; full search/detours remain.
                            if (!exact) {
                                exact = true;
                                approach = Double.POSITIVE_INFINITY;
                                nearest.clear();
                            }
                            if (nearest.size() >= 512) {
                                abandon();
                                return;
                            }
                            nearest.add(point);
                            continue;
                        }
                        if (exact) continue;
                        double distance = point.distanceTo(goal);
                        if (distance >= 4) continue;
                        if (distance < approach) {
                            approach = distance;
                            nearest.removeIf(
                                    previous -> previous.distanceTo(goal) > distance + .03);
                        }
                        if (distance <= approach + .03
                                && nearest.stream()
                                        .noneMatch(
                                                previous -> previous.distanceToSqr(point) < 1e-8)) {
                            if (nearest.size() >= 512) {
                                abandon();
                                return;
                            }
                            nearest.add(point);
                        }
                    }
                }
                done = true;
            } catch (AllowanceExhausted defer) {
                if (deferredColumn == cursor && !scene.waterExit) {
                    // Land search can continue without optional goal hints. Reverse water
                    // search needs its terminal seeds: retain them under the same request lifetime.
                    abandon();
                } else {
                    deferredColumn = cursor;
                    throw defer;
                }
            } catch (Exhausted unavailable) {
                // This optimization cannot add a new refusal for native/unknown geometry.
                abandon();
            }
        }
    }

    static SurfacePath reverseRoute(
            Mob mob, Vertex start, Entity target, boolean reached, float range, int accuracy) {
        var anchors = new ArrayList<Anchor>();
        for (Vertex v = start; v != null; v = v.previous) {
            anchors.add(v.anchor);
            if (v.lift != null) anchors.add(v.lift);
        }
        compactWaterAnchors(anchors);
        var path =
                new SurfacePath(
                        mob,
                        anchors,
                        target.blockPosition(),
                        target.getUUID(),
                        reached,
                        range,
                        accuracy,
                        target);
        path.searchFinished = true;
        path.checkedTick = Long.MIN_VALUE;
        return path;
    }

    static Path route(
            Mob mob, Vertex end, Entity target, boolean reached, float range, int accuracy) {
        ArrayList<Anchor> route = new ArrayList<>();
        for (Vertex n = end; n != null; n = n.previous) {
            route.add(n.anchor == null ? new Anchor(n.p) : n.anchor);
            if (n.lift != null) route.add(n.lift);
        }
        Collections.reverse(route);
        compactWaterAnchors(route);
        return new SurfacePath(
                mob,
                route,
                BlockPos.containing(target.position()),
                target.getUUID(),
                reached,
                range,
                accuracy,
                target);
    }

    /**
     * Remove only exactly collinear open-water contacts. Their swept collision and fluid spans are
     * the same checked segment; corners, support changes and lift phases remain explicit. Keep each
     * intent within the existing three-block validation horizon.
     */
    static void compactWaterAnchors(ArrayList<Anchor> anchors) {
        int kept = 0;
        for (int i = 0; i < anchors.size(); i++) {
            Anchor next = anchors.get(i);
            if (kept >= 2) {
                Anchor a = anchors.get(kept - 2), b = anchors.get(kept - 1);
                if (plainWater(a) && plainWater(b) && plainWater(next)) {
                    Vec3 ab = b.planned.subtract(a.planned), bc = next.planned.subtract(b.planned);
                    if (ab.dot(bc) >= 0
                            && ab.cross(bc).lengthSqr() < 1e-16
                            && a.planned.distanceToSqr(next.planned) <= 4) kept--;
                }
            }
            anchors.set(kept++, next);
        }
        anchors.subList(kept, anchors.size()).clear();
    }

    static boolean plainWater(Anchor anchor) {
        return anchor != null
                && anchor.water
                && anchor.fixed
                && anchor.owner == null
                && anchor.face == null
                && anchor.liftLanding == null;
    }

    static final class Anchor {
        final SubLevel owner;
        final Vec3 local, normal;
        final double cost;
        final boolean fixed;
        boolean water;
        Anchor liftLanding;
        final Face face;
        Vec3 planned;

        Anchor(Vec3 world) {
            this(world, 0);
        }

        Anchor(Vec3 world, double mediumCost) {
            owner = null;
            local = world;
            normal = Y;
            cost = Math.max(0, mediumCost);
            fixed = true;
            face = null;
            planned = world;
        }

        static Anchor water(Vec3 world, double cost) {
            var a = new Anchor(world, cost);
            a.water = true;
            return a;
        }

        static Anchor lift(Vec3 source, Anchor landing) {
            var a = water(new Vec3(source.x, landing.planned.y + SKIN, source.z), 0);
            a.liftLanding = landing;
            return a;
        }

        Anchor(Frame f, Vec3 world, Vec3 normal, double cost) {
            this(f, world, normal, cost, null);
        }

        Anchor(Frame f, Vec3 world, Vec3 normal, double cost, Face face) {
            this.face = face;
            owner = f.body;
            local = f.local(world);
            this.normal = normal;
            this.cost = cost;
            fixed = false;
        }

        Anchor(Scene scene, Frame frame, Vec3 world, Vec3 normal, double nativeCost, Face face) {
            this.face = face;
            owner = frame.body;
            local = frame.local(world);
            this.normal = normal;
            fixed = false;
            planned = point(scene.mob);
            boolean submerged =
                    (scene.waterExit || scene.groundWater) && scene.waterAtFeet(planned);
            water = scene.waterExit && submerged;
            cost =
                    Math.max(
                            nativeCost,
                            submerged ? scene.mob.getPathfindingMalus(PathType.WATER) : 0);
        }

        Vec3 point(Entity e) {
            if (liftLanding != null)
                return new Vec3(local.x, liftLanding.point(e).y + SKIN, local.z);
            if (fixed) return local;
            if (owner != null && owner.isRemoved()) throw new Exhausted();
            Vec3 contact = owner == null ? local : owner.logicalPose().transformPosition(local);
            Vec3 n =
                    owner == null
                            ? normal
                            : owner.logicalPose().transformNormal(normal).normalize();
            if (n.y <= .6) throw new Exhausted();
            AABB b = e.getBoundingBox();
            double ox = (b.minX + b.maxX) * .5 - e.getX(), oz = (b.minZ + b.maxZ) * .5 - e.getZ();
            double height =
                    (-n.x * ox
                                    - n.z * oz
                                    + Math.abs(n.x) * b.getXsize() * .5
                                    + Math.abs(n.z) * b.getZsize() * .5)
                            / n.y;
            double y = face == null ? contact.y + height : face.height(owner, contact, e);
            if (!Double.isFinite(y)) throw new Exhausted();
            return new Vec3(contact.x, y - (b.minY - e.getY()) + SKIN, contact.z);
        }
    }

    /** A step still supports the body while any part of its footprint overlaps the top face. */
    static final class Face {
        final Vec3[] local;
        final boolean nativeFoot;

        Face(Vec3[] local) {
            this(local, false);
        }

        Face(Vec3[] local, boolean nativeFoot) {
            this.local = local;
            this.nativeFoot = nativeFoot;
        }

        double height(SubLevel owner, Vec3 at, Entity entity) {
            return heightAt(owner == null ? null : owner.logicalPose(), at, entity);
        }

        double heightAt(Pose3d pose, Vec3 at, Entity entity) {
            List<Vec3> polygon = new ArrayList<>(4);
            for (Vec3 p : local) polygon.add(pose == null ? p : pose.transformPosition(p));
            AABB box = entity.getBoundingBox();
            if (nativeFoot && pose != null) {
                Vec3[] footprint = nativeAxes(pose);
                double cx = at.x + box.getCenter().x - entity.getX();
                double cz = at.z + box.getCenter().z - entity.getZ();
                for (int axis : new int[] {0, 2}) {
                    Vec3 direction = footprint[axis];
                    double center = cx * direction.x + cz * direction.z;
                    double half = (axis == 0 ? box.getXsize() : box.getZsize()) * .5;
                    polygon = clipFoot(polygon, direction, center - half, true);
                    polygon = clipFoot(polygon, direction, center + half, false);
                }
                double nativeHeight =
                        polygon.stream().mapToDouble(p -> p.y).max().orElse(Double.NaN);
                return nativeHeight;
            }
            double fast = rectangleHeight(polygon, at, entity, box);
            if (fast != Double.POSITIVE_INFINITY) return fast;
            polygon = clip(polygon, 0, at.x + box.minX - entity.getX(), true);
            polygon = clip(polygon, 0, at.x + box.maxX - entity.getX(), false);
            polygon = clip(polygon, 2, at.z + box.minZ - entity.getZ(), true);
            polygon = clip(polygon, 2, at.z + box.maxZ - entity.getZ(), false);
            return polygon.stream().mapToDouble(p -> p.y).max().orElse(Double.NaN);
        }

        // A collision-box face aligned with world X/Z needs only rectangle overlap.
        // Positive infinity asks for clipping; grazing contacts keep its exact behavior.
        static double rectangleHeight(List<Vec3> points, Vec3 at, Entity entity, AABB box) {
            if (points.size() != 4) return Double.POSITIVE_INFINITY;
            Vec3 a = points.get(0), b = points.get(1), c = points.get(2), d = points.get(3);
            if (a.y != b.y
                    || a.y != c.y
                    || a.y != d.y
                    || !finite(a)
                    || !finite(b)
                    || !finite(c)
                    || !finite(d)) return Double.POSITIVE_INFINITY;
            boolean aligned =
                    (a.x == b.x && b.z == c.z && c.x == d.x && d.z == a.z)
                            || (a.z == b.z && b.x == c.x && c.z == d.z && d.x == a.x);
            if (!aligned) return Double.POSITIVE_INFINITY;
            double margin =
                    Math.min(
                            Math.min(
                                    Math.max(a.x, c.x) - (at.x + box.minX - entity.getX()),
                                    (at.x + box.maxX - entity.getX()) - Math.min(a.x, c.x)),
                            Math.min(
                                    Math.max(a.z, c.z) - (at.z + box.minZ - entity.getZ()),
                                    (at.z + box.maxZ - entity.getZ()) - Math.min(a.z, c.z)));
            if (margin > EPS) return a.y;
            if (margin < -EPS) return Double.NaN;
            return Double.POSITIVE_INFINITY;
        }

        static List<Vec3> clip(List<Vec3> input, int axis, double boundary, boolean minimum) {
            if (input.isEmpty()) return input;
            List<Vec3> out = new ArrayList<>(input.size() + 1);
            Vec3 previous = input.getLast();
            double before = coordinate(previous, axis) - boundary;
            boolean insideBefore = minimum ? before >= 0 : before <= 0;
            for (Vec3 current : input) {
                double after = coordinate(current, axis) - boundary;
                boolean insideAfter = minimum ? after >= 0 : after <= 0;
                if (insideAfter != insideBefore) {
                    double t = before / (before - after);
                    out.add(previous.add(current.subtract(previous).scale(t)));
                }
                if (insideAfter) out.add(current);
                previous = current;
                before = after;
                insideBefore = insideAfter;
            }
            return out;
        }

        static List<Vec3> clipFoot(List<Vec3> polygon, Vec3 axis, double limit, boolean minimum) {
            if (polygon.isEmpty()) return polygon;
            var result = new ArrayList<Vec3>(polygon.size() + 1);
            Vec3 previous = polygon.getLast();
            double before = previous.dot(axis) - limit;
            boolean insideBefore = minimum ? before >= 0 : before <= 0;
            for (Vec3 point : polygon) {
                double after = point.dot(axis) - limit;
                boolean insideAfter = minimum ? after >= 0 : after <= 0;
                if (insideBefore != insideAfter)
                    result.add(
                            previous.add(
                                    point.subtract(previous).scale(before / (before - after))));
                if (insideAfter) result.add(point);
                previous = point;
                before = after;
                insideBefore = insideAfter;
            }
            return result;
        }

        static double coordinate(Vec3 p, int axis) {
            return axis == 0 ? p.x : p.z;
        }
    }

    static final class SurfacePath extends Path {
        List<Anchor> anchors;
        List<SubLevel> bodies;
        final List<Node> nodeStorage;
        BlockPos destination;
        boolean reached;
        // A finished approach can be useful even when the exact goal is not reachable.
        boolean searchFinished;
        boolean refined;
        boolean waterExit;
        final Mob mob;
        final ServerLevel level;
        final UUID targetId;
        final java.lang.ref.WeakReference<Entity> goal;
        SubLevel requestedGoalOwner;
        Vec3 requestedGoalPoint;
        final float range;
        final int accuracy;
        boolean retired;
        Search request;
        Set<BlockPos> nativeTargets;
        long extendedTick = Long.MIN_VALUE;
        long requestTick;
        long checkedTick = Long.MIN_VALUE;
        int checkedIndex = -1, checkedLastIndex = -1;
        int liftingIndex = -1;
        long liftingTick;

        SurfacePath(Mob mob, List<Anchor> a, BlockPos target, UUID targetId, boolean reached) {
            this(mob, a, target, targetId, reached, 32, 0, null);
        }

        SurfacePath(
                Mob mob,
                List<Anchor> a,
                BlockPos target,
                UUID targetId,
                boolean reached,
                float range,
                int accuracy,
                Entity goal) {
            this(mob, a, target, targetId, reached, range, accuracy, goal, nodes(a));
        }

        private SurfacePath(
                Mob mob,
                List<Anchor> a,
                BlockPos target,
                UUID targetId,
                boolean reached,
                float range,
                int accuracy,
                Entity goal,
                List<Node> storage) {
            super(storage, target, reached);
            nodeStorage = storage;
            destination = target;
            this.reached = reached;
            searchFinished = reached;
            this.goal = new java.lang.ref.WeakReference<>(goal);
            requestedGoalOwner = goal == null ? null : Sable.HELPER.getTrackingSubLevel(goal);
            var goalPoint = goal == null ? Vec3.atCenterOf(target) : goal.position();
            requestedGoalPoint =
                    requestedGoalOwner == null
                            ? goalPoint
                            : requestedGoalOwner.logicalPose().transformPositionInverse(goalPoint);
            this.range = range;
            this.accuracy = accuracy;
            this.mob = mob;
            this.targetId = targetId;
            level = (ServerLevel) mob.level();
            requestTick = level.getGameTime();
            anchors = List.copyOf(a);
            waterExit = a.stream().anyMatch(anchor -> anchor.water);
            if (waterExit && anchors.size() > 1) setNextNodeIndex(1);
            // Search has checked every edge in this exact server tick already.
            checkedTick = level.getGameTime();
            checkedIndex = 0;
            checkedLastIndex = anchors.size() - 1;
            bodies =
                    java.util.stream.Stream.concat(
                                    anchors.stream().map(a1 -> a1.owner),
                                    java.util.stream.Stream.of(
                                            goal == null
                                                    ? null
                                                    : Sable.HELPER.getTrackingSubLevel(goal)))
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList();
        }

        void accept(SurfacePath result) {
            // Keep the same Path identity: native or modded goals may retain the result they
            // submitted. Completing a search must update that accepted owner, not strand it.
            nodeStorage.clear();
            nodeStorage.addAll(result.nodeStorage);
            anchors = result.anchors;
            requestedGoalOwner = result.requestedGoalOwner;
            requestedGoalPoint = result.requestedGoalPoint;
            waterExit = result.waterExit;
            refined = result.refined;
            bodies = result.bodies;
            destination = result.destination;
            reached = result.reached;
            searchFinished = result.searchFinished;
            checkedTick = result.checkedTick;
            checkedIndex = result.checkedIndex;
            checkedLastIndex = result.checkedLastIndex;
            request = null;
            setNextNodeIndex(result.getNextNodeIndex());
        }

        void completePartial(Entity target) {
            var result = request.partial(mob, target);
            if (result == null) retire();
            else accept((SurfacePath) result);
        }

        boolean matchesGoal(Entity target) {
            if (requestedGoalOwner != Sable.HELPER.getTrackingSubLevel(target)
                    || requestedGoalOwner != null && requestedGoalOwner.isRemoved()) return false;
            var expected =
                    requestedGoalOwner == null
                            ? requestedGoalPoint
                            : requestedGoalOwner
                                    .logicalPose()
                                    .transformPosition(requestedGoalPoint);
            double tolerance = accuracy + 1.5;
            return finite(expected)
                    && expected.distanceToSqr(target.position()) < tolerance * tolerance;
        }

        void retire() {
            if (mob instanceof NativeLandNavigation capability
                    && capability.npships$waterRequest() == this)
                capability.npships$waterRequest(null);
            if (mob instanceof NativeLandNavigation capability
                    && capability.npships$planningRequest() == this)
                capability.npships$planningRequest(null);
            retired = true;
            request = null;
        }

        @Override
        public Path copy() {
            return copyForRequest(range, accuracy);
        }

        SurfacePath copyForRequest(float nextRange, int nextAccuracy) {
            var result =
                    new SurfacePath(
                            mob,
                            anchors,
                            destination,
                            targetId,
                            reached,
                            nextRange,
                            nextAccuracy,
                            goal.get());
            result.nodeStorage.clear();
            for (var node : nodeStorage)
                result.nodeStorage.add(node.cloneAndMove(node.x, node.y, node.z));
            result.bodies = bodies;
            result.requestedGoalOwner = requestedGoalOwner;
            result.requestedGoalPoint = requestedGoalPoint;
            result.setNextNodeIndex(getNextNodeIndex());
            result.checkedTick = checkedTick;
            result.checkedIndex = checkedIndex;
            result.checkedLastIndex = checkedLastIndex;
            result.extendedTick = extendedTick;
            result.requestTick = requestTick;
            result.retired = retired;
            result.searchFinished = searchFinished;
            result.waterExit = waterExit;
            result.refined = refined;
            result.nativeTargets = nativeTargets;
            result.liftingIndex = liftingIndex;
            result.liftingTick = liftingTick;
            result.request = request == null ? null : request.copy();
            return result;
        }

        @Override
        public boolean canReach() {
            return reached;
        }

        @Override
        public BlockPos getTarget() {
            return destination;
        }

        @Override
        public float getDistToTarget() {
            return nodeStorage.isEmpty()
                    ? Float.MAX_VALUE
                    : getEndNode().distanceManhattan(destination);
        }

        static List<Node> nodes(List<Anchor> a) {
            ArrayList<Node> n = new ArrayList<>();
            for (Anchor p : a) {
                Vec3 v = p.planned;
                Node node =
                        new Node(
                                (int) Math.floor(v.x),
                                (int) Math.floor(v.y),
                                (int) Math.floor(v.z));
                node.type = p.water ? PathType.WATER : PathType.WALKABLE;
                n.add(node);
            }
            return n;
        }

        @Override
        public void advance() {
            // Only a pending search holds its last node. A finished partial route must end
            // normally without claiming that the actual target was reached.
            if (!searchFinished && getNextNodeIndex() + 1 >= getNodeCount()) return;
            super.advance();
        }

        @Override
        public boolean sameAs(Path other) {
            // Rounded Node coordinates cannot establish equality of precise local anchors.
            return this == other;
        }

        @Override
        public Vec3 getEntityPosAtNode(Entity e, int i) {
            try {
                var anchor = anchors.get(i);
                var point = anchor.point(e);
                // Only the current execution phase is vertical at the actor's validated position.
                // Future contacts keep their planned coordinates for lookahead and collision tests.
                return i == getNextNodeIndex() && anchor.liftLanding != null
                        ? new Vec3(mob.getX(), point.y, mob.getZ())
                        : point;
            } catch (Exhausted refused) {
                retire();
                return e.position();
            }
        }

        @Override
        public BlockPos getNodePos(int i) {
            return BlockPos.containing(getEntityPosAtNode(mob, i));
        }

        @Override
        public BlockPos getNextNodePos() {
            return getNodePos(getNextNodeIndex());
        }

        boolean ready() {
            if (isDone() || request != null) return false;
            int index = getNextNodeIndex();
            return checkedTick == level.getGameTime()
                    && index >= checkedIndex
                    && index <= checkedLastIndex;
        }

        @Override
        public boolean isDone() {
            if (retired || super.isDone()) return true;
            if (!NPShipsRuntimeConfig.mobCrossStructureFixEnabled()
                    || !mob.isAlive()
                    || !nativeTraversal(mob)
                    || mob.level() != level
                    || !level.getServer().isSameThread()
                    || bodies.stream().anyMatch(SubLevel::isRemoved)) {
                retire();
                return true;
            }
            if (request != null) return false;
            int current = getNextNodeIndex();
            if (anchors.get(current).liftLanding != null) {
                if (liftingIndex != current) {
                    liftingIndex = current;
                    liftingTick = level.getGameTime();
                } else if (level.getGameTime() - liftingTick > 40) {
                    retire();
                    return true;
                }
            } else liftingIndex = -1;
            long tick = level.getGameTime();
            int index = getNextNodeIndex();
            if (tick != checkedTick || index < checkedIndex || index > checkedLastIndex) {
                Budget budget = new Budget(level);
                Vec3 descentPoint = null;
                try {
                    budget.check();
                    int last = Math.min(index + 1, anchors.size() - 1);
                    Vec3 next = getEntityPosAtNode(mob, index), end = getEntityPosAtNode(mob, last);
                    if (!finite(next) || !finite(end) || next.distanceToSqr(mob.position()) > 9)
                        throw new Exhausted();
                    descentPoint = next;
                    int ahead = index;
                    if (waterExit && plainWater(anchors.get(index))) {
                        for (int n = index + 1; n < Math.min(index + 9, anchors.size()); n++) {
                            var anchor = anchors.get(n);
                            if (!plainWater(anchor)
                                    || Math.abs(anchor.planned.y - next.y) > SKIN
                                    || anchor.planned.distanceToSqr(mob.position()) > 4) break;
                            ahead = n;
                        }
                    }
                    var far = getEntityPosAtNode(mob, Math.min(ahead + 1, anchors.size() - 1));
                    var bounds =
                            mob.getBoundingBox()
                                    .minmax(
                                            mob.getBoundingBox()
                                                    .move(next.subtract(mob.position())))
                                    .minmax(mob.getBoundingBox().move(end.subtract(mob.position())))
                                    .minmax(mob.getBoundingBox().move(far.subtract(mob.position())))
                                    .inflate(2);
                    var scene = new Scene(level, mob, budget, bounds, waterExit);
                    Vec3 start = mob.position().add(0, SKIN, 0);
                    boolean shortcut = false;
                    // One bounded chord test per tick, never through a lift/support change.
                    if (ahead > index) {
                        var point = anchors.get(ahead).point(mob);
                        if (scene.waterSupport(point) && scene.edge(start, point)) {
                            index = ahead;
                            last = Math.min(index + 1, anchors.size() - 1);
                            shortcut = true;
                        }
                    }
                    if (mob.onGround() && !scene.clear(start, start)) {
                        for (Anchor floor : scene.floors(start.x, start.z, start.y)) {
                            if (Math.abs(floor.planned.y - start.y) <= .03
                                    && scene.clear(floor.planned, floor.planned)) {
                                start = floor.planned;
                                break;
                            }
                        }
                    }
                    for (int n = index; n <= last; n++) {
                        Vec3 point = getEntityPosAtNode(mob, n);
                        boolean support =
                                (anchors.get(n).water
                                                ? scene.waterSupport(point)
                                                : anchors.get(n).fixed)
                                        || !anchors.get(n).water
                                                && scene.floors(point.x, point.z, point.y).stream()
                                                        .anyMatch(
                                                                a ->
                                                                        !a.water
                                                                                && a.planned
                                                                                                .distanceToSqr(
                                                                                                        point)
                                                                                        < .025);
                        if (!support || !(shortcut && n == index) && !scene.edge(start, point))
                            throw new Exhausted();
                        start = point;
                    }
                    // Publish a shortcut only after its complete next phase has been checked.
                    if (shortcut) setNextNodeIndex(index);
                    checkedTick = tick;
                    checkedIndex = index;
                    checkedLastIndex = last;
                } catch (AllowanceExhausted defer) {
                    // Another search may have consumed this tick. Retain the route and wait.
                } catch (Exhausted invalidGeometry) {
                    // Native falling can temporarily cross the edge's swept clearance. Preserve
                    // only its recent checked contact phase until landing or its four-tick bound.
                    boolean recentDescent =
                            refined
                                    && !waterExit
                                    && !mob.onGround()
                                    && mob.getDeltaMovement().y < 0
                                    && descentPoint != null
                                    && checkedTick != Long.MIN_VALUE
                                    && tick >= checkedTick
                                    && tick - checkedTick <= 4
                                    && index >= checkedIndex
                                    && index <= checkedLastIndex
                                    && descentPoint.y < mob.getY()
                                    && descentPoint.distanceToSqr(mob.position()) < 1;
                    if (!recentDescent) retire();
                } finally {
                    budget.finish();
                }
            }
            return retired;
        }
    }

    static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /** Conservative land-jump rise from native power/gravity; no jump event or AI is run. */
    static double jumpRise(Mob mob) {
        return jumpRise(mob, false);
    }

    static double jumpRise(Mob mob, boolean waterExit) {
        double step = Math.max(0, mob.maxUpStep()) + .03;
        if (!(mob instanceof NPShipsMobJumpAccessor nativeJump)
                || mob.isNoGravity()
                || mob.isInWaterOrBubble() && !waterExit
                || mob.isInLava()) return step;
        double velocity = nativeJump.npships$jumpPower(), gravity = mob.getGravity();
        if (!Double.isFinite(velocity)
                || !Double.isFinite(gravity)
                || velocity <= 0
                || gravity <= 0) return step;
        double height = 0;
        // Native land travel applies gravity and 0.98 vertical drag after each movement.
        // The fixed horizon underestimates unusually high jumps instead of expanding work.
        for (int tick = 0; tick < 32 && velocity > 0; tick++) {
            height += velocity;
            velocity = (velocity - gravity) * .98;
        }
        return Math.max(step, height - .01);
    }

    // Ask the installed collision owner for its actor yaw; do not copy its grid-snapping rule.
    static Vec3[] nativeAxes(Pose3d pose) {
        double yaw =
                dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.getHitBoxYaw(
                        pose);
        if (!Double.isFinite(yaw)) throw new Exhausted();
        double c = Math.cos(yaw), s = Math.sin(yaw);
        return new Vec3[] {new Vec3(c, 0, -s), Y, new Vec3(s, 0, c)};
    }

    record FloorKey(long x, long z, int y) {}

    static final class Scene {
        final ServerLevel level;
        final Mob mob;
        final Budget budget;
        final List<Frame> frames = new ArrayList<>();
        final AABB startBox;
        final Vec3 centerOffset;
        final double hx, hy, hz, maxRise;
        final Map<FloorKey, List<Anchor>> floorCache = new HashMap<>();
        final boolean waterExit, groundWater, waterOnly;
        final it.unimi.dsi.fastutil.longs.Long2ObjectMap<FluidState> waterCache;
        double actorPadding;

        Scene(ServerLevel l, Mob m, Budget b, AABB bounds) {
            this(l, m, b, bounds, false);
        }

        Scene(ServerLevel l, Mob m, Budget b, AABB bounds, boolean waterExit) {
            this.waterExit = waterExit;
            // A native swimmer needs no prior land request for a fully submerged crossing.
            // Without that evidence it receives water contacts only, never a shore/lift path.
            waterOnly =
                    m.getNavigation() instanceof WaterBoundPathNavigation
                            && !(m instanceof NativeLandNavigation capability
                                    && capability.npships$hasLandNavigation());
            // Native land evaluators may enter water and walk on its solid bottom. This is
            // permeability only: floating support and lift phases still require waterExit.
            groundWater =
                    m.getNavigation() instanceof GroundPathNavigation
                            && m.getNavigation().getNodeEvaluator().getClass()
                                    == WalkNodeEvaluator.class
                            && m.getPathfindingMalus(PathType.WATER) >= 0;
            waterCache =
                    waterExit || groundWater
                            ? new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>()
                            : it.unimi.dsi.fastutil.longs.Long2ObjectMaps.emptyMap();
            level = l;
            mob = m;
            budget = b;
            maxRise = jumpRise(m, waterExit);
            startBox = m.getBoundingBox();
            hx = startBox.getXsize() / 2;
            hy = startBox.getYsize() / 2;
            hz = startBox.getZsize() / 2;
            centerOffset = startBox.getCenter().subtract(m.position());
            frames.add(new Frame(this, null));
            for (var owner :
                    Sable.HELPER.getAllIntersecting(
                            l,
                            new BoundingBox3d(
                                    bounds.minX,
                                    bounds.minY,
                                    bounds.minZ,
                                    bounds.maxX,
                                    bounds.maxY,
                                    bounds.maxZ))) {
                if (frames.size() == 17 || owner.isRemoved()) throw new Exhausted();
                frames.add(new Frame(this, owner));
            }
        }

        List<Solid> solids(AABB area) {
            ArrayList<Solid> all = new ArrayList<>();
            scanSolids(area, all, null, null);
            return all;
        }

        /**
         * One admission loop serves both queries. A non-null collector requests every solid;
         * otherwise the sweep may stop only at a proven collision. An accepted clear sweep still
         * examines the entire area, including native shape, missing-chunk and budget refusals.
         */
        private boolean scanSolids(AABB area, List<Solid> collected, Vec3 first, Vec3 velocity) {
            area = area.inflate(actorPadding, 0, actorPadding);
            AABB broad = area.inflate(EPS);
            for (Frame f : frames) {
                budget.check();
                AABB a = f.inverseBounds(area).inflate(1);
                int minX = (int) Math.floor(a.minX),
                        maxX = (int) Math.floor(a.maxX),
                        minY = (int) Math.floor(a.minY),
                        maxY = (int) Math.floor(a.maxY),
                        minZ = (int) Math.floor(a.minZ),
                        maxZ = (int) Math.floor(a.maxZ);
                if (f.body != null) {
                    var owned = f.body.getPlot().getBoundingBox();
                    minX = Math.max(minX, owned.minX());
                    maxX = Math.min(maxX, owned.maxX());
                    minY = Math.max(minY, owned.minY());
                    maxY = Math.min(maxY, owned.maxY());
                    minZ = Math.max(minZ, owned.minZ());
                    maxZ = Math.min(maxZ, owned.maxZ());
                    if (minX > maxX || minY > maxY || minZ > maxZ) continue;
                }
                if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 4096)
                    throw new Exhausted();
                var cursor = new BlockPos.MutableBlockPos();
                for (int x = minX; x <= maxX; x++)
                    for (int y = minY; y <= maxY; y++)
                        for (int z = minZ; z <= maxZ; z++)
                            // This loop is already clipped to the physical plot above.
                            for (Solid solid : f.readSolids(cursor.set(x, y, z)))
                                if (solid.bounds.intersects(broad)) {
                                    if (collected != null) collected.add(solid);
                                    else if (solid.intersects(first, velocity)) return false;
                                }
            }
            return true;
        }

        List<Anchor> floors(double x, double z, double from) {
            var key =
                    new FloorKey(
                            Double.doubleToLongBits(x),
                            Double.doubleToLongBits(z),
                            (int) Math.floor(from));
            var solidFloors = floorCache.computeIfAbsent(key, k -> readFloors(x, z, k.y));
            if (waterOnly)
                solidFloors = solidFloors.stream().filter(anchor -> anchor.water).toList();
            if (!waterExit) return solidFloors;
            var out = new ArrayList<>(solidFloors);
            double surface = waterSurface(x, z, from);
            double height = Math.min(surface - mob.getFluidJumpThreshold() - .1, from + .5);
            if (Double.isFinite(height) && height >= from - mob.getMaxFallDistance() - 1) {
                var up = new Vec3(x, height, z);
                if (waterSupport(up))
                    out.add(Anchor.water(up, mob.getPathfindingMalus(PathType.WATER)));
            }
            // Swimming can continue underneath an overhang before rising in a clear column.
            // The precise depth belongs to the edge, not the integer-height solid-floor cache.
            if (Double.isFinite(surface)
                    && from < surface - SKIN
                    && Math.abs(height - from) > SKIN) {
                var level = new Vec3(x, from, z);
                if (waterSupport(level))
                    out.add(Anchor.water(level, mob.getPathfindingMalus(PathType.WATER)));
            }
            out.sort(Comparator.comparingDouble(anchor -> Math.abs(anchor.planned.y - from)));
            return out;
        }

        List<Anchor> readFloors(double x, double z, double from) {
            return readFloors(
                    x, z, from, mob.getMaxFallDistance() + 1.0, Math.max(1, mob.maxUpStep()) + 2);
        }

        List<Anchor> readFloors(double x, double z, double from, double below, double above) {
            var out = new ArrayList<Anchor>();
            AABB area =
                    new AABB(
                            x - hx - .01,
                            from - below,
                            z - hz - .01,
                            x + hx + .01,
                            from + above,
                            z + hz + .01);
            for (Solid s : solids(area))
                for (StandFace top : s.topFaces()) {
                    var n = top.normal;
                    var c = top.center;
                    double y = c.y - (n.x * (x - c.x) + n.z * (z - c.z)) / n.y;
                    Vec3 on = new Vec3(x, y, z);
                    if (!Double.isFinite(top.face.height(s.frame.body, on, mob))) continue;
                    Anchor anchor =
                            new Anchor(this, s.frame, on, top.localNormal, s.cost, top.face);
                    Vec3 feet = anchor.planned;
                    if (feet.y < area.minY || feet.y > area.maxY) continue;
                    boolean duplicate =
                            out.stream().anyMatch(old -> old.planned.distanceToSqr(feet) < 1e-8);
                    if (!duplicate) {
                        out.add(anchor);
                    }
                }
            out.sort(Comparator.comparingDouble(a -> Math.abs(a.planned.y - from)));
            return out;
        }

        boolean edge(Vec3 a, Vec3 b) {
            if (b.y - a.y < -mob.getMaxFallDistance() - .03) return false;
            if (b.y - a.y > maxRise + SKIN && !(waterExit && waterSupport(a) && waterSupport(b)))
                return false;
            if (!clear(b, b)) return false;
            if (!supportedEdge(a, b)) return false;
            if (clear(a, b)) return true;
            if (Math.abs(a.y - b.y) < 1e-6) return false;
            double y = Math.max(a.y, b.y) + SKIN;
            Vec3 highA = new Vec3(a.x, y, a.z), highB = new Vec3(b.x, y, b.z);
            return clear(a, highA) && clear(highA, highB) && clear(highB, b);
        }

        /** Collision-free space alone does not provide a ground route across a gap. */
        boolean supportedEdge(Vec3 a, Vec3 b) {

            double footOffset = startBox.minY - mob.getY();
            double lowest = Math.min(a.y, b.y) + footOffset - mob.getMaxFallDistance() - .03;
            double highest = Math.max(a.y, b.y) + footOffset + .03;
            double ax = a.x + centerOffset.x, az = a.z + centerOffset.z;
            double bx = b.x + centerOffset.x, bz = b.z + centerOffset.z;
            AABB area =
                    new AABB(
                                    Math.min(ax, bx) - hx,
                                    lowest - 1,
                                    Math.min(az, bz) - hz,
                                    Math.max(ax, bx) + hx,
                                    highest,
                                    Math.max(az, bz) + hz)
                            .inflate(EPS);
            List<double[]> spans = new ArrayList<>();
            double tolerance = SKIN / Math.max(SKIN, Math.hypot(b.x - a.x, b.z - a.z));
            if (waterExit && mob.getPathfindingMalus(PathType.WATER) >= 0) {
                waterSpans(a, b, spans);
                // A completely supported water segment needs no additional solid support.
                // Collision is still checked independently against the entire actor sweep.
                if (covers(spans, tolerance)) return true;
            }
            for (Solid solid : solids(area)) {
                for (StandFace top : solid.topFaces()) {
                    Vec3[] actor = solid.frame.actorAxes;
                    int d = top.axis;
                    Vec3 normal = top.normal;
                    Vec3 face = top.center;
                    double y0 =
                            face.y
                                    - (normal.x * (ax - face.x) + normal.z * (az - face.z))
                                            / normal.y;
                    double y1 =
                            face.y
                                    - (normal.x * (bx - face.x) + normal.z * (bz - face.z))
                                            / normal.y;
                    Vec3 first = new Vec3(ax, y0, az).subtract(solid.center);
                    Vec3 delta = new Vec3(bx - ax, y1 - y0, bz - az);
                    double heightRadius =
                            (Math.abs(normal.dot(actor[0])) * hx
                                            + Math.abs(normal.dot(actor[2])) * hz)
                                    / normal.y;
                    double[] span = {0, 1};
                    boolean admitted =
                            clipInterval(
                                    span,
                                    y0,
                                    y1 - y0,
                                    lowest - heightRadius,
                                    highest + heightRadius);
                    double rx = 0, rz = 0;
                    for (int axis = 0; axis < 3 && admitted; axis++)
                        if (axis != d) {
                            Vec3 direction = solid.frame.axes[axis];
                            double px = direction.x - direction.y * normal.x / normal.y;
                            double pz = direction.z - direction.y * normal.z / normal.y;
                            double radius =
                                    Math.abs(px * actor[0].x + pz * actor[0].z) * hx
                                            + Math.abs(px * actor[2].x + pz * actor[2].z) * hz;
                            admitted =
                                    clipInterval(
                                            span,
                                            first.dot(direction),
                                            delta.dot(direction),
                                            -solid.half[axis] - radius,
                                            solid.half[axis] + radius);
                            rx += Math.abs(direction.dot(actor[0])) * solid.half[axis];
                            rz += Math.abs(direction.dot(actor[2])) * solid.half[axis];
                        }
                    // The face-edge normals plus X/Z give the exact horizontal Minkowski
                    // footprint, including its corners rather than an expanded miter box.
                    Vec3 u = actor[0], v = actor[2];
                    admitted =
                            admitted
                                    && clipInterval(
                                            span,
                                            ax * u.x + az * u.z,
                                            (bx - ax) * u.x + (bz - az) * u.z,
                                            face.dot(u) - rx - hx,
                                            face.dot(u) + rx + hx)
                                    && clipInterval(
                                            span,
                                            ax * v.x + az * v.z,
                                            (bx - ax) * v.x + (bz - az) * v.z,
                                            face.dot(v) - rz - hz,
                                            face.dot(v) + rz + hz);
                    if (admitted) spans.add(span);
                }
            }
            return covers(spans, tolerance);
        }

        private static boolean covers(List<double[]> spans, double tolerance) {
            spans.sort(Comparator.comparingDouble(v -> v[0]));
            double covered = 0;
            for (double[] span : spans) {
                if (span[0] > covered + tolerance) return false;
                covered = Math.max(covered, span[1]);
                if (covered >= 1 - tolerance) return true;
            }
            return false;
        }

        // World fluid reads distinguish walkable bottom water from explicit swimming support.
        // Reads use the loaded ordinary-world frame and this Scene's existing shared budget.
        FluidState water(BlockPos p) {
            long key = p.asLong();
            var known = waterCache.get(key);
            if (known != null) return known;
            budget.cell();
            var fluid = frames.getFirst().getFluidState(p);
            var result =
                    fluid.is(net.minecraft.tags.FluidTags.WATER)
                            ? fluid
                            : net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
            waterCache.put(key, result);
            return result;
        }

        double waterSurface(double x, double z, double from) {
            if (!waterExit) return Double.NaN;
            var p = BlockPos.containing(x, from, z).mutable();
            // A dry deck can stand above the water surface. Respect the actor's native fall
            // bound while looking down; deep-water swimming keeps its original local horizon.
            int below =
                    mob.isInWaterOrBubble() ? 1 : Math.clamp(mob.getMaxFallDistance() + 1, 1, 6);
            p.move(0, -below, 0);
            for (int i = 0; i < below + 6; i++, p.move(0, 1, 0)) {
                var fluid = water(p);
                if (fluid.isEmpty()) continue;
                if (water(p.above()).isEmpty()) return p.getY() + fluid.getOwnHeight();
            }
            return Double.NaN;
        }

        boolean waterAtFeet(Vec3 point) {
            var feet = point.add(centerOffset.x, startBox.minY - mob.getY(), centerOffset.z);
            var cell = BlockPos.containing(feet);
            var fluid = water(cell);
            if (fluid.isEmpty() || mob.getPathfindingMalus(PathType.WATER) < 0) return false;
            try {
                return feet.y < cell.getY() + fluid.getHeight(frames.getFirst(), cell) - SKIN;
            } catch (Exhausted refused) {
                throw refused;
            } catch (RuntimeException unsupported) {
                throw new Exhausted("Native fluid requires an unsupported query owner");
            }
        }

        boolean waterSupport(Vec3 point) {
            double surface = waterSurface(point.x, point.z, point.y);
            return mob.getPathfindingMalus(PathType.WATER) >= 0
                    && Double.isFinite(surface)
                    && (!waterOnly || waterAtFeet(point))
                    && point.y <= surface + Math.max(.03, mob.maxUpStep());
        }

        void waterSpans(Vec3 a, Vec3 b, List<double[]> spans) {
            int minX = (int) Math.floor(Math.min(a.x, b.x) + centerOffset.x - hx);
            int maxX = (int) Math.floor(Math.max(a.x, b.x) + centerOffset.x + hx);
            int minZ = (int) Math.floor(Math.min(a.z, b.z) + centerOffset.z - hz);
            int maxZ = (int) Math.floor(Math.max(a.z, b.z) + centerOffset.z + hz);
            if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > 256) throw new Exhausted();
            for (int x = minX; x <= maxX; x++)
                for (int z = minZ; z <= maxZ; z++) {
                    double surface = waterSurface(x + .5, z + .5, Math.min(a.y, b.y));
                    if (!Double.isFinite(surface)) continue;
                    double[] span = {0, 1};
                    if (clipInterval(span, a.x + centerOffset.x, b.x - a.x, x - hx, x + 1 + hx)
                            && clipInterval(
                                    span, a.z + centerOffset.z, b.z - a.z, z - hz, z + 1 + hz)
                            && clipInterval(
                                    span,
                                    a.y,
                                    b.y - a.y,
                                    surface - 6,
                                    surface + Math.max(mob.maxUpStep(), mob.getMaxFallDistance())))
                        spans.add(span);
                }
        }

        static boolean clipInterval(
                double[] span, double start, double delta, double low, double high) {
            if (Math.abs(delta) < 1e-12) return start >= low - EPS && start <= high + EPS;
            double first = (low - start) / delta, last = (high - start) / delta;
            if (first > last) {
                double swap = first;
                first = last;
                last = swap;
            }
            span[0] = Math.max(span[0], first);
            span[1] = Math.min(span[1], last);
            return span[0] <= span[1] + EPS;
        }

        boolean clear(Vec3 a, Vec3 b) {
            AABB area =
                    startBox.move(a.subtract(mob.position()))
                            .minmax(startBox.move(b.subtract(mob.position())));
            Vec3 first = a.add(centerOffset), velocity = b.subtract(a);
            return scanSolids(area, null, first, velocity);
        }
    }

    static final class Frame implements BlockGetter {
        final Scene scene;
        final SubLevel body;
        final Pose3d pose;
        final Vec3[] axes = new Vec3[3], tests, actorAxes;
        final double[] scale = new double[3];
        final it.unimi.dsi.fastutil.longs.Long2ObjectMap<List<Solid>> cache =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        Map<BlockPos, List<Solid>> wideCache;
        net.minecraft.world.level.chunk.LevelChunk lastChunk;
        int lastChunkX, lastChunkZ;

        // Native packed coordinates avoid BlockPos hash collisions in distant physical plots.
        // Keep exact keys outside that format instead of aliasing unusual dimension coordinates.
        private static boolean packable(BlockPos point, long key) {
            return BlockPos.getX(key) == point.getX()
                    && BlockPos.getY(key) == point.getY()
                    && BlockPos.getZ(key) == point.getZ();
        }

        List<Solid> cached(BlockPos point) {
            long key = point.asLong();
            return packable(point, key)
                    ? cache.get(key)
                    : wideCache == null ? null : wideCache.get(point);
        }

        void remember(BlockPos point, List<Solid> result) {
            long key = point.asLong();
            if (packable(point, key)) cache.put(key, result);
            else {
                if (wideCache == null) wideCache = new HashMap<>();
                wideCache.put(point.immutable(), result);
            }
        }

        Frame(Scene s, SubLevel b) {
            scene = s;
            body = b;
            pose = b == null ? null : new Pose3d(b.logicalPose());
            Vec3 reference =
                    b == null ? Vec3.ZERO : Vec3.atLowerCornerOf(b.getPlot().getCenterBlock());
            Vec3 origin = world(reference);
            Vec3[] unit = {X, Y, Z};
            // Physical collision uses the native oriented actor in both land and water routes.
            actorAxes = pose != null ? nativeAxes(pose) : unit;
            double actorX = Math.abs(actorAxes[0].x) * s.hx + Math.abs(actorAxes[2].x) * s.hz;
            double actorZ = Math.abs(actorAxes[0].z) * s.hx + Math.abs(actorAxes[2].z) * s.hz;
            s.actorPadding = Math.max(s.actorPadding, Math.max(actorX - s.hx, actorZ - s.hz));
            for (int i = 0; i < 3; i++) {
                Vec3 axis = world(reference.add(unit[i])).subtract(origin);
                scale[i] = axis.length();
                if (!Double.isFinite(scale[i]) || scale[i] < 1e-6 || scale[i] > 16)
                    throw new Exhausted();
                axes[i] = axis.scale(1 / scale[i]);
            }
            if (Math.abs(axes[0].dot(axes[1])) > 1e-6
                    || Math.abs(axes[1].dot(axes[2])) > 1e-6
                    || Math.abs(axes[0].dot(axes[2])) > 1e-6) throw new Exhausted();
            ArrayList<Vec3> a =
                    new ArrayList<>(
                            List.of(
                                    actorAxes[0],
                                    actorAxes[1],
                                    actorAxes[2],
                                    axes[0],
                                    axes[1],
                                    axes[2]));
            for (Vec3 w : actorAxes)
                for (Vec3 axis : axes) {
                    Vec3 cross = w.cross(axis);
                    if (cross.lengthSqr() > 1e-16) a.add(cross.normalize());
                }
            ArrayList<Vec3> unique = new ArrayList<>();
            for (Vec3 axis : a)
                if (unique.stream().noneMatch(existing -> existing.cross(axis).lengthSqr() < 1e-24))
                    unique.add(axis);
            tests = unique.toArray(Vec3[]::new);
        }

        Vec3 world(Vec3 p) {
            return pose == null ? p : pose.transformPosition(p);
        }

        Vec3 local(Vec3 p) {
            return pose == null ? p : pose.transformPositionInverse(p);
        }

        AABB inverseBounds(AABB a) {
            if (pose == null) return a;
            double minX = Double.POSITIVE_INFINITY,
                    minY = minX,
                    minZ = minX,
                    maxX = Double.NEGATIVE_INFINITY,
                    maxY = maxX,
                    maxZ = maxX;
            for (int n = 0; n < 8; n++) {
                Vec3 p =
                        local(
                                new Vec3(
                                        (n & 1) == 0 ? a.minX : a.maxX,
                                        (n & 2) == 0 ? a.minY : a.maxY,
                                        (n & 4) == 0 ? a.minZ : a.maxZ));
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
                minZ = Math.min(minZ, p.z);
                maxZ = Math.max(maxZ, p.z);
            }
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        net.minecraft.world.level.chunk.LevelChunk chunk(BlockPos p) {
            if (body != null
                    && !body.getPlot().getBoundingBox().contains(p.getX(), p.getY(), p.getZ()))
                return null;
            int x = p.getX() >> 4, z = p.getZ() >> 4;
            // The loaded chunk is reused only within this synchronous Scene. State/BE/fluid
            // reads still ask that chunk each time; no chunk is loaded or retained across ticks.
            if (lastChunk != null && lastChunkX == x && lastChunkZ == z) return lastChunk;
            net.minecraft.world.level.chunk.LevelChunk result;
            if (body == null) result = scene.level.getChunkSource().getChunkNow(x, z);
            else {
                var plot = body.getPlot();
                result = plot.getChunk(plot.toLocal(new net.minecraft.world.level.ChunkPos(x, z)));
            }
            if (result == null) throw new Exhausted();
            lastChunkX = x;
            lastChunkZ = z;
            lastChunk = result;
            return result;
        }

        @Override
        public BlockState getBlockState(BlockPos p) {
            var c = chunk(p);
            return c == null ? Blocks.AIR.defaultBlockState() : c.getBlockState(p);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos p) {
            var c = chunk(p);
            return c == null ? null : c.getBlockEntities().get(p);
        }

        @Override
        public FluidState getFluidState(BlockPos p) {
            return getBlockState(p).getFluidState();
        }

        @Override
        public int getHeight() {
            return scene.level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return scene.level.getMinBuildHeight();
        }

        private net.minecraft.world.phys.shapes.VoxelShape collisionShape(
                BlockState state, BlockPos at) {
            try {
                return state.getCollisionShape(this, at);
            } catch (Exhausted refused) {
                throw refused;
            } catch (RuntimeException unsupported) {
                throw new Exhausted(
                        "Native shape requires an unsupported query owner: "
                                + state.getBlock().getClass().getName());
            }
        }

        List<Solid> solids(BlockPos p) {
            if (body != null
                    && !body.getPlot().getBoundingBox().contains(p.getX(), p.getY(), p.getZ()))
                return List.of();
            return readSolids(p);
        }

        List<Solid> readSolids(BlockPos p) {
            var known = cached(p);
            if (known != null) return known;
            scene.budget.cell();
            BlockPos k = p.immutable();
            BlockState state = getBlockState(k);
            if (state.isAir()) {
                remember(k, List.of());
                return List.of();
            }
            ArrayList<Solid> out = new ArrayList<>();
            PathType type = SableNavigationFix.worldPathType(this, k);
            float malus = scene.mob.getPathfindingMalus(type);
            boolean fluid = !state.getFluidState().isEmpty();
            boolean nativeWater =
                    (scene.waterExit || scene.groundWater)
                            && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                            && scene.mob.getPathfindingMalus(PathType.WATER) >= 0;
            boolean blocked =
                    fluid && !nativeWater
                            || type != PathType.BLOCKED && type != PathType.OPEN && malus < 0;
            boolean surface = !blocked;
            int[] boxes = {0};
            collisionShape(state, k)
                    .forAllBoxes(
                            (x0, y0, z0, x1, y1, z1) -> {
                                if (++boxes[0] > 256) throw new Exhausted();
                                scene.budget.box();
                                out.add(
                                        new Solid(
                                                this,
                                                new AABB(
                                                        x0 + k.getX(),
                                                        y0 + k.getY(),
                                                        z0 + k.getZ(),
                                                        x1 + k.getX(),
                                                        y1 + k.getY(),
                                                        z1 + k.getZ()),
                                                surface,
                                                Math.max(0, malus)));
                            });
            if (blocked) {
                scene.budget.box();
                out.add(new Solid(this, new AABB(k), false, 0));
            }
            remember(k, out);
            return out;
        }
    }

    /** Geometry derived once per Scene, shared by footprint and continuous support queries. */
    record StandFace(int axis, Vec3 normal, Vec3 center, Vec3 localNormal, Face face) {}

    static final class Solid {
        final Frame frame;
        final Vec3 center;
        final double[] half = new double[3], radii;
        final AABB bounds;
        final boolean surface;
        final double cost;
        List<StandFace> topFaces;

        List<StandFace> topFaces() {
            if (topFaces != null) return topFaces;
            var result = new ArrayList<StandFace>();
            if (surface)
                for (int d = 0; d < 3; d++)
                    for (int sign = -1; sign <= 1; sign += 2) {
                        Vec3 n = frame.axes[d].scale(sign);
                        if (n.y <= .6) continue;
                        Vec3 c = center.add(frame.axes[d].scale(sign * half[d]));
                        int u = (d + 1) % 3, v = (d + 2) % 3;
                        Vec3[] corners = new Vec3[4];
                        for (int i = 0; i < 4; i++) {
                            Vec3 corner =
                                    c.add(
                                                    frame.axes[u].scale(
                                                            (i == 0 || i == 3 ? -1 : 1) * half[u]))
                                            .add(frame.axes[v].scale((i < 2 ? -1 : 1) * half[v]));
                            corners[i] = frame.local(corner);
                        }
                        result.add(
                                new StandFace(
                                        d,
                                        n,
                                        c,
                                        (d == 0 ? X : d == 1 ? Y : Z).scale(sign),
                                        new Face(corners, frame.body != null)));
                    }
            return topFaces = List.copyOf(result);
        }

        Solid(Frame f, AABB box, boolean surface, double cost) {
            frame = f;
            this.surface = surface;
            this.cost = cost;
            center = f.world(box.getCenter());
            half[0] = box.getXsize() * f.scale[0] / 2;
            half[1] = box.getYsize() * f.scale[1] / 2;
            half[2] = box.getZsize() * f.scale[2] / 2;
            double rx = 0, ry = 0, rz = 0;
            for (int i = 0; i < 3; i++) {
                rx += Math.abs(f.axes[i].x) * half[i];
                ry += Math.abs(f.axes[i].y) * half[i];
                rz += Math.abs(f.axes[i].z) * half[i];
            }
            bounds =
                    new AABB(
                            center.x - rx,
                            center.y - ry,
                            center.z - rz,
                            center.x + rx,
                            center.y + ry,
                            center.z + rz);
            radii = new double[f.tests.length];
            for (int j = 0; j < radii.length; j++) {
                Vec3 a = f.tests[j];
                double radius =
                        Math.abs(a.dot(f.actorAxes[0])) * f.scene.hx
                                + Math.abs(a.dot(f.actorAxes[1])) * f.scene.hy
                                + Math.abs(a.dot(f.actorAxes[2])) * f.scene.hz;
                for (int i = 0; i < 3; i++) radius += Math.abs(a.dot(f.axes[i])) * half[i];
                radii[j] = radius - EPS;
            }
        }

        boolean intersects(Vec3 initial, Vec3 velocity) {
            return intersects(initial, velocity, frame.tests, radii);
        }

        private boolean intersects(Vec3 initial, Vec3 velocity, Vec3[] tests, double[] radii) {
            double enter = 0, exit = 1;
            Vec3 relative = initial.subtract(center);
            for (int i = 0; i < tests.length; i++) {
                Vec3 axis = tests[i];
                double c = relative.dot(axis), v = velocity.dot(axis), r = radii[i];
                if (Math.abs(v) < 1e-12) {
                    if (Math.abs(c) >= r) return false;
                    continue;
                }
                double low = (-r - c) / v, high = (r - c) / v;
                if (low > high) {
                    double swap = low;
                    low = high;
                    high = swap;
                }
                enter = Math.max(enter, low);
                exit = Math.min(exit, high);
                if (enter >= exit) return false;
            }
            return true;
        }
    }
}
