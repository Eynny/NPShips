package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.ship.DirectSpawnJournal;
import com.eynny.npships.ship.DirectSpawnOwnership;
import com.google.gson.JsonObject;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;
import java.util.function.LongConsumer;

/** Initial references for authored pairs while their native endpoints are placed. */
public final class InitialSpringLinks {
    public interface Attached {
        Endpoint npships$initialSpring();

        void npships$initialSpring(Endpoint value);
    }

    private static final class Lifetime {
        boolean closed;
    }

    /** A BE attachment contains only its pair, never the template, resolver or resource owner. */
    public static final class Endpoint {
        final Link link;
        final boolean controller;
        BlockEntity entity;
        ServerSubLevel body;
        boolean installed;

        Endpoint(Link link, boolean controller) {
            this.link = link;
            this.controller = controller;
        }

        Endpoint other() {
            return controller ? link.second : link.first;
        }
    }

    private static final class Link {
        final Lifetime lifetime;
        final double length;
        final Endpoint first = new Endpoint(this, true), second = new Endpoint(this, false);
        boolean resolved;

        Link(Lifetime lifetime, double length) {
            this.lifetime = lifetime;
            this.length = length;
        }

        void resolve() {
            if (resolved || first.entity == null || second.entity == null) return;
            requireCurrent(first);
            requireCurrent(second);
            Native.references(first, second);
            resolved = true;
        }

        void detach() {
            detach(first);
            detach(second);
        }

        private static void detach(Endpoint endpoint) {
            if (endpoint.entity instanceof Attached attached
                    && attached.npships$initialSpring() == endpoint)
                attached.npships$initialSpring(null);
            endpoint.entity = null;
            endpoint.body = null;
        }
    }

    private final Lifetime lifetime = new Lifetime();
    private List<JsonObject> patches;
    private LongConsumer reserve;
    private Map<BlockPos, Endpoint> endpoints = new HashMap<>();
    private List<Link> links = new ArrayList<>();
    private int read, cleanup;
    private boolean prepared;

    public InitialSpringLinks(List<JsonObject> patches, LongConsumer reserve) {
        this.patches = Objects.requireNonNull(patches);
        this.reserve = Objects.requireNonNull(reserve);
    }

    /**
     * One authored record per shared owner unit; each declared spring has exactly two endpoints.
     */
    public boolean prepareStep() {
        if (lifetime.closed) throw new IllegalStateException("Initial spring links are closed");
        if (prepared) return true;
        if (read < patches.size()) {
            var patch = patches.get(read++);
            if (com.eynny.npships.authoring.patches.ExternalPatches.external(patch)
                    || !patch.has("kind")
                    || !patch.get("kind").getAsString().equals("spring")
                    || !patch.has("target")) return false;
            var first = SchematicPatches.position(patch.getAsJsonArray("pos"));
            var second =
                    first.offset(
                            SchematicPatches.position(
                                    patch.getAsJsonObject("target").getAsJsonArray("offset")));
            if (endpoints.containsKey(first) || endpoints.containsKey(second))
                throw new IllegalArgumentException(
                        "Spring endpoint belongs to multiple authored pairs");
            reserve.accept(48L * Long.BYTES);
            var link = new Link(lifetime, SpringPatch.length(patch));
            endpoints.put(first, link.first);
            endpoints.put(second, link.second);
            links.add(link);
            return false;
        }
        patches = null;
        reserve = null;
        prepared = true;
        return true;
    }

    public void initialBlockPlaced(BlockPos local, BlockEntity entity) {
        var endpoint = endpoints.get(local);
        if (endpoint == null) return;
        if (!prepared || lifetime.closed || endpoint.installed)
            throw new IllegalStateException("Spring initial installation is not unique");
        install(endpoint, entity);
        Native.initial(endpoint);
        endpoint.link.resolve();
    }

    private static void install(Endpoint endpoint, BlockEntity entity) {
        if (!(entity instanceof Attached attached)
                || !(entity.getLevel() instanceof ServerLevel level)
                || !(Sable.HELPER.getContaining(entity) instanceof ServerSubLevel body)
                || !DirectSpawnOwnership.activePendingBody(body)
                || NativeBlockAccess.at(level, entity.getBlockPos()) != entity)
            throw new IllegalStateException(
                    "Initial spring endpoint is not an exact pending native BE");
        var old = attached.npships$initialSpring();
        if (old != null && old != endpoint)
            throw new IllegalStateException("Spring endpoint has another initial link");
        endpoint.entity = entity;
        endpoint.body = body;
        endpoint.installed = true;
        attached.npships$initialSpring(endpoint);
    }

