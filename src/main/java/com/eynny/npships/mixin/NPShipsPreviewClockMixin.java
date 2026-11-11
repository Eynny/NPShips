package com.eynny.npships.mixin;

import com.eynny.npships.client.editor.render.PreviewAnimation;

import net.createmod.catnip.animation.AnimationTickHolder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AnimationTickHolder.class, remap = false)
public abstract class NPShipsPreviewClockMixin {
    @Inject(
            method = {"getRenderTime()F", "getPartialTicks()F", "getPartialTicksUI()F"},
            at = @At("HEAD"),
            cancellable = true)
    private static void npships$staticFloat(CallbackInfoReturnable<Float> cir) {
        if (PreviewAnimation.frozen()) cir.setReturnValue(0f);
    }

    @Inject(method = "getTicks(Z)I", at = @At("HEAD"), cancellable = true)
    private static void npships$staticTicks(boolean paused, CallbackInfoReturnable<Integer> cir) {
        if (PreviewAnimation.frozen()) cir.setReturnValue(0);
    }
}
