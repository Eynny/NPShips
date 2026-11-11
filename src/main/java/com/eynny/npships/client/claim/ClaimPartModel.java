package com.eynny.npships.client.claim;

import com.eynny.npships.block.ClaimBlock;
import com.mojang.math.Transformation;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.QuadTransformers;
import net.neoforged.neoforge.client.model.data.ModelData;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** World-aligned quads let the block lighting pipeline sample the correct neighboring faces. */
final class ClaimPartModel extends BakedModelWrapper<BakedModel> {
    private final List<BakedQuad> quads;
    private final List<BakedQuad> glow;
    private net.createmod.catnip.render.SuperByteBuffer worldMesh;

    ClaimPartModel(BakedModel source, BlockState state) {
        super(source);
        var rotation = ClaimBlock.orientation(state);
        var transform =
                QuadTransformers.applying(
                        new Transformation(
                                new Matrix4f()
                                        .translation(.5f, .5f, .5f)
                                        .rotate(rotation)
                                        .translate(-.5f, -.5f, -.5f)));
        var result = new ArrayList<BakedQuad>();
        var random = RandomSource.create(42);
        for (int face = 0; face <= 6; face++) {
            random.setSeed(42);
            var side = face == 6 ? null : Direction.from3DDataValue(face);
            for (var quad :
                    source.getQuads(state, side, random, ModelData.EMPTY, RenderType.cutout())) {
                var normal = quad.getDirection().step().rotate(rotation);
                var copy =
                        new BakedQuad(
                                quad.getVertices().clone(),
                                quad.getTintIndex(),
                                Direction.getNearest(normal.x, normal.y, normal.z),
                                quad.getSprite(),
                                quad.isShade(),
                                quad.hasAmbientOcclusion());
                transform.processInPlace(copy);
                retainFaceUvMapping(copy);
                result.add(copy);
            }
        }
        quads = List.copyOf(result);
        glow = ClaimGlowQuads.build(quads);
    }

    private static void retainFaceUvMapping(BakedQuad quad) {
        var vertices = quad.getVertices();
        int stride = vertices.length / 4;
        float centerU = 0, centerV = 0;
        for (int i = 0; i < 4; i++) {
            centerU += Float.intBitsToFloat(vertices[i * stride + 4]) / 4;
            centerV += Float.intBitsToFloat(vertices[i * stride + 5]) / 4;
        }
        var sprite = quad.getSprite();
        float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float u = Float.intBitsToFloat(vertices[i * stride + 4]);
            float v = Float.intBitsToFloat(vertices[i * stride + 5]);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        float width = (maxU - minU) / (sprite.getU1() - sprite.getU0()) * sprite.contents().width();
        float height =
                (maxV - minV) / (sprite.getV1() - sprite.getV0()) * sprite.contents().height();
        if (width > 1.001f && height > 1.001f) {
            var pixels = sprite.contents();
            int left =
                    Math.max(
                            0,
                            (int)
                                    Math.floor(
                                            (minU - sprite.getU0())
                                                    / (sprite.getU1() - sprite.getU0())
                                                    * pixels.width()));
            int right =
                    Math.min(
                            pixels.width(),
                            (int)
                                    Math.ceil(
                                            (maxU - sprite.getU0())
                                                    / (sprite.getU1() - sprite.getU0())
                                                    * pixels.width()));
            int top =
                    Math.max(
                            0,
                            (int)
                                    Math.floor(
                                            (minV - sprite.getV0())
                                                    / (sprite.getV1() - sprite.getV0())
                                                    * pixels.height()));
            int bottom =
                    Math.min(
                            pixels.height(),
                            (int)
                                    Math.ceil(
                                            (maxV - sprite.getV0())
                                                    / (sprite.getV1() - sprite.getV0())
                                                    * pixels.height()));
            for (int y = top; y < bottom; y++)
                for (int x = left; x < right; x++)
                    if (pixels.isTransparent(0, x, y)) {
                        // FaceBakery's proportional UV shrink expands an interior cutout beyond
                        // the geometry meeting it. Restore that face's authored mapping only;
                        // opaque faces and the separate narrow-strip sampling guard stay intact.
                        float scale = 1 / (1 - sprite.uvShrinkRatio());
                        for (int i = 0; i < 4; i++) {
                            float u = Float.intBitsToFloat(vertices[i * stride + 4]);
                            float v = Float.intBitsToFloat(vertices[i * stride + 5]);
                            vertices[i * stride + 4] =
                                    Float.floatToRawIntBits(centerU + (u - centerU) * scale);
                            vertices[i * stride + 5] =
                                    Float.floatToRawIntBits(centerV + (v - centerV) * scale);
                        }
                        return;
                    }
            return;
        }
        float marginU = (sprite.getU1() - sprite.getU0()) / sprite.contents().width() * .02f;
        float marginV = (sprite.getV1() - sprite.getV0()) / sprite.contents().height() * .02f;
        // Native proportional shrink is too small for a one-texel strip at an atlas join.
        // Preserve broad-face mapping and every authored texel; only narrow strips gain a margin.
        for (int i = 0; i < 4; i++) {
            float u = Float.intBitsToFloat(vertices[i * stride + 4]);
            float v = Float.intBitsToFloat(vertices[i * stride + 5]);
            vertices[i * stride + 4] =
                    Float.floatToRawIntBits(
                            u
                                    + Math.copySign(
                                            Math.min(marginU, Math.abs(centerU - u)), centerU - u));
            vertices[i * stride + 5] =
                    Float.floatToRawIntBits(
                            v
                                    + Math.copySign(
                                            Math.min(marginV, Math.abs(centerV - v)), centerV - v));
        }
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return side == null ? quads : List.of();
    }

    @Override
    public List<BakedQuad> getQuads(
            BlockState state,
            Direction side,
            RandomSource random,
            ModelData data,
            RenderType type) {
        return getQuads(state, side, random);
    }

    net.createmod.catnip.render.SuperByteBuffer worldMesh(BlockState state) {
        if (worldMesh == null) {
            var builder = new net.createmod.catnip.render.SuperByteBufferBuilder();
            builder.prepare();
            net.createmod.catnip.client.render.model.BakedModelBufferer.bufferModel(
                    this,
                    net.minecraft.core.BlockPos.ZERO,
                    dev.engine_room.flywheel.lib.model.baked.EmptyVirtualBlockGetter.FULL_DARK,
                    state,
                    null,
                    (net.createmod.catnip.client.render.model.ShadeSeparatedResultConsumer)
                            (type, shaded, data) -> builder.add(data, shaded));
            worldMesh = builder.build();
        }
        return worldMesh;
    }

    List<BakedQuad> glow() {
        return glow;
    }

    boolean wraps(BakedModel source) {
        return originalModel == source;
    }
}
