package com.eynny.npships.mixin;

import com.eynny.npships.client.editor.TextInputHistory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.*;

/** Native multiline fields outside the shared NPShips widget own one bounded text history. */
@Mixin(MultiLineEditBox.class)
public abstract class NPShipsMultilineHistoryMixin extends AbstractScrollWidget {
    @Shadow @Final private MultilineTextField textField;

    protected NPShipsMultilineHistoryMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Unique private TextInputHistory npships$history;

    @Unique
    private boolean npships$owns() {
        var box = (MultiLineEditBox) (Object) this;
        return box.isFocused() && box.active && box.visible && TextInputHistory.owns(this);
    }

    @Unique
    private TextInputHistory npships$history() {
        if (npships$history == null) npships$history = new TextInputHistory();
        return npships$history;
    }

    @Unique
    private TextInputHistory.Snapshot npships$snapshot() {
        return new TextInputHistory.Snapshot(
                textField.value(),
                textField.cursor(),
                ((NPShipsMultilineWidthAccessor) (Object) textField).npships$selectionCursor(),
                scrollAmount());
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
            var h = npships$history();
            var value =
                    h.take(
                            key == GLFW.GLFW_KEY_Y || (modifiers & GLFW.GLFW_MOD_SHIFT) != 0,
                            npships$snapshot());
            if (value != null) {
                h.restoring = true;
                try {
                    textField.setValue(value.text());
                    textField.setSelecting(false);
                    textField.seekCursor(Whence.ABSOLUTE, value.anchor());
                    textField.setSelecting(true);
                    textField.seekCursor(Whence.ABSOLUTE, value.cursor());
                    textField.setSelecting(false);
                    if (!Double.isNaN(value.scroll())) setScrollAmount(value.scroll());
                    var box = (MultiLineEditBox) (Object) this;
                    setScrollAmount(
                            TextInputHistory.visibleLineScroll(
                                    scrollAmount(),
                                    textField.getLineAtCursor(),
                                    9,
                                    box.getHeight(),
                                    8));
                } finally {
                    h.restoring = false;
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

    @WrapMethod(method = "setValue")
    private void npships$value(String value, Operation<Void> original) {
        var before = npships$begin();
        try {
            original.call(value);
        } finally {
            npships$end(before);
        }
    }
}
