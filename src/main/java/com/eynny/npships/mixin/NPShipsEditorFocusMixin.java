package com.eynny.npships.mixin;

import com.eynny.npships.client.editor.SchematicEditorScreen;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Release at the native focus event, including loss/regain between two rendered frames. */
@Mixin(Minecraft.class)
public abstract class NPShipsEditorFocusMixin {
    @Inject(method = "setWindowActive(Z)V", at = @At("TAIL"), require = 1)
    private void npships$releaseEditorFocus(boolean active, CallbackInfo ci) {
        var client = (Minecraft) (Object) this;
        if (!active && client.screen instanceof SchematicEditorScreen editor)
            editor.consumeFirstPersonMouse(0, 0);
    }
}
