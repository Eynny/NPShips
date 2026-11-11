package com.eynny.npships.client.editor.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.LinkedHashMap;
import java.util.SequencedMap;

/** Screen-owned native callback boundary; neither a world sandbox nor a placement transaction. */
public final class PreviewRendererScope implements AutoCloseable {
    /** Explicit one-shot capture reuses the tested full recovery boundary, also on success. */
    public static AutoCloseable preserveState() {
        return new State().capture();
    }

    public static final class RecoveryFailure extends RuntimeException {
        private RecoveryFailure(String message, Throwable original) {
            super(message, original);
        }
    }

    @FunctionalInterface
    public interface Draw {
        void render(PoseStack pose, MultiBufferSource buffers) throws Exception;
    }

    private ByteBufferBuilder arena;
    private final SequencedMap<RenderType, ByteBufferBuilder> fixedBuffers = new LinkedHashMap<>();
    private MultiBufferSource.BufferSource buffers;
    private int reported;
    private final State state = new State();
    private PoseStack detached;
    private boolean rendering, closeRequested;

    public void render(PoseStack caller, MultiBufferSource.BufferSource destination, Draw draw)
            throws Exception {
        render(caller, destination, draw, false);
    }

    /**
     * General addon boundary: restore captured state after success as well as failure. This keeps
     * one native capture and the same private pose/output ownership as the existing renderer path.
     * Already drawn pixels and arbitrary foreign global side effects are not rolled back.
     */
    public void renderIsolated(
            PoseStack caller, MultiBufferSource.BufferSource destination, Draw draw)
            throws Exception {
        try {
            render(caller, destination, draw, true);
        } catch (Exception | LinkageError failure) {
            rethrowFatal(failure);
            if (!state.restored)
                throw new RecoveryFailure("Extension preview state recovery failed", failure);
            throw failure;
        }
    }

    private void render(
            PoseStack caller,
            MultiBufferSource.BufferSource destination,
            Draw draw,
            boolean restoreOnSuccess)
            throws Exception {
        if (rendering) throw new IllegalStateException("Reentrant preview renderer callback");
        rendering = true;
        state.restored = false;
        Throwable original = null;
        try {
            // Do not discard healthy geometry queued before this callback, or reorder its draw.
            destination.endBatch();
            if (arena == null) {
                arena = new ByteBufferBuilder(16 * 1024);
                // Native foil consumers keep both glint and base consumers alive simultaneously.
                // Match RenderBuffers' fixed glint ownership, including our native layer wrappers.
                for (var type :
                        java.util.List.of(
                                RenderType.armorEntityGlint(),
                                RenderType.glint(),
                                RenderType.glintTranslucent(),
                                RenderType.entityGlint(),
                                RenderType.entityGlintDirect())) {
                    for (var layer :
                            java.util.List.of(
                                    type, PreviewRenderType.of(type), GhostRenderType.of(type)))
                        fixedBuffers.put(layer, new ByteBufferBuilder(layer.bufferSize()));
                }
                buffers = MultiBufferSource.immediateWithBuffers(fixedBuffers, arena);
            }
            if (detached == null || !detached.clear()) detached = new PoseStack();
            else detached.setIdentity();
            detached.mulPose(caller.last().pose());
            detached.last().normal().set(caller.last().normal());
            try (var state = this.state.capture();
                    var output = new Output();
                    var frozen = PreviewAnimation.freeze()) {
                draw.render(detached, buffers);
                if (!closeRequested) {
                    buffers.endBatch();
                    output.complete = true;
                }
                state.complete = !restoreOnSuccess && !closeRequested;
            }
        } catch (Exception | Error failure) {
            original = failure;
            throw failure;
        } finally {
            rendering = false;
            if (closeRequested) {
                closeRequested = false;
                try {
                    close();
                } catch (RuntimeException | Error cleanup) {
                    if (original == null) throw cleanup;
                    original.addSuppressed(cleanup);
                }
            }
        }
    }

    private final class Output implements AutoCloseable {
        boolean complete;

