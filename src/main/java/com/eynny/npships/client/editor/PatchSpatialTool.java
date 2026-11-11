package com.eynny.npships.client.editor;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;

/**
 * Native inspector region interaction. Host Screen arbitration and history remain authoritative.
 */
interface PatchSpatialTool extends AutoCloseable {
    interface Factory {
        boolean applies(EditorToolsPanel owner);

        PatchSpatialTool open(EditorToolsPanel owner);
    }

    void clear();

    void cancel();

    void cancelGesture();

    default net.minecraft.network.chat.Component pendingHint() {
        return net.minecraft.network.chat.Component.translatable(
                "npships.tools.patch_pending_finish");
    }

    boolean pending();

    boolean choosing();

    boolean supported();

    boolean dragging();

    AABB extent();

    boolean render();

    boolean begin(double x, double y, Matrix4f inverse, float depth);

    boolean drag(double x, double y);

    boolean end();

    void renderScene(GuiGraphics graphics, PoseStack pose);

    @Override
    default void close() {
        clear();
    }
}
