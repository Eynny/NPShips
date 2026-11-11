package com.eynny.npships.client.editor.widget;

import com.eynny.npships.config.NPShipsEditorConfig;

import net.minecraft.client.gui.GuiGraphics;

/** Shared palette and native widget presentation; appearance only. */
public final class EditorButtonTheme {
    public enum Palette {
        DEFAULT(0x354352, 0x647586, 0x25313e, 0x779daa, 0x1b293c, 0x2b4057, 0x234f78),
        SLATE(0x41474e, 0x707981, 0x30363d, 0x99aab3, 0x252c35, 0x3b4653, 0x394957),
        BLUE(0x30485a, 0x5d8097, 0x243746, 0x79bed1, 0x192e43, 0x2c4b68, 0x235d88),
        WARM(0x49433e, 0x807469, 0x35302d, 0xc2a582, 0x342a25, 0x504137, 0x594939),
        MINT(0x344842, 0x648779, 0x27352f, 0x8abbab, 0x20372f, 0x355447, 0x326452),
        LOW_CONTRAST(0x39434e, 0x52606c, 0x2f3943, 0x8195a5, 0x2b3542, 0x3c4b5c, 0x3e536c),
        MIDNIGHT(0x242938, 0x586485, 0x141822, 0x9daee6, 0x101421, 0x1e273b, 0x192b46),
        PLUM(0x493a50, 0x88738f, 0x2a2130, 0xd8a1d3, 0x30223a, 0x4d365a, 0x533f67),
        FOREST(0x2c4131, 0x6a8766, 0x18291f, 0xa5c88b, 0x202f23, 0x354b36, 0x375d3e),
        CRIMSON(0x613d46, 0xa57580, 0x36222a, 0xee9da2, 0x33212b, 0x51313f, 0x633442),
        AMBER(0x63543a, 0xa99a6b, 0x393222, 0xf1d375, 0x302d20, 0x4e4630, 0x62572e),
        COPPER(0x604638, 0xac8767, 0x39291f, 0xf1b77c, 0x33271f, 0x4f392b, 0x674631),
        SCARLET(0x692b29, 0xae5b50, 0x381b19, 0xff7963, 0x301917, 0x502520, 0x692921),
        OCEAN(0x254d61, 0x568fa2, 0x173440, 0x65d3dd, 0x162d38, 0x254451, 0x1e596c),
        OLIVE(0x4b5130, 0x899363, 0x2b301c, 0xc3cf77, 0x252a1c, 0x3c4529, 0x4a5530),
        VIOLET(0x403663, 0x8473ad, 0x28213d, 0xbda0ff, 0x221b36, 0x362b51, 0x433366);
        final int base, light, dark, accent, background, panel, scene;

        Palette(int base, int light, int dark, int accent, int background, int panel, int scene) {
            this.base = base;
            this.light = light;
            this.dark = dark;
            this.accent = accent;
            this.background = background;
            this.panel = panel;
            this.scene = scene;
        }
    }

    /** The active screen may choose a local appearance without changing stored preferences. */
    public interface PaletteSource {
        Palette editorPalette();
    }

    private static Palette fallback = Palette.DEFAULT;

