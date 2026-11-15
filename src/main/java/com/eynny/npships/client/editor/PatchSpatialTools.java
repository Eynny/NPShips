package com.eynny.npships.client.editor;

import com.eynny.npships.api.patches.*;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchCode;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;

import java.util.Objects;

/** Context-local tool lease; planning occurs only on an inspector rebuild, never each frame. */
final class PatchSpatialTools implements PatchSpatialTool {
    private final EditorToolsPanel panel;
    private final PatchPolicy policy = NPShipsRuntimeConfig.patchPolicy();
    private PatchSpatialTool current;
    private SchematicDocument source;
    private BlockPos position;

    PatchSpatialTools(EditorToolsPanel panel) {
        this.panel = panel;
    }

    private void resolve() {
        if (source == panel.doc() && Objects.equals(position, panel.pos())) return;
        clear();
        var candidates =
                PatchCode.applicable(
                        PatchSpatialTool.Factory.class,
                        factory -> factory.applies(panel),
                        PatchStage.AUTHOR_PREPARATION,
                        policy);
        if (candidates.size() > 1)
            throw new IllegalArgumentException("Conflicting spatial tool owners at " + panel.pos());
        if (!candidates.isEmpty())
            current = Objects.requireNonNull(candidates.getFirst().getValue().open(panel));
        source = panel.doc();
        position = panel.pos();
    }

    @Override
    public void clear() {
        var previous = current;
        current = null;
        source = null;
        position = null;
        if (previous != null) previous.close();
    }

    @Override
    public void cancel() {
        if (current != null) current.cancel();
    }

    @Override
    public void cancelGesture() {
        if (current != null) current.cancelGesture();
    }

    @Override
    public net.minecraft.network.chat.Component pendingHint() {
        return current == null ? PatchSpatialTool.super.pendingHint() : current.pendingHint();
    }

    @Override
    public boolean pending() {
        return current != null && current.pending();
    }

    @Override
    public boolean choosing() {
        return current != null && current.choosing();
    }

    @Override
    public boolean supported() {
        return current != null && current.supported();
    }

    @Override
    public boolean dragging() {
        return current != null && current.dragging();
    }

    @Override
    public AABB extent() {
        return current == null ? null : current.extent();
    }

    @Override
    public boolean render() {
        resolve();
        return current != null && current.render();
    }

    @Override
    public boolean begin(double x, double y, Matrix4f inverse, float depth) {
        return current != null && current.begin(x, y, inverse, depth);
    }

    @Override
    public boolean drag(double x, double y) {
        return current != null && current.drag(x, y);
    }

    @Override
    public boolean end() {
        return current != null && current.end();
    }

    @Override
    public void renderScene(GuiGraphics graphics, PoseStack pose) {
        if (current != null) current.renderScene(graphics, pose);
    }
}
