package com.eynny.npships.client.spawn;

import com.eynny.npships.diagnostics.SpawnVisualizationData;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Field;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One analytic MAX composite, not blended discs. No terrain queries or retained world geometry. */
@EventBusSubscriber(modid = "npships", value = Dist.CLIENT)
public final class SpawnVisualizationRenderer {
    // Camera-relative XYZ, radius, pressure;64 bounded fields, no per-frame packing allocation.
    public static final int FIELD_UNIFORM_FLOATS = SpawnVisualizationData.MAX_FIELDS * 5;

    private static final float[] FIELDS = new float[FIELD_UNIFORM_FLOATS];

    private static TextureTarget sceneDepth;

    private SpawnVisualizationRenderer() {}

    static void releaseDepth() {
        if (sceneDepth != null) {
            try (var state = new State()) {
                RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                sceneDepth.destroyBuffers();
                sceneDepth = null;
            }
        }
    }

    public static void pack(List<Field> fields, Vec3 camera, float[] target) {
        if (fields.size() > SpawnVisualizationData.MAX_FIELDS || target.length != FIELDS.length)
            throw new IllegalArgumentException("Unbounded diagnostic geometry");
        Arrays.fill(target, 0);
        for (int i = 0; i < fields.size(); i++) {
            var f = fields.get(i);
            int k = i * 5;
            target[k] = (float) (f.position().x - camera.x);
            target[k + 1] = (float) (f.position().y - camera.y);
            target[k + 2] = (float) (f.position().z - camera.z);
            target[k + 3] = (float) f.radius();
            target[k + 4] = (float) f.pressure();
        }
    }

    /**
     * Copy depth before sampling it: sampling an attached draw texture is undefined even with
     * writes disabled.
     */
    public static void surfaces(
            RenderTarget target, Matrix4f view, Matrix4f projection, float[] fields, int count) {
        surfaces(target, view, projection, fields, count, 2.0);
    }

    public static void surfaces(
            RenderTarget target,
            Matrix4f view,
            Matrix4f projection,
            float[] fields,
            int count,
            double maximumPressure) {
        if (!Double.isFinite(maximumPressure) || maximumPressure < 0 || maximumPressure > 16)
            throw new IllegalArgumentException("Invalid pressure color scale");
        if (count < 0
                || count > SpawnVisualizationData.MAX_FIELDS
                || fields.length != FIELDS.length)
            throw new IllegalArgumentException("Invalid field uniform count");
        var shader = SpawnVisualizationShaders.fields;
        if (shader == null || !target.useDepth || target.width < 1 || target.height < 1) return;
        try (var state = new State()) {
            RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            if (sceneDepth == null
                    || sceneDepth.width != target.width
                    || sceneDepth.height != target.height
                    || sceneDepth.isStencilEnabled() != target.isStencilEnabled()) {
                releaseDepth();
                sceneDepth = new TextureTarget(target.width, target.height, true, Minecraft.ON_OSX);
                if (target.isStencilEnabled()) sceneDepth.enableStencil();
            }
            sceneDepth.copyDepthFrom(target);
            target.bindWrite(true);
            shader.setSampler("SceneDepth", sceneDepth.getDepthTextureId());
            shader.getUniform("InverseViewProjection")
                    .set(new Matrix4f(projection).mul(view).invert());
            shader.getUniform("Fields").set(fields);
            shader.getUniform("FieldCount").set(count);
            shader.getUniform("MaximumPressure").set((float) maximumPressure);
            var vertices =
                    Tesselator.getInstance()
                            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            vertices.addVertex(-1, -1, 0);
            vertices.addVertex(1, -1, 0);
            vertices.addVertex(1, 1, 0);
            vertices.addVertex(-1, 1, 0);
            draw(shader, vertices.buildOrThrow(), true);
        }
    }

    private static void draw(ShaderInstance shader, MeshData mesh) {
        // The native position/color shader is shared; an earlier draw can leave a tint or alpha.
        var modulator = shader.COLOR_MODULATOR;
        var values = modulator.getFloatBuffer();
        float r = values.get(0), g = values.get(1), b = values.get(2), a = values.get(3);
        modulator.set(1f, 1f, 1f, 1f);
        try {
            draw(shader, mesh, false);
        } finally {
            modulator.set(r, g, b, a);
        }
    }