    public static Palette current() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.screen instanceof PaletteSource owner) {
            var local = owner.editorPalette();
            if (local != null) return local;
        }
        return NPShipsEditorConfig.SPEC.isLoaded()
                ? parse(NPShipsEditorConfig.PALETTE.get())
                : fallback;
    }

    /** Legacy stored SOFT is the unchanged DEFAULT theme; reading never rewrites the file. */
    public static Palette parse(String name) {
        return "SOFT".equals(name) ? Palette.DEFAULT : Palette.valueOf(name);
    }

    public static void use(Palette palette) {
        if (NPShipsEditorConfig.SPEC.isLoaded()) {
            NPShipsEditorConfig.withNativeOwner(
                    () -> {
                        NPShipsEditorConfig.PALETTE.set(palette.name());
                        NPShipsEditorConfig.SPEC.save();
                    });
        }
        fallback = palette;
    }

    public static boolean light(Palette p) {
        return ((p.base >> 16) & 255) > 160;
    }

    public static int text() {
        return text(current());
    }

    public static int text(Palette p) {
        return light(p == null ? current() : p) ? 0xff202b37 : 0xffedf3f8;
    }

    public static int background() {
        return 0xff000000 | current().background;
    }

    public static int panel() {
        return 0xff000000 | current().panel;
    }

    public static int scene() {
        return 0xff000000 | current().scene;
    }

    public static int edge() {
        return 0xff000000 | current().light;
    }

    /** Popup borders remain distinguishable from their panel under every palette. */
    public static int popupEdge() {
        return mix(edge(), text(), .35f);
    }

    public static int muted() {
        return mix(text(), panel(), .35f);
    }

    public static int accent() {
        return 0xff000000 | current().accent;
    }

    public static void widgets(net.minecraft.client.gui.screens.Screen screen) {
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractStringWidget label)
                label.setColor(text());
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget)
                meaningfulTooltip(widget);
        }
    }

    public static void meaningfulTooltip(
            net.minecraft.client.gui.components.AbstractWidget widget) {
        if (widget instanceof EditorIconButton || widget.getTooltip() == null) return;
        if (widget instanceof EditorButton button && button.ownsOverflowTooltip()) return;
        var text = new StringBuilder();
        for (var line :
                widget.getTooltip().toCharSequence(net.minecraft.client.Minecraft.getInstance()))
            line.accept(
                    (index, style, code) -> {
                        text.appendCodePoint(code);
                        return true;
                    });
        if (sameTooltipText(text, widget.getMessage().getString())) widget.setTooltip(null);
    }

    private static boolean sameTooltipText(CharSequence a, CharSequence b) {
        int left = 0, right = 0;
        while (true) {
            while (left < a.length() && tooltipWhitespace(a.charAt(left))) left++;
            while (right < b.length() && tooltipWhitespace(b.charAt(right))) right++;
            if (left == a.length() || right == b.length())
                return left == a.length() && right == b.length();
            if (a.charAt(left++) != b.charAt(right++)) return false;
        }
    }

    // Java's default regex \s covers these ASCII characters, not all Unicode whitespace.
    private static boolean tooltipWhitespace(char value) {
        return value == ' '
                || value == '\t'
                || value == '\n'
                || value == '\u000b'
                || value == '\f'
                || value == '\r';
    }

    /** Neutral UI surfaces follow the palette; semantic warning/selection colours are unchanged. */
    public static int surface(int original) {
        int alpha = original & 0xff000000;
        int rgb = original & 0xffffff;
        float luma = (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) / 765f;
        return alpha
                | (mix(
                                current().background,
                                current().light,
                                Math.min(.85f, Math.max(0, (luma - .06f) * 1.5f)))
                        & 0xffffff);
    }

    public static final class Fade {
        private long previous;
        private float value;

        float hover(boolean on) {
            long now = net.minecraft.Util.getMillis();
            float step = Math.min(1, (now - previous) / 120f);
            previous = now;
            value = on ? Math.min(1, value + step) : Math.max(0, value - step);
            return value;
        }
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t),
                g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t),
                c = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | c;
    }

    public static void draw(
            GuiGraphics g,
            int x,
            int y,
            int w,
            int h,
            boolean bevel,
            boolean active,
            boolean down,
            boolean selected,
            float hover,
            Palette palette) {
        var p = palette == null ? current() : palette;
        int base = mix(p.base, p.light, active ? hover * .46f : 0);
        if (selected) base = mix(base, p.accent, .79f);
        if (down) base = mix(base, p.dark, .6f);
        if (!active) base = mix(base, p.dark, .6f);
        int edge = mix(base, selected ? p.accent : p.light, selected ? .85f : .38f + hover * .16f);
        g.fill(x, y, x + w, y + h, edge);
        g.fillGradient(
                x + 1,
                y + 1,
                x + w - 1,
                y + h - 1,
                mix(base, p.light, .10f),
                mix(base, p.dark, .16f));
        if (bevel) {
            int top = mix(base, down ? p.dark : p.light, active ? .50f : .2f),
                    bottom = mix(base, down ? p.light : p.dark, .55f);
            g.fill(x + 1, y, x + w - 1, y + 1, top);
            g.fill(x, y + 1, x + 1, y + h - 1, top);
            g.fill(x + 1, y + h - 1, x + w - 1, y + h, bottom);
            g.fill(x + w - 1, y + 1, x + w, y + h - 1, bottom);
        }
    }
}
