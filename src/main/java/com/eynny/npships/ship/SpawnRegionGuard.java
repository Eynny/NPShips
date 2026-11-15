package com.eynny.npships.ship;

import com.eynny.npships.NPShips;
import com.eynny.npships.config.EnvironmentRule;
import com.eynny.npships.config.NPShipsStructureConfig;
import com.eynny.npships.config.SupportBlockDetector;
import com.eynny.npships.lifecycle.NPShipsLifecycleQuery;
import com.eynny.npships.mixin.NPShipsLevelEntityManagerAccessor;
import com.eynny.npships.naturalspawn.NaturalSpawnChunkLoader;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalValidator;
import com.eynny.npships.naturalspawn.NaturalSpawnRotationCheck;
import com.eynny.npships.naturalspawn.search.EnvironmentBlockMatcher;
import com.eynny.npships.naturalspawn.search.SpawnSearchVolume;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Attempt-owned live admission. Initial reads are resumable; real section/visibility/body mutations
 * maintain their validity until close. No world writes, ticker, block snapshot or final block
 * rescan.
 */
@EventBusSubscriber(modid = NPShips.MOD_ID)
public final class SpawnRegionGuard implements AutoCloseable {
    private enum Phase {
        ORIGINAL,
        ROTATED,
        SWEEP,
        UNDER,
        ABOVE,
        BODIES,
        CHUNKS,
        READY
    }

    private static final Object WATCH_LOCK = new Object();
    private static final Map<ServerLevel, Set<SpawnRegionGuard>> LEVELS = new IdentityHashMap<>();
    private static final Map<Object, Set<SpawnRegionGuard>> MANAGERS = new IdentityHashMap<>();
    private static final Map<LevelChunkSection, Map<SpawnRegionGuard, SectionWatch>> SECTIONS =
            new IdentityHashMap<>();
    private static volatile int activeGuards, retainedGuards;
    private static final int DIRTY_CAPACITY = 64;

    private record SectionWatch(SpawnRegionGuard owner, int x, int y, int z) {}

    private record ChunkWatch(long key, LevelChunk chunk) {}

    private final ServerLevel level;
    private final NPShipsStructureConfig config;
    private final BlockPos origin, size;
    private final SpawnSearchVolume original, rotated, swept, required;
    private final RotatedBox finalBox;
    private final boolean checkSweep;
    private final LongConsumer reserve;
    private final Object entityManager;
    private Set<UUID> owned = new HashSet<>();
    private Map<LevelChunkSection, SectionWatch> sections = new IdentityHashMap<>();
    private Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>();
    private List<ChunkWatch> chunkOrder = new ArrayList<>();
    private final SubLevel[] dirtyBodies = new SubLevel[DIRTY_CAPACITY];
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private Phase phase = Phase.ORIGINAL;
    private long cell, column, operations;
    private int distance = 1, bodyIndex, chunkIndex, dirtyCount;
    private boolean initialBodiesComplete, rescanBodies;
    private volatile boolean closed;
    private boolean cleanupComplete;
    private java.util.Iterator<Map.Entry<LevelChunkSection, SectionWatch>> cleanupSections;
    private long cleanupProgress;
    private volatile String failure;
    private long cachedChunkKey = Long.MIN_VALUE;
    private LevelChunk cachedChunk;

    private SpawnRegionGuard(
            ServerLevel level,
            NPShipsStructureConfig config,
            BlockPos origin,
            BlockPos size,
            double yaw,
            LongConsumer reserve) {
        this.level = Objects.requireNonNull(level);
        this.config = Objects.requireNonNull(config);
        this.origin = origin.immutable();
        this.size = size.immutable();
        this.reserve = Objects.requireNonNull(reserve);
        requireThread();
        finalBox = new RotatedBox(origin, size, yaw);
        original = box(origin, size);
        rotated = finalBox.volume();
        checkSweep = yaw != 0 || config.placement().requireRotationSpace();
        swept = checkSweep ? NaturalSpawnRotationCheck.sweptVolume(origin, size) : original;
        required = requiredVolume(config, origin, size, yaw);
        entityManager = ((NPShipsLevelEntityManagerAccessor) level).npships$entityManager();
        reserve.accept((DIRTY_CAPACITY + 24L) * Long.BYTES);
        synchronized (WATCH_LOCK) {
            LEVELS.computeIfAbsent(level, ignored -> identitySet()).add(this);
            MANAGERS.computeIfAbsent(entityManager, ignored -> identitySet()).add(this);
            activeGuards++;
            retainedGuards++;
        }
    }

