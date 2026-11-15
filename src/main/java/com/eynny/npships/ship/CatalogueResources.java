package com.eynny.npships.ship;

import com.eynny.npships.resources.ResourceLimitException;
import com.eynny.npships.resources.ResourceMemoryBudget;
import com.eynny.npships.resources.ServerResourcePolicy;

import net.minecraft.server.MinecraftServer;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Admission for identifiable catalogue payload and metadata capacities, not a Java heap estimator.
 * Decoded NBT, object headers, registry/world graphs and external API retention remain unbounded by
 * this meter. Old template handles conservatively pin their entire generation's admitted weight.
 */
public final class CatalogueResources {
    private static final WeakHashMap<MinecraftServer, Ledger> SERVERS = new WeakHashMap<>();

    private CatalogueResources() {}

    static synchronized Generation begin(MinecraftServer server) {
        var limits = ServerResourcePolicy.current(server);
        var ledger =
                SERVERS.computeIfAbsent(
                        server,
                        unused ->
                                new Ledger(
                                        limits.maxCatalogueRetainedBytes(),
                                        ServerResourcePolicy.memory(server)));
        return ledger.begin(limits.maxCatalogueGenerationBytes());
    }

    static synchronized void clear(MinecraftServer server) {
        var ledger = SERVERS.remove(server);
        if (ledger != null) ledger.clear();
    }

    /** UTF-16 content capacity, not native NBT accounting or a Java object-size estimate. */
    public static long text(String value) {
        return Math.multiplyExact((long) value.length(), Character.BYTES);
    }

    /** Maximum supported 64-bit JVM reference width, excluding array/object headers. */
    public static long references(long count) {
        return Math.multiplyExact(count, Long.BYTES);
    }

    static final class Ledger {
        final long limit;
        final ResourceMemoryBudget working;
        final ReferenceQueue<Generation> collected = new ReferenceQueue<>();
        final Set<GenerationReference> live = new HashSet<>();
        long bytes;

        Ledger(long limit) {
            this(limit, null);
        }

        Ledger(long limit, ResourceMemoryBudget working) {
            this.working = working;
            if (limit < 0) throw new IllegalArgumentException("Negative catalogue retained limit");
            this.limit = limit;
        }

        synchronized Generation begin(long perGeneration) {
            drain();
            var generation = new Generation(this, perGeneration);
            var reference = new GenerationReference(generation, collected);
            generation.reference = reference;
            live.add(reference);
            return generation;
        }

        void drain() {
            GenerationReference reference;
            while ((reference = (GenerationReference) collected.poll()) != null)
                if (live.remove(reference)) bytes -= reference.bytes;
        }

        synchronized long available(Generation generation) {
            drain();
            if (generation.closed) throw new IllegalStateException("Closed catalogue generation");
            return Math.min(generation.limit - generation.reference.bytes, limit - bytes);
        }

        synchronized void check(Generation generation, long count) {
            if (count < 0) throw new IllegalArgumentException("Negative catalogue reservation");
            if (generation.closed) throw new IllegalStateException("Closed catalogue generation");
            drain();
            if (count > generation.limit - generation.reference.bytes)
                throw new ResourceLimitException(
                        "catalogue_generation_bytes",
                        generation.reference.bytes,
                        count,
                        generation.limit);
            if (count > limit - bytes)
                throw new ResourceLimitException("catalogue_retained_bytes", bytes, count, limit);
        }

        synchronized void reserve(Generation generation, long count) {
            check(generation, count);
            bytes = Math.addExact(bytes, count);
            generation.reference.bytes = Math.addExact(generation.reference.bytes, count);
        }

        synchronized void release(Generation generation, long count) {
            if (generation.closed) return;
            if (count < 0 || count > generation.reference.bytes)
                throw new IllegalArgumentException("Invalid catalogue release");
            generation.reference.bytes -= count;
            bytes -= count;
        }

        synchronized void discard(Generation generation) {
            if (generation.closed) return;
            generation.closed = true;
            if (live.remove(generation.reference)) bytes -= generation.reference.bytes;
            generation.reference.bytes = 0;
            generation.reference.clear();
        }

        synchronized void clear() {
            for (var reference : live) {
                var generation = reference.get();
                if (generation != null) generation.closed = true;
                reference.bytes = 0;
                reference.clear();
            }
            live.clear();
            bytes = 0;
            while (collected.poll() != null) {}
        }
    }

    private static final class GenerationReference extends WeakReference<Generation> {
        long bytes;

        GenerationReference(Generation value, ReferenceQueue<Generation> queue) {
            super(value, queue);
        }
    }

    /** Internal token shared by a catalogue and each of its frozen templates. */
    public static final class Generation implements AutoCloseable {
        private final Ledger ledger;
        private final long limit;
        private GenerationReference reference;
        private boolean closed;

        private Generation(Ledger ledger, long limit) {
            if (limit < 0) throw new IllegalArgumentException("Negative generation limit");
            this.ledger = ledger;
            this.limit = limit;
        }

        public long available() {
            return ledger.available(this);
        }

        public void check(long count) {
            ledger.check(this, count);
        }

        public void reserve(long count) {
            ledger.reserve(this, count);
        }

        public void release(long count) {
            ledger.release(this, count);
        }

        public long bytes() {
            synchronized (ledger) {
                return reference.bytes;
            }
        }

        public Scope derived() {
            return new Scope(this);
        }

        ResourceMemoryBudget.Scope working(String owner) {
            return ledger.working == null ? null : ledger.working.scope(owner);
        }

        @Override
        public void close() {
            ledger.discard(this);
        }
    }

    /** Explicitly released derived-view/index ownership; no periodic catalogue poll. */
    public static final class Scope implements AutoCloseable {
        private final Generation generation;
        private long bytes;
        private boolean closed;

        private Scope(Generation generation) {
            this.generation = generation;
        }

        public void reserve(long count) {
            if (closed) throw new IllegalStateException("Closed catalogue metadata scope");
            generation.reserve(count);
            bytes = Math.addExact(bytes, count);
        }

        public void release(long count) {
            if (count < 0 || count > bytes)
                throw new IllegalArgumentException("Invalid metadata release");
            if (closed) return;
            generation.release(count);
            bytes -= count;
        }

        public long bytes() {
            return bytes;
        }

        @Override
        public void close() {
            if (closed) return;
            generation.release(bytes);
            bytes = 0;
            closed = true;
        }
    }
}
