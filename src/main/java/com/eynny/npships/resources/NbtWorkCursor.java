package com.eynny.npships.resources;

import net.minecraft.nbt.*;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Attempt-owned iterative NBT payload walk/copy. The caller keeps the source immutable and owns all
 * reservations and partial-output lifetime. No scheduler, global state or native world access.
 * Containers are never recursively copied. Primitive-array copies use bounded portions; allocating
 * their admitted backing array is one indivisible JDK operation, measured by the caller's budget.
 */
public final class NbtWorkCursor implements AutoCloseable {
    private static final int ARRAY_PORTION = 1024;
    private static final LongConsumer NO_RESERVATION = bytes -> {};
    private final boolean copying, supplied;
    private final LongConsumer reserveMutable;
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private Tag source, result;
    private long mutableBytes, textBytes, nodes, progress;
    private boolean started, finished, closed, failed;

    private static final class Frame {
        final Tag source;
        final int depth;
        final String key;
        Tag copy;
        Iterator<String> keys;
        int index;
        boolean entered;

        Frame(Tag source, int depth, String key) {
            this.source = Objects.requireNonNull(source);
            this.depth = depth;
            this.key = key;
        }
    }

    private NbtWorkCursor(
            Tag source, boolean copying, boolean supplied, LongConsumer reserveMutable) {
        this.source = Objects.requireNonNull(source);
        this.copying = copying;
        this.supplied = supplied;
        this.reserveMutable = Objects.requireNonNull(reserveMutable);
    }

    public static NbtWorkCursor measure(Tag source) {
        return new NbtWorkCursor(source, false, false, NO_RESERVATION);
    }

    public static NbtWorkCursor measureSupplied(Tag source) {
        return new NbtWorkCursor(source, false, true, NO_RESERVATION);
    }

    /**
     * Reserves each known mutable payload before its output allocation. Text/scalar values are
     * immutable and shared, as in native copy. The owner must abort its scope if this cursor fails;
     * closing the cursor only drops references and never guesses the caller's reservation lifetime.
     */
    public static NbtWorkCursor copy(Tag source, LongConsumer reserveMutable) {
        return new NbtWorkCursor(source, true, false, reserveMutable);
    }

    /** Checks the shared owner allowance before every node, edge, array portion or completion. */
    public boolean step(BooleanSupplier canContinue) {
        requireOpen();
        Objects.requireNonNull(canContinue);
        try {
            while (!finished && canContinue.getAsBoolean()) {
                if (!started) {
                    started = true;
                    stack.push(new Frame(source, 0, null));
                }
                var frame = stack.peek();
                if (!frame.entered) enter(frame);
                else if (frame.source instanceof CompoundTag compound && frame.keys.hasNext()) {
                    var key = frame.keys.next();
                    textBytes =
                            Math.addExact(textBytes, Math.multiplyExact((long) key.length(), 2));
                    stack.push(new Frame(compound.get(key), frame.depth + 1, key));
                } else if (frame.source instanceof ListTag list && frame.index < list.size()) {
                    stack.push(new Frame(list.get(frame.index++), frame.depth + 1, null));
                } else if (copying && copyArrayPortion(frame)) {
                    // Array elements do not count as NBT nodes in the existing payload ledger.
                } else complete(frame);
                progress = Math.addExact(progress, 1);
            }
            return finished;
        } catch (RuntimeException | Error error) {
            failed = true;
            stack.clear();
            source = result = null;
            throw error;
        }
    }