    private static void draw(ShaderInstance shader, MeshData mesh, boolean preserveSceneAlpha) {
        shader.apply();
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            if (preserveSceneAlpha)
                RenderSystem.blendFuncSeparate(
                        GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            else RenderSystem.defaultBlendFunc();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            BufferUploader.draw(mesh);
        } finally {
            shader.clear();
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        var view = SpawnVisualizationClient.VIEW;
        if (mc.level == null || mc.level != SpawnVisualizationClient.owner || !view.enabled()) {
            releaseDepth();
            return;
        }
        boolean fresh = view.fresh(SpawnVisualizationClient.tick);
        if (!fresh) releaseDepth();
        var frame = view.frame();
        var camera = event.getCamera().getPosition();
        // Native terrain is complete; entities, particles, clouds and translucency render
        // afterwards.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS
                && fresh
                && !frame.overflow()
                && !frame.fields().isEmpty()) {
            pack(frame.fields(), camera, FIELDS);
            surfaces(
                    mc.getMainRenderTarget(),
                    event.getModelViewMatrix(),
                    event.getProjectionMatrix(),
                    FIELDS,
                    frame.fields().size(),
                    frame.maximumPressure());
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        long now = view.estimatedServerTick(mc.level.getGameTime());
        markers(frame, now, camera, event.getModelViewMatrix(), event.getProjectionMatrix());
    }

    static void markers(
            com.eynny.npships.diagnostics.SpawnVisualizationData.Frame frame,
            long now,
            Vec3 camera,
            Matrix4f view,
            Matrix4f projection) {
        if (frame.markers().isEmpty()) return;
        try (var state = new State()) {
            var shader = GameRenderer.getPositionColorShader();
            shader.MODEL_VIEW_MATRIX.set(view);
            shader.PROJECTION_MATRIX.set(projection);
            GL11.glLineWidth(1f);
            var vertices =
                    Tesselator.getInstance()
                            .begin(
                                    VertexFormat.Mode.DEBUG_LINES,
                                    DefaultVertexFormat.POSITION_COLOR);
            int count = 0;
            for (var m : frame.markers()) {
                if (now >= m.expiresAt()) continue;
                var center =
                        new Vector3f(
                                (float) (m.position().getX() - camera.x),
                                (float) (m.position().getY() - camera.y),
                                (float) (m.position().getZ() - camera.z));
                float half = Math.max(.3f, Math.min(32, center.length() * .012f));
                // Twelve shared-endpoint native lines: no dark pass, billboard strips or fill.
                // Pixel width is constant even when the camera is very close to a marker.
                for (int axis = 0; axis < 3; axis++)
                    for (int u = -1; u <= 1; u += 2)
                        for (int v = -1; v <= 1; v += 2) {
                            var from = new Vector3f(center);
                            from.setComponent(axis, center.get(axis) - half);
                            from.setComponent(
                                    (axis + 1) % 3, center.get((axis + 1) % 3) + u * half);
                            from.setComponent(
                                    (axis + 2) % 3, center.get((axis + 2) % 3) + v * half);
                            var to = new Vector3f(from).setComponent(axis, center.get(axis) + half);
                            vertices.addVertex(from.x, from.y, from.z).setColor(1f, .65f, .08f, 1f);
                            vertices.addVertex(to.x, to.y, to.z).setColor(1f, .65f, .08f, 1f);
                        }
                count++;
            }
            if (count > 0) draw(shader, vertices.buildOrThrow());
            else {
                var unused = vertices.build();
                if (unused != null) unused.close();
            }
        }
    }

    record HudRow(
            float x, float y, float width, float scale, FormattedCharSequence text, Style style) {
        boolean contains(double mouseX, double mouseY, int height) {
            return mouseX >= x - scale
                    && mouseX < x + width + scale
                    && mouseY >= y - scale
                    && mouseY < y + (height - 1) * scale;
        }
    }

    /**
     * Keep diagnostic information and a newest-first prefix of whole timers at native font size.
     */
    static List<HudRow> layoutHud(
            Font font, List<Component> lines, int screenWidth, int screenHeight) {
        int availableWidth = screenWidth - 16;
        int availableHeight = screenHeight - 52;
        if (availableWidth <= 0 || availableHeight < font.lineHeight) return List.of();
        int wrapWidth = Math.min(420, availableWidth);
        int pitch = font.lineHeight + 3;
        int capacity = (availableHeight + 3) / pitch;
        var rows = new ArrayList<HudRow>();
        // Reserve ordinary header/legend/schedule rows before considering timer rows. The
        // components come only from the current bounded Frame; no cross-frame marker history.
        for (int pass = 0; pass < 2; pass++)
            for (var line : lines) {
                boolean marker = line.getStyle().getClickEvent() != null;
                if (marker != (pass == 1)) continue;
                var wrapped = font.split(line, wrapWidth);
                // Admit a whole logical timer or stop. Its continuation must not hide its XYZ
                // or make hit testing select another marker at the bottom of the viewport.
                if (wrapped.size() > capacity - rows.size()) return rows;
                for (var text : wrapped)
                    rows.add(
                            new HudRow(
                                    8,
                                    8 + rows.size() * pitch,
                                    font.width(text),
                                    1,
                                    text,
                                    line.getStyle()));
            }
        return rows;
    }

    /** One bounded layout for drawing and chat clicks; never cached across worlds or frames. */
    static List<HudRow> hudRows(Minecraft mc) {
        var view = SpawnVisualizationClient.VIEW;
        if (mc.level == null
                || mc.level != SpawnVisualizationClient.owner
                || !view.enabled()
                || mc.options.hideGui
                || mc.getDebugOverlay().showDebugScreen()
                || (mc.screen != null && !(mc.screen instanceof ChatScreen))) return List.of();
        var frame = view.frame();
        var lines =
                SpawnVisualizationText.lines(
                        frame,
                        view.estimatedServerTick(mc.level.getGameTime()),
                        view.fresh(SpawnVisualizationClient.tick),
                        SpawnVisualizationShaders.fields != null,
                        cameraPosition(mc),
                        id -> {
                            var info =
                                    mc.getConnection() == null
                                            ? null
                                            : mc.getConnection().getPlayerInfo(id);
                            return info == null
                                    ? id.toString().substring(0, 8)
                                    : info.getProfile().getName();
                        });
        return layoutHud(
                mc.font,
                lines,
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight());
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        var graphics = event.getGuiGraphics();
        var rows = hudRows(mc);
        if (rows.isEmpty()) return;
        Style hovered = null;
        double mouseX =
                mc.mouseHandler.xpos()
                        * mc.getWindow().getGuiScaledWidth()
                        / mc.getWindow().getScreenWidth();
        double mouseY =
                mc.mouseHandler.ypos()
                        * mc.getWindow().getGuiScaledHeight()
                        / mc.getWindow().getScreenHeight();
        for (var row : rows) {
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(row.x, row.y, 0);
                graphics.pose().scale(row.scale, row.scale, 1);
                graphics.fill(
                        -1,
                        -1,
                        (int) Math.ceil(row.width / row.scale) + 1,
                        mc.font.lineHeight - 1,
                        0x90505050);
                graphics.drawString(
                        mc.font, row.text, 0, 0, SpawnVisualizationText.VALUE_COLOR, false);
            } finally {
                graphics.pose().popPose();
            }
            if (mc.screen instanceof ChatScreen
                    && row.style.getClickEvent() != null
                    && row.contains(mouseX, mouseY, mc.font.lineHeight)) hovered = row.style;
        }
        if (hovered != null)
            graphics.renderComponentHoverEffect(mc.font, hovered, (int) mouseX, (int) mouseY);
    }

