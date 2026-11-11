package com.eynny.npships.client.claim;

import com.eynny.npships.block.ClaimAnimation;
import com.eynny.npships.block.ClaimBlock;
import com.eynny.npships.block.ClaimBlockEntity;
import com.eynny.npships.network.ClaimNetworking;
import com.eynny.npships.registry.NPShipsRegistry;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

@EventBusSubscriber(modid = "npships", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClaimRenderer implements BlockEntityRenderer<ClaimBlockEntity> {
    private static final String[] PARTS = {"base", "column", "cap", "top"};
    private final ClaimPartModel[][] litModels = new ClaimPartModel[16][24];

    private static final String[] COLORS = {"cyan", "green", "off", "red"};

    private static ModelResourceLocation model(String part, String color) {
        return ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(
                        "npships", "block/claim_" + part + "_" + color));
    }

    @SubscribeEvent
    public static void models(ModelEvent.RegisterAdditional event) {
        for (var part : PARTS) for (var color : COLORS) event.register(model(part, color));
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                NPShipsRegistry.CLAIM_BLOCK_ENTITY.get(), context -> new ClaimRenderer());
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ClaimNetworking.client(ClaimWaveRenderer::receive));
    }

    @Override
    public void render(
            ClaimBlockEntity be,
            float partial,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        if (be.getLevel() == null) return;
        double elapsed = be.getLevel().getGameTime() + partial - be.started();
        var offsets = ClaimAnimation.pose(be.animation() == ClaimBlockEntity.STAMP ? elapsed : -1);
        String color =
                !be.physical()
                                || be.animation() == ClaimBlockEntity.BLINK
                                        && ClaimAnimation.blinkOff(elapsed)
                        ? "off"
                        : be.denied() ? "red" : be.retained() ? "green" : "cyan";
        var mc = Minecraft.getInstance();
        pose.pushPose();

        for (int i = 0; i < PARTS.length; i++) {
            pose.pushPose();
            double pixels =
                    switch (i) {
                        case 1 -> offsets.column();
                        case 2 -> offsets.cap();
                        case 3 -> offsets.top();
                        default -> 0;
                    };
            var displacement =
                    new org.joml.Vector3f(0, (float) pixels / 16, 0)
                            .rotate(ClaimBlock.orientation(be.getBlockState()));
            pose.translate(displacement.x, displacement.y, displacement.z);
            var baked = mc.getModelManager().getModel(model(PARTS[i], color));
            int partIndex = i * 4 + java.util.Arrays.asList(COLORS).indexOf(color);
            int orientation =
                    be.getBlockState().getValue(ClaimBlock.FACING).ordinal() * 4
                            + be.getBlockState().getValue(ClaimBlock.TURN);
            var litModel = litModels[partIndex][orientation];
            if (litModel == null || !litModel.wraps(baked)) {
                // Drop all orientations of a replaced resource model on reload.
                if (litModel != null) java.util.Arrays.fill(litModels[partIndex], null);
                litModel = new ClaimPartModel(baked, be.getBlockState());
                litModels[partIndex][orientation] = litModel;
            }
            if (be.getLevel()
                    instanceof com.eynny.npships.client.editor.render.SchematicPreviewLevel) {
                // Match the editor's ordinary block meshes: its virtual level has shade=1,
                // so Catnip applies the camera-space diffuse lighting when emitting the mesh.
                var builder = new net.createmod.catnip.render.SuperByteBufferBuilder();
                builder.prepare();
                net.createmod.catnip.client.render.model.BakedModelBufferer.bufferModel(
                        litModel,
                        be.getBlockPos(),
                        be.getLevel(),
                        be.getBlockState(),
                        null,
                        (net.createmod.catnip.client.render.model.ShadeSeparatedResultConsumer)
                                (type, shaded, data) -> builder.add(data, shaded));
                var mesh = builder.build();
                mesh.light(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                mesh.renderInto(pose, buffers.getBuffer(RenderType.cutout()));
            } else {
                // Use Create's dynamic diffuse path for every installed block, with the light
                // supplied by the dispatcher (including Sable's physical-world correction).
                var mesh = litModel.worldMesh(be.getBlockState());
                mesh.light(light);
                mesh.renderInto(pose, buffers.getBuffer(RenderType.cutout()));
            }
            for (var quad : litModel.glow()) {
                buffers.getBuffer(RenderType.cutout())
                        .putBulkData(
                                pose.last(),
                                quad,
                                1,
                                1,
                                1,
                                1,
                                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                                overlay);
            }
            pose.popPose();
        }
        pose.popPose();
    }
}
