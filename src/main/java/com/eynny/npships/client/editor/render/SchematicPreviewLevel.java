package com.eynny.npships.client.editor.render;

import com.eynny.npships.authoring.document.PreviewBlockData;
import com.eynny.npships.authoring.document.SchematicDocument;

import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.createmod.ponder.api.VirtualBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;

/** Static, isolated view: no entity deserialization, scheduled ticks or live contraptions. */
public final class SchematicPreviewLevel extends SchematicLevel {
    @Override
    public long getGameTime() {
        return 0;
    }

    @Override
    public long getDayTime() {
        return 0;
    }

    private SchematicDocument document;
    private PreviewTankTopology tankTopology;
    private final java.util.Set<BlockPos> absentBlockEntities = new java.util.HashSet<>();
    public final PreviewDiagnostics diagnostics = new PreviewDiagnostics();
    private final net.minecraft.world.level.storage.WritableLevelData previewData;
    private final net.minecraft.world.TickRateManager previewTicks =
            new net.minecraft.world.TickRateManager();
    private final net.minecraft.world.scores.Scoreboard previewScoreboard =
            new net.minecraft.world.scores.Scoreboard();
    private net.minecraft.world.level.lighting.LevelLightEngine previewLight;
    private final java.util.Map<Long, Integer> occupiedSections = new java.util.HashMap<>();
    java.util.Set<BlockPos> translucentPositions = java.util.Set.of();
    Boolean meshTranslucent;
    private int ceiling;
    private int sliceMode;
    private final com.eynny.npships.authoring.patches.PatchPreviewScenes nativeScenes;

    public SchematicPreviewLevel(final SchematicDocument document, final Level original) {
        super(BlockPos.ZERO, original);
        previewData = new PreviewData(original);
        this.document = document;
        renderMode = true;
        var size = document.size();
        bounds = new BoundingBox(0, 0, 0, size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        ceiling = size.getY() - 1;
        var paletteNbt = document.paletteCopy();
        var missingMods = new java.util.TreeSet<String>();
        var missingBlocks = new java.util.TreeSet<String>();
        for (int i = 0; i < paletteNbt.size(); i++) {
            String name = paletteNbt.getCompound(i).getString("Name");
            var id = ResourceLocation.tryParse(name);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                missingBlocks.add(name);
                if (id != null && !net.neoforged.fml.ModList.get().isLoaded(id.getNamespace()))
                    missingMods.add(id.getNamespace());
            }
        }
        if (!missingBlocks.isEmpty()) {
            String detail =
                    net.minecraft.network.chat.Component.translatable(
                                    "npships.editor.missing_blocks",
                                    String.join(", ", missingBlocks))
                            .getString();
            if (!missingMods.isEmpty())
                detail =
                        net.minecraft.network.chat.Component.translatable(
                                                "npships.editor.missing_mods",
                                                String.join(", ", missingMods))
                                        .getString()
                                + "\n"
                                + detail;
            throw new IllegalArgumentException(detail);
        }
        var palette = new ArrayList<BlockState>();
        for (int i = 0; i < paletteNbt.size(); i++) {
            var tag = paletteNbt.getCompound(i);
            palette.add(NbtUtils.readBlockState(original.holderLookup(Registries.BLOCK), tag));
        }
        document.blocks()
                .forEach(
                        (pos, index) -> {
                            BlockState state = palette.get(index);
                            if (!state.isAir()) {
                                blocks.put(pos, state);
                                countSection(pos, 1);
                            }
                        });
        tankTopology = new PreviewTankTopology(document, blocks, diagnostics::add);
        nativeScenes = new com.eynny.npships.authoring.patches.PatchPreviewScenes(document);
    }

    private void countSection(BlockPos pos, int delta) {
        long key = net.minecraft.core.SectionPos.asLong(pos);
        occupiedSections.compute(
                key,
                (k, count) -> {
                    int next = (count == null ? 0 : count) + delta;
                    return next <= 0 ? null : next;
                });
    }

