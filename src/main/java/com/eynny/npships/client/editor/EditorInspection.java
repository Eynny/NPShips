package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.BlockAreaLayers;
import com.eynny.npships.authoring.document.FittedPlaneLayers;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.geometry.SchematicViewProjection;
import com.eynny.npships.client.editor.render.SchematicPreviewLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only ordinary-object hits on one finite ray; no tool filters or construction-plane hits. */
final class EditorInspection {
    private EditorInspection() {}

    sealed interface Target
            permits BlockTarget,
                    EntityTarget,
                    AreaTarget,
                    PlaneTarget,
                    SegmentTarget,
                    MobZoneTarget,
                    GlueTarget {}

    record BlockTarget(BlockPos position, Direction face) implements Target {}

    record EntityTarget(int index) implements Target {}

    record AreaTarget(String id) implements Target {}

    record PlaneTarget(String id) implements Target {}

    record SegmentTarget(String id) implements Target {}

    record MobZoneTarget(String id) implements Target {}

    record GlueTarget(int index) implements Target {}

    // Indices belong only to this immutable document version, never to a later reordered list.
    record Candidate(
            SchematicDocument source,
            Target target,
            Vec3 hit,
            double distance,
            BlockAreaLayers.State layerSource,
            AABB bounds) {
        Candidate(SchematicDocument source, Target target, Vec3 hit, double distance) {
            this(source, target, hit, distance, null, null);
        }
    }

    static String areaId(Target target) {
        return target instanceof AreaTarget area
                ? area.id()
                : target instanceof PlaneTarget plane ? plane.id() : "";
    }

    static boolean glue(net.minecraft.world.entity.Entity entity) {
        return com.eynny.npships.authoring.document.SchematicGlue.supported(
                net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString());
    }

    static List<Candidate> collect(
            SchematicDocument document,
            SchematicPreviewLevel level,
            EntityPreview preview,
            AABB sliceBounds,
            Vec3 from,
            Vec3 to) {
        if (from.distanceToSqr(to) < 1e-12) return List.of();
        var hits = new ArrayList<Candidate>();
        var clipped = SchematicViewProjection.clip(sliceBounds.inflate(.001), from, to);
        if (clipped.isPresent()) {
            var hit =
                    level.clip(
                            new ClipContext(
                                    clipped.get().start(),
                                    clipped.get().end(),
                                    ClipContext.Block.OUTLINE,
                                    ClipContext.Fluid.ANY,
                                    CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.BLOCK
                    && document.blocks().containsKey(hit.getBlockPos())
                    && level.visible(hit.getBlockPos())
                    && !level.originalState(hit.getBlockPos()).isAir()) {
                hits.add(
                        new Candidate(
                                document,
                                new BlockTarget(hit.getBlockPos().immutable(), hit.getDirection()),
                                hit.getLocation(),
                                from.distanceToSqr(hit.getLocation()),
                                null,
                                new AABB(hit.getBlockPos())));
            }
        }
        // A stale preview may still render until the next frame; its list indices are ineligible.
        if (document.sameEntities(preview.entitySource)) {
            for (int i = 0; i < preview.entities.size(); i++) {
                var entity = preview.entities.get(i);
                if (glue(entity)
                        || preview.failedEntities.contains(entity)
                        || !level.visible(BlockPos.containing(entity.position()))) continue;
                int index = preview.entityIndices.get(i);
                if (index < 0 || index >= document.entityCount()) continue;
                var hit =
                        SchematicViewProjection.hit(entity.getBoundingBox().inflate(.05), from, to);
                hit.ifPresent(
                        point ->
                                hits.add(
                                        new Candidate(
                                                document,
                                                new EntityTarget(index),
                                                point,
                                                from.distanceToSqr(point),
                                                null,
                                                entity.getBoundingBox())));
            }
        }
        // Outline surfaces occlude deeper hitboxes. Exact depth ties favor the block surface;
        // entity ties retain the existing document-index ordering. This is not pixel visibility.
        hits.sort(
                Comparator.comparingDouble(Candidate::distance)
                        .thenComparingInt(
                                candidate ->
                                        candidate.target() instanceof BlockTarget
                                                ? -1
                                                : ((EntityTarget) candidate.target()).index()));
        return List.copyOf(hits);
    }

    static List<Candidate> collectAuthoring(
            SchematicDocument document,
            BlockAreaLayers.State layers,
            EntityPreview preview,
            SchematicPreviewLevel level,
            List<StructureSegments.Segment> segments,
            List<StructureCrew.Rule> zones,
            Vec3 from,
            Vec3 to) {
        if (from.distanceToSqr(to) < 1e-12) return List.of();
        var hits = new ArrayList<Candidate>();
        for (var layer : layers.layers())
            addRegion(
                    hits,
                    document,
                    layers,
                    FittedPlaneLayers.plane(layer)
                            ? new PlaneTarget(layer.id())
                            : new AreaTarget(layer.id()),
                    layer.region().bounds(),
                    from,
                    to);
        for (var segment : segments)
            addRegion(
                    hits,
                    document,
                    layers,
                    new SegmentTarget(segment.id()),
                    new AABB(
                            Vec3.atLowerCornerOf(segment.origin()),
                            Vec3.atLowerCornerOf(segment.origin().offset(segment.size()))),
                    from,
                    to);
        for (var zone : zones)
            addRegion(
                    hits,
                    document,
                    layers,
                    new MobZoneTarget(zone.id()),
                    new AABB(
                            Vec3.atLowerCornerOf(zone.origin()),
                            Vec3.atLowerCornerOf(zone.origin().offset(zone.size()))),
                    from,
                    to);
        if (document.sameEntities(preview.entitySource))
            for (int i = 0; i < preview.entities.size(); i++) {
                var entity = preview.entities.get(i);
                int index = preview.entityIndices.get(i);
                if (glue(entity)
                        && !preview.failedEntities.contains(entity)
                        && level.visible(BlockPos.containing(entity.position()))
                        && index >= 0
                        && index < document.entityCount())
                    addRegion(
                            hits,
                            document,
                            layers,
                            new GlueTarget(index),
                            entity.getBoundingBox(),
                            from,
                            to);
            }
        // Same finite volume geometry as the old authoring picker, without block occlusion or
        // per-region slice clipping. Exact-depth ties use typed session IDs, never layer priority.
        hits.sort(
                Comparator.comparingDouble(Candidate::distance)
                        .thenComparing(candidate -> identity(candidate.target())));
        return List.copyOf(hits);
    }

    private static void addRegion(
            List<Candidate> hits,
            SchematicDocument source,
            BlockAreaLayers.State layers,
            Target target,
            AABB bounds,
            Vec3 from,
            Vec3 to) {
        SchematicViewProjection.hit(bounds, from, to)
                .ifPresent(
                        point ->
                                hits.add(
                                        new Candidate(
                                                source,
                                                target,
                                                point,
                                                from.distanceToSqr(point),
                                                layers,
                                                bounds)));
    }

    static String identity(Target target) {
        return switch (target) {
            case AreaTarget area -> "area:" + area.id();
            case PlaneTarget plane -> "plane:" + plane.id();
            case SegmentTarget segment -> "segment:" + segment.id();
            case MobZoneTarget zone -> "zone:" + zone.id();
            case GlueTarget glue -> "glue:" + glue.index();
            case BlockTarget block -> "block:" + block.position();
            case EntityTarget entity -> "entity:" + entity.index();
        };
    }
}
