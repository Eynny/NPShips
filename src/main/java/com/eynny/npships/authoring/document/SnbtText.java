package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.validation.ValidationProblem;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.nbt.*;

import java.util.*;

/** Native SNBT syntax plus lossless author-text boundaries; duplicate compound keys are refused. */
public final class SnbtText {
    private SnbtText() {}

    record Token(List<String> path, int start, int end, Tag value, CompoundTag parentState) {}

    private static final int MAX_CONTAINERS = 65; // Root plus the existing 64 nested frames.

    /**
     * Admit lexical depth before invoking Minecraft's recursive parser, including unfinished text.
     */
    public static void checkDepth(String source) throws CommandSyntaxException {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{' || c == '[') {
                if (++depth > MAX_CONTAINERS)
                    throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                                    new com.mojang.brigadier.LiteralMessage(
                                            "SNBT is nested too deeply (maximum 65 containers)."))
                            .create();
            } else if ((c == '}' || c == ']') && depth > 0) depth--;
        }
    }

    public static boolean allowedDepth(String source) {
        try {
            checkDepth(source);
            return true;
        } catch (CommandSyntaxException tooDeep) {
            return false;
        }
    }

    /** Native syntax for stored fragments/help, without the full author's duplicate-key scan. */
    public static CompoundTag nativeCompound(String source) throws CommandSyntaxException {
        checkDepth(source);
        return TagParser.parseTag(source);
    }

    static Tag nativeValue(StringReader reader) throws CommandSyntaxException {
        checkDepth(reader.getString());
        return new TagParser(reader).readValue();
    }

    public static CompoundTag parse(String source) throws CommandSyntaxException {
        var root = nativeCompound(source);
        new Scan(source, -1).compound(List.of(), 0, root, null);
        return root;
    }

    static Token token(String source, int offset) throws CommandSyntaxException {
        var root = nativeCompound(source);
        var scan = new Scan(source, offset);
        scan.compound(List.of(), 0, root, null);
        return scan.found;
    }

    /**
     * Current compound siblings, excluding the key being replaced; no cross-instance schema union.
     */
    static Set<String> siblingKeys(String source, int start, int replacement) {
        var reader = new StringReader(source);
        reader.setCursor(Math.clamp(start + 1, 0, source.length()));
        var keys = new HashSet<String>();
        try {
            while (reader.canRead()) {
                reader.skipWhitespace();
                if (!reader.canRead() || reader.peek() == '}') break;
                int keyStart = reader.getCursor();
                String key = reader.readString();
                if (key.isEmpty()) break;
                if (replacement < keyStart || replacement >= reader.getCursor()) keys.add(key);
                reader.skipWhitespace();
                if (!reader.canRead() || reader.peek() != ':') {
                    while (reader.canRead() && reader.peek() != ',' && reader.peek() != '}')
                        reader.skip();
                } else {
                    reader.skip();
                    nativeValue(reader);
                    reader.skipWhitespace();
                }
                if (!reader.canRead() || reader.peek() != ',') break;
                reader.skip();
            }
        } catch (CommandSyntaxException incompleteDraft) {
            // Retain proven preceding siblings while the current value is incomplete.
        }
        return keys;
    }

    private static final class Scan {
        final StringReader reader;
        final int offset;
        Token found;

        Scan(String source, int offset) {
            reader = new StringReader(source);
            this.offset = offset;
        }

        void compound(List<String> path, int depth, CompoundTag node, CompoundTag parent)
                throws CommandSyntaxException {
            if (depth > 64) throw new IllegalArgumentException("SNBT is nested too deeply");
            reader.skipWhitespace();
            reader.expect('{');
            var keys = new HashSet<String>();
            reader.skipWhitespace();
            while (reader.canRead() && reader.peek() != '}') {
                String key = reader.readString();
                if (!keys.add(key))
                    throw ValidationProblem.invalid(
                            "snbt_duplicate_key", "Duplicate SNBT key: " + key, key);
                reader.skipWhitespace();
                reader.expect(':');
                reader.skipWhitespace();
                var next = new ArrayList<>(path);
                next.add(key);
                Tag value = node == null ? null : node.get(key);
                if (reader.peek() == '{')
                    compound(next, depth + 1, value instanceof CompoundTag c ? c : null, node);
                else if (reader.peek() == '[') list(next, depth + 1, value);
                else {
                    int start = reader.getCursor();
                    reader.readString();
                    if (start <= offset && offset < reader.getCursor())
                        found =
                                new Token(
                                        List.copyOf(next),
                                        start,
                                        reader.getCursor(),
                                        value,
                                        !path.isEmpty() && path.getLast().equals("Properties")
                                                ? parent
                                                : null);
                }
                reader.skipWhitespace();
                if (reader.peek() != ',') break;
                reader.skip();
                reader.skipWhitespace();
            }
            reader.expect('}');
        }

        void list(List<String> path, int depth, Tag node) throws CommandSyntaxException {
            if (depth > 64) throw new IllegalArgumentException("SNBT is nested too deeply");
            reader.expect('[');
            reader.skipWhitespace();
            if (reader.canRead(2) && reader.getString().charAt(reader.getCursor() + 1) == ';') {
                // Native syntax already established that this is a primitive typed array.
                while (reader.canRead() && reader.peek() != ']') reader.skip();
                reader.expect(']');
                return;
            }
            var values = node instanceof ListTag l ? l : new ListTag();
            var next = new ArrayList<>(path);
            next.set(next.size() - 1, next.getLast() + "[]");
            int index = 0;
            while (reader.canRead() && reader.peek() != ']') {
                Tag value = index < values.size() ? values.get(index) : null;
                index++;
                if (reader.peek() == '{')
                    compound(next, depth + 1, value instanceof CompoundTag c ? c : null, null);
                else if (reader.peek() == '[') list(next, depth + 1, value);
                else {
                    int start = reader.getCursor();
                    reader.readString();
                    if (start <= offset && offset < reader.getCursor())
                        found =
                                new Token(
                                        List.copyOf(next), start, reader.getCursor(), value, null);
                }
                reader.skipWhitespace();
                if (reader.peek() != ',') break;
                reader.skip();
                reader.skipWhitespace();
            }
            reader.expect(']');
        }
    }
}