        @Override
        public void close() {
            // BufferSource has no vertex checkpoint. Closing our arena also discards an unfinished
            // vertex without calling BufferBuilder.build(), which could itself throw. Native layer
            // switches/direct GL may already have drawn pixels; those cannot be rolled back here.
            if (!complete) PreviewRendererScope.this.close();
        }
    }

    public String failure(String phase, Object type, Object target, Exception failure) {
        rethrowFatal(failure);
        Throwable cause = failure;
        for (int i = 0; i < 16 && cause.getCause() != null && cause.getCause() != cause; i++)
            cause = cause.getCause();
        String message =
                bounded(phase, 64)
                        + " | "
                        + bounded(type, 160)
                        + " | "
                        + bounded(target, 96)
                        + " | "
                        + bounded(
                                cause.getClass().getSimpleName() + ": " + cause.getMessage(), 384);
        if (reported++ < 4)
            LogUtils.getLogger()
                    .warn(
                            "[NPShips editor] {} (recovery failures: {})",
                            message,
                            failure.getSuppressed().length);
        else if (reported == 5)
            LogUtils.getLogger()
                    .warn(
                            "[NPShips editor] Further renderer failures in this preview are suppressed");
        if (!state.restored)
            throw new RecoveryFailure("Preview recovery failed: " + message, failure);
        return message;
    }