    private void enter(Frame frame) {
        var tag = frame.source;
        if (frame.depth > 512)
            throw new IllegalArgumentException("NBT resource walk exceeds native depth");
        if (supplied && (tag.getId() < Tag.TAG_BYTE || tag.getId() > Tag.TAG_LONG_ARRAY))
            throw new IllegalArgumentException("Invalid supplied NBT value type: " + tag.getId());
        long bytes =
                switch (tag.getId()) {
                    case Tag.TAG_BYTE_ARRAY ->
                            ResourceMemoryBudget.arrayBytes(((ByteArrayTag) tag).size(), 1);
                    case Tag.TAG_INT_ARRAY ->
                            ResourceMemoryBudget.arrayBytes(((IntArrayTag) tag).size(), 4);
                    case Tag.TAG_LONG_ARRAY ->
                            ResourceMemoryBudget.arrayBytes(((LongArrayTag) tag).size(), 8);
                    case Tag.TAG_LIST ->
                            ResourceMemoryBudget.arrayBytes(((ListTag) tag).size(), Long.BYTES);
                    case Tag.TAG_COMPOUND ->
                            Math.multiplyExact((long) ((CompoundTag) tag).size(), 2L * Long.BYTES);
                    default -> 0;
                };
        // Perform checked totals/reservation before allocating any mutable output for this node.
        long nextMutable = Math.addExact(mutableBytes, bytes);
        long nextNodes = Math.addExact(nodes, 1);
        long nextText =
                tag instanceof StringTag
                        ? Math.addExact(
                                textBytes, Math.multiplyExact((long) tag.getAsString().length(), 2))
                        : textBytes;
        if (copying && bytes > 0) reserveMutable.accept(bytes);
        if (tag instanceof CompoundTag compound) {
            frame.keys = compound.getAllKeys().iterator();
            if (copying) frame.copy = new CompoundTag(compound.size());
        } else if (tag instanceof ListTag list) {
            // Empty native lists can retain a nonzero element tag after decode. Native copy
            // is constant for that special case and preserves the exact empty-list type.
            if (copying) frame.copy = list.isEmpty() ? list.copy() : new ListTag(list.size());
        } else if (copying) {
            frame.copy =
                    switch (tag.getId()) {
                        case Tag.TAG_BYTE_ARRAY ->
                                new ByteArrayTag(new byte[((ByteArrayTag) tag).size()]);
                        case Tag.TAG_INT_ARRAY ->
                                new IntArrayTag(new int[((IntArrayTag) tag).size()]);
                        case Tag.TAG_LONG_ARRAY ->
                                new LongArrayTag(new long[((LongArrayTag) tag).size()]);
                        default -> tag; // Native scalar and string tags are immutable.
                    };
        }
        mutableBytes = nextMutable;
        nodes = nextNodes;
        textBytes = nextText;
        frame.entered = true;
    }

    private boolean copyArrayPortion(Frame frame) {
        final Object from, to;
        final int length;
        if (frame.source instanceof ByteArrayTag bytes) {
            from = bytes.getAsByteArray();
            to = ((ByteArrayTag) frame.copy).getAsByteArray();
            length = bytes.size();
        } else if (frame.source instanceof IntArrayTag ints) {
            from = ints.getAsIntArray();
            to = ((IntArrayTag) frame.copy).getAsIntArray();
            length = ints.size();
        } else if (frame.source instanceof LongArrayTag longs) {
            from = longs.getAsLongArray();
            to = ((LongArrayTag) frame.copy).getAsLongArray();
            length = longs.size();
        } else return false;
        if (frame.index == length) return false;
        int count = Math.min(ARRAY_PORTION, length - frame.index);
        System.arraycopy(from, frame.index, to, frame.index, count);
        frame.index += count;
        return true;
    }

    private void complete(Frame frame) {
        stack.pop();
        if (stack.isEmpty()) {
            result = frame.copy;
            source = null;
            finished = true;
        } else if (copying) {
            var parent = stack.peek().copy;
            if (parent instanceof CompoundTag compound) compound.put(frame.key, frame.copy);
            else ((ListTag) parent).add(frame.copy);
        }
    }

    public boolean finished() {
        return finished;
    }

    public long progress() {
        return progress;
    }

    public PreparedTemplateResources.Payload payload() {
        requireComplete();
        return new PreparedTemplateResources.Payload(mutableBytes, textBytes, nodes);
    }

    /**
     * The caller takes responsibility for the detached mutable graph and its existing reservation.
     */
    public Tag copyResult() {
        requireComplete();
        if (!copying) throw new IllegalStateException("Measurement cursor has no mutable result");
        return result;
    }

    private void requireOpen() {
        if (closed || failed) throw new IllegalStateException("NBT cursor is closed or failed");
    }

    private void requireComplete() {
        requireOpen();
        if (!finished) throw new IllegalStateException("NBT cursor is unfinished");
    }

    @Override
    public void close() {
        closed = true;
        stack.clear();
        source = result = null;
    }
}
