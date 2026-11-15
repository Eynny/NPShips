package com.eynny.npships.mixin;

import com.mojang.blaze3d.systems.RenderSystem;

import net.createmod.ponder.Ponder;
import net.createmod.ponder.enums.PonderKeybinds;
import net.createmod.ponder.foundation.PonderTooltipHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Temporary upstream compatibility workaround for Minecraft 1.21.1, NeoForge 21.1.228, Create
 * 6.0.10-280 and Ponder 1.0.82+mc1.21.1. Background search-tree workers can invoke Ponder's
 * font-dependent tooltip formatting while resource reload replaces/closes native font resources.
 * Return the original instruction without font metrics only on workers; render-thread tooltip
 * behavior remains unchanged. This avoids that caller, not the broader FontManager race.
 *
 * <p>Related tracking: https://github.com/Creators-of-Create/Create/issues/9422 (similar evidence,
 * not proof of an identical root cause). Maintenance contract:
 * docs/NPShipsUpstreamCompatibility.md.
 *
 * <p>TODO(upstream-compat): Remove this worker-thread guard after the upstream Ponder/font
 * lifecycle fix is verified. Review the affected versions and removal regressions before any
 * dependency upgrade.
 */
@Mixin(targets = "net.createmod.ponder.foundation.PonderTooltipHandler", remap = false)
public abstract class NPShipsPonderWorkerTooltipMixin {
    @Inject(
            method = "makeProgressBar(F)Lnet/minecraft/network/chat/Component;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private static void textWithoutFont(float progress, CallbackInfoReturnable<Component> result) {
        if (!RenderSystem.isOnRenderThread()) {
            result.setReturnValue(
                    Ponder.lang()
                            .translate(
                                    PonderTooltipHandler.HOLD_TO_PONDER,
                                    PonderKeybinds.PONDER
                                            .message()
                                            .copy()
                                            .withStyle(ChatFormatting.GRAY))
                            .style(ChatFormatting.DARK_GRAY)
                            .component());
        }
    }
}
