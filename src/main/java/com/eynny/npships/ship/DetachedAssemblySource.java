package com.eynny.npships.ship;

import net.createmod.catnip.levelWrappers.DummyLevelEntityGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTickAccess;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/** Callback-scoped prepared blocks, using the legacy unrotated source-world coordinate frame. */
public final class DetachedAssemblySource extends Level {
    public static final class UnsupportedAccess extends RuntimeException {
        private final String operation;

        public UnsupportedAccess(String operation) {
            super("Detached assembly does not support " + operation);
            this.operation = operation;
        }

        public String operation() {
            return operation;
        }
    }

    private BoundingBox bounds;
    private final LevelEntityGetter<Entity> emptyEntities = new DummyLevelEntityGetter<>();
    private final BlockPos origin;
    private LongConsumer reservation;
    private final RegistryAccess registries;
    private final net.minecraft.world.flag.FeatureFlagSet features;
    private final Metadata metadata;
    private final int minimumY, height;
    private Map<BlockPos, BlockState> ownedBlocks;
    private boolean closed;

    public static DetachedAssemblySource open(
            ServerLevel context,
            BlockPos origin,
            BlockPos size,
            Map<BlockPos, BlockState> effectiveLocalBlocks,
            LongConsumer reserveReferences) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(size);
        Objects.requireNonNull(effectiveLocalBlocks);
        Objects.requireNonNull(reserveReferences);
        if (!context.getServer().isSameThread())
            throw new IllegalStateException("Detached assembly requires the server thread");
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0)
            throw new IllegalArgumentException("Invalid detached source size");
        // Entry references and overlapping resize buckets, not an estimate of total native heap.
        reserveMap(reserveReferences, effectiveLocalBlocks.size());
        return new DetachedAssemblySource(
                context, origin, size, effectiveLocalBlocks, reserveReferences);
    }

    static void reserveMap(LongConsumer reserve, long entries) {
        long buckets = 1;
        while (buckets < entries * 4 / 3 + 1) buckets = Math.multiplyExact(buckets, 2);
        reserve.accept(
                Math.multiplyExact(
                        Math.addExact(
                                Math.multiplyExact(entries, 3), Math.multiplyExact(buckets, 2)),
                        Long.BYTES));
    }

    private record Context(
            Metadata metadata,
            ResourceKey<Level> dimension,
            RegistryAccess registries,
            Holder<DimensionType> dimensionType,
            net.minecraft.world.flag.FeatureFlagSet features,
            int minimumY,
            int height,
            boolean debug) {
        static Context capture(ServerLevel context) {
            return new Context(
                    new Metadata(context),
                    context.dimension(),
                    context.registryAccess(),
                    context.dimensionTypeRegistration(),
                    context.enabledFeatures(),
                    context.getMinBuildHeight(),
                    context.getHeight(),
                    context.isDebug());
        }
    }

    private static ProfilerFiller inactiveProfiler() {
        return InactiveProfiler.INSTANCE;
    }

    private DetachedAssemblySource(
            ServerLevel context,
            BlockPos origin,
            BlockPos size,
            Map<BlockPos, BlockState> input,
            LongConsumer reservation) {
        this(Context.capture(context), origin, size, input, reservation);
    }

    private DetachedAssemblySource(
            Context context,
            BlockPos origin,
            BlockPos size,
            Map<BlockPos, BlockState> input,
            LongConsumer reservation) {
        super(
                context.metadata(),
                context.dimension(),
                context.registries(),
                context.dimensionType(),
                DetachedAssemblySource::inactiveProfiler,
                false,
                context.debug(),
                0,
                0);
        this.reservation = reservation;
        this.origin = origin.immutable();
        registries = context.registries();
        features = context.features();
        metadata = context.metadata();
        minimumY = context.minimumY();
        height = context.height();
        int maxX = Math.addExact(origin.getX(), size.getX() - 1),
                maxY = Math.addExact(origin.getY(), size.getY() - 1),
                maxZ = Math.addExact(origin.getZ(), size.getZ() - 1);
        bounds = new BoundingBox(origin.getX(), origin.getY(), origin.getZ(), maxX, maxY, maxZ);
        ownedBlocks = new HashMap<>();
        for (var entry : input.entrySet()) {
            var local = Objects.requireNonNull(entry.getKey());
            var state = Objects.requireNonNull(entry.getValue());
            if (local.getX() < 0
                    || local.getY() < 0
                    || local.getZ() < 0
                    || local.getX() >= size.getX()
                    || local.getY() >= size.getY()
                    || local.getZ() >= size.getZ())
                throw new IllegalArgumentException("Prepared block outside source bounds");
            var world =
                    new BlockPos(
                            Math.addExact(origin.getX(), local.getX()),
                            Math.addExact(origin.getY(), local.getY()),
                            Math.addExact(origin.getZ(), local.getZ()));
            if (!state.isAir()) ownedBlocks.put(world, state);
        }
    }

    public void reserveReferences(long references) {
        ensureOpen();
        reservation.accept(Math.multiplyExact(references, Long.BYTES));
    }

    public void reserveMap(long entries) {
        ensureOpen();
        reserveMap(reservation, entries);
    }

    public BlockPos origin() {
        ensureOpen();
        return origin;
    }

    public Set<BlockPos> permittedSourceWorld() {
        ensureOpen();
        return Collections.unmodifiableSet(ownedBlocks.keySet());
    }

    public BlockPos local(BlockPos world) {
        ensureOpen();
        return world.subtract(origin);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Detached assembly source is closed");
    }

    private UnsupportedAccess denied(String operation) {
        ensureOpen();
        return new UnsupportedAccess(operation);
    }

    @Override
    public BlockState getBlockState(BlockPos position) {
        ensureOpen();
        if (isOutsideBuildHeight(position)) return Blocks.AIR.defaultBlockState();
        if (!bounds.isInside(position)) throw denied("outside_source_blocks");
        return ownedBlocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
    }

    @Override
    public net.minecraft.world.level.material.FluidState getFluidState(BlockPos position) {
        return getBlockState(position).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos position) {
        var state = getBlockState(position);
        if (state.hasBlockEntity()) throw denied("block_entity");
        return null;
    }

    public Map<BlockPos, BlockState> getBlockMap() {
        ensureOpen();
        return Collections.unmodifiableMap(ownedBlocks);
    }

    public Set<BlockPos> getAllPositions() {
        return permittedSourceWorld();
    }

    public List<Entity> getEntityList() {
        ensureOpen();
        return List.of();
    }

    @Override
    public List<Entity> getEntities(Entity ignored, AABB box, Predicate<? super Entity> filter) {
        ensureOpen();
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntitiesOfClass(
            Class<T> type, AABB box, Predicate<? super T> filter) {
        ensureOpen();
        return List.of();
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        ensureOpen();
        return emptyEntities;
    }

    @Override
    public Entity getEntity(int id) {
        ensureOpen();
        return null;
    }

    @Override
    public List<? extends Player> players() {
        ensureOpen();
        return List.of();
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        ensureOpen();
        if (!bounds.isInside(pos)) throw denied("outside_source_readiness");
        return true;
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(pos));
    }

    @Override
    public RegistryAccess registryAccess() {
        ensureOpen();
        return registries;
    }

    @Override
    public int getMinBuildHeight() {
        return minimumY;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMaxBuildHeight() {
        return minimumY + height;
    }

    @Override
    public int getMinSection() {
        return minimumY >> 4;
    }

    @Override
    public int getMaxSection() {
        return (minimumY + height) >> 4;
    }

    @Override
    public int getSectionsCount() {
        return height >> 4;
    }

    @Override
    public int getSectionIndex(int y) {
        return (y >> 4) - getMinSection();
    }

    @Override
    public int getSectionIndexFromSectionY(int y) {
        return y - getMinSection();
    }

    @Override
    public int getSectionYFromSectionIndex(int index) {
        return index + getMinSection();
    }

    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        return isOutsideBuildHeight(pos.getY());
    }

    @Override
    public boolean isOutsideBuildHeight(int y) {
        return y < minimumY || y >= minimumY + height;
    }

    @Override
    public LevelData getLevelData() {
        ensureOpen();
        return metadata;
    }

    @Override
    public long getGameTime() {
        ensureOpen();
        return metadata.gameTime;
    }

    @Override
    public long getDayTime() {
        ensureOpen();
        return metadata.dayTime;
    }

    @Override
    public TickRateManager tickRateManager() {
        throw denied("tick_rate");
    }

    public ServerLevel getLevel() {
        throw denied("live_level");
    }

    @Override
    public MinecraftServer getServer() {
        throw denied("server");
    }

    @Override
    public ChunkSource getChunkSource() {
        throw denied("chunks");
    }

    public void setChunkSource(ChunkSource source) {
        throw denied("chunk_source_mutation");
    }

    @Override
    public LevelLightEngine getLightEngine() {
        throw denied("light_engine");
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        throw denied("light");
    }

    @Override
    public int getMaxLocalRawBrightness(BlockPos pos) {
        throw denied("light");
    }

    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        throw denied("biome");
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        throw denied("biome");
    }

    @Override
    public GameRules getGameRules() {
        throw denied("game_rules");
    }

    @Override
    public net.minecraft.world.scores.Scoreboard getScoreboard() {
        throw denied("scoreboard");
    }

    @Override
    public net.minecraft.world.item.crafting.RecipeManager getRecipeManager() {
        throw denied("recipes");
    }

    @Override
    public net.minecraft.world.item.alchemy.PotionBrewing potionBrewing() {
        throw denied("brewing");
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        throw denied("block_ticks");
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        throw denied("fluid_ticks");
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags) {
        throw denied("block_mutation");
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        throw denied("block_mutation");
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drops) {
        throw denied("block_mutation");
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        throw denied("block_mutation");
    }

    @Override
    public void setBlockEntity(BlockEntity entity) {
        throw denied("block_entity_mutation");
    }

    @Override
    public void removeBlockEntity(BlockPos pos) {
        throw denied("block_entity_mutation");
    }

    @Override
    public void blockEntityChanged(BlockPos pos) {
        throw denied("block_entity_mutation");
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        throw denied("entity_mutation");
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState before, BlockState after, int flags) {
        throw denied("block_updates");
    }

    @Override
    public void updateNeighbourForOutputSignal(BlockPos pos, Block block) {
        throw denied("neighbor_updates");
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block) {
        throw denied("neighbor_updates");
    }

    @Override
    public void blockEvent(BlockPos pos, Block block, int first, int second) {
        throw denied("block_events");
    }

    @Override
    public void levelEvent(Player player, int event, BlockPos pos, int data) {
        throw denied("level_events");
    }

    @Override
    public void gameEvent(
            Entity entity,
            Holder<net.minecraft.world.level.gameevent.GameEvent> event,
            net.minecraft.world.phys.Vec3 pos) {
        throw denied("game_events");
    }

    @Override
    public void gameEvent(
            Holder<net.minecraft.world.level.gameevent.GameEvent> event,
            net.minecraft.world.phys.Vec3 pos,
            net.minecraft.world.level.gameevent.GameEvent.Context context) {
        throw denied("game_events");
    }

    @Override
    public void destroyBlockProgress(int id, BlockPos pos, int stage) {
        throw denied("block_events");
    }

    @Override
    public net.minecraft.world.level.saveddata.maps.MapId getFreeMapId() {
        throw denied("map_ids");
    }

    @Override
    public net.minecraft.world.level.saveddata.maps.MapItemSavedData getMapData(
            net.minecraft.world.level.saveddata.maps.MapId id) {
        throw denied("map_data");
    }

    @Override
    public void setMapData(
            net.minecraft.world.level.saveddata.maps.MapId id,
            net.minecraft.world.level.saveddata.maps.MapItemSavedData data) {
        throw denied("map_data");
    }

    public BoundingBox getBounds() {
        ensureOpen();
        return new BoundingBox(
                bounds.minX(),
                bounds.minY(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxY(),
                bounds.maxZ());
    }

    public void setBounds(BoundingBox next) {
        throw denied("bounds_mutation");
    }

    @Override
    public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
        ensureOpen();
        return features;
    }

    @Override
    public float getShade(Direction face, boolean shade) {
        throw denied("shade");
    }

    @Override
    public int getSkyDarken() {
        throw denied("sky_light");
    }

    @Override
    public float getDayTimeFraction() {
        throw denied("day_time_fraction");
    }

    @Override
    public float getDayTimePerTick() {
        throw denied("day_time_rate");
    }

    @Override
    public void setDayTimeFraction(float value) {
        throw denied("day_time_mutation");
    }

    @Override
    public void setDayTimePerTick(float value) {
        throw denied("day_time_mutation");
    }

    @Override
    public String gatherChunkSourceStats() {
        throw denied("chunk_stats");
    }

    @Override
    public <T, C> T getCapability(
            net.neoforged.neoforge.capabilities.BlockCapability<T, C> capability,
            BlockPos pos,
            C context) {
        throw denied("block_capability");
    }

    @Override
    public <T, C> T getCapability(
            net.neoforged.neoforge.capabilities.BlockCapability<T, C> capability,
            BlockPos pos,
            BlockState state,
            BlockEntity entity,
            C context) {
        throw denied("block_capability");
    }

    @Override
    public <T> T getCapability(
            net.neoforged.neoforge.capabilities.BlockCapability<T, Void> capability, BlockPos pos) {
        throw denied("block_capability");
    }

    @Override
    public <T> T getCapability(
            net.neoforged.neoforge.capabilities.BlockCapability<T, Void> capability,
            BlockPos pos,
            BlockState state,
            BlockEntity entity) {
        throw denied("block_capability");
    }

    @Override
    public void invalidateCapabilities(BlockPos pos) {
        throw denied("capability_mutation");
    }

    @Override
    public void invalidateCapabilities(net.minecraft.world.level.ChunkPos pos) {
        throw denied("capability_mutation");
    }

    @Override
    public void playSeededSound(
            Player player,
            double x,
            double y,
            double z,
            Holder<net.minecraft.sounds.SoundEvent> sound,
            net.minecraft.sounds.SoundSource category,
            float volume,
            float pitch,
            long seed) {
        throw denied("sound");
    }

    @Override
    public void playSeededSound(
            Player player,
            Entity entity,
            Holder<net.minecraft.sounds.SoundEvent> sound,
            net.minecraft.sounds.SoundSource category,
            float volume,
            float pitch,
            long seed) {
        throw denied("sound");
    }

    @Override
    public void playSound(
            Player player,
            double x,
            double y,
            double z,
            net.minecraft.sounds.SoundEvent sound,
            net.minecraft.sounds.SoundSource category,
            float volume,
            float pitch) {
        throw denied("sound");
    }

    @Override
    public void playSound(
            Player player,
            Entity entity,
            net.minecraft.sounds.SoundEvent sound,
            net.minecraft.sounds.SoundSource category,
            float volume,
            float pitch) {
        throw denied("sound");
    }

    @Override
    public void playLocalSound(
            double x,
            double y,
            double z,
            net.minecraft.sounds.SoundEvent sound,
            net.minecraft.sounds.SoundSource category,
            float volume,
            float pitch,
            boolean delay) {
        throw denied("sound");
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, Direction ignored) {
        throw denied("neighbor_updates");
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, BlockPos from) {
        throw denied("neighbor_updates");
    }

    @Override
    public void neighborChanged(
            BlockState state, BlockPos pos, Block block, BlockPos from, boolean moving) {
        throw denied("neighbor_updates");
    }

    @Override
    public net.minecraft.world.level.Explosion explode(
            Entity entity,
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
            Holder<net.minecraft.sounds.SoundEvent> sound) {
        throw denied("explosion");
    }

    @Override
    public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
        throw denied("world_border");
    }

    @Override
    public float getRainLevel(float partialTick) {
        throw denied("weather");
    }

    @Override
    public float getThunderLevel(float partialTick) {
        throw denied("weather");
    }

    @Override
    public boolean isRaining() {
        throw denied("weather");
    }

    @Override
    public boolean isThundering() {
        throw denied("weather");
    }

    @Override
    public boolean isRainingAt(BlockPos pos) {
        throw denied("weather");
    }

    @Override
    public void setRainLevel(float value) {
        throw denied("weather_mutation");
    }

    @Override
    public void setThunderLevel(float value) {
        throw denied("weather_mutation");
    }

    @Override
    public float getSunAngle(float partialTick) {
        throw denied("sky");
    }

    @Override
    public void updateSkyBrightness() {
        throw denied("sky_mutation");
    }

    @Override
    public void setSkyFlashTime(int value) {
        throw denied("sky_mutation");
    }

    // Known providers use snapshot data only. Unsupported native operations must refuse
    // before retaining borrowed objects or accepting mutations; this is not a Java sandbox.
    @Override
    public void markAndNotifyBlock(
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.chunk.LevelChunk chunk,
            net.minecraft.world.level.block.state.BlockState before,
            net.minecraft.world.level.block.state.BlockState after,
            int flags,
            int recursion) {
        throw denied("block_updates");
    }

    @Override
    public void onBlockStateChange(
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState before,
            net.minecraft.world.level.block.state.BlockState after) {
        throw denied("block_updates");
    }

    @Override
    public boolean destroyBlock(
            net.minecraft.core.BlockPos pos,
            boolean drops,
            net.minecraft.world.entity.Entity entity,
            int recursion) {
        throw denied("block_mutation");
    }

    @Override
    public void addDestroyBlockEffect(
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        throw denied("block_updates");
    }

    @Override
    public void setBlocksDirty(
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState before,
            net.minecraft.world.level.block.state.BlockState after) {
        throw denied("block_updates");
    }

    @Override
    public void neighborShapeChanged(
            net.minecraft.core.Direction direction,
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.core.BlockPos pos,
            net.minecraft.core.BlockPos neighborPos,
            int flags,
            int recursion) {
        throw denied("neighbor_updates");
    }

    @Override
    public boolean isDay() {
        throw denied("sky");
    }

    @Override
    public boolean isNight() {
        throw denied("sky");
    }

    @Override
    public void addParticle(
            net.minecraft.core.particles.ParticleOptions particle,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz) {
        throw denied("particles");
    }

    @Override
    public void addParticle(
            net.minecraft.core.particles.ParticleOptions particle,
            boolean overrideLimiter,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz) {
        throw denied("particles");
    }

    @Override
    public void addAlwaysVisibleParticle(
            net.minecraft.core.particles.ParticleOptions particle,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz) {
        throw denied("particles");
    }

    @Override
    public void addAlwaysVisibleParticle(
            net.minecraft.core.particles.ParticleOptions particle,
            boolean overrideLimiter,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz) {
        throw denied("particles");
    }

    @Override
    public void addBlockEntityTicker(
            net.minecraft.world.level.block.entity.TickingBlockEntity ticker) {
        throw denied("block_entity_mutation");
    }

    @Override
    public void addFreshBlockEntities(
            java.util.Collection<net.minecraft.world.level.block.entity.BlockEntity>
                    blockEntities) {
        throw denied("block_entity_mutation");
    }

    @Override
    protected void tickBlockEntities() {
        throw denied("block_entity_mutation");
    }

    @Override
    public <T extends net.minecraft.world.entity.Entity> void guardEntityTick(
            java.util.function.Consumer<T> consumer, T entity) {
        throw denied("entity_tick_mutation");
    }

    @Override
    public boolean shouldTickDeath(net.minecraft.world.entity.Entity entity) {
        throw denied("entity_tick_readiness");
    }

    @Override
    public boolean shouldTickBlocksAt(long chunkPos) {
        throw denied("block_tick_readiness");
    }

    @Override
    public boolean shouldTickBlocksAt(net.minecraft.core.BlockPos pos) {
        throw denied("block_tick_readiness");
    }

    @Override
    public int getSeaLevel() {
        throw denied("sea_level");
    }

    @Override
    public void disconnect() {
        throw denied("level_lifecycle");
    }

    @Override
    public boolean mayInteract(
            net.minecraft.world.entity.player.Player player, net.minecraft.core.BlockPos pos) {
        throw denied("interaction_permissions");
    }

    @Override
    public void broadcastEntityEvent(net.minecraft.world.entity.Entity entity, byte event) {
        throw denied("entity_events");
    }

    @Override
    public void broadcastDamageEvent(
            net.minecraft.world.entity.Entity entity,
            net.minecraft.world.damagesource.DamageSource damage) {
        throw denied("entity_events");
    }

    @Override
    public void globalLevelEvent(int event, net.minecraft.core.BlockPos pos, int data) {
        throw denied("level_events");
    }

    @Override
    public net.minecraft.CrashReportCategory fillReportDetails(net.minecraft.CrashReport report) {
        throw denied("live_level_diagnostics");
    }

    @Override
    public void createFireworks(
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            java.util.List<net.minecraft.world.item.component.FireworkExplosion> explosions) {
        throw denied("fireworks");
    }

    @Override
    public net.minecraft.world.DifficultyInstance getCurrentDifficultyAt(
            net.minecraft.core.BlockPos pos) {
        throw denied("local_difficulty");
    }

    @Override
    public void sendPacketToServer(net.minecraft.network.protocol.Packet<?> packet) {
        throw denied("network");
    }

    @Override
    public net.minecraft.util.RandomSource getRandom() {
        throw denied("random");
    }

    @Override
    public net.minecraft.core.BlockPos getBlockRandomPos(int x, int y, int z, int verticalMask) {
        throw denied("random");
    }

    @Override
    public boolean noSave() {
        throw denied("save_policy");
    }

    @Override
    public net.minecraft.world.level.biome.BiomeManager getBiomeManager() {
        throw denied("biome");
    }

    @Override
    public double getMaxEntityRadius() {
        throw denied("entity_radius");
    }

    @Override
    public double increaseMaxEntityRadius(double radius) {
        throw denied("entity_radius_mutation");
    }

    @Override
    public long nextSubTickCount() {
        throw denied("sub_tick_mutation");
    }

    @Override
    public <T> T getExistingDataOrNull(net.neoforged.neoforge.attachment.AttachmentType<T> type) {
        throw denied("attachments");
    }

    @Override
    public <T> T setData(net.neoforged.neoforge.attachment.AttachmentType<T> type, T value) {
        throw denied("attachment_mutation");
    }

    @Override
    public <T> T removeData(net.neoforged.neoforge.attachment.AttachmentType<T> type) {
        throw denied("attachment_mutation");
    }

    @Override
    public <T> boolean hasData(
            java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<T>>
                    typeSupplier) {
        throw denied("attachments");
    }

    @Override
    public <T> T getData(
            java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<T>>
                    typeSupplier) {
        throw denied("attachments");
    }

    @Override
    public <T> T getExistingDataOrNull(
            java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<T>>
                    typeSupplier) {
        throw denied("attachments");
    }

    @Override
    public <T> T setData(
            java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<T>>
                    typeSupplier,
            T value) {
        throw denied("attachment_mutation");
    }

    @Override
    public <T> T removeData(
            java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<T>>
                    typeSupplier) {
        throw denied("attachment_mutation");
    }

    @Override
    public void syncData(net.neoforged.neoforge.attachment.AttachmentType<?> type) {
        throw denied("attachment_mutation");
    }

    @Override
    public void syncData(
            java.util.function.Supplier<
                            ? extends net.neoforged.neoforge.attachment.AttachmentType<?>>
                    typeSupplier) {
        throw denied("attachment_mutation");
    }

    @Override
    public net.neoforged.neoforge.client.model.data.ModelData getModelData(
            net.minecraft.core.BlockPos pos) {
        throw denied("model_data");
    }

    @Override
    public net.neoforged.neoforge.client.model.data.ModelDataManager getModelDataManager() {
        throw denied("model_data");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ownedBlocks.clear();
        ownedBlocks = null;
        reservation = null;
    }

    private static final class Metadata implements WritableLevelData {
        final Difficulty difficulty;
        final boolean hardcore;
        final boolean difficultyLocked;
        final long gameTime, dayTime;
        final BlockPos spawn;
        final float angle;

        Metadata(Level source) {
            difficulty = source.getDifficulty();
            hardcore = source.getLevelData().isHardcore();
            difficultyLocked = source.getLevelData().isDifficultyLocked();
            gameTime = source.getGameTime();
            dayTime = source.getDayTime();
            spawn = source.getLevelData().getSpawnPos().immutable();
            angle = source.getLevelData().getSpawnAngle();
        }

        public BlockPos getSpawnPos() {
            return spawn;
        }

        public float getSpawnAngle() {
            return angle;
        }

        public long getGameTime() {
            return gameTime;
        }

        public long getDayTime() {
            return dayTime;
        }

        public boolean isThundering() {
            throw new UnsupportedAccess("weather");
        }

        public boolean isRaining() {
            throw new UnsupportedAccess("weather");
        }

        public void setRaining(boolean value) {
            throw new UnsupportedAccess("weather_mutation");
        }

        public void setSpawn(BlockPos pos, float angle) {
            throw new UnsupportedAccess("spawn_mutation");
        }

        public boolean isHardcore() {
            return hardcore;
        }

        public GameRules getGameRules() {
            throw new UnsupportedAccess("game_rules");
        }

        public Difficulty getDifficulty() {
            return difficulty;
        }

        public boolean isDifficultyLocked() {
            return difficultyLocked;
        }
    }
}