    private static void requireCurrent(Endpoint endpoint) {
        var entity = endpoint.entity;
        if (entity == null
                || entity.isRemoved()
                || !(entity.getLevel() instanceof ServerLevel level)
                || !DirectSpawnOwnership.activePendingBody(endpoint.body)
                || NativeBlockAccess.at(level, entity.getBlockPos()) != entity)
            throw new IllegalStateException("Initial spring endpoint disappeared before pairing");
    }

    /** Only the native missing-peer destruction branch waits for a never-created authored peer. */
    public static boolean missingPeerWaits(BlockEntity entity, Level level, BlockPos target) {
        if (!(entity instanceof Attached attached)) return false;
        var endpoint = attached.npships$initialSpring();
        if (endpoint == null
                || endpoint.entity != entity
                || endpoint.link.lifetime.closed
                || endpoint.link.resolved
                || endpoint.other().installed
                || entity.isRemoved()
                || level != entity.getLevel()
                || !target.equals(entity.getBlockPos())
                || !(level instanceof ServerLevel server)) return false;
        return DirectSpawnOwnership.activePendingBody(endpoint.body)
                && NativeBlockAccess.at(server, target) == entity;
    }

    /** Native movement copies the pending attachment after the native BE's own load/afterMove. */
    public static void moved(BlockEntity oldEntity, BlockEntity newEntity) {
        if (!(oldEntity instanceof Attached old)) return;
        var endpoint = old.npships$initialSpring();
        if (endpoint == null
                || endpoint.entity != oldEntity
                || endpoint.link.lifetime.closed
                || !DirectSpawnOwnership.pendingBody(endpoint.body)) return;
        install(endpoint, newEntity);
        old.npships$initialSpring(null);
        if (!endpoint.link.resolved) endpoint.link.resolve();
    }

    /**
     * During exact pending teardown, a replaced peer is foreign state, not an authored endpoint.
     */
    public static boolean foreignPartnerMustSurvive(
            BlockEntity entity, Level level, BlockPos target) {
        if (!(entity instanceof Attached attached) || !(level instanceof ServerLevel server))
            return false;
        var endpoint = attached.npships$initialSpring();
        if (endpoint == null
                || endpoint.entity != entity
                || endpoint.body == null
                || !DirectSpawnJournal.pending(server, endpoint.body.getUniqueId())) return false;
        var peer = endpoint.other();
        if (peer.entity == null
                || peer.body == null
                || peer.entity.isRemoved()
                || !target.equals(peer.entity.getBlockPos())
                || !NativeBlockAccess.current(server, peer.body)
                || Sable.HELPER.getContaining(server, target) != peer.body) return true;
        var chunk =
                NativeBlockAccess.chunk(server, peer.body, target.getX() >> 4, target.getZ() >> 4);
        return !chunk.getBlockState(target).is(peer.entity.getBlockState().getBlock())
                || chunk.getBlockEntities().get(target) != peer.entity;
    }

    /** Direct completion verifies the actual relationship; it never reapplies authored fields. */
    public void verify(JsonObject patch) {
        if (!patch.has("target")) return;
        var endpoint = endpoints.get(SchematicPatches.position(patch.getAsJsonArray("pos")));
        if (endpoint == null
                || !endpoint.link.resolved
                || !Native.paired(endpoint.link.first, endpoint.link.second))
            throw new IllegalStateException(
                    "Initial spring pair did not reach its native references");
    }

    public void beginCleanup() {
        lifetime.closed = true;
    }

    public boolean stepCleanup() {
        beginCleanup();
        if (links == null) return true;
        if (cleanup < links.size()) {
            links.get(cleanup++).detach();
            return false;
        }
        patches = null;
        reserve = null;
        endpoints = null;
        links = null;
        return true;
    }

    /** Optional native subclasses are linked only when an actual declared spring is installed. */
    private static final class Native {
        private static dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity spring(
                Endpoint endpoint) {
            if (!(endpoint.entity
                    instanceof
                    dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity spring))
                throw new IllegalStateException(
                        "Authored spring endpoint has another native BE type");
            return spring;
        }

        static void initial(Endpoint endpoint) {
            var spring = spring(endpoint);
            spring.setController(endpoint.controller);
            spring.setDesiredLength(endpoint.link.length);
            spring.setChanged();
        }

        static void references(Endpoint first, Endpoint second) {
            var a = spring(first);
            var b = spring(second);
            a.setPartnerPos(b.getBlockPos(), second.body.getUniqueId());
            b.setPartnerPos(a.getBlockPos(), first.body.getUniqueId());
            a.setChanged();
            b.setChanged();
        }

        static boolean paired(Endpoint first, Endpoint second) {
            return spring(first).getPairedSpring() == second.entity
                    && spring(second).getPairedSpring() == first.entity;
        }
    }
}