    /** Conservative distance to occupied 16-block sections, not the whole schematic bounds. */
    public double occupiedSectionDistance(double x, double y, double z) {
        double nearest = Double.POSITIVE_INFINITY;
        for (long key : occupiedSections.keySet()) {
            double sx = net.minecraft.core.SectionPos.x(key) * 16.0;
            double sy = net.minecraft.core.SectionPos.y(key) * 16.0;
            double sz = net.minecraft.core.SectionPos.z(key) * 16.0;
            double dx = Math.max(0, Math.max(sx - x, x - sx - 16));
            double dy = Math.max(0, Math.max(sy - y, y - sy - 16));
            double dz = Math.max(0, Math.max(sz - z, z - sz - 16));
            nearest = Math.min(nearest, dx * dx + dy * dy + dz * dz);
            if (nearest == 0) break;
        }
        return Double.isFinite(nearest) ? Math.sqrt(nearest) : 12;
    }

    public void slice(final int ceiling, final boolean singleLayer) {
        this.ceiling = ceiling;
        this.sliceMode = singleLayer ? 1 : 0;
    }

    /**
     * Retained native entities keep this level identity while their detached block context changes.
     */
    public void refresh(SchematicDocument next) {
        if (document == next) return;
        var changed = next.changedBlocks(document, 256);
        if (changed == null) {
            changed = new java.util.HashSet<>(document.blocks().keySet());
            changed.addAll(next.blocks().keySet());
        }
        if (!next.size().equals(document.size())) {
            var size = next.size();
            bounds = new BoundingBox(0, 0, 0, size.getX() - 1, size.getY() - 1, size.getZ() - 1);
            ceiling = size.getY() - 1;
        }
        update(next, changed);
    }

    public java.util.Set<BlockPos> update(SchematicDocument next, java.util.Set<BlockPos> changed) {
        changed = new java.util.HashSet<>(changed);
        changed.addAll(nativeScenes.refresh(next));
        document = next;
        var palette = next.paletteCopy();
        for (var p : changed) {
            diagnostics.remove(p);
            absentBlockEntities.remove(p);
            var index = next.blocks().get(p);
            var state =
                    index == null
                            ? Blocks.AIR.defaultBlockState()
                            : NbtUtils.readBlockState(
                                    holderLookup(Registries.BLOCK), palette.getCompound(index));
            boolean occupied = blocks.containsKey(p);
            if (occupied == state.isAir()) countSection(p, occupied ? -1 : 1);
            if (state.isAir()) blocks.remove(p);
            else blocks.put(p, state);
            var removed = blockEntities.remove(p);
            if (removed != null) renderedBlockEntities.remove(removed);
        }
        var topologyChanges = tankTopology.update(next, blocks, changed);
        for (var p : topologyChanges) {
            diagnostics.remove(p);
            absentBlockEntities.remove(p);
            var removed = blockEntities.remove(p);
            if (removed != null) renderedBlockEntities.remove(removed);
        }
        changed.addAll(topologyChanges);
        return changed;
    }

    public void slice(int height, int mode) {
        ceiling = height;
        sliceMode = mode;
    }

    public boolean visible(final BlockPos pos) {
        return com.eynny.npships.authoring.geometry.PreviewSlice.visible(pos, ceiling, sliceMode);
    }

    public String savedLootTable(BlockPos pos) {
        return document.savedLootTable(pos);
    }

    /** Resource-derived native instances are lazy; accepted document data remains unchanged. */
    public void resetVisualInstances() {
        absentBlockEntities.clear();
        blockEntities.clear();
        renderedBlockEntities.clear();
        diagnostics.clear();
    }

    public BlockState originalState(final BlockPos pos) {
        var state = blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        return tankTopology == null ? state : tankTopology.state(pos, state);
    }

    BlockPos visualMask;
    java.util.function.Predicate<BlockPos> meshRegion;

    @Override
    public BlockState getBlockState(final BlockPos pos) {
        if (Boolean.FALSE.equals(meshTranslucent) && translucentPositions.contains(pos))
            return Blocks.AIR.defaultBlockState();
        if (pos.equals(visualMask) || meshRegion != null && !meshRegion.test(pos))
            return Blocks.AIR.defaultBlockState();
        return visible(pos) ? originalState(pos) : Blocks.AIR.defaultBlockState();
    }

