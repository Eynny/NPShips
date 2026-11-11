package com.eynny.npships.client.editor.screen;

import com.eynny.npships.client.editor.widget.EditorButton;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/** Use the same flat actions in editor confirmation dialogs as in their parent. */
public final class EditorConfirmScreen extends ConfirmScreen {
    public EditorConfirmScreen(BooleanConsumer callback, Component title, Component message) {
        super(callback, title, message);
    }

    public EditorConfirmScreen(
            BooleanConsumer callback,
            Component title,
            Component message,
            Component yes,
            Component no) {
        super(callback, title, message, yes, no);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        for (var child : java.util.List.copyOf(children()))
            if (child instanceof Button old) {
                removeWidget(old);
                var button =
                        addRenderableWidget(
                                EditorButton.flat(old.getMessage(), b -> old.onPress())
                                        .bounds(
                                                old.getX(),
                                                old.getY(),
                                                old.getWidth(),
                                                old.getHeight())
                                        .build());
                button.active = old.active;
            }
    }
}
