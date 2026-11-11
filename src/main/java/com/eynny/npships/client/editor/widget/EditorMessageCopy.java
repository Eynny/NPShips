package com.eynny.npships.client.editor.widget;

import net.minecraft.client.Minecraft;

/** Copies the untruncated message, without consuming clicks outside its displayed row. */
public final class EditorMessageCopy {
    private EditorMessageCopy() {}

    public static boolean click(
            Minecraft client,
            String message,
            double x,
            double y,
            int button,
            int left,
            int top,
            int width,
            int height) {
        if (button != 0
                || client == null
                || message == null
                || message.isEmpty()
                || x < left
                || x >= left + width
                || y < top
                || y >= top + height) return false;
        client.keyboardHandler.setClipboard(message);
        return true;
    }
}
