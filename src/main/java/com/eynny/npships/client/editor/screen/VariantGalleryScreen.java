package com.eynny.npships.client.editor.screen;

import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.geometry.SchematicViewProjection;
import com.eynny.npships.client.editor.SchematicEditorScreen;
import com.eynny.npships.client.editor.render.SchematicPreviewLevel;
import com.eynny.npships.client.editor.render.SchematicPreviewRenderer;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorScrollBar;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/**
 * Independently framed variants; only visible cards build meshes. Clicking opens the full editor.
 */
public final class VariantGalleryScreen extends Screen {
    private final Screen parent;
    private final SchematicDocument document;
    private final StructureSegments.Segment segment;
    private final Consumer<StructureSegments.Variant> open, delete;
    private final Map<String, SchematicPreviewRenderer> scenes = new HashMap<>();
    private final EditorScrollBar listBar = new EditorScrollBar();
    private int scroll;
    private final Runnable refresh;

    public VariantGalleryScreen(
            Screen parent,
            SchematicDocument document,
            StructureSegments.Segment segment,
            Consumer<StructureSegments.Variant> open,
            Consumer<StructureSegments.Variant> delete,
            Runnable refresh) {
        super(Component.literal(segment.id()));
        this.parent = parent;
        this.document = document;
        this.segment = segment;
        this.open = open;
        this.delete = delete;
        this.refresh = refresh;
    }

    private int maximumScroll() {
        int columns = Math.max(1, width / 180);
        int rows = (segment.pool().size() + columns - 1) / columns;
        return Math.max(0, rows * 160 - Math.max(0, height - 48));
    }

    @Override
    protected void init() {
        scroll = Math.clamp(scroll, 0, maximumScroll());
        addRenderableWidget(
                EditorButton.flat(Component.translatable("gui.back"), b -> onClose())
                        .bounds(width - 78, 8, 70, 20)
                        .build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float tick) {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float tick) {
        scroll = Math.clamp(scroll, 0, maximumScroll());
        g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        g.drawString(font, title, 12, 12, EditorButtonTheme.text());
        int columns = Math.max(1, width / 180), cardW = (width - 24) / columns, cardH = 160;
        for (int i = 0; i < segment.pool().size(); i++) {
            var v = segment.pool().get(i);
            int x = 12 + (i % columns) * cardW, y = 40 + (i / columns) * cardH - scroll;
            if (y + cardH - 12 <= 36 || y + 18 >= height - 8) continue;
            g.enableScissor(8, 36, width - 8, height - 8);
            g.fill(x, y, x + cardW - 8, y + cardH - 8, EditorButtonTheme.surface(0xff304052));
            g.drawString(
                    font,
                    font.plainSubstrByWidth(v.name() + " / " + v.weight(), cardW - 40),
                    x + 4,
                    y + 4,
                    EditorButtonTheme.text());
            g.fill(x + cardW - 30, y, x + cardW - 10, y + 16, 0xff943d3d);
            g.drawString(font, "X", x + cardW - 25, y + 4, EditorButtonTheme.text());
            g.disableScissor();
            var renderer =
                    scenes.computeIfAbsent(
                            v.id(),
                            id ->
                                    new SchematicPreviewRenderer(
                                            new SchematicPreviewLevel(
                                                    document.variantDocument(id),
                                                    minecraft.level)));
            renderer.buildNextBatch();
            g.flush();
            g.enableScissor(
                    x, Math.max(36, y + 18), x + cardW - 8, Math.min(height - 8, y + cardH - 12));
            RenderSystem.backupProjectionMatrix();
            RenderSystem.getModelViewStack().pushMatrix().identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(
                    SchematicViewProjection.projection(width, height, 10000),
                    VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.clear(
                    org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
                    net.minecraft.client.Minecraft.ON_OSX);
            var pose = g.pose();
            pose.pushPose();
            try {
                var s = segment.size();
                float scale =
                        (float)
                                (Math.min(cardW - 24, cardH - 36)
                                        / Math.sqrt(
                                                (double) s.getX() * s.getX()
                                                        + (double) s.getY() * s.getY()
                                                        + (double) s.getZ() * s.getZ()));
                pose.translate(x + (cardW - 8) / 2f, y + cardH / 2f + 8, 200);
                pose.scale(scale, -scale, scale);
                pose.mulPose(Axis.XP.rotationDegrees(25));
                pose.mulPose(Axis.YP.rotationDegrees(135));
                pose.translate(-s.getX() / 2f, -s.getY() / 2f, -s.getZ() / 2f);
                renderer.render(pose, g.bufferSource());
                g.bufferSource().endBatch();
            } finally {
                pose.popPose();
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.getModelViewStack().popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.clear(
                        org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
                        net.minecraft.client.Minecraft.ON_OSX);
                RenderSystem.disableDepthTest();
                g.disableScissor();
            }
        }
        listBar.configure(
                width - 7,
                40,
                height - 48,
                height - 48,
                ((segment.pool().size() + columns - 1) / columns) * 160,
                scroll,
                v -> scroll = v);
        listBar.render(g);
        EditorButtonTheme.widgets(this);
        super.render(g, mx, my, tick);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (listBar.click(x, y, button)) return true;
        if (button == 0 && y >= 40 && x >= 12) {
            int columns = Math.max(1, width / 180), cw = (width - 24) / columns;
            int col = ((int) x - 12) / cw, index = ((int) y - 40 + scroll) / 160 * columns + col;
            if (col < columns && index >= 0 && index < segment.pool().size()) {
                if (x >= 12 + col * cw + cw - 30 && ((int) y - 40 + scroll) % 160 < 16) {
                    delete.accept(segment.pool().get(index));
                } else open.accept(segment.pool().get(index));
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.clamp(scroll - (int) (vertical * 30), 0, maximumScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (hasControlDown()
                && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_Z || key == org.lwjgl.glfw.GLFW.GLFW_KEY_Y)
                && parent instanceof SchematicEditorScreen editor) {
            editor.restoreHistory(key == org.lwjgl.glfw.GLFW.GLFW_KEY_Y || hasShiftDown());
            refresh.run();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void removed() {
        scenes.values().forEach(SchematicPreviewRenderer::close);
        scenes.clear();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return button == 0 && listBar.drag(y) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        listBar.release();
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
