package com.eynny.npships.integration.contraptions;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDetachedData;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;

import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.createmod.ponder.api.VirtualBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.*;

/**
 * Captured storage is projected with Create's native API into short-lived, isolated block entities.
 */
public final class MountedResourceAuthoring implements PatchDetachedData {
    @Override
    public boolean applies(BlockEntity entity) {
        return entity != null;
    }

    @Override
    public void prepare(SchematicDocument doc, BlockPos pos, BlockEntity entity, CompoundTag data) {
        // Registry-only readers keep their original native data. Native unmount needs a Level view.
    }

    @Override
    public boolean resourceView(SchematicDocument doc, BlockPos pos) {
        var block = doc.blockEntryCopy(pos);
        return block != null
                && block.contains(MountedStorageSnapshot.KEY, Tag.TAG_COMPOUND)
                && (block.getCompound(MountedStorageSnapshot.KEY).contains("items")
                        || block.getCompound(MountedStorageSnapshot.KEY).contains("fluids"));
    }

    private static MountedStorageSnapshot.Prepared read(
            SchematicDocument doc, BlockPos pos, Level context) {
        var selected = new CompoundTag();
        selected.put("palette", doc.paletteCopy());
        var blocks = new ListTag();
        blocks.add(doc.blockEntryCopy(pos));
        selected.put("blocks", blocks);
        return MountedStorageSnapshot.prepare(context.registryAccess(), selected).getFirst();
    }

    @Override
    public void prepare(
            SchematicDocument doc,
            BlockPos pos,
            BlockEntity entity,
            CompoundTag data,
            Level context) {
        if (!resourceView(doc, pos)) return;
        var source = read(doc, pos, context);
        var view = new StorageLevel(doc, context);
        if (entity instanceof VirtualBlockEntity virtual) virtual.markVirtual();
        view.owned.put(pos.immutable(), entity);
        entity.setLevel(view);
        try {
            var complete = entity.saveWithoutMetadata(context.registryAccess());
            complete.merge(data);
            entity.loadWithComponents(complete, context.registryAccess());
            var before = entity.saveWithoutMetadata(context.registryAccess());
            if (source.items() != null)
                source.items().unmount(view, entity.getBlockState(), pos, entity);
            if (source.fluids() != null)
                source.fluids().unmount(view, entity.getBlockState(), pos, entity);
            com.eynny.npships.authoring.patches.SchematicResources.mergeSerialized(
                    data, before, entity.saveWithoutMetadata(context.registryAccess()));
        } finally {
            view.release();
        }
    }

    @Override
    public SchematicDocument acceptResourceView(
            SchematicDocument before, SchematicDocument after, BlockPos pos, Level context) {
        if (!resourceView(before, pos) || !before.blockName(pos).equals(after.blockName(pos)))
            return after;
        var source = read(before, pos, context);
        var old = before.blockEntryCopy(pos).getCompound(MountedStorageSnapshot.KEY);
        var ops = context.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        // A newer or unknown native field cannot be silently discarded when retiring this snapshot.
        if (source.items() != null
                        && !old.get("items")
                                .equals(
                                        MountedItemStorage.CODEC
                                                .encodeStart(ops, source.items())
                                                .getOrThrow())
                || source.fluids() != null
                        && !old.get("fluids")
                                .equals(
                                        MountedFluidStorage.CODEC
                                                .encodeStart(ops, source.fluids())
                                                .getOrThrow()))
            throw new IllegalArgumentException(
                    "Captured resource fields cannot be preserved by the installed native codec. Original data is unchanged.");
        var entry = after.blockEntryCopy(pos);
        var payload = entry.getCompound(MountedStorageSnapshot.KEY);
        payload.remove("items");
        payload.remove("fluids");
        if (payload.isEmpty()) entry.remove(MountedStorageSnapshot.KEY);
        else entry.put(MountedStorageSnapshot.KEY, payload);
        return after.withBlocks(Map.of(pos, entry), after.paletteCopy(), after.configCopy());
    }

    /** Sparse operation-local view. Block access never falls back to the user's level or chunks. */
    private static final class StorageLevel extends SchematicLevel {
        private final SchematicDocument document;
        private final List<BlockState> palette;
        private final Map<BlockPos, BlockEntity> owned = new HashMap<>();

        StorageLevel(SchematicDocument document, Level original) {
            super(BlockPos.ZERO, original);
            this.document = document;
            palette =
                    document.paletteCopy().stream()
                            .map(
                                    tag ->
                                            NbtUtils.readBlockState(
                                                    original.holderLookup(Registries.BLOCK),
                                                    (CompoundTag) tag))
                            .toList();
            renderMode = true;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            var index = document.blocks().get(pos);
            return index == null ? Blocks.AIR.defaultBlockState() : palette.get(index);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (owned.containsKey(pos)) return owned.get(pos);
            var state = getBlockState(pos);
            if (!(state.getBlock() instanceof EntityBlock factory)) return null;
            if (owned.size() >= 256)
                throw new IllegalArgumentException(
                        "Captured resource view needs too many dependent block entities");
            var entity = factory.newBlockEntity(pos.immutable(), state);
            if (entity == null) return null;
            owned.put(pos.immutable(), entity);
            if (entity instanceof VirtualBlockEntity virtual) virtual.markVirtual();
            entity.setLevel(this);
            var data = entity.saveWithoutMetadata(registryAccess());
            data.merge(document.blockEntityData(pos));
            entity.loadWithComponents(data, registryAccess());
            return entity;
        }

        void release() {
            for (var entity : owned.values()) entity.setLevel(null);
            owned.clear();
        }

        @Override
        public boolean addFreshEntity(Entity entity) {
            return false;
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            throw new UnsupportedOperationException("Resource view cannot place blocks");
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
            throw new UnsupportedOperationException("Resource view cannot place blocks");
        }

        @Override
        public void setBlockEntity(BlockEntity entity) {
            throw new UnsupportedOperationException(
                    "Resource view owns its detached block entities");
        }

        @Override
        public void removeBlockEntity(BlockPos pos) {
            throw new UnsupportedOperationException("Resource view cannot remove block entities");
        }

        @Override
        public void blockEntityChanged(BlockPos pos) {}

        @Override
        public void sendBlockUpdated(
                BlockPos pos, BlockState before, BlockState after, int flags) {}

        @Override
        public void updateNeighborsAt(BlockPos pos, Block block) {}

        @Override
        public void blockEvent(BlockPos pos, Block block, int first, int second) {}

        @Override
        public ServerLevel getLevel() {
            throw new UnsupportedOperationException("Resource view has no live server level");
        }

        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            throw new UnsupportedOperationException("Resource view cannot access chunks");
        }
    }
}
