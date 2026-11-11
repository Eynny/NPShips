package com.eynny.npships.client.editor.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Transient native scene readback; no target survives an explicit capture request. */
public final class PhotoCapture {
    public static byte[] render(int size, int background, Consumer<GuiGraphics> draw)
            throws Exception {
        RenderSystem.assertOnRenderThread();
        if (size != 256 && size != 512 && size != 1024)
            throw new IllegalArgumentException("PNG size must be 256, 512 or 1024.");
        var mc = Minecraft.getInstance();
        mc.renderBuffers().bufferSource().endBatch();
        float[] clear = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
        int[] stores = {
            GL11.GL_PACK_ALIGNMENT,
            GL11.GL_PACK_ROW_LENGTH,
            GL11.GL_PACK_SKIP_ROWS,
            GL11.GL_PACK_SKIP_PIXELS
        };
        int[] packing = java.util.Arrays.stream(stores).map(GL11::glGetInteger).toArray();
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        try (var state = PreviewRendererScope.preserveState()) {
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            TextureTarget target = new TextureTarget(size, size, true, Minecraft.ON_OSX);
            try (AutoCloseable gpu = target::destroyBuffers;
                    var arena = new ByteBufferBuilder(16 * 1024)) {
                RenderSystem.disableScissor();
                RenderSystem.depthMask(true);
                RenderSystem.colorMask(true, true, true, true);
                target.setClearColor(
                        (background >> 16 & 255) / 255f,
                        (background >> 8 & 255) / 255f,
                        (background & 255) / 255f,
                        1);
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                var graphics = new GuiGraphics(mc, MultiBufferSource.immediate(arena));
                draw.accept(graphics);
                graphics.flush();
                // NativeImage.downloadTexture sets alignment only. A foreign pack stride/offset
                // must not redirect this tightly allocated native image or its readback address.
                GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                for (int store : stores)
                    GlStateManager._pixelStore(store, store == GL11.GL_PACK_ALIGNMENT ? 4 : 0);
                try (var image = Screenshot.takeScreenshot(target)) {
                    return image.asByteArray();
                }
            }
        } finally {
            for (int i = 0; i < stores.length; i++)
                GlStateManager._pixelStore(stores[i], packing[i]);
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            RenderSystem.clearColor(clear[0], clear[1], clear[2], clear[3]);
        }
    }

    /** Only publish a complete PNG. Never overwrite an existing file or follow its link. */
    public static void saveNew(Path chosen, byte[] png) throws IOException {
        if (!chosen.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
            throw new IOException("Choose a .png filename.");
        if (png.length > 8 * 1024 * 1024) throw new IOException("PNG exceeds 8 MiB.");
        Path destination = chosen.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(destination.getParent(), ".npships-photo-", ".png");
        try {
            Files.write(temporary, png);
            Files.move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private PhotoCapture() {}
}
