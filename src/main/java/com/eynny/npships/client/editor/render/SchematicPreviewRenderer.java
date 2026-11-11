package com.eynny.npships.client.editor.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.createmod.catnip.client.render.model.BakedModelBufferer;
import net.createmod.catnip.client.render.model.ShadeSeparatedResultConsumer;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.SuperByteBufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cached preview meshes, rebuilt in bounded batches when their source changes. */
public final class SchematicPreviewRenderer implements AutoCloseable {
    private final SchematicPreviewLevel level;
    private final PreviewRendererScope renderScope = new PreviewRendererScope();
    private final PreviewCameraContext cameraContext = new PreviewCameraContext();
    private long resourceEpoch = PreviewResources.epoch();

    private boolean resourcesChanged() {
        long next = PreviewResources.epoch();
        if (next == resourceEpoch) return false;
        resourceEpoch = next;
        level.resetVisualInstances();
        failedRenderers.clear();
        invalidate();
        return true;
    }

    private float brightness = 1;

    public void brightness(float value) {
        brightness = value;
    }

    private record Batch(
            List<BlockPos> positions,
            Map<RenderType, SuperByteBuffer> meshes,
            Map<RenderType, SuperByteBuffer> translucentMeshes) {
        void delete() {
            meshes.values().forEach(SuperByteBuffer::delete);
            translucentMeshes.values().forEach(SuperByteBuffer::delete);
        }
    }

    private final List<Batch> batches = new ArrayList<>();
    private final Map<BlockPos, Integer> batchIndex = new java.util.HashMap<>();
    private final Set<BlockPos> queuedPositions = new HashSet<>();
    private Set<BlockPos> translucentPositions = Set.of();
    private BlockPos maskedPosition;
    private final Map<Batch, Batch> maskedBatches = new java.util.IdentityHashMap<>();
    private int maskedBuilt = -1;

    private Object moveKey;
    private java.util.function.Predicate<BlockPos> moving = p -> false;
    private net.minecraft.world.phys.Vec3 moveOffset = net.minecraft.world.phys.Vec3.ZERO;

    private record MoveBatch(Batch fixed, Batch shifted, boolean owns) {}

    private final Map<Batch, MoveBatch> moveBatches = new java.util.IdentityHashMap<>();

    /** Split boundary batches once per gesture; translation reuses the prepared meshes. */
    public void movement(
            Object key,
            java.util.function.Predicate<BlockPos> contains,
            net.minecraft.world.phys.Vec3 offset) {
        if (key != moveKey) {
            clearMovement();
            moveKey = key;
            moving = contains;
        }
        moveOffset = offset;
    }

    private void clearMovement() {
        for (var split : moveBatches.values())
            if (split.owns()) {
                split.fixed().delete();
                split.shifted().delete();
            }
        moveBatches.clear();
        moveKey = null;
        moving = p -> false;
        moveOffset = net.minecraft.world.phys.Vec3.ZERO;
    }

    private void drawMovingBatch(
            Batch batch,
            RenderType type,
            boolean translucent,
            PoseStack pose,
            MultiBufferSource.BufferSource buffers) {
        if (moveKey == null) {
            drawMesh(maskedBatches.getOrDefault(batch, batch), type, translucent, pose, buffers);
            return;
        }
        var split =
                moveBatches.computeIfAbsent(
                        batch,
                        b -> {
                            var shifted = b.positions().stream().filter(moving).toList();
                            boolean boundary = false;
                            for (var p : b.positions()) {
                                boolean own = moving.test(p);
                                for (var face : net.minecraft.core.Direction.values())
                                    if (moving.test(p.relative(face)) != own) {
                                        boundary = true;
                                        break;
                                    }
                                if (boundary) break;
                            }
                            if (!boundary && shifted.isEmpty())
                                return new MoveBatch(b, null, false);
                            if (!boundary && shifted.size() == b.positions().size())
                                return new MoveBatch(null, b, false);
                            Batch fixed = null;
                            try {
                                level.meshRegion = moving.negate();
                                fixed =
                                        buildBatch(
                                                b.positions().stream()
                                                        .filter(moving.negate())
                                                        .toList());
                                level.meshRegion = moving;
                                return new MoveBatch(fixed, buildBatch(shifted), true);
                            } catch (RuntimeException failure) {
                                if (fixed != null) fixed.delete();
                                throw failure;
                            } finally {
                                level.meshRegion = null;
                            }
                        });
        if (split.fixed() != null) drawMesh(split.fixed(), type, translucent, pose, buffers);
        if (split.shifted() != null) {
            pose.pushPose();
            pose.translate(moveOffset.x, moveOffset.y, moveOffset.z);
            drawMesh(split.shifted(), type, translucent, pose, buffers);
            pose.popPose();
        }
    }

    private void drawMesh(
            Batch batch,
            RenderType type,
            boolean translucent,
            PoseStack pose,
            MultiBufferSource.BufferSource buffers) {
        var mesh = (translucent ? batch.translucentMeshes() : batch.meshes()).get(type);
        if (mesh == null) return;
        mesh.light(LightTexture.FULL_BRIGHT);
        if (translucent) mesh.color(255, 255, 255, 166);
        mesh.renderInto(
                pose,
                buffers.getBuffer(
                        translucent ? GhostRenderType.of(type) : PreviewRenderType.of(type)));
    }

