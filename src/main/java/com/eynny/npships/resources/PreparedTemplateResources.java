package com.eynny.npships.resources;

import net.minecraft.nbt.*;

/**
 * One detached preparation's declared NBT payload/copy and native list capacities. This is not an
 * estimate of the complete decoded heap, object headers, native factories or provider graphs. The
 * prepared template holds this token through close and any externally retained readable handle.
 */
public final class PreparedTemplateResources {
    private final ResourceMemoryBudget.Scope memory;
    private final ResourceWorkMeter work;
    private long nativeLists, inputBytes, crewCapacity = -1;
    private long nativeEntityCapacity, nativePaletteCapacity;

    public PreparedTemplateResources(ResourceMemoryBudget budget, ResourceWorkMeter work) {
        this.memory = budget.scope("prepared template NBT/list capacity");
        this.work = java.util.Objects.requireNonNull(work);
    }

    public ResourceWorkMeter work() {
        return work;
    }

    public long bytes() {
        return memory.used();
    }

    /** Post-decode admission; cannot prevent the preceding opaque native decoder allocation. */
    public void retain(Tag tag) {
        var weight = measure(tag);
        work.record(ResourceWorkMeter.Family.NBT_NODE_VISIT, weight.nodes());
        long bytes = Math.addExact(weight.mutableBytes(), weight.textBytes());
        memory.reserve(bytes);
        inputBytes = Math.addExact(inputBytes, bytes);
    }

    public long reserveCopy(Tag tag) {
        var weight = measure(tag);
        work.record(ResourceWorkMeter.Family.NBT_NODE_VISIT, weight.nodes());
        memory.reserve(weight.mutableBytes());
        work.record(ResourceWorkMeter.Family.NBT_COPY_INPUT_NODE, weight.nodes());
        return weight.mutableBytes();
    }

    /**
     * Newly supplied provider data may retain text that was absent from the admitted source.
     * Reserve its declared mutable/text capacity before the host copies it. This is separate from
     * the composition input ledger and does not claim complete Java/native heap accounting.
     */
    public long reserveGrowth(Tag tag) {
        var weight = measureSupplied(tag);
        work.record(ResourceWorkMeter.Family.NBT_NODE_VISIT, weight.nodes());
        work.record(ResourceWorkMeter.Family.NBT_COPY_INPUT_NODE, weight.nodes());
        long bytes = Math.addExact(weight.mutableBytes(), weight.textBytes());
        memory.reserve(bytes);
        return bytes;
    }

    /** Known transient reference-array capacity; reserve before actual allocation/growth. */
    public void reserveCapacity(long bytes) {
        memory.reserve(bytes);
    }

    /** Reserve the known mutable payload before calling the installed native copy operation. */
    @SuppressWarnings("unchecked")
    public <T extends Tag> T copy(T tag) {
        reserveCopy(tag);
        return (T) tag.copy();
    }

    /** One result-root copy belongs to mutable composition input, not the final private copies. */
    public void reserveInputCopy(Tag tag) {
        inputBytes = Math.addExact(inputBytes, reserveCopy(tag));
    }

    public ListTag copyEntities(ListTag tag) {
        reserveInputCopy(tag);
        crewCapacity = tag.size();
        return tag.copy();
    }

    /**
     * After the final native load/last use of composition intermediates, retain one final input
     * envelope, not all discarded variant roots or old entity lists. Private defensive copies and
     * native list owners remain charged separately. This is still a conservative complete input
     * envelope, not an assertion that native load retains every root field.
     */
    public void completeInput(CompoundTag tag) {
        var weight = measure(tag);
        work.record(ResourceWorkMeter.Family.NBT_NODE_VISIT, weight.nodes());
        long finalBytes = Math.addExact(weight.mutableBytes(), weight.textBytes());
        if (crewCapacity >= 0) {
            long size = tag.getList("entities", Tag.TAG_COMPOUND).size();
            finalBytes =
                    Math.addExact(
                            finalBytes,
                            ResourceMemoryBudget.arrayBytes(crewCapacity, Long.BYTES)
                                    - ResourceMemoryBudget.arrayBytes(size, Long.BYTES));
        }
        if (finalBytes > inputBytes) memory.reserve(finalBytes - inputBytes);
        else memory.release(inputBytes - finalBytes);
        inputBytes = finalBytes;
    }