    private static String bounded(Object value, int limit) {
        String text = String.valueOf(value);
        if (text.length() > limit) text = text.substring(0, limit) + "...";
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private static void rethrowFatal(Throwable failure) {
        var pending = new java.util.ArrayDeque<Throwable>();
        pending.add(failure);
        for (int inspected = 0; inspected < 32 && !pending.isEmpty(); inspected++) {
            var next = pending.removeFirst();
            if (next instanceof VirtualMachineError fatal) throw fatal;
            if (next instanceof ThreadDeath fatal) throw fatal;
            if (next.getCause() != null && next.getCause() != next) pending.add(next.getCause());
            var suppressed = next.getSuppressed();
            for (int i = 0; i < Math.min(4, suppressed.length); i++) pending.add(suppressed[i]);
        }
    }

    @Override
    public void close() {
        if (rendering) {
            closeRequested = true;
            return;
        }
        buffers = null;
        detached = null;
        var owned = arena;
        arena = null;
        RuntimeException failure = null;
        var allocations = new java.util.ArrayList<>(fixedBuffers.values());
        fixedBuffers.clear();
        if (owned != null) allocations.add(owned);
        for (var allocation : allocations) {
            try {
                allocation.close();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    /**
     * One capture: legacy success skips repair; isolated callbacks always restore captured state.
     */
    private static final class State implements AutoCloseable {
        boolean complete;
        boolean restored = true;
        boolean depth, depthMask, blend, cull, scissor, polygonOffset;
        int depthFunc,
                src,
                dst,
                srcAlpha,
                dstAlpha,
                equation,
                equationAlpha,
                cullFace,
                frontFace,
                readFramebuffer,
                drawFramebuffer,
                program,
                vao,
                arrayBuffer,
                activeTexture;
        float offsetFactor, offsetUnits, lineWidth;
        final int[] viewport = new int[4], box = new int[4], mask = new int[4];
        final int[] textures = new int[GlStateManager.TEXTURE_COUNT];
        final int[] shaderTextures = new int[GlStateManager.TEXTURE_COUNT];
        final float[] color = new float[4];
        final float[] fogColor = new float[4];
        float fogStart, fogEnd;
        com.mojang.blaze3d.shaders.FogShape fogShape;
        final Matrix4f projection = new Matrix4f(),
                modelView = new Matrix4f(),
                modelViewTop = new Matrix4f(),
                textureMatrix = new Matrix4f();
        VertexSorting sorting;
        ShaderInstance shader;

        State capture() {
            complete = false;
            depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            polygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
            depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            src = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            dst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            equation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
            readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
            offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
            lineWidth = RenderSystem.getShaderLineWidth();
            fogStart = RenderSystem.getShaderFogStart();
            fogEnd = RenderSystem.getShaderFogEnd();
            fogShape = RenderSystem.getShaderFogShape();
            sorting = RenderSystem.getVertexSorting();
            shader = RenderSystem.getShader();
            System.arraycopy(RenderSystem.getShaderColor(), 0, color, 0, 4);
            System.arraycopy(RenderSystem.getShaderFogColor(), 0, fogColor, 0, 4);
            projection.set(RenderSystem.getProjectionMatrix());
            modelView.set(RenderSystem.getModelViewMatrix());
            modelViewTop.set(RenderSystem.getModelViewStack());
            textureMatrix.set(RenderSystem.getTextureMatrix());
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
            GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < textures.length; i++) {
                shaderTextures[i] = RenderSystem.getShaderTexture(i);
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            RenderSystem.activeTexture(activeTexture);
            return this;
        }

        @Override
        public void close() {
            try {
                if (complete) {
                    restored = true;
                    return;
                }
                // Invalidate native upload/shader caches before restoring their corresponding GL
                // state.
                BufferUploader.invalidate();
                var changedShader = RenderSystem.getShader();
                if (changedShader != null) changedShader.clear();
                RenderSystem.setShader(() -> shader);
                GlStateManager._glUseProgram(program);
                GlStateManager._glBindVertexArray(vao);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                RenderSystem.enableScissor(box[0], box[1], box[2], box[3]);
                if (!scissor) RenderSystem.disableScissor();
                RenderSystem.depthFunc(depthFunc);
                RenderSystem.depthMask(depthMask);
                if (depth) RenderSystem.enableDepthTest();
                else RenderSystem.disableDepthTest();
                GL11.glDepthFunc(depthFunc);
                GL11.glDepthMask(depthMask);
                enabled(GL11.GL_DEPTH_TEST, depth);
                RenderSystem.blendFuncSeparate(src, dst, srcAlpha, dstAlpha);
                RenderSystem.blendEquation(equation);
                GL14.glBlendFuncSeparate(src, dst, srcAlpha, dstAlpha);
                GL20.glBlendEquationSeparate(equation, equationAlpha);
                if (blend) RenderSystem.enableBlend();
                else RenderSystem.disableBlend();
                enabled(GL11.GL_BLEND, blend);
                GL11.glCullFace(cullFace);
                GL11.glFrontFace(frontFace);
                if (cull) RenderSystem.enableCull();
                else RenderSystem.disableCull();
                enabled(GL11.GL_CULL_FACE, cull);
                enabled(GL11.GL_SCISSOR_TEST, scissor);
                RenderSystem.polygonOffset(offsetFactor, offsetUnits);
                if (polygonOffset) RenderSystem.enablePolygonOffset();
                else RenderSystem.disablePolygonOffset();
                enabled(GL11.GL_POLYGON_OFFSET_FILL, polygonOffset);
                RenderSystem.colorMask(mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
                GL11.glColorMask(mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
                RenderSystem.lineWidth(lineWidth);
                RenderSystem.setProjectionMatrix(projection, sorting);
                RenderSystem.getModelViewStack().set(modelViewTop);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.getModelViewMatrix().set(modelView);
                RenderSystem.setTextureMatrix(textureMatrix);
                RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
                RenderSystem.setShaderFogStart(fogStart);
                RenderSystem.setShaderFogEnd(fogEnd);
                RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
                RenderSystem.setShaderFogShape(fogShape);
                for (int i = 0; i < textures.length; i++) {
                    RenderSystem.setShaderTexture(i, shaderTextures[i]);
                    RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                    RenderSystem.bindTexture(textures[i]);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
                }
                RenderSystem.activeTexture(activeTexture);
                GL13.glActiveTexture(activeTexture);
                restored = true;
            } finally {
                shader = null;
            }
        }

        private static void enabled(int flag, boolean enabled) {
            // A third-party renderer may bypass GlStateManager. Match its cache AND actual GL.
            if (enabled) GL11.glEnable(flag);
            else GL11.glDisable(flag);
        }
    }
}