    public static SpawnRegionGuard open(
            ServerLevel level,
            NPShipsStructureConfig config,
            BlockPos origin,
            BlockPos size,
            double yaw,
            LongConsumer reserve) {
        return new SpawnRegionGuard(level, config, origin, size, yaw, reserve);
    }

    /**
     * Retain these existing-loader chunks through construction; this class never requests chunks.
     */
    public static SpawnSearchVolume requiredVolume(
            NPShipsStructureConfig config, BlockPos origin, BlockPos size, double yaw) {
        var original = box(origin, size);
        var rotated = new RotatedBox(origin, size, yaw).volume();
        var swept =
                yaw != 0 || config.placement().requireRotationSpace()
                        ? NaturalSpawnRotationCheck.sweptVolume(origin, size)
                        : original;
        int under =
                config.environment().under().type() == EnvironmentRule.Type.NONE
                        ? 0
                        : config.environment().under().depth();
        int above =
                config.environment().above().type() == EnvironmentRule.Type.NONE
                        ? 0
                        : config.environment().above().depth();
        return new SpawnSearchVolume(
                Math.min(original.minX(), Math.min(rotated.minX(), swept.minX())),
                Math.toIntExact((long) original.minY() - under),
                Math.min(original.minZ(), Math.min(rotated.minZ(), swept.minZ())),
                Math.max(original.maxX(), Math.max(rotated.maxX(), swept.maxX())),
                Math.toIntExact((long) original.maxY() + above),
                Math.max(original.maxZ(), Math.max(rotated.maxZ(), swept.maxZ())));
    }