    public void release(long bytes) {
        memory.release(bytes);
    }

    /** Called immediately before ListTag's known ArrayList growth, with old/new overlap. */
    public void reserveEntityGrowth(long next) {
        long bytes = ResourceMemoryBudget.arrayBytes(next, Long.BYTES);
        memory.reserve(bytes);
        inputBytes = Math.addExact(inputBytes, bytes);
        crewCapacity = next;
        // The caller immediately appends with no callbacks; only the replacement remains after it.
        // Keep the previous array charged through the actual append; release is explicit below.
    }

    public void entityGrowthCompleted(long previous) {
        long bytes = ResourceMemoryBudget.arrayBytes(previous, Long.BYTES);
        memory.release(bytes);
        inputBytes -= bytes;
    }

    /**
     * Native loadPalette builds three classification lists plus a merged list. Bound their
     * reference capacity before load, without decoding block states or invoking native shapes.
     * Object/HashMap nodes, SimplePalette tables and native callback scratch are not covered.
     */
    public long beforeNativeLoad(CompoundTag tag) {
        long blocks = tag.getList("blocks", Tag.TAG_COMPOUND).size();
        long entities = tag.getList("entities", Tag.TAG_COMPOUND).size();
        int palettes =
                tag.contains("palettes", Tag.TAG_LIST)
                        ? tag.getList("palettes", Tag.TAG_LIST).size()
                        : 1;
        long blockList = ResourceMemoryBudget.arrayBytes(arrayListCapacity(blocks), Long.BYTES);
        long persistent =
                Math.addExact(
                        Math.multiplyExact(blockList, palettes),
                        Math.addExact(
                                ResourceMemoryBudget.arrayBytes(
                                        Math.max(nativeEntityCapacity, arrayListCapacity(entities)),
                                        Long.BYTES),
                                ResourceMemoryBudget.arrayBytes(
                                        Math.max(
                                                nativePaletteCapacity, arrayListCapacity(palettes)),
                                        Long.BYTES)));
        // Only one palette's classification/merge scratch is active at a time; reserve its
        // three classification arrays, their growth overlap and merged-list growth overlap.
        long scratch = Math.multiplyExact(blockList, 7L);
        long total = Math.addExact(persistent, scratch);
        memory.reserve(total);
        work.record(ResourceWorkMeter.Family.NATIVE_TEMPLATE_LOAD, 1);
        work.record(
                ResourceWorkMeter.Family.TEMPLATE_BLOCK_ENTRY,
                Math.multiplyExact(blocks, palettes));
        work.record(ResourceWorkMeter.Family.TEMPLATE_ENTITY_ENTRY, entities);
        // Successful native replacement may still retain old capacity during this invocation.
        return total;
    }

    public void afterNativeLoad(CompoundTag tag, long reserved) {
        // Native load clears these two outer ArrayLists in place. A smaller replacement does
        // not shrink their backing arrays; only per-palette block lists become replaceable.
        nativeEntityCapacity =
                Math.max(
                        nativeEntityCapacity,
                        arrayListCapacity(tag.getList("entities", Tag.TAG_COMPOUND).size()));
        int palettes =
                tag.contains("palettes", Tag.TAG_LIST)
                        ? tag.getList("palettes", Tag.TAG_LIST).size()
                        : 1;
        nativePaletteCapacity = Math.max(nativePaletteCapacity, arrayListCapacity(palettes));
        long persistent =
                Math.addExact(
                        Math.multiplyExact(
                                ResourceMemoryBudget.arrayBytes(
                                        arrayListCapacity(
                                                tag.getList("blocks", Tag.TAG_COMPOUND).size()),
                                        Long.BYTES),
                                palettes),
                        Math.addExact(
                                ResourceMemoryBudget.arrayBytes(nativeEntityCapacity, Long.BYTES),
                                ResourceMemoryBudget.arrayBytes(
                                        Math.max(
                                                nativePaletteCapacity, arrayListCapacity(palettes)),
                                        Long.BYTES)));
        memory.release(reserved - persistent + nativeLists);
        nativeLists = persistent;
    }

