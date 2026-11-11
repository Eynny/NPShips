package com.eynny.npships.client.editor;

import com.eynny.npships.api.patches.*;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchCode;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/**
 * Screen-local sessions projected from the sole frozen registry, never a second registry/ticker.
 */
final class PatchEditorParticipants implements AutoCloseable {
    private final EditorToolsPanel panel;
    private final PatchPolicy policy = NPShipsRuntimeConfig.patchPolicy();
    private Map<ResourceLocation, PatchEditorParticipant> owners;
    private long lifetime;
    private boolean closed;
    private boolean initializing;
    private final IdentityHashMap<PatchEditorParticipant, Long> observed = new IdentityHashMap<>();

    PatchEditorParticipants(EditorToolsPanel panel) {
        this.panel = panel;
    }

    private Collection<PatchEditorParticipant> all() {
        if (closed) return List.of();
        if (owners == null) {
            if (initializing)
                throw new IllegalStateException("Reentrant editor participant creation");
            initializing = true;
            try {
                long generation = lifetime;
                var factories = PatchTypes.capabilities(PatchEditorParticipant.Factory.class);
                var active = new TreeSet<ResourceLocation>();
                for (var id : factories.keySet()) if (policy.enabled(id)) active.add(id);
                PatchCode.validate(policy, active);
                var created = new LinkedHashMap<ResourceLocation, PatchEditorParticipant>();
                try {
                    for (var id :
                            PatchOrdering.discoveryOrder(
                                    PatchStage.AUTHOR_PREPARATION, active, policy))
                        created.put(id, Objects.requireNonNull(factories.get(id).create(panel)));
                } catch (RuntimeException | LinkageError failure) {
                    created.values().forEach(PatchEditorParticipant::close);
                    throw failure;
                }
                if (closed || generation != lifetime) {
                    created.values().forEach(PatchEditorParticipant::close);
                    return List.of();
                }
                owners = Collections.unmodifiableMap(created);
                observed.clear();
                lifetime++;
            } finally {
                initializing = false;
            }
        }
        return owners.values();
    }

    void refresh() {
        all().forEach(PatchEditorParticipant::refresh);
    }

    void poll() {
        if (owners != null) owners.values().forEach(PatchEditorParticipant::poll);
    }

    long revision() {
        for (var owner : all()) {
            long value = owner.revision();
            var previous = observed.get(owner);
            if (previous == null || previous.longValue() != value) {
                observed.put(owner, value);
                lifetime++;
            }
        }
        return lifetime;
    }

    BlockPos representative(BlockPos target) {
        BlockPos result = target;
        for (var owner : all()) {
            var next = owner.representative(target);
            if (!next.equals(target)) {
                if (!result.equals(target) && !result.equals(next))
                    throw new IllegalArgumentException("Conflicting editor group ownership");
                result = next;
            }
        }
        return result;
    }

    boolean selects(JsonObject record, BlockPos selection) {
        return all().stream().anyMatch(owner -> owner.selects(record, selection));
    }

    boolean blocksCreation(BlockPos target) {
        return all().stream().anyMatch(owner -> owner.blocksCreation(target));
    }

    void highlights(Map<BlockPos, Boolean> result) {
        all().forEach(owner -> owner.highlights(result));
    }

    void label() {
        all().forEach(PatchEditorParticipant::label);
    }

    SchematicDocument prepareEdit(SchematicDocument source, JsonObject record) {
        var result = source;
        for (var owner : all()) result = owner.prepareEdit(result, record);
        return result;
    }

    void activate() {
        closed = false;
    }

    @Override
    public void close() {
        if (closed) return;
        var previous = owners;
        owners = null;
        closed = true;
        observed.clear();
        lifetime++;
        if (previous != null) previous.values().forEach(PatchEditorParticipant::close);
    }
}
