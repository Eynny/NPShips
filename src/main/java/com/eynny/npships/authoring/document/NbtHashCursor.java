package com.eynny.npships.authoring.document;

import net.minecraft.nbt.*;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Exact native Tag.hashCode semantics without recursive container or unbounded array/string
 * hashing.
 */
final class NbtHashCursor implements AutoCloseable {
    private static final int PORTION = 1024;
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private Tag source;
    private boolean started, done, closed, failed;
    private int result;
    private long progress;

    private static final class Frame {
        final Tag tag;
        final int keyHash, depth;
        boolean entered;
        int hash, index, keyIndex, pendingKeyHash;
        String pendingKey;
        Iterator<String> keys;

        Frame(Tag tag, int keyHash, int depth) {
            this.tag = tag;
            this.keyHash = keyHash;
            this.depth = depth;
        }
    }

    NbtHashCursor(Tag source) {
        this.source = Objects.requireNonNull(source);
    }

    boolean step(BooleanSupplier allowed) {
        requireOpen();
        try {
            while (!done && allowed.getAsBoolean()) {
                if (!started) {
                    started = true;
                    stack.push(new Frame(source, 0, 0));
                }
                one();
                progress++;
            }
            return done;
        } catch (RuntimeException | Error error) {
            failed = true;
            source = null;
            stack.clear();
            throw error;
        }
    }

    private void one() {
        var f = stack.peek();
        if (!f.entered) {
            if (f.depth > 512) throw new IllegalArgumentException("NBT hash exceeds native depth");
            f.entered = true;
            if (f.tag instanceof CompoundTag c) f.keys = c.getAllKeys().iterator();
            else if (f.tag instanceof ListTag
                    || f.tag instanceof ByteArrayTag
                    || f.tag instanceof IntArrayTag
                    || f.tag instanceof LongArrayTag) f.hash = 1;
            return;
        }
        if (f.tag instanceof CompoundTag c) {
            if (f.pendingKey != null) {
                int end = f.keyIndex + Math.min(PORTION, f.pendingKey.length() - f.keyIndex);
                while (f.keyIndex < end)
                    f.pendingKeyHash = 31 * f.pendingKeyHash + f.pendingKey.charAt(f.keyIndex++);
                if (f.keyIndex == f.pendingKey.length()) {
                    var key = f.pendingKey;
                    f.pendingKey = null;
                    stack.push(new Frame(c.get(key), f.pendingKeyHash, f.depth + 1));
                }
            } else if (f.keys.hasNext()) {
                f.pendingKey = f.keys.next();
                f.keyIndex = 0;
                f.pendingKeyHash = 0;
            } else complete(f);
        } else if (f.tag instanceof ListTag list) {
            if (f.index < list.size()) stack.push(new Frame(list.get(f.index++), 0, f.depth + 1));
            else complete(f);
        } else if (f.tag instanceof StringTag text) {
            String value = text.getAsString();
            int end = f.index + Math.min(PORTION, value.length() - f.index);
            while (f.index < end) f.hash = 31 * f.hash + value.charAt(f.index++);
            if (f.index == value.length()) complete(f);
        } else if (f.tag instanceof ByteArrayTag values) {
            var array = values.getAsByteArray();
            int end = f.index + Math.min(PORTION, array.length - f.index);
            while (f.index < end) f.hash = 31 * f.hash + array[f.index++];
            if (f.index == array.length) complete(f);
        } else if (f.tag instanceof IntArrayTag values) {
            var array = values.getAsIntArray();
            int end = f.index + Math.min(PORTION, array.length - f.index);
            while (f.index < end) f.hash = 31 * f.hash + array[f.index++];
            if (f.index == array.length) complete(f);
        } else if (f.tag instanceof LongArrayTag values) {
            var array = values.getAsLongArray();
            int end = f.index + Math.min(PORTION, array.length - f.index);
            while (f.index < end) f.hash = 31 * f.hash + Long.hashCode(array[f.index++]);
            if (f.index == array.length) complete(f);
        } else {
            f.hash = f.tag.hashCode();
            complete(f);
        }
    }

    private void complete(Frame f) {
        stack.pop();
        if (stack.isEmpty()) {
            result = f.hash;
            done = true;
            source = null;
        } else {
            var parent = stack.peek();
            if (parent.tag instanceof CompoundTag) parent.hash += f.keyHash ^ f.hash;
            else parent.hash = 31 * parent.hash + f.hash;
        }
    }

    int result() {
        requireOpen();
        if (!done) throw new IllegalStateException("Unfinished NBT hash");
        return result;
    }

    long progress() {
        return progress;
    }

    private void requireOpen() {
        if (closed || failed) throw new IllegalStateException("NBT hash is closed or failed");
    }

    @Override
    public void close() {
        closed = true;
        source = null;
        stack.clear();
    }
}
