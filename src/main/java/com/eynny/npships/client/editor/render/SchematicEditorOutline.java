package com.eynny.npships.client.editor.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

/**
 * Depth-tested native hardware lines. The ordinary outline shader pulls geometry toward the camera,
 * exposing the filled cursor profile through the tilted tabletop at a distance. Shape drawing and
 * physical-owner transforms remain with the native renderer.
 */
public final class SchematicEditorOutline extends RenderType {
    private SchematicEditorOutline() {
        super(
                "npships_desk_outline_owner",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.DEBUG_LINES,
                1536,
                false,
                false,
                () -> {},
                () -> {});
    }

    private static final RenderType TYPE =
            create(
                    "npships_desk_outline",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.DEBUG_LINES,
                    1536,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setDepthTestState(
                                    new DepthTestStateShard("<", org.lwjgl.opengl.GL11.GL_LESS))
                            .setLineState(new LineStateShard(OptionalDouble.of(2)))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(ITEM_ENTITY_TARGET)
                            .setWriteMaskState(COLOR_DEPTH_WRITE)
                            .setCullState(NO_CULL)
                            .createCompositeState(false));

    public static RenderType type() {
        return TYPE;
    }
}
