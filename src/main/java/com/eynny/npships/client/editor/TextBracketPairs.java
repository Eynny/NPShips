package com.eynny.npships.client.editor;

import java.util.Arrays;

/** Tolerant lexical display index; never parses, repairs or executes authored text. */
final class TextBracketPairs {
    private final int[] positions, partners;

    private TextBracketPairs(int[] positions, int[] partners) {
        this.positions = positions;
        this.partners = partners;
    }

    int matching(int offset) {
        int index = Arrays.binarySearch(positions, offset);
        return index < 0 || partners[index] < 0 ? -1 : positions[partners[index]];
    }

    static TextBracketPairs scan(String text, boolean lua) {
        int initial = Math.min(64, text.length());
        int[] positions = new int[initial], partners = new int[initial], stack = new int[initial];
        int count = 0, depth = 0;
        for (int index = 0; index < text.length(); ) {
            char ch = text.charAt(index);
            if (lua && text.startsWith("--", index)) {
                index += 2;
                int end = LuaCodeSyntax.longStringEnd(text, index);
                if (end >= 0) index = end;
                else while (index < text.length() && text.charAt(index) != '\n') index++;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                index++;
                while (index < text.length()) {
                    char next = text.charAt(index++);
                    if (next == '\\' && index < text.length()) index++;
                    else if (next == ch || lua && next == '\n') break;
                }
                continue;
            }
            if (lua && ch == '[') {
                int end = LuaCodeSyntax.longStringEnd(text, index);
                if (end >= 0) {
                    index = end;
                    continue;
                }
            }
            if ("()[]{}".indexOf(ch) >= 0) {
                if (count == positions.length) {
                    int capacity = Math.min(text.length(), Math.max(count + 1, count * 2));
                    positions = Arrays.copyOf(positions, capacity);
                    partners = Arrays.copyOf(partners, capacity);
                }
                positions[count] = index;
                partners[count] = -1;
                if (ch == '(' || ch == '[' || ch == '{') {
                    if (depth == stack.length)
                        stack =
                                Arrays.copyOf(
                                        stack,
                                        Math.min(text.length(), Math.max(depth + 1, depth * 2)));
                    stack[depth++] = count;
                } else if (depth > 0) {
                    int opening = stack[depth - 1];
                    char expected =
                            switch (text.charAt(positions[opening])) {
                                case '(' -> ')';
                                case '[' -> ']';
                                default -> '}';
                            };
                    if (ch == expected) {
                        depth--;
                        partners[count] = opening;
                        partners[opening] = count;
                    } else depth = 0;
                }
                count++;
            }
            index++;
        }
        return new TextBracketPairs(
                Arrays.copyOf(positions, count), Arrays.copyOf(partners, count));
    }
}
