package com.eynny.npships.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import org.lwjgl.glfw.GLFW;

/** Invocation-local provenance only; commands remain owned by the current Screen. */
public final class EditorKeyboardInput {
    private static final ThreadLocal<Event> CURRENT = new ThreadLocal<>();

    private EditorKeyboardInput() {}

    public record Event(Screen screen, long window, int key, int scan, int action, int modifiers) {}

    public static Scope enter(long window, int key, int scan, int action, int modifiers) {
        var previous = CURRENT.get();
        CURRENT.set(
                new Event(Minecraft.getInstance().screen, window, key, scan, action, modifiers));
        return new Scope(previous);
    }

    public record Scope(Event previous) implements AutoCloseable {
        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static Event event(Screen screen, int key, int scan, int modifiers) {
        var e = CURRENT.get();
        return e != null
                        && e.screen == screen
                        && e.key == key
                        && e.scan == scan
                        && e.modifiers == modifiers
                        && e.window == Minecraft.getInstance().getWindow().getWindow()
                ? e
                : null;
    }

    static boolean control(int modifiers) {
        return (modifiers & (Minecraft.ON_OSX ? GLFW.GLFW_MOD_SUPER : GLFW.GLFW_MOD_CONTROL)) != 0;
    }
}