    private static SpawnSearchVolume box(BlockPos origin, BlockPos size) {
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0)
            throw new IllegalArgumentException("Spawn region dimensions must be positive");
        return new SpawnSearchVolume(
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                Math.toIntExact((long) origin.getX() + size.getX() - 1),
                Math.toIntExact((long) origin.getY() + size.getY() - 1),
                Math.toIntExact((long) origin.getZ() + size.getZ() - 1));
    }

    /**
     * Register only exact identities allocated by this attempt, including provider-owned children.
     */
    public void own(UUID id) {
        requireThread();
        Objects.requireNonNull(id);
        synchronized (WATCH_LOCK) {
            if (!owned.contains(id)) {
                reserve.accept(8L * Long.BYTES);
                owned.add(id);
            }
        }
    }

    /** Each callback check precedes the next cell, body or readiness observation. */
    public boolean step(BooleanSupplier canContinue) {
        requireThread();
        current();
        while (!ready() && canContinue.getAsBoolean()) {
            current();
            if (dirtyCount > 0) {
                var body = dirtyBodies[--dirtyCount];
                dirtyBodies[dirtyCount] = null;
                checkBody(body);
            } else if (rescanBodies) scanBody(true);
            else unit();
            operations++;
            current();
        }
        return ready();
    }

    public long operations() {
        return operations;
    }

    public SpawnSearchVolume requiredVolume() {
        return required;
    }

    /**
     * O(1) boundary check; callers retain this owner and its notifications through final commit.
     */
    public boolean ready() {
        return !closed
                && failure == null
                && phase == Phase.READY
                && dirtyCount == 0
                && !rescanBodies;
    }

    public void requireReady() {
        requireThread();
        current();
        if (!ready())
            throw new IllegalStateException(
                    "Spawn region validation still has bounded work pending");
    }

    private void current() {
        if (closed) throw new IllegalStateException("Spawn region guard is closed");
        if (level.getServer().getLevel(level.dimension()) != level)
            invalidate("Spawn dimension is no longer current");
        if (origin.getY() < level.getMinBuildHeight()
                || (long) origin.getY() + size.getY() > level.getMaxBuildHeight())
            invalidate("Spawn region is outside build height");
        var border = level.getWorldBorder();
        if (required.minX() < border.getMinX()
                || required.maxX() + 1.0 > border.getMaxX()
                || required.minZ() < border.getMinZ()
                || required.maxZ() + 1.0 > border.getMaxZ())
            invalidate("Spawn region is outside the current world border");
        if (failure != null) throw new IllegalStateException(failure);
    }

    private void unit() {
        switch (phase) {
            case ORIGINAL -> scanVolume(original, false, false);
            case ROTATED -> scanVolume(rotated, true, false);
            case SWEEP -> {
                if (checkSweep) scanVolume(swept, false, true);
                else advance();
            }
            case UNDER -> scanSide(config.environment().under(), false);
            case ABOVE -> scanSide(config.environment().above(), true);
            case BODIES -> scanBody(false);
            case CHUNKS -> {
                if (chunkIndex == chunkOrder.size()) {
                    advance();
                    return;
                }
                var watch = chunkOrder.get(chunkIndex++);
                int x = ChunkPos.getX(watch.key), z = ChunkPos.getZ(watch.key);
                if (level.getChunkSource().getChunkNow(x, z) != watch.chunk
                        || !NaturalSpawnChunkLoader.readyForPlacement(level, x, z))
                    invalidate("Spawn chunk changed identity or lost readiness at " + x + ", " + z);
            }
            case READY -> {}
        }
    }

    private void advance() {
        phase = Phase.values()[phase.ordinal() + 1];
        cell = column = 0;
        distance = 1;
    }

    private void scanVolume(SpawnSearchVolume volume, boolean finalYaw, boolean clearance) {
        if (cell == volume.blockCount()) {
            advance();
            return;
        }
        long at = cell++;
        int y = (int) (at % volume.sizeY());
        long col = at / volume.sizeY();
        int z = (int) (col % volume.sizeZ()), x = (int) (col / volume.sizeZ());
        position.set(volume.minX() + x, volume.minY() + y, volume.minZ() + z);
        if (finalYaw && !finalBox.cell(position.getX(), position.getZ())) return;
        var state = read(position, false);
        if (clearance
                ? SupportBlockDetector.isSupportBlock(level, position, state)
                : !config.environment().inside().matches(state))
            invalidate(
                    (clearance
                                    ? "Rotation clearance changed at "
                                    : "Spawn inside AIR/FLUID rule failed at ")
                            + position.toShortString());
    }

    private void scanSide(EnvironmentRule rule, boolean above) {
        if (rule.type() == EnvironmentRule.Type.NONE
                || column == (long) size.getX() * size.getZ()) {
            advance();
            return;
        }
        int x = (int) (column / size.getZ()), z = (int) (column % size.getZ());
        int y =
                Math.toIntExact(
                        (long) origin.getY()
                                + (above ? (long) size.getY() + distance - 1 : -distance));
        position.set(origin.getX() + x, y, origin.getZ() + z);
        var state = read(position, rule.type() == EnvironmentRule.Type.AIR);
        boolean done = false, valid;
        switch (rule.type()) {
            case AIR -> valid = state.isAir();
            case FLUID ->
                    valid =
                            state.getFluidState().isSource()
                                    && (rule.fluid() == null
                                            || state.getFluidState()
                                                    .getType()
                                                    .builtInRegistryHolder()
                                                    .key()
                                                    .location()
                                                    .equals(rule.fluid()));
            case ALL -> valid = EnvironmentBlockMatcher.matchesAll(level, position, state, rule);
            case SURFACE -> {
                if (SupportBlockDetector.isSupportBlock(level, position, state)) {
                    valid = EnvironmentBlockMatcher.matchesSurfaceSupport(state, rule);
                    done = true;
                } else
                    valid =
                            state.getFluidState().isEmpty()
                                    && !EnvironmentBlockMatcher.isExcluded(state, rule)
                                    && distance < rule.depth();
            }
            default -> throw new IllegalStateException("Unexpected environment side rule");
        }
        if (!valid) {
            invalidate(
                    "Spawn "
                            + (above ? "above" : "under")
                            + " rule failed at "
                            + position.toShortString());
            return;
        }
        if (done || distance == rule.depth()) {
            column++;
            distance = 1;
        } else distance++;
    }

    private BlockState read(BlockPos pos, boolean virtualAir) {
        if (level.isOutsideBuildHeight(pos)) {
            if (virtualAir) return Blocks.AIR.defaultBlockState();
            throw new IllegalStateException(
                    "Required spawn environment cell is outside build height: " + pos);
        }
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        long key = ChunkPos.asLong(x, z);
        if (cachedChunk == null || cachedChunkKey != key) {
            cachedChunk = level.getChunkSource().getChunkNow(x, z);
            cachedChunkKey = key;
            if (cachedChunk == null || !NaturalSpawnChunkLoader.readyForPlacement(level, x, z))
                throw new IllegalStateException(
                        "Required spawn chunk is not ready: " + x + ", " + z);
            if (!chunks.containsKey(key)) {
                reserve.accept(16L * Long.BYTES);
                chunks.put(key, cachedChunk);
                chunkOrder.add(new ChunkWatch(key, cachedChunk));
            } else if (chunks.get(key) != cachedChunk)
                throw new IllegalStateException("Spawn chunk changed during initial validation");
        }
        var section = cachedChunk.getSection(cachedChunk.getSectionIndex(pos.getY()));
        if (!sections.containsKey(section)) {
            reserve.accept(16L * Long.BYTES);
            var watch = new SectionWatch(this, x << 4, (pos.getY() >> 4) << 4, z << 4);
            synchronized (WATCH_LOCK) {
                sections.put(section, watch);
                SECTIONS.computeIfAbsent(section, ignored -> new IdentityHashMap<>())
                        .put(this, watch);
            }
        }
        return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    private void scanBody(boolean retry) {
        if (!config.placement().avoidPhysicalStructures()) {
            rescanBodies = false;
            initialBodiesComplete = true;
            if (!retry) advance();
            return;
        }
        var container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("Missing spawn physical container");
        var bodies = container.getAllSubLevels();
        if (bodyIndex >= bodies.size()) {
            bodyIndex = 0;
            rescanBodies = false;
            initialBodiesComplete = true;
            if (!retry) advance();
            return;
        }
        checkBody(bodies.get(bodyIndex++));
    }

    private void checkBody(SubLevel body) {
        if (body == null
                || body.isRemoved()
                || owned.contains(body.getUniqueId())
                || !hasBounds(body)) return;
        var container = SubLevelContainer.getContainer(level);
        if (container.getSubLevel(body.getUniqueId()) != body) return;
        var bounds = NPShipsLifecycleQuery.worldBounds(body);
        if (bounds != null && intersects(bounds))
            invalidate("Spawn region overlaps foreign physical body " + body.getUniqueId());
    }

    private static boolean hasBounds(SubLevel body) {
        var bounds = body.getPlot().getBoundingBox();
        return bounds != null
                && bounds.minX() <= bounds.maxX()
                && bounds.minY() <= bounds.maxY()
                && bounds.minZ() <= bounds.maxZ();
    }

    /** Shared exact authored/final-yaw footprint for resumable rotation preflight. */
    public static java.util.function.Predicate<AABB> physicalFootprint(
            BlockPos origin, BlockPos size, double yaw) {
        var fixedOrigin = origin.immutable();
        var fixedSize = size.immutable();
        var rotated = new RotatedBox(fixedOrigin, fixedSize, yaw);
        return bounds ->
                NaturalSpawnPhysicalValidator.overlapsBounds(bounds, fixedOrigin, fixedSize)
                        || rotated.intersects(bounds, true);
    }

    private boolean intersects(AABB bounds) {
        return NaturalSpawnPhysicalValidator.overlapsBounds(bounds, origin, size)
                || finalBox.intersects(bounds, true);
    }

    private boolean watches(int x, int y, int z) {
        if (y >= original.minY() && y <= original.maxY()) {
            if (x >= original.minX()
                    && x <= original.maxX()
                    && z >= original.minZ()
                    && z <= original.maxZ()) return true;
            if (finalBox.cell(x, z)) return true;
            if (checkSweep
                    && x >= swept.minX()
                    && x <= swept.maxX()
                    && z >= swept.minZ()
                    && z <= swept.maxZ()) return true;
        }
        return x >= original.minX()
                && x <= original.maxX()
                && z >= original.minZ()
                && z <= original.maxZ()
                && y >= required.minY()
                && y <= required.maxY();
    }

    private void invalidate(String message) {
        if (failure == null) failure = message;
    }

    private void requireThread() {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Spawn region guard requires the server thread");
    }

    /** Invoked at the actual section state write, including direct section writers. */
    public static void sectionChanged(
            LevelChunkSection section,
            int x,
            int y,
            int z,
            BlockState oldState,
            BlockState newState) {
        if (activeGuards == 0 || oldState == newState) return;
        synchronized (WATCH_LOCK) {
            var watchers = SECTIONS.get(section);
            if (watchers == null) return;
            for (var watch : watchers.values()) {
                var guard = watch.owner;
                if (!guard.closed
                        && guard.failure == null
                        && guard.watches(watch.x + x, watch.y + y, watch.z + z))
                    guard.invalidate(
                            "Spawn environment changed during construction at "
                                    + (watch.x + x)
                                    + ", "
                                    + (watch.y + y)
                                    + ", "
                                    + (watch.z + z));
            }
        }
    }

    /**
     * Native visibility changes invalidate actual already-read chunks, without polling a fake
     * epoch.
     */
    public static void visibilityChanged(Object manager, ChunkPos chunk, FullChunkStatus status) {
        if (activeGuards == 0 || status.isOrAfter(FullChunkStatus.FULL)) return;
        synchronized (WATCH_LOCK) {
            var guards = MANAGERS.get(manager);
            if (guards != null)
                for (var guard : guards)
                    if (!guard.closed && guard.chunks.containsKey(chunk.toLong()))
                        guard.invalidate("Spawn chunk lost native visibility: " + chunk);
        }
    }

    /**
     * Bound publications carry actual current poses/local extents; owned identities are excluded.
     */
    public static void bodyChanged(SubLevel body) {
        if (activeGuards == 0
                || body.isRemoved()
                || !hasBounds(body)
                || !(body.getLevel() instanceof ServerLevel level)) return;
        synchronized (WATCH_LOCK) {
            var guards = LEVELS.get(level);
            if (guards == null) return;
            AABB bounds = null;
            boolean queried = false;
            for (var guard : guards) {
                if (guard.closed
                        || guard.failure != null
                        || !guard.config.placement().avoidPhysicalStructures()
                        || guard.owned.contains(body.getUniqueId())) continue;
                if (!queried) {
                    bounds = NPShipsLifecycleQuery.worldBounds(body);
                    queried = true;
                }
                if (bounds == null || !guard.intersects(bounds) || guard.rescanBodies) continue;
                boolean queued = false;
                for (int i = 0; i < guard.dirtyCount; i++)
                    if (guard.dirtyBodies[i] == body) {
                        queued = true;
                        break;
                    }
                if (queued) continue;
                if (guard.dirtyCount == DIRTY_CAPACITY) {
                    java.util.Arrays.fill(guard.dirtyBodies, null);
                    guard.dirtyCount = 0;
                    guard.rescanBodies = true;
                    guard.bodyIndex = 0;
                } else guard.dirtyBodies[guard.dirtyCount++] = body;
            }
        }
    }

    /** List insertion/removal cannot make the resumable initial body cursor skip an old entry. */
    public static void bodyListChanged(ServerLevel level) {
        if (activeGuards == 0) return;
        synchronized (WATCH_LOCK) {
            var guards = LEVELS.get(level);
            if (guards != null)
                for (var guard : guards)
                    if (!guard.closed && (!guard.initialBodiesComplete || guard.rescanBodies))
                        guard.bodyIndex = 0;
        }
    }

    @SubscribeEvent
    public static void chunkUnloaded(ChunkEvent.Unload event) {
        if (activeGuards == 0 || !(event.getLevel() instanceof ServerLevel level)) return;
        synchronized (WATCH_LOCK) {
            var guards = LEVELS.get(level);
            if (guards != null)
                for (var guard : guards)
                    if (!guard.closed
                            && guard.chunks.containsKey(event.getChunk().getPos().toLong()))
                        guard.invalidate(
                                "Spawn chunk unloaded during construction: "
                                        + event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void levelUnloaded(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        synchronized (WATCH_LOCK) {
            var guards = LEVELS.get(level);
            if (guards != null)
                for (var guard : guards) {
                    guard.invalidate("Spawn dimension unloaded during construction");
                    guard.beginCleanup();
                }
        }
    }

    private static <T> Set<T> identitySet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Non-closing listener owners, not a count of admitted/ready spawn regions. */
    public static int activeCount() {
        return activeGuards;
    }

    /** Includes owners whose section registrations await their attempt's bounded cleanup. */
    public static int retainedCount() {
        return retainedGuards;
    }

    public void beginCleanup() {
        requireThread();
        synchronized (WATCH_LOCK) {
            if (closed) return;
            closed = true;
            activeGuards--;
            cleanupSections = sections.entrySet().iterator();
        }
    }

    /** Unregister one observed section per unit; no complete section/map clearing loop. */
    public boolean stepCleanup(BooleanSupplier allowed) {
        Objects.requireNonNull(allowed);
        requireThread();
        if (cleanupComplete) return true;
        beginCleanup();
        while (!cleanupComplete && allowed.getAsBoolean()) {
            synchronized (WATCH_LOCK) {
                if (cleanupSections.hasNext()) {
                    var section = cleanupSections.next().getKey();
                    var watchers = SECTIONS.get(section);
                    if (watchers != null) {
                        watchers.remove(this);
                        if (watchers.isEmpty()) SECTIONS.remove(section);
                    }
                } else {
                    var levels = LEVELS.get(level);
                    if (levels != null) {
                        levels.remove(this);
                        if (levels.isEmpty()) LEVELS.remove(level);
                    }
                    var managers = MANAGERS.get(entityManager);
                    if (managers != null) {
                        managers.remove(this);
                        if (managers.isEmpty()) MANAGERS.remove(entityManager);
                    }
                    retainedGuards--;
                    cleanupSections = null;
                    sections = null;
                    chunks = null;
                    chunkOrder = null;
                    owned = null;
                    java.util.Arrays.fill(dirtyBodies, null);
                    dirtyCount = 0;
                    cachedChunk = null;
                    cleanupComplete = true;
                }
                cleanupProgress++;
            }
        }
        return cleanupComplete;
    }

    public long cleanupProgress() {
        return cleanupProgress;
    }

    /**
     * Explicit synchronous drain for fixtures/legacy owners; runtime supplies its shared budget.
     */
    @Override
    public void close() {
        beginCleanup();
        while (!stepCleanup(() -> true)) {}
    }

    /**
     * OBB versus axis-aligned cell/bounds SAT; bounding-box corners alone never imply occupancy.
     */
    static final class RotatedBox {
        private final double x, z, hx, hz, cos, sin, ex, ez, minY, maxY;

        RotatedBox(BlockPos origin, BlockPos size, double yaw) {
            if (!Double.isFinite(yaw))
                throw new IllegalArgumentException("Spawn yaw must be finite");
            box(origin, size);
            hx = size.getX() / 2.0;
            hz = size.getZ() / 2.0;
            x = origin.getX() + hx;
            z = origin.getZ() + hz;
            cos = snap(Math.cos(yaw));
            sin = snap(Math.sin(yaw));
            ex = Math.abs(cos) * hx + Math.abs(sin) * hz;
            ez = Math.abs(sin) * hx + Math.abs(cos) * hz;
            minY = origin.getY();
            maxY = (long) origin.getY() + size.getY();
        }

        private static double snap(double value) {
            return Math.abs(value) < 1e-12
                    ? 0
                    : Math.abs(Math.abs(value) - 1) < 1e-12 ? Math.copySign(1, value) : value;
        }

        SpawnSearchVolume volume() {
            return new SpawnSearchVolume(
                    coordinate(Math.floor(x - ex)),
                    coordinate(minY),
                    coordinate(Math.floor(z - ez)),
                    coordinate(Math.ceil(x + ex) - 1),
                    coordinate(maxY - 1),
                    coordinate(Math.ceil(z + ez) - 1));
        }

        private static int coordinate(double value) {
            if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
                throw new IllegalArgumentException(
                        "Spawn transformed region exceeds coordinate range");
            return (int) value;
        }

        boolean cell(int bx, int bz) {
            return rectangle(bx + .5, bz + .5, .5, .5, false);
        }

        boolean intersects(AABB b, boolean touching) {
            if (touching ? b.maxY < minY || b.minY > maxY : b.maxY <= minY || b.minY >= maxY)
                return false;
            return rectangle(
                    (b.minX + b.maxX) / 2,
                    (b.minZ + b.maxZ) / 2,
                    b.getXsize() / 2,
                    b.getZsize() / 2,
                    touching);
        }

        private boolean rectangle(double bx, double bz, double bhx, double bhz, boolean touching) {
            double dx = bx - x, dz = bz - z;
            return overlap(Math.abs(dx), ex + bhx, touching)
                    && overlap(Math.abs(dz), ez + bhz, touching)
                    && overlap(
                            Math.abs(dx * cos - dz * sin),
                            hx + Math.abs(cos) * bhx + Math.abs(sin) * bhz,
                            touching)
                    && overlap(
                            Math.abs(dx * sin + dz * cos),
                            hz + Math.abs(sin) * bhx + Math.abs(cos) * bhz,
                            touching);
        }

        private static boolean overlap(double distance, double extent, boolean touching) {
            double tolerance = 4 * Math.ulp(Math.max(distance, extent));
            return touching ? distance <= extent + tolerance : distance < extent - tolerance;
        }
    }
}