    /** Native/JDK ArrayList append capacity (JDK21), including its initial ten slots. */
    public static long arrayListCapacity(long size) {
        if (size < 0 || size > Integer.MAX_VALUE - 8L)
            throw new ResourceLimitException(
                    "prepared list arithmetic", 0, size, Integer.MAX_VALUE - 8L);
        if (size == 0) return 0;
        long capacity = 10;
        while (capacity < size)
            capacity = Math.max(size < 10 ? size : capacity + capacity / 2, capacity + 1);
        return capacity;
    }

    public record Payload(long mutableBytes, long textBytes, long nodes) {}

    /**
     * Declared primitive-array capacity, list slots, compound key/value slots and UTF-16 content.
     * Installed value tags/StringTag.copy share immutable values: copies charge no duplicate text.
     * Keys/values are counted by occurrence, not advertised as an exact retained-heap graph size.
     */
    public static Payload measure(Tag tag) {
        long[] totals = new long[3];
        visit(tag, totals, 0, false);
        return new Payload(totals[0], totals[1], totals[2]);
    }

    /** Checked structure of newly supplied native tags, before any host defensive copy. */
    public static Payload measureSupplied(Tag tag) {
        long[] totals = new long[3];
        visit(java.util.Objects.requireNonNull(tag), totals, 0, true);
        return new Payload(totals[0], totals[1], totals[2]);
    }

    private static void visit(Tag tag, long[] totals, int depth, boolean incoming) {
        if (incoming && (tag.getId() < Tag.TAG_BYTE || tag.getId() > Tag.TAG_LONG_ARRAY))
            throw new IllegalArgumentException("Invalid supplied NBT value type: " + tag.getId());
        if (depth > 512)
            throw new IllegalArgumentException("NBT resource walk exceeds native depth");
        totals[2] = Math.addExact(totals[2], 1);
        long bytes = 0, text = 0;
        switch (tag.getId()) {
            case Tag.TAG_BYTE_ARRAY ->
                    bytes = ResourceMemoryBudget.arrayBytes(((ByteArrayTag) tag).size(), 1);
            case Tag.TAG_INT_ARRAY ->
                    bytes = ResourceMemoryBudget.arrayBytes(((IntArrayTag) tag).size(), 4);
            case Tag.TAG_LONG_ARRAY ->
                    bytes = ResourceMemoryBudget.arrayBytes(((LongArrayTag) tag).size(), 8);
            case Tag.TAG_STRING -> text = Math.multiplyExact((long) tag.getAsString().length(), 2);
            case Tag.TAG_LIST -> {
                var list = (ListTag) tag;
                bytes = ResourceMemoryBudget.arrayBytes(list.size(), Long.BYTES);
                for (var child : list) visit(child, totals, depth + 1, incoming);
            }
            case Tag.TAG_COMPOUND -> {
                var compound = (CompoundTag) tag;
                bytes = Math.multiplyExact((long) compound.size(), 2L * Long.BYTES);
                for (var key : compound.getAllKeys()) {
                    text = Math.addExact(text, Math.multiplyExact((long) key.length(), 2));
                    visit(compound.get(key), totals, depth + 1, incoming);
                }
            }
            default -> {
                /* Immutable native scalar values; headers are outside this capacity meter. */
            }
        }
        totals[0] = Math.addExact(totals[0], bytes);
        totals[1] = Math.addExact(totals[1], text);
    }

    /** Close must preserve readable native/template data: retire until the template token dies. */
    public void retire() {
        memory.retire();
    }

    /** Only unpublished failed preparation may drop its full reservation immediately. */
    public void abort() {
        memory.close();
    }
}
