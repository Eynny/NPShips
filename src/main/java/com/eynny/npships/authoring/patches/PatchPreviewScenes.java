package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchOrdering;
import com.eynny.npships.api.patches.PatchPolicy;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.function.Function;

/**
 * One preview's immutable policy and lazily opened detached sessions; no registry scan per frame.
 */
public final class PatchPreviewScenes {
    private record Kind(Class<?> entity, BlockState state) {}

    private final PatchPolicy policy = NPShipsRuntimeConfig.patchPolicy();
    private final Map<ResourceLocation, PatchPreviewScene> code =
            PatchTypes.capabilities(PatchPreviewScene.class);
    private final Map<ResourceLocation, PatchPreviewScene.Session> sessions = new LinkedHashMap<>();
    private final Map<Kind, List<PatchPreviewScene.Session>> kinds = new HashMap<>();
    private SchematicDocument document;

    public PatchPreviewScenes(SchematicDocument document) {
        this.document = document;
    }

    private List<PatchPreviewScene.Session> forEntity(BlockEntity entity) {
        String name = BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString();
        return kinds.computeIfAbsent(
                new Kind(entity.getClass(), entity.getBlockState()),
                ignored -> {
                    var applicable = new TreeSet<ResourceLocation>();
                    for (var entry : code.entrySet())
                        if (entry.getValue().applies(entity, name)) applicable.add(entry.getKey());
                    PatchRuntimePolicy.validate(policy, applicable);
                    var result = new ArrayList<PatchPreviewScene.Session>();
                    for (var id :
                            PatchOrdering.discoveryOrder(
                                    PatchStage.AUTHOR_PREPARATION, applicable, policy))
                        result.add(
                                sessions.computeIfAbsent(id, key -> code.get(key).open(document)));
                    return List.copyOf(result);
                });
    }

    public Set<BlockPos> refresh(SchematicDocument next) {
        var changed = new HashSet<BlockPos>();
        for (var session : sessions.values()) changed.addAll(session.refresh(next));
        document = next;
        kinds.clear();
        return changed;
    }

    public boolean loadNative(BlockEntity entity) {
        boolean result = true;
        for (var session : forEntity(entity)) result &= session.loadNative(entity);
        return result;
    }

    public void prepare(
            BlockEntity entity,
            BlockPos pos,
            CompoundTag saved,
            Function<BlockPos, BlockState> states) {
        for (var session : forEntity(entity)) session.prepare(entity, pos, saved, states);
    }

    public void afterNativeLoad(BlockEntity entity, BlockPos pos, HolderLookup.Provider registry) {
        for (var session : forEntity(entity)) session.afterNativeLoad(entity, pos, registry);
    }

    public void afterVirtual(
            BlockEntity entity, BlockPos pos, CompoundTag saved, HolderLookup.Provider registry) {
        for (var session : forEntity(entity)) session.afterVirtual(entity, pos, saved, registry);
    }

    public Component describe(BlockEntity entity) {
        if (entity == null) return null;
        String name = BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString();
        Component result = null;
        for (var session : forEntity(entity)) {
            var detail = session.describe(entity, name);
            if (detail == null) continue;
            if (result != null)
                throw new IllegalArgumentException("Competing native descriptions: " + name);
            result = detail;
        }
        return result;
    }
}