    public void mask(BlockPos pos) {
        if (java.util.Objects.equals(pos, maskedPosition) && maskedBuilt == built) return;
        clearMask();
        maskedPosition = pos;
        maskedBuilt = built;
        if (pos == null) return;
        level.visualMask = pos;
        try {
            var indices = new HashSet<Integer>();
            Integer own = batchIndex.get(pos);
            if (own != null) indices.add(own);
            for (var direction : net.minecraft.core.Direction.values()) {
                var i = batchIndex.get(pos.relative(direction));
                if (i != null) indices.add(i);
            }
            for (int i : indices) {
                var batch = batches.get(i);
                maskedBatches.put(
                        batch,
                        buildBatch(
                                batch.positions().stream().filter(p -> !p.equals(pos)).toList()));
            }
        } finally {
            level.visualMask = null;
        }
    }

    private void clearMask() {
        maskedBatches.values().forEach(Batch::delete);
        maskedBatches.clear();
        maskedPosition = null;
        maskedBuilt = -1;
    }

    private final Set<BlockPos> failedRenderers = new HashSet<>();
    private final List<BlockPos> blockEntities = new ArrayList<>();
    private List<BlockPos> visible = List.of();
    private int built;
    private final java.util.LinkedHashSet<Integer> dirtyBatches = new java.util.LinkedHashSet<>();

    public SchematicPreviewRenderer(final SchematicPreviewLevel level) {
        this.level = level;
        invalidate();
    }

    public void invalidate() {
        close();
        visible =
                new ArrayList<>(
                        level.getAllPositions().stream()
                                .filter(level::visible)
                                .sorted(
                                        java.util.Comparator.comparingLong(
                                                p ->
                                                        net.minecraft.core.SectionPos.asLong(
                                                                p.getX() >> 3,
                                                                p.getY() >> 3,
                                                                p.getZ() >> 3)))
                                .toList());
        queuedPositions.addAll(visible);
        blockEntities.clear();
        built = 0;
    }

    public void buildNextBatch() {
        resourcesChanged();
        // Soft preparation budget, checked between batches; one native model callback may exceed
        // it.
        long deadline = System.nanoTime() + 4_000_000;
        do {
            buildOneBatch();
        } while ((!dirtyBatches.isEmpty() || built < visible.size())
                && System.nanoTime() < deadline);
    }

    private void buildOneBatch() {
        if (!dirtyBatches.isEmpty()) {
            clearMask();
            int index = dirtyBatches.iterator().next();
            dirtyBatches.remove(index);
            var old = batches.get(index);
            var replacement = buildBatch(old.positions());
            old.delete();
            batches.set(index, replacement);
            return;
        }
        if (built >= visible.size()) return;
        int end = Math.min(built + 256, visible.size());
        var batch = visible.subList(built, end);
        int index = batches.size();
        batches.add(buildBatch(List.copyOf(batch)));
        for (var p : batch) batchIndex.put(p, index);
        for (BlockPos pos : batch)
            if (level.getBlockState(pos).hasBlockEntity()) blockEntities.add(pos);
        built = end;
    }

