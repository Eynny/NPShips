package com.eynny.npships.mixin;

import com.eynny.npships.client.editor.widget.EditorTooltips;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Bound native and custom tooltips only for NPShips editor screens; preserve other mods' policy.
 */
@Mixin(Screen.class)
public abstract class NPShipsEditorTooltipMixin {
    @ModifyVariable(
            method =
                    "setTooltipForNextRenderPass(Ljava/util/List;Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Z)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 1)
    private ClientTooltipPositioner npships$boundedTooltip(ClientTooltipPositioner positioner) {
        return getClass().getName().startsWith("com.eynny.npships.client.")
                ? EditorTooltips.bounded(positioner)
                : positioner;
    }

    @ModifyVariable(
            method =
                    "setTooltipForNextRenderPass(Ljava/util/List;Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Z)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 1)
    private java.util.List<net.minecraft.util.FormattedCharSequence> npships$fitText(
            java.util.List<net.minecraft.util.FormattedCharSequence> lines) {
        var screen = (Screen) (Object) this;
        return getClass().getName().startsWith("com.eynny.npships.client.")
                ? EditorTooltips.fit(
                        net.minecraft.client.Minecraft.getInstance().font,
                        lines,
                        screen.width,
                        screen.height)
                : lines;
    }
}
