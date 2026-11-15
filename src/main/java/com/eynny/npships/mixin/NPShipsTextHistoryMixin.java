package com.eynny.npships.mixin;

import com.eynny.npships.client.editor.TextInputHistory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** One native field owns its own undo; unrelated screens and document history stay untouched. */
@Mixin(EditBox.class)
public abstract class NPShipsTextHistoryMixin {
    @Shadow private int cursorPos, highlightPos;
    @Shadow private boolean isEditable;
    @Unique private TextInputHistory npships$history;

    @Unique
    private EditBox npships$box() {
        return (EditBox) (Object) this;
    }

    @Unique
    private boolean npships$owns() {
        return npships$box().isFocused()
                && npships$box().active
                && npships$box().visible
                && isEditable
                && TextInputHistory.owns(this);
    }

    @Unique
    private TextInputHistory npships$history() {
        if (npships$history == null) npships$history = new TextInputHistory();
        return npships$history;
    }

    @Unique
    private TextInputHistory.Snapshot npships$snapshot() {
        return new TextInputHistory.Snapshot(npships$box().getValue(), cursorPos, highlightPos);
    }

    @Unique
    private TextInputHistory.Frame npships$begin() {
        return npships$owns() ? npships$history().begin(npships$snapshot()) : null;
    }

    @Unique
    private void npships$end(TextInputHistory.Frame before) {
        if (npships$history != null) npships$history.end(before, npships$snapshot());
    }

    @WrapMethod(method = "keyPressed")
    private boolean npships$key(int key, int scan, int modifiers, Operation<Boolean> original) {
        if (npships$owns()
                && ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || Screen.hasControlDown())
                && (key == GLFW.GLFW_KEY_Z || key == GLFW.GLFW_KEY_Y)) {
            var history = npships$history();
            var value =
                    history.take(
                            key == GLFW.GLFW_KEY_Y || (modifiers & GLFW.GLFW_MOD_SHIFT) != 0,
                            npships$snapshot());
            if (value != null) {
                history.restoring = true;
                try {
                    npships$box().setValue(value.text());
                    npships$box().setCursorPosition(value.cursor());
                    npships$box().setHighlightPos(value.anchor());
                } finally {
                    history.restoring = false;
                }
            }
            return true;
        }
        var before = npships$begin();
        try {
            return original.call(key, scan, modifiers);
        } finally {
            npships$end(before);
        }
    }

    @WrapMethod(method = "charTyped")
    private boolean npships$character(char ch, int modifiers, Operation<Boolean> original) {
        var before = npships$begin();
        try {
            return original.call(ch, modifiers);
        } finally {
            npships$end(before);
        }
    }

    @WrapMethod(method = {"setValue", "insertText"})
    private void npships$value(String value, Operation<Void> original) {
        var before = npships$begin();
        try {
            original.call(value);
        } finally {
            npships$end(before);
        }
    }
}
