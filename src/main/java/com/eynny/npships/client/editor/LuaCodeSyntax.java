package com.eynny.npships.client.editor;

import java.util.*;

/** Lexical colours and identifiers only: never runs user code or resolves Lua globals. */
final class LuaCodeSyntax {
    static final int NORMAL = 0xffe1e7ee,
            KEYWORD = 0xffdd8cff,
            STRING = 0xffb4ed72,
            COMMENT = 0xff72bd9a,
            NUMBER = 0xffffb45e;
    static final Set<String> KEYWORDS =
            Set.of(
                    "and",
                    "break",
                    "do",
                    "else",
                    "elseif",
                    "end",
                    "false",
                    "for",
                    "function",
                    "goto",
                    "if",
                    "in",
                    "local",
                    "nil",
                    "not",
                    "or",
                    "repeat",
                    "return",
                    "then",
                    "true",
                    "until",
                    "while");

    record Analysis(int[] colours, Set<String> identifiers) {}

    static Analysis analyse(String code) {
        int[] colours = new int[code.length()];
        Arrays.fill(colours, NORMAL);
        Set<String> identifiers = new TreeSet<>();
        int i = 0;
        while (i < code.length()) {
            int start = i, colour = NORMAL;
            char c = code.charAt(i);
            if (code.startsWith("--", i)) {
                i += 2;
                int end = longStringEnd(code, i);
                if (end >= 0) i = end;
                else while (i < code.length() && code.charAt(i) != '\n') i++;
                colour = COMMENT;
            } else if (c == '\'' || c == '"') {
                i++;
                while (i < code.length()) {
                    char next = code.charAt(i++);
                    if (next == '\\' && i < code.length()) i++;
                    else if (next == c || next == '\n') break;
                }
                colour = STRING;
            } else if (c == '[' && longStringEnd(code, i) >= 0) {
                i = longStringEnd(code, i);
                colour = STRING;
            } else if (Character.isLetter(c) || c == '_') {
                i++;
                while (i < code.length()
                        && (Character.isLetterOrDigit(code.charAt(i)) || code.charAt(i) == '_'))
                    i++;
                String word = code.substring(start, i);
                if (KEYWORDS.contains(word)) colour = KEYWORD;
                else identifiers.add(word);
            } else if (Character.isDigit(c)) {
                i++;
                while (i < code.length()) {
                    char next = code.charAt(i);
                    if (Character.isLetterOrDigit(next)
                            || next == '.' && !code.startsWith("..", i)
                            || (next == '+' || next == '-')
                                    && "eEpP".indexOf(code.charAt(i - 1)) >= 0) i++;
                    else break;
                }
                colour = NUMBER;
            } else i++;
            Arrays.fill(colours, start, i, colour);
        }
        return new Analysis(colours, identifiers);
    }

    static int longStringEnd(String text, int start) {
        if (start >= text.length() || text.charAt(start) != '[') return -1;
        int cursor = start + 1;
        while (cursor < text.length() && text.charAt(cursor) == '=') cursor++;
        if (cursor >= text.length() || text.charAt(cursor) != '[') return -1;
        String closing = "]" + "=".repeat(cursor - start - 1) + "]";
        int end = text.indexOf(closing, cursor + 1);
        return end < 0 ? text.length() : end + closing.length();
    }
}