    @Override
    public BlockEntity getBlockEntity(final BlockPos pos) {
        if (!visible(pos) || isOutsideBuildHeight(pos) || diagnostics.contains(pos)) return null;
        if (absentBlockEntities.contains(pos)) return null;
        if (blockEntities.containsKey(pos)) return blockEntities.get(pos);
        var state = getBlockState(pos);
        if (!state.hasBlockEntity()) return null;
        try {
            var target = pos.immutable();
            var be =
                    ((net.minecraft.world.level.block.EntityBlock) state.getBlock())
                            .newBlockEntity(target, state);
            if (be == null) {
                // EntityBlock may have state-dependent ownership (e.g. an upper sliding door).
                // Cache legitimate absence, but never hide saved data that cannot be represented.
                if (document.blockEntityData(target).getAllKeys().stream()
                        .anyMatch(key -> !java.util.Set.of("x", "y", "z").contains(key)))
                    throw new IllegalStateException(
                            "Native state has no BlockEntity but saved BlockEntity data is present");
                absentBlockEntities.add(target);
                return null;
            }
            if (BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()) == null
                    || !be.getType().isValid(state)
                    || !be.getBlockPos().equals(target)
                    || !be.getBlockState().equals(state))
                throw new IllegalArgumentException(
                        "Unregistered or incompatible native BlockEntity type, state or position");
            onBEadded(be, target);
            blockEntities.put(target, be);
            renderedBlockEntities.add(be);
            return be;
        } catch (Exception failure) {
            fail(pos, "npships.editor.preview_be_load", failure);
            return null;
        }
    }

    private void fail(BlockPos pos, String key, Exception failure) {
        var removed = blockEntities.remove(pos);
        if (removed != null) renderedBlockEntities.remove(removed);
        diagnostics.add(
                pos.immutable(),
                net.minecraft.network.chat.Component.translatable(
                        key,
                        PreviewDiagnostics.bounded(
                                BuiltInRegistries.BLOCK.getKey(originalState(pos).getBlock()), 160),
                        pos.toShortString(),
                        PreviewDiagnostics.cause(failure)));
    }

    @Override
    protected void onBEadded(final BlockEntity blockEntity, final BlockPos pos) {
        super.onBEadded(blockEntity, pos);
        // Native readers may ask for their own block entity while resolving multiblocks.
        blockEntities.put(pos, blockEntity);
        var saved =
                com.eynny.npships.authoring.patches.SchematicResources.projectedData(
                        document, pos, this);
        if (nativeScenes.loadNative(blockEntity)) {
            nativeScenes.prepare(blockEntity, pos, saved, this::originalState);
            tankTopology.visualData(pos, saved);
            PreviewBlockData.load(blockEntity, saved, registryAccess());
        }
        nativeScenes.afterNativeLoad(blockEntity, pos, registryAccess());
        if (blockEntity instanceof VirtualBlockEntity virtual) virtual.markVirtual();
        nativeScenes.afterVirtual(blockEntity, pos, saved, registryAccess());
    }

    @Override
    public net.neoforged.neoforge.client.model.data.ModelData getModelData(BlockPos pos) {
        var be = getBlockEntity(pos);
        if (be != null)
            try {
                return java.util.Objects.requireNonNull(
                        be.getModelData(), "Native getModelData returned null");
            } catch (Exception failure) {
                fail(pos, "npships.editor.preview_be_model", failure);
            }
        return net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
    }

    @Override
    public net.neoforged.neoforge.common.world.AuxiliaryLightManager getAuxLightManager(
            BlockPos pos) {
        return null;
    }

    public net.minecraft.network.chat.Component describe(BlockPos pos) {
        var id = BuiltInRegistries.BLOCK.getKey(originalState(pos).getBlock());
        var be = getBlockEntity(pos);
        var nativeDescription = nativeScenes.describe(be);
        if (nativeDescription != null) return nativeDescription;
        return originalState(pos)
                .getBlock()
                .getName()
                .copy()
                .append(
                        "\n"
                                + net.minecraft.commands.arguments.blocks.BlockStateParser
                                        .serialize(originalState(pos)));
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }

    @Override
    public int getHeight() {
        return SchematicDocument.MAX_SIDE;
    }

    @Override
    protected BlockState processBlockStateForPrinting(final BlockState state) {
        return state;
    }

    @Override
    public boolean addFreshEntity(final Entity entity) {
        return false;
    }

    @Override
    public boolean setBlock(final BlockPos pos, final BlockState state, final int flags) {
        return false;
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        return false;
    }

    @Override
    public void setBlockEntity(BlockEntity entity) {}

    @Override
    public void removeBlockEntity(BlockPos pos) {}

    @Override
    public void blockEntityChanged(BlockPos pos) {}

    @Override
    public void blockEvent(
            BlockPos pos, net.minecraft.world.level.block.Block block, int id, int param) {}

    @Override
    public void updateNeighborsAt(BlockPos pos, net.minecraft.world.level.block.Block block) {}

    @Override
    public void updateNeighborsAtExceptFromFacing(
            BlockPos pos,
            net.minecraft.world.level.block.Block block,
            net.minecraft.core.Direction except) {}

    @Override
    public void neighborChanged(
            BlockPos pos, net.minecraft.world.level.block.Block block, BlockPos from) {}

    @Override
    public net.minecraft.world.level.Explosion explode(
            Entity source,
            net.minecraft.world.damagesource.DamageSource damage,
            net.minecraft.world.level.ExplosionDamageCalculator calculator,
            double x,
            double y,
            double z,
            float radius,
            boolean fire,
            Level.ExplosionInteraction interaction,
            boolean particles,
            net.minecraft.core.particles.ParticleOptions small,
            net.minecraft.core.particles.ParticleOptions large,
            net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound) {
        throw new UnsupportedOperationException(
                "Preview cannot execute explosions or their events");
    }

    @Override
    public net.minecraft.world.level.storage.LevelData getLevelData() {
        return previewData;
    }

    @Override
    public net.minecraft.world.level.GameRules getGameRules() {
        return previewData.getGameRules();
    }

    @Override
    public net.minecraft.world.TickRateManager tickRateManager() {
        return previewTicks;
    }

    @Override
    public net.minecraft.world.scores.Scoreboard getScoreboard() {
        return previewScoreboard;
    }

    @Override
    public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
        if (previewLight == null)
            previewLight =
                    new net.minecraft.world.level.lighting.LevelLightEngine(
                            getChunkSource(), false, false);
        return previewLight;
    }

    @Override
    public net.minecraft.world.level.saveddata.maps.MapId getFreeMapId() {
        throw new UnsupportedOperationException("Preview cannot allocate map IDs");
    }

    @Override
    public net.minecraft.server.level.ServerLevel getLevel() {
        throw new UnsupportedOperationException("Preview has no live ServerLevel");
    }

    /** WrappedLevel otherwise exposes live weather/rules, even though its blocks are detached. */
    private static final class PreviewData
            implements net.minecraft.world.level.storage.WritableLevelData {
        private final net.minecraft.world.Difficulty difficulty;
        private final boolean hardcore;
        private final net.minecraft.world.level.GameRules rules;

        PreviewData(Level original) {
            difficulty = original.getDifficulty();
            hardcore = original.getLevelData().isHardcore();
            rules = original.getGameRules().copy();
        }

        @Override
        public BlockPos getSpawnPos() {
            return BlockPos.ZERO;
        }

        @Override
        public float getSpawnAngle() {
            return 0;
        }

        @Override
        public long getGameTime() {
            return 0;
        }

        @Override
        public long getDayTime() {
            return 0;
        }

        @Override
        public boolean isThundering() {
            return false;
        }

        @Override
        public boolean isRaining() {
            return false;
        }

        @Override
        public void setRaining(boolean raining) {}

        @Override
        public void setSpawn(BlockPos pos, float angle) {}

        @Override
        public boolean isHardcore() {
            return hardcore;
        }

        @Override
        public net.minecraft.world.level.GameRules getGameRules() {
            return rules;
        }

        @Override
        public net.minecraft.world.Difficulty getDifficulty() {
            return difficulty;
        }

        @Override
        public boolean isDifficultyLocked() {
            return true;
        }
    }
}
