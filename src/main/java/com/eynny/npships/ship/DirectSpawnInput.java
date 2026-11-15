package com.eynny.npships.ship;

import com.eynny.npships.authoring.io.SubLevelImport;
import com.eynny.npships.authoring.patches.PatchCode;
import com.eynny.npships.authoring.patches.PatchVolumeFormat;
import com.eynny.npships.authoring.patches.PatchedTemplate;
import com.eynny.npships.authoring.patches.SchematicPatches;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Decodes one prepared cell or glue volume per turn; does not copy the whole prepared template. */
final class DirectSpawnInput implements AutoCloseable {
    final DetachedAssemblySource source;
    ListTag entities;
    private PatchedTemplate owner;
    private ListTag blocks, palette;
    private Map<Integer, BlockState> states = new HashMap<>();
    private GroupMemberships groups;
    Map<BlockPos, Set<BlockPos>> membership = Map.of();
    private Map<String, Map.Entry<net.minecraft.resources.ResourceLocation, PatchVolumeFormat>>
            volumes = new HashMap<>();
    private int blockIndex, entityIndex;
    private boolean complete, cleaning, closed;
    private long cleanupProgress;

    DirectSpawnInput(ServerLevel level, PatchedTemplate owner, BlockPos size) {
        this.owner = owner;
        var input = owner.directInput();
        blocks = input.getList("blocks", Tag.TAG_COMPOUND);
        palette = input.getList("palette", Tag.TAG_COMPOUND);
        if (input.contains("palettes", Tag.TAG_LIST))
            throw new IllegalStateException("Palette selection must precede direct input decoding");
        entities = input.getList("entities", Tag.TAG_COMPOUND);
        source =
                DetachedAssemblySource.openEmpty(
                        level, BlockPos.ZERO, size, owner::reserveDirectCapacity);
        for (var entry : PatchCode.sourceFormats(PatchVolumeFormat.class).entrySet())
            volumes.put(entry.getValue().type(), entry);
    }

    boolean step() {
        if (closed || cleaning) throw new IllegalStateException("Direct input is closed");
        if (complete) return true;
        if (blockIndex < blocks.size()) {
            var block = blocks.getCompound(blockIndex++);
            int index = block.getInt("state");
            if (index < 0 || index >= palette.size())
                throw new IllegalArgumentException("Prepared palette index outside palette");
            var state =
                    states.computeIfAbsent(
                            index,
                            key ->
                                    NbtUtils.readBlockState(
                                            source.holderLookup(Registries.BLOCK),
                                            palette.getCompound(key)));
            var local = SchematicPatches.blockPosition(block);
            source.append(
                    local,
                    state,
                    block.contains("nbt", Tag.TAG_COMPOUND) ? block.getCompound("nbt") : null);
            if (!state.isAir() && block.contains(SubLevelImport.GROUP, Tag.TAG_STRING)) {
                if (groups == null) {
                    owner.reserveDirectCapacity(8L * Long.BYTES);
                    groups = new GroupMemberships(owner::reserveDirectCapacity);
                    membership = groups;
                }
                groups.add(block.getString(SubLevelImport.GROUP), local);
            }
            return false;
        }
        if (entityIndex < entities.size()) {
            var wrapper = entities.getCompound(entityIndex++);
            var data = wrapper.getCompound("nbt");
            var entry = volumes.get(data.getString("id"));
            if (entry != null) {
                var format = PatchCode.requireNativeVolume(entry);
                var list = wrapper.getList("pos", Tag.TAG_DOUBLE);
                if (list.size() != 3)
                    throw new IllegalArgumentException("Invalid authored volume position");
                var position = new Vec3(list.getDouble(0), list.getDouble(1), list.getDouble(2));
                var bounds = format.bounds(data, position);
                if (bounds == null)
                    throw new IllegalArgumentException("Missing native volume bounds");
                source.appendEntity(format.detached(source, bounds, data));
            }
            return false;
        }
        source.seal();
        complete = true;
        return true;
    }

    boolean nativeVolume(int index) {
        if (closed || cleaning) throw new IllegalStateException("Direct input is closed");
        return volumes.containsKey(entities.getCompound(index).getCompound("nbt").getString("id"));
    }

    /**
     * One lazy shared position index and one insertion chain per group. Balanced small pages have
     * no whole-source hash-table rehash, and even many tiny groups do not each allocate a page
     * table. Values are one stable immutable view per group, never one wrapper per source cell.
     */
    static final class GroupMemberships extends AbstractMap<BlockPos, Set<BlockPos>> {
        private static final int PAGE_COUNT = 512;
        private final java.util.function.LongConsumer reserve;
        private TreeMap<BlockPos, Group>[] pages;
        private TreeMap<String, Group> groups;
        private int size;

        private static final class Cell {
            final BlockPos position;
            Cell next;

            Cell(BlockPos position) {
                this.position = position;
            }
        }

