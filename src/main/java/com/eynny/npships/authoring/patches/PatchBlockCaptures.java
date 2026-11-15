package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchOrdering;
import com.eynny.npships.api.patches.PatchPolicy;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Operation-local code plan inside the existing capture loop; never a scanner or scheduler. */
public final class PatchBlockCaptures {
    private final PatchPolicy policy;
    private final Map<ResourceLocation, PatchBlockCapture.Operation> operations =
            new LinkedHashMap<>();
    private final TreeSet<ResourceLocation> active = new TreeSet<>();

    public PatchBlockCaptures() {
        policy = NPShipsRuntimeConfig.patchPolicy();
        var code = PatchTypes.capabilities(PatchBlockCapture.class);
        var scratch = new PatchBlockCapture.Context();
        var defaults = new ArrayList<>(code.keySet());
        defaults.sort(Comparator.comparingInt(id -> code.get(id).defaultRank()));
        // Disabled code still participates in pure applicability. It refuses only affected input;
        // it is never invoked for capture and unrelated source remains usable.
        for (var id :
                PatchOrdering.discoveryOrder(
                        PatchStage.CAPTURE_BLOCK,
                        code.keySet(),
                        new PatchPolicy(java.util.Set.of(), policy.preferredTypes()),
                        defaults)) operations.put(id, code.get(id).begin(scratch));
    }

    public JsonObject capture(
            ServerLevel level,
            BlockPos origin,
            SchematicDocument source,
            BlockPos pos,
            String name,
            CompoundTag data,
            JsonObject previous,
            java.util.function.BiPredicate<ResourceLocation, BlockPos> replaced)
            throws Exception {
        for (var entry : operations.entrySet()) {
            if (!entry.getValue().applies(level, origin, source, pos, name, data)) continue;
            if (active.add(entry.getKey())) PatchRuntimePolicy.validate(policy, active);
            if (policy.enabled(entry.getKey()))
                previous =
                        entry.getValue()
                                .capture(
                                        level, origin, source, pos, name, data, previous, replaced);
        }
        return previous;
    }

    public boolean skipRemaining(
            SchematicDocument source, BlockPos pos, String name, CompoundTag data) {
        for (var operation : operations.values())
            if (operation.skipRemaining(source, pos, name, data)) return true;
        return false;
    }

    public CompoundTag entry(
            ServerLevel level,
            BlockPos origin,
            SchematicDocument source,
            BlockPos pos,
            String name,
            CompoundTag data,
            java.util.function.BiPredicate<ResourceLocation, BlockPos> replaced)
            throws Exception {
        CompoundTag result = null;
        for (var entry : operations.entrySet()) {
            if (!entry.getValue().changesEntry()) continue;
            if (!entry.getValue().applies(level, origin, source, pos, name, data)) continue;
            if (active.add(entry.getKey())) PatchRuntimePolicy.validate(policy, active);
            if (policy.enabled(entry.getKey()))
                result =
                        entry.getValue()
                                .entry(level, origin, source, pos, name, data, result, replaced);
        }
        return result;
    }

    public void complete(
            ServerLevel level, SchematicDocument source, com.google.gson.JsonArray entries)
            throws Exception {
        for (var entry : operations.entrySet())
            if (active.contains(entry.getKey()) && policy.enabled(entry.getKey()))
                entry.getValue().complete(level, source, entries);
    }
}
