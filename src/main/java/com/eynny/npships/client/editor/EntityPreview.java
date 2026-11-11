package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.client.editor.render.GhostRenderType;
import com.eynny.npships.client.editor.render.PreviewDiagnostics;
import com.eynny.npships.client.editor.render.PreviewRendererScope;
import com.eynny.npships.client.editor.render.SchematicPreviewLevel;
import com.eynny.npships.ship.EntityHeadPose;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Detached entities and renderer failures survive unrelated block edits. Never ticks a world. */
final class EntityPreview implements AutoCloseable {
    private final PreviewRendererScope renderScope = new PreviewRendererScope();
    private final com.eynny.npships.client.editor.render.PreviewCameraContext cameraContext =
            new com.eynny.npships.client.editor.render.PreviewCameraContext();
    private long resourceEpoch = com.eynny.npships.client.editor.render.PreviewResources.epoch();
    List<Entity> entities = List.of();
    List<Integer> entityIndices = List.of();
    SchematicDocument entitySource;
    private SchematicPreviewLevel entityLevel;
    ListTag entityEntries = new ListTag();
    String entityError = "";
    PreviewDiagnostics diagnostics = new PreviewDiagnostics();
    final Set<Entity> failedEntities = Collections.newSetFromMap(new IdentityHashMap<>());

    boolean render(
            Entity entity,
            PoseStack pose,
            MultiBufferSource.BufferSource destination,
            Vec3 offset,
            boolean ghost) {
        if (failedEntities.contains(entity)) return false;
        try (var context = cameraContext.open(entity.level(), pose, offset)) {
            renderScope.render(
                    pose,
                    destination,
                    (detached, isolated) -> {
                        Minecraft.getInstance()
                                .getEntityRenderDispatcher()
                                .render(
                                        entity,
                                        entity.getX() + offset.x,
                                        entity.getY() + offset.y,
                                        entity.getZ() + offset.z,
                                        entity.getYRot(),
                                        0,
                                        detached,
                                        ghost
                                                ? type ->
                                                        isolated.getBuffer(GhostRenderType.of(type))
                                                : isolated,
                                        0xf000f0);
                    });
            return true;
        } catch (Exception failure) {
            entityError =
                    renderScope.failure(
                            ghost ? "paste entity" : "entity",
                            BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                            entity.position(),
                            failure);
            failedEntities.add(entity);
            return false;
        }
    }

    @Override
    public void close() {
        renderScope.close();
        cameraContext.release();
    }

    void refresh(SchematicDocument source, net.minecraft.world.level.Level level) {
        long epoch = com.eynny.npships.client.editor.render.PreviewResources.epoch();
        if (resourceEpoch != epoch) {
            close();
            resourceEpoch = epoch;
            entitySource = null;
        }
        if (entitySource == source) return;
        if (source.sameEntities(entitySource)) {
            if (!entities.isEmpty()) {
                entityLevel.refresh(source);
            }
            entitySource = source;
            return;
        }
        var list = source.entitiesCopy();
        entitySource = source;
        entityEntries = list;
        failedEntities.clear();
        var rendered = new ArrayList<Entity>();
        var indices = new ArrayList<Integer>();
        var virtual = entityLevel = new SchematicPreviewLevel(source, level);
        entityError = "";
        diagnostics = new PreviewDiagnostics();
        for (int i = 0; i < Math.min(list.size(), 1024); i++) {
            var entry = list.getCompound(i);
            var data = entry.getCompound("nbt").copy();
            String id = PreviewDiagnostics.bounded(data.getString("id"), 160);
            String target =
                    "X "
                            + entry.getList("pos", Tag.TAG_DOUBLE).getDouble(0)
                            + " Y "
                            + entry.getList("pos", Tag.TAG_DOUBLE).getDouble(1)
                            + " Z "
                            + entry.getList("pos", Tag.TAG_DOUBLE).getDouble(2)
                            + " ("
                            + (i + 1)
                            + ")";
            String phase = "npships.editor.preview_entity_malformed";
            try {
                if (!data.contains("id", Tag.TAG_STRING) || data.getString("id").isBlank()) {
                    diagnostics.add(
                            i,
                            net.minecraft.network.chat.Component.translatable(
                                    "npships.editor.preview_entity_no_type", target));
                    continue;
                }
                var key = ResourceLocation.tryParse(data.getString("id"));
                var position = entry.getList("pos", Tag.TAG_DOUBLE);
                if (key == null
                        || !entry.contains("nbt", Tag.TAG_COMPOUND)
                        || position.size() != 3
                        || !Double.isFinite(position.getDouble(0))
                        || !Double.isFinite(position.getDouble(1))
                        || !Double.isFinite(position.getDouble(2)))
                    throw new IllegalArgumentException(
                            "Expected native entity id and three finite position values");
                if (com.eynny.npships.authoring.SchematicSafety.movingEntity(key.toString())) {
                    diagnostics.add(
                            i,
                            net.minecraft.network.chat.Component.translatable(
                                    "npships.editor.preview_entity_excluded", id, target));
                    continue;
                }
                var type = BuiltInRegistries.ENTITY_TYPE.getOptional(key);
                if (type.isEmpty()) {
                    diagnostics.add(
                            i,
                            net.minecraft.network.chat.Component.translatable(
                                    "npships.editor.preview_entity_missing", id, target));
                    continue;
                }
                phase = "npships.editor.preview_entity_create";
                var e = type.get().create(virtual);
                if (e == null)
                    throw new IllegalStateException("Native entity factory returned null");
                phase = "npships.editor.preview_entity_load";
                com.eynny.npships.authoring.document.PreviewEntityData.load(e, entry);
                e.xo = e.getX();
                e.yo = e.getY();
                e.zo = e.getZ();
                e.yRotO = e.getYRot();
                e.xRotO = e.getXRot();
                EntityHeadPose.apply(e);
                rendered.add(e);
                indices.add(i);
            } catch (Exception ex) {
                diagnostics.add(
                        i,
                        net.minecraft.network.chat.Component.translatable(
                                phase, id, target, PreviewDiagnostics.cause(ex)));
            }
        }
        if (list.size() > 1024)
            diagnostics.add(
                    "limit",
                    net.minecraft.network.chat.Component.translatable(
                            "npships.editor.preview_entity_limit", list.size() - 1024));
        entityError =
                String.join(
                        "\n",
                        diagnostics.lines().stream()
                                .map(net.minecraft.network.chat.Component::getString)
                                .toList());
        entities = List.copyOf(rendered);
        entityIndices = List.copyOf(indices);
    }
}
