package com.eynny.npships.client.editor;

import com.eynny.npships.client.editor.widget.EditorButtonTheme;

import java.util.Arrays;

/** Lexical colouring only; invalid/incomplete SNBT stays editable. */
final class SnbtCodeSyntax {
    static final int HELP_BACKGROUND = 0xff0c0e12;
    static final int HELP_TEXT = 0xffe6edf3;
    static final int HELP_MUTED = 0xffb5bdc8;

    private SnbtCodeSyntax() {}

    static int readableColor(int rgb) {
        int source = rgb | 0xff000000;
        if (contrast(source, HELP_BACKGROUND) >= 4.5) return source;
        float low = 0, high = 1;
        for (int i = 0; i < 12; i++) {
            float amount = (low + high) / 2;
            if (contrast(EditorButtonTheme.mix(source, HELP_TEXT, amount), HELP_BACKGROUND) >= 4.5)
                high = amount;
            else low = amount;
        }
        return EditorButtonTheme.mix(source, HELP_TEXT, high);
    }

    private static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    private static double luminance(int rgb) {
        return .2126 * channel(rgb >> 16 & 255)
                + .7152 * channel(rgb >> 8 & 255)
                + .0722 * channel(rgb & 255);
    }

    private static double channel(int v) {
        double value = v / 255d;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    static net.minecraft.network.chat.Component styled(String code) {
        int[] colours = colours(code, HELP_TEXT);
        var result = net.minecraft.network.chat.Component.empty();
        for (int start = 0; start < code.length(); ) {
            int end = start + 1;
            while (end < code.length() && colours[end] == colours[start]) end++;
            final int color = colours[start];
            result.append(
                    net.minecraft.network.chat.Component.literal(code.substring(start, end))
                            .withStyle(style -> style.withColor(color)));
            start = end;
        }
        return result;
    }

    static int[] colours(String code) {
        return colours(code, EditorButtonTheme.text());
    }

    static int[] colours(String code, int base) {
        int[] result = new int[code.length()];
        Arrays.fill(result, base);
        for (int i = 0; i < code.length(); ) {
            char ch = code.charAt(i);
            if (Character.isWhitespace(ch) || ",:{}[];".indexOf(ch) >= 0) {
                i++;
                continue;
            }
            int start = i++;
            boolean quoted = ch == '\"' || ch == '\'';
            if (quoted) {
                while (i < code.length()) {
                    char c = code.charAt(i++);
                    if (c == '\\' && i < code.length()) i++;
                    else if (c == ch) break;
                }
            } else while (i < code.length() && ",:{}[]; \t\r\n".indexOf(code.charAt(i)) < 0) i++;
            int after = i;
            while (after < code.length() && Character.isWhitespace(code.charAt(after))) after++;
            boolean key = after < code.length() && code.charAt(after) == ':';
            int colour =
                    key
                            ? LuaCodeSyntax.KEYWORD
                            : quoted ? LuaCodeSyntax.STRING : LuaCodeSyntax.NUMBER;
            Arrays.fill(result, start, i, colour);
        }
        return result;
    }
}