    public void update(Set<BlockPos> changed) {
        if (changed.isEmpty()) return;
        clearMask();
        var affected = new HashSet<BlockPos>();
        for (var p : changed)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++)
                    for (int x = -1; x <= 1; x++) affected.add(p.offset(x, y, z));
        rebuildPositions(affected);
        for (var p : changed)
            if (level.visible(p) && !level.originalState(p).isAir() && queuedPositions.add(p))
                visible.add(p);
        blockEntities.removeIf(affected::contains);
        failedRenderers.removeAll(affected);
        for (var p : affected)
            if (batchIndex.containsKey(p) && level.getBlockState(p).hasBlockEntity())
                blockEntities.add(p);
    }

    public void translucent(Set<BlockPos> positions) {
        if (translucentPositions.equals(positions)) return;
        var changed = new HashSet<>(translucentPositions);
        for (var p : positions) if (!changed.add(p)) changed.remove(p);
        translucentPositions = Collections.unmodifiableSet(new HashSet<>(positions));
        level.translucentPositions = translucentPositions;
        var edges = new HashSet<BlockPos>(changed);
        for (var p : changed)
            for (var face : net.minecraft.core.Direction.values()) edges.add(p.relative(face));
        clearMask();
        rebuildPositions(edges);
    }

    private void rebuildPositions(Set<BlockPos> positions) {
        var affectedBatches = new HashSet<Integer>();
        for (var p : positions) {
            var index = batchIndex.get(p);
            if (index != null) affectedBatches.add(index);
        }
        dirtyBatches.addAll(affectedBatches);
    }

    private Batch buildBatch(List<BlockPos> batch) {
        return com.eynny.npships.diagnostics.EditorWorkMetrics.measure(
                com.eynny.npships.diagnostics.EditorWorkMetrics.Phase.MESH,
                () -> new Batch(batch, buildMeshes(batch, false), buildMeshes(batch, true)));
    }

    private Map<RenderType, SuperByteBuffer> buildMeshes(
            List<BlockPos> batch, boolean translucent) {
        Map<RenderType, SuperByteBufferBuilder> builders = new LinkedHashMap<>();
        ShadeSeparatedResultConsumer consumer =
                (type, shaded, data) -> {
                    var builder =
                            builders.computeIfAbsent(
                                    type,
                                    ignored -> {
                                        var created = new SuperByteBufferBuilder();
                                        created.prepare();
                                        return created;
                                    });
                    builder.add(data, shaded);
                };
        level.meshTranslucent = translucent;
        try {
            BakedModelBufferer.bufferBlocks(
                    batch.stream()
                            .filter(
                                    p ->
                                            translucentPositions.contains(p) == translucent
                                                    && level.visible(p)
                                                    && !level.originalState(p).isAir())
                            .iterator(),
                    level,
                    null,
                    true,
                    consumer);
        } finally {
            level.meshTranslucent = null;
        }
        var result = new LinkedHashMap<RenderType, SuperByteBuffer>();
        builders.forEach((type, builder) -> result.put(type, builder.build()));
        return result;
    }

    public void render(final PoseStack pose, final MultiBufferSource.BufferSource buffers) {
        render(pose, buffers, () -> {});
    }

    /**
     * Native opaque content precedes blended block layers, without sharing unsafe callback buffers.
     */
    public void render(
            PoseStack pose, MultiBufferSource.BufferSource buffers, Runnable opaqueContent) {
        if (resourcesChanged()) buildNextBatch();
        for (RenderType type : RenderType.chunkBufferLayers()) {
            if (type == RenderType.translucent() || type == RenderType.tripwire()) continue;
            for (var batch : batches) {
                drawMovingBatch(batch, type, false, pose, buffers);
            }
            buffers.endBatch(PreviewRenderType.of(type));
        }
        renderBlockEntities(pose, buffers, false);
        opaqueContent.run();
        buffers.endBatch();
        for (RenderType type : List.of(RenderType.translucent(), RenderType.tripwire())) {
            for (var batch : batches) drawMovingBatch(batch, type, false, pose, buffers);
            buffers.endBatch(PreviewRenderType.of(type));
        }
        renderBlockEntities(pose, buffers, true);
        for (RenderType type : RenderType.chunkBufferLayers()) {
            var ghost = GhostRenderType.of(type);
            for (var batch : batches) {
                drawMovingBatch(batch, type, true, pose, buffers);
            }
            buffers.endBatch(ghost);
        }
    }

    private void renderBlockEntities(
            PoseStack pose, MultiBufferSource.BufferSource buffers, boolean translucent) {
        var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
                brightness, brightness, brightness, translucent ? .65f : 1);
        for (BlockPos pos : blockEntities) {
            if (translucentPositions.contains(pos) != translucent) continue;
            if (failedRenderers.contains(pos) || pos.equals(maskedPosition)) continue;
            var entity = level.getBlockEntity(pos);
            if (entity == null) continue;
            var renderer = dispatcher.getRenderer(entity);
            if (renderer == null) continue;
            try (var context =
                    cameraContext.open(
                            level,
                            pose,
                            moveKey != null && moving.test(pos)
                                    ? moveOffset
                                    : net.minecraft.world.phys.Vec3.ZERO)) {
                // Native saved visual state, without ticking or activating the preview world.
                renderScope.render(
                        pose,
                        buffers,
                        (detached, isolated) -> {
                            detached.translate(pos.getX(), pos.getY(), pos.getZ());
                            if (moveKey != null && moving.test(pos))
                                detached.translate(moveOffset.x, moveOffset.y, moveOffset.z);
                            renderer.render(
                                    entity,
                                    0,
                                    detached,
                                    type ->
                                            isolated.getBuffer(
                                                    translucent
                                                            ? GhostRenderType.of(type)
                                                            : PreviewRenderType.of(type)),
                                    LightTexture.FULL_BRIGHT,
                                    OverlayTexture.NO_OVERLAY);
                        });
            } catch (Exception exception) {
                renderScope.failure(
                        translucent ? "ghost BER" : "BER",
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(
                                entity.getType()),
                        pos,
                        exception);
                failedRenderers.add(pos);
            }
        }
        buffers.endBatch();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
                brightness, brightness, brightness, 1);
    }

    public int built() {
        resourcesChanged();
        return Math.max(0, built - dirtyBatches.size() * 256);
    }

    public int total() {
        return visible.size();
    }

    public int failedRenderers() {
        return failedRenderers.size();
    }

    @Override
    public void close() {
        renderScope.close();
        cameraContext.release();
        clearMovement();
        clearMask();
        dirtyBatches.clear();
        batches.forEach(Batch::delete);
        batches.clear();
        batchIndex.clear();
        queuedPositions.clear();
        blockEntities.clear();
        built = 0;
    }
}
