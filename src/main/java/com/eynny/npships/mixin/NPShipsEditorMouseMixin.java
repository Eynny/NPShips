package com.eynny.npships.mixin;

import com.eynny.npships.client.editor.SchematicEditorScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep native MouseHandler capture; only the requesting editor replaces native player look. */
@Mixin(MouseHandler.class)
public abstract class NPShipsEditorMouseMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Redirect(
            method = "grabMouse()V",
            require = 1,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void npships$keepEditor(Minecraft client, Screen next) {
        if (client.screen instanceof SchematicEditorScreen editor
                && editor.retainsFirstPersonMouse()) return;
        client.setScreen(next);
    }

    @Inject(method = "handleAccumulatedMovement()V", at = @At("HEAD"), require = 1)
    private void npships$editorLook(CallbackInfo ci) {
        var client = Minecraft.getInstance();
        if (client.screen instanceof SchematicEditorScreen editor
                && editor.consumeFirstPersonMouse(accumulatedDX, accumulatedDY)) {
            // Native Screen mouseMoved/drag and player turn must not consume the same delta.
            accumulatedDX = 0;
            accumulatedDY = 0;
        }
    }

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void npships$keepPlayerLook(double elapsed, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof SchematicEditorScreen editor
                && editor.retainsFirstPersonMouse()) ci.cancel();
    }
}
