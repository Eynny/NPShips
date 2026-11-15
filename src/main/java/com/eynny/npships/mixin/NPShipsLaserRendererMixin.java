package com.eynny.npships.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserBlockEntity;
import dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserRenderer;

import net.minecraft.client.renderer.MultiBufferSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide only the beam renderer; server raycasts and the static block model stay active. */
@org.spongepowered.asm.mixin.Pseudo
@Mixin(
        targets = "dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserRenderer",
        remap = false)
public abstract class NPShipsLaserRendererMixin {
    @org.spongepowered.asm.mixin.Unique
    private static final net.minecraft.resources.ResourceLocation npships$styleProvider =
            net.minecraft.resources.ResourceLocation.parse("npships:simulated_devices");

    @org.spongepowered.asm.mixin.injection.Redirect(
            method =
                    "renderSafe(Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserRenderer;getColors(Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserBlockEntity;F)Lorg/joml/Vector4f;"),
            remap = false)
    private org.joml.Vector4f npships$beamColor(
            AbstractLaserRenderer<AbstractLaserBlockEntity> renderer,
            AbstractLaserBlockEntity block,
            float partial) {
        var original = renderer.getColors(block, partial);
        var style = com.eynny.npships.client.editor.NativeRenderStyles.get(npships$styleProvider);
        return style == null ? original : style.color(block, original);
    }

    @Inject(
            method =
                    "renderSafe(Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void npships$hideBeam(
            AbstractLaserBlockEntity block,
            float partial,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay,
            CallbackInfo ci) {
        var style = com.eynny.npships.client.editor.NativeRenderStyles.get(npships$styleProvider);
        if (style != null && style.hidden(block)) ci.cancel();
    }
}
