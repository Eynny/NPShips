package com.eynny.npships.resources;

import net.minecraft.nbt.*;

import java.io.*;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.zip.GZIPInputStream;

/**
 * Reads privately pinned standard binary NBT under the existing attempt allowance. Each field, list
 * member and array portion is a separate unit. This implements the file format, without invoking
 * block/entity factories. The owner reserves decoded payload before allocation and retains that
 * reservation until the graph is transferred or its preparation is aborted.
 */
public final class NbtReadCursor implements AutoCloseable {
    private static final int ARRAY_BYTES = 1024;
    private final InputStream compressed;
    private final LongConsumer reserve, release;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private DataInputStream input;
    private CompoundTag result;
    private long progress, nodes;
    private boolean started, finished, closed, streamReserved;

    private static final class Frame {
        final int type;
        final String key;
        Tag value;
        int index, length, memberType;
        boolean entered;

        Frame(int type, String key) {
            this.type = type;
            this.key = key;
        }
    }

    public NbtReadCursor(InputStream compressed, LongConsumer reserve, LongConsumer release) {
        this.compressed = Objects.requireNonNull(compressed);
        this.reserve = Objects.requireNonNull(reserve);
        this.release = Objects.requireNonNull(release);
    }

    public boolean step(BooleanSupplier allowance) throws IOException {
        if (closed) throw new IllegalStateException("NBT reader is closed");
        Objects.requireNonNull(allowance);
        try {
            while (!finished && allowance.getAsBoolean()) {
                if (!started) {
                    // The decoder's bounded stream buffers are released when this read closes.
                    reserve.accept(32768);
                    streamReserved = true;
                    input =
                            new DataInputStream(
                                    new BufferedInputStream(new GZIPInputStream(compressed), 8192));
                    int type = input.readUnsignedByte();
                    if (type != Tag.TAG_COMPOUND)
                        throw new IOException("NBT root is not a compound");
                    release.accept(2L * text().length()); // Root name is not retained.
                    frames.push(new Frame(type, null));
                    started = true;
                } else {
                    var frame = frames.peek();
                    if (!frame.entered) enter(frame);
                    else if (frame.type == Tag.TAG_COMPOUND) {
                        int type = input.readUnsignedByte();
                        if (type == Tag.TAG_END) complete(frame);
                        else {
                            validType(type);
                            String key = text();
                            reserve.accept(2L * Long.BYTES);
                            push(type, key);
                        }
                    } else if (frame.type == Tag.TAG_LIST && frame.index < frame.length) {
                        frame.index++;
                        push(frame.memberType, null);
                    } else if (arrayPortion(frame)) {
                        // Primitive elements are bytes of one tag, not additional NBT nodes.
                    } else complete(frame);
                }
                progress++;
            }
            return finished;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void push(int type, String key) throws IOException {
        frames.push(new Frame(type, key));
    }

    private static void validType(int type) throws IOException {
        if (type < Tag.TAG_BYTE || type > Tag.TAG_LONG_ARRAY)
            throw new IOException("Unknown NBT type: " + type);
    }

    private String text() throws IOException {
        int length = input.readUnsignedShort();
        // Modified UTF-8 has a 16-bit byte length. Decoder scratch and the maximum decoded
        // text are admitted first; only actual retained characters remain charged afterwards.
        long scratch =
                ResourceMemoryBudget.arrayBytes(length + 2L, 1)
                        + ResourceMemoryBudget.arrayBytes(length, 1)
                        + ResourceMemoryBudget.arrayBytes(length, 2);
        reserve.accept(scratch + 2L * length);
        String decoded = null;
        try {
            byte[] encoded = new byte[length + 2];
            encoded[0] = (byte) (length >>> 8);
            encoded[1] = (byte) length;
            input.readFully(encoded, 2, length);
            decoded =
                    DataInputStream.readUTF(new DataInputStream(new ByteArrayInputStream(encoded)));
            return decoded;
        } finally {
            release.accept(scratch + 2L * (length - (decoded == null ? 0 : decoded.length())));
        }
    }

    private int length() throws IOException {
        int value = input.readInt();
        if (value < 0) throw new IOException("Negative NBT container length");
        return value;
    }

    private void enter(Frame frame) throws IOException {
        // Native depth counts compound/list containers, not the scalar child they contain.
        if ((frame.type == Tag.TAG_COMPOUND || frame.type == Tag.TAG_LIST) && frames.size() > 512)
            throw new IOException("NBT exceeds native depth");
        nodes++;
        frame.entered = true;
        switch (frame.type) {
            case Tag.TAG_BYTE -> frame.value = ByteTag.valueOf(input.readByte());
            case Tag.TAG_SHORT -> frame.value = ShortTag.valueOf(input.readShort());
            case Tag.TAG_INT -> frame.value = IntTag.valueOf(input.readInt());
            case Tag.TAG_LONG -> frame.value = LongTag.valueOf(input.readLong());
            case Tag.TAG_FLOAT -> frame.value = FloatTag.valueOf(input.readFloat());
            case Tag.TAG_DOUBLE -> frame.value = DoubleTag.valueOf(input.readDouble());
            case Tag.TAG_STRING -> frame.value = StringTag.valueOf(text());
            case Tag.TAG_COMPOUND -> frame.value = new CompoundTag();
            case Tag.TAG_LIST -> {
                frame.memberType = input.readUnsignedByte();
                frame.length = length();
                if (frame.length != 0) validType(frame.memberType);
                reserve.accept(ResourceMemoryBudget.arrayBytes(frame.length, Long.BYTES));
                if (frame.length == 0) {
                    // Native empty lists preserve the encoded member type, including TAG_END.
                    // This five-byte leaf has no descendants or content to process synchronously.
                    byte[] header = {(byte) frame.memberType, 0, 0, 0, 0};
                    frame.value =
                            ListTag.TYPE.load(
                                    new DataInputStream(new ByteArrayInputStream(header)),
                                    NbtAccounter.unlimitedHeap());
                } else frame.value = new ListTag(frame.length);
            }
            case Tag.TAG_BYTE_ARRAY, Tag.TAG_INT_ARRAY, Tag.TAG_LONG_ARRAY -> {
                frame.length = length();
                int bytes =
                        frame.type == Tag.TAG_BYTE_ARRAY
                                ? 1
                                : frame.type == Tag.TAG_INT_ARRAY ? 4 : 8;
                reserve.accept(ResourceMemoryBudget.arrayBytes(frame.length, bytes));
                frame.value =
                        switch (frame.type) {
                            case Tag.TAG_BYTE_ARRAY -> new ByteArrayTag(new byte[frame.length]);
                            case Tag.TAG_INT_ARRAY -> new IntArrayTag(new int[frame.length]);
                            default -> new LongArrayTag(new long[frame.length]);
                        };
            }
            default -> throw new IOException("Unknown NBT type: " + frame.type);
        }
    }

    private boolean arrayPortion(Frame frame) throws IOException {
        if (frame.index >= frame.length) return false;
        if (frame.value instanceof ByteArrayTag bytes) {
            int count = Math.min(ARRAY_BYTES, frame.length - frame.index);
            input.readFully(bytes.getAsByteArray(), frame.index, count);
            frame.index += count;
        } else if (frame.value instanceof IntArrayTag ints) {
            int end = Math.min(frame.length, frame.index + ARRAY_BYTES / 4);
            while (frame.index < end) ints.getAsIntArray()[frame.index++] = input.readInt();
        } else if (frame.value instanceof LongArrayTag longs) {
            int end = Math.min(frame.length, frame.index + ARRAY_BYTES / 8);
            while (frame.index < end) longs.getAsLongArray()[frame.index++] = input.readLong();
        } else return false;
        return true;
    }

    private void complete(Frame frame) {
        frames.pop();
        if (frames.isEmpty()) {
            result = (CompoundTag) frame.value;
            finished = true;
        } else if (frames.peek().value instanceof CompoundTag compound)
            compound.put(frame.key, frame.value);
        else ((ListTag) frames.peek().value).add(frame.value);
    }

    public long progress() {
        return progress;
    }

    public long nodes() {
        return nodes;
    }

    public CompoundTag result() {
        if (closed || !finished)
            throw new IllegalStateException("NBT read is unfinished or closed");
        return result;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        result = null;
        frames.clear();
        try {
            if (input != null) input.close();
            else compressed.close();
        } finally {
            input = null;
            if (streamReserved) {
                streamReserved = false;
                release.accept(32768);
            }
        }
    }
}