    @SubscribeEvent
    public static void chatClick(ScreenEvent.MouseButtonPressed.Pre event) {
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof ChatScreen)
                || event.getScreen() != mc.screen
                || event.getButton() != 0) return;
        if (copyHud(
                (ChatScreen) mc.screen,
                hudRows(mc),
                event.getMouseX(),
                event.getMouseY(),
                mc.font.lineHeight)) event.setCanceled(true);
    }

    static boolean copyHud(ChatScreen chat, List<HudRow> rows, double x, double y, int lineHeight) {
        for (var row : rows)
            if (row.style.getClickEvent() != null
                    && row.contains(x, y, lineHeight)
                    && chat.handleComponentClicked(row.style)) return true;
        return false;
    }

    private static Vec3 cameraPosition(Minecraft mc) {
        return mc.gameRenderer.getMainCamera().getPosition();
    }

    /** Preserve the surrounding world's state, including upload bindings and shader program. */
    static final class State implements AutoCloseable {
        final int activeTexture = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        final int texture0;
        final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int[] viewport = new int[4];
        final float[] clearColor = new float[4];
        final double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        final float lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        final float shaderLineWidth = RenderSystem.getShaderLineWidth();

        State() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
            RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            RenderSystem.activeTexture(activeTexture);
        }

        final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        final boolean mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int src = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                dst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final int equation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),
                equationA = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                buffer = GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);

        @Override
        public void close() {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            RenderSystem.clearDepth(clearDepth);
            GL11.glLineWidth(lineWidth);
            RenderSystem.lineWidth(shaderLineWidth);
            RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(texture0);
            RenderSystem.activeTexture(activeTexture);
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            RenderSystem.depthMask(mask);
            RenderSystem.blendFuncSeparate(src, dst, srcA, dstA);
            RenderSystem.blendEquation(equation);
            if (equation != equationA) GL20.glBlendEquationSeparate(equation, equationA);
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, buffer);
            GlStateManager._glUseProgram(program);
        }
    }
}
