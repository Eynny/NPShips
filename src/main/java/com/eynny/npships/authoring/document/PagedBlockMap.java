package com.eynny.npships.authoring.document;

import net.minecraft.core.BlockPos;

import java.util.*;

/** Immutable hash pages: a local edit copies one small bucket, not the entire schematic. */
final class PagedBlockMap<V> extends AbstractMap<BlockPos, V> {
    private static final int PAGE_COUNT = 2048;
    private final Map<BlockPos, V>[] pages;
    private final int count;

    @SuppressWarnings("unchecked")
    PagedBlockMap(Map<BlockPos, V> initial) {
        pages = (Map<BlockPos, V>[]) new Map<?, ?>[PAGE_COUNT];
        for (var e : initial.entrySet()) {
            int i = index(e.getKey());
            if (pages[i] == null) pages[i] = new HashMap<>();
            pages[i].put(e.getKey(), e.getValue());
        }
        count = initial.size();
    }

    private PagedBlockMap(Map<BlockPos, V>[] pages, int count) {
        this.pages = pages;
        this.count = count;
    }

    private static int index(Object key) {
        int h = key.hashCode();
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return h & (PAGE_COUNT - 1);
    }

    PagedBlockMap<V> updated(Map<BlockPos, V> changes) {
        if (changes.isEmpty()) return this;
        var next = pages.clone();
        var copied = new BitSet(PAGE_COUNT);
        int size = count;
        for (var e : changes.entrySet()) {
            int i = index(e.getKey());
            if (!copied.get(i)) {
                next[i] = next[i] == null ? new HashMap<>() : new HashMap<>(next[i]);
                copied.set(i);
            }
            if (e.getValue() == null) {
                if (next[i].remove(e.getKey()) != null) size--;
            } else if (next[i].put(e.getKey().immutable(), e.getValue()) == null) size++;
        }
        return new PagedBlockMap<>(next, size);
    }

    @Override
    public V get(Object key) {
        if (key == null) return null;
        var page = pages[index(key)];
        return page == null ? null : page.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public Set<Entry<BlockPos, V>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return count;
            }

            @Override
            public Iterator<Entry<BlockPos, V>> iterator() {
                return new Iterator<>() {
                    int page;
                    Iterator<Entry<BlockPos, V>> current = Collections.emptyIterator();

                    @Override
                    public boolean hasNext() {
                        while (!current.hasNext() && page < pages.length) {
                            var p = pages[page++];
                            if (p != null) current = p.entrySet().iterator();
                        }
                        return current.hasNext();
                    }

                    @Override
                    public Entry<BlockPos, V> next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        return new SimpleImmutableEntry<>(current.next());
                    }
                };
            }
        };
    }
}
