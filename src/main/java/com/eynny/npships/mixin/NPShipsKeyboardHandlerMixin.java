package com.eynny.npships.mixin;

import com.eynny.npships.client.editor.EditorKeyboardInput;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.KeyboardHandler;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(KeyboardHandler.class)
public abstract class NPShipsKeyboardHandlerMixin {
    @WrapMethod(method = "keyPress")
    private void npships$keyboardAction(
            long window, int key, int scan, int action, int modifiers, Operation<Void> original) {
        // Includes native/mod early returns; no GLFW callback replacement or extra dispatch.
        try (var scope = EditorKeyboardInput.enter(window, key, scan, action, modifiers)) {
            original.call(window, key, scan, action, modifiers);
        }
    }
}