        private final class Group extends AbstractSet<BlockPos> {
            final Set<BlockPos> view = Collections.unmodifiableSet(this);
            Cell first, last;
            int count;

            @Override
            public int size() {
                return count;
            }

            @Override
            public boolean contains(Object key) {
                return groupAt(key) == this;
            }

            @Override
            public Iterator<BlockPos> iterator() {
                return new Iterator<>() {
                    Cell next = first;

                    @Override
                    public boolean hasNext() {
                        return next != null;
                    }

                    @Override
                    public BlockPos next() {
                        if (next == null) throw new NoSuchElementException();
                        var position = next.position;
                        next = next.next;
                        return position;
                    }
                };
            }

            void append(BlockPos position) {
                var cell = new Cell(position);
                if (last == null) first = cell;
                else last.next = cell;
                last = cell;
                count++;
            }
        }

        GroupMemberships(java.util.function.LongConsumer reserve) {
            this.reserve = Objects.requireNonNull(reserve);
        }

        @SuppressWarnings("unchecked")
        void add(String id, BlockPos position) {
            Objects.requireNonNull(id);
            Objects.requireNonNull(position);
            if (groupAt(position) != null)
                throw new IllegalArgumentException(
                        "Duplicate imported source position: " + position);
            int index = page(position);
            var group = groups == null ? null : groups.get(id);
            boolean newPage = pages == null || pages[index] == null;
            long growth = 12L * Long.BYTES; // One tree entry and ordered cell link.
            if (pages == null)
                growth +=
                        com.eynny.npships.resources.ResourceMemoryBudget.arrayBytes(
                                        PAGE_COUNT, Long.BYTES)
                                + 8L * Long.BYTES;
            if (newPage) growth += 8L * Long.BYTES;
            if (group == null) growth += 20L * Long.BYTES; // Group, one view and group index entry.
            // Admission precedes every predictable array/page/group/node mutation. A refused
            // insertion leaves the previously published map and ordered group views unchanged.
            reserve.accept(growth);
            if (pages == null) {
                pages = (TreeMap<BlockPos, Group>[]) new TreeMap<?, ?>[PAGE_COUNT];
                groups = new TreeMap<>();
            }
            if (newPage) pages[index] = new TreeMap<>();
            if (group == null) {
                group = new Group();
                groups.put(id, group);
            }
            var key = position.immutable();
            pages[index].put(key, group);
            group.append(key);
            size++;
        }

        private static int page(BlockPos position) {
            long mixed = position.asLong();
            mixed ^= mixed >>> 33;
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            return (int) mixed & (PAGE_COUNT - 1);
        }

        private Group groupAt(Object key) {
            if (pages == null || !(key instanceof BlockPos position)) return null;
            var page = pages[page(position)];
            return page == null ? null : page.get(position);
        }

        @Override
        public Set<BlockPos> get(Object key) {
            var group = groupAt(key);
            return group == null ? null : group.view;
        }

        @Override
        public boolean containsKey(Object key) {
            return groupAt(key) != null;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public Set<Entry<BlockPos, Set<BlockPos>>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return GroupMemberships.this.size;
                }

                @Override
                public Iterator<Entry<BlockPos, Set<BlockPos>>> iterator() {
                    return new Iterator<>() {
                        int page;
                        Iterator<Entry<BlockPos, Group>> entries = Collections.emptyIterator();

                        @Override
                        public boolean hasNext() {
                            while (!entries.hasNext() && pages != null && page < PAGE_COUNT) {
                                var next = pages[page++];
                                if (next != null) entries = next.entrySet().iterator();
                            }
                            return entries.hasNext();
                        }

                        @Override
                        public Entry<BlockPos, Set<BlockPos>> next() {
                            if (!hasNext()) throw new NoSuchElementException();
                            var entry = entries.next();
                            return new SimpleImmutableEntry<>(
                                    entry.getKey(), entry.getValue().view);
                        }
                    };
                }
            };
        }
    }

    void beginCleanup() {
        if (closed || cleaning) return;
        cleaning = true;
        source.beginCleanup();
    }

    boolean stepCleanup(java.util.function.BooleanSupplier allowed) {
        Objects.requireNonNull(allowed);
        if (closed) return true;
        beginCleanup();
        long before = source.cleanupProgress();
        boolean done = source.stepCleanup(allowed);
        cleanupProgress += source.cleanupProgress() - before;
        if (!done || !allowed.getAsBoolean()) return false;
        // Partition cleanup precedes this owner; no external provider receives these scratch maps.
        states = null;
        membership = null;
        groups = null;
        volumes = null;
        owner = null;
        blocks = null;
        palette = null;
        entities = null;
        closed = true;
        cleaning = false;
        cleanupProgress++;
        return true;
    }

    long cleanupProgress() {
        return cleanupProgress;
    }

    /** Explicit synchronous drain for existing fixtures; runtime calls stepCleanup. */
    @Override
    public void close() {
        beginCleanup();
        while (!stepCleanup(() -> true)) {}
    }
}
