package com.eynny.npships.ship;

import com.eynny.npships.authoring.patches.EntityIdentity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.function.Function;

/** One native template's map. No joins, positional lookups, delayed work or world-wide scans. */
public final class EntityPlacementScope implements AutoCloseable {
    private static final ThreadLocal<EntityPlacementScope> CURRENT = new ThreadLocal<>();
    private final ServerLevel level;
    private final Set<UUID> required;
    private final Map<UUID, Entity> mapped = new HashMap<>();
    private final Map<UUID, String> failures = new HashMap<>();
    private boolean closed;
    private final List<PaintingAnchor> paintingAnchors = new ArrayList<>();
    private final Map<Entity, PaintingAnchor> pendingPaintings = new IdentityHashMap<>();

    private record PaintingAnchor(
            net.minecraft.world.entity.decoration.Painting entity,
            net.minecraft.world.phys.Vec3 center) {
        void restore() {
            if (entity.isRemoved()) return;
            var direction = entity.getDirection();
            var variant = entity.getVariant().value();
            var cellCenter =
                    center.relative(direction, .46875)
                            .relative(
                                    direction.getCounterClockWise(),
                                    variant.width() % 2 == 0 ? -.5 : 0)
                            .add(0, variant.height() % 2 == 0 ? -.5 : 0, 0);
            var anchor = net.minecraft.core.BlockPos.containing(cellCenter);
            // Native moveTo floors the visual center, which is not the anchor of an even
            // painting. Read only the native additional data before insertion, retaining UUID,
            // object identity and provider mapping. No delayed tick or world scan.
            var data = new CompoundTag();
            entity.saveWithoutId(data);
            data.putInt("TileX", anchor.getX());
            data.putInt("TileY", anchor.getY());
            data.putInt("TileZ", anchor.getZ());
            entity.readAdditionalSaveData(data);
        }
    }

    public EntityPlacementScope(ServerLevel level) {
        this(level, Set.of());
    }

    public EntityPlacementScope(ServerLevel level, Set<UUID> required) {
        if (required.size() > 1024)
            throw new IllegalArgumentException("Maximum 1024 required entities");
        this.required = Set.copyOf(required);
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Entity placement requires server thread");
        this.level = level;
    }

    public AutoCloseable open() {
        if (closed) throw new IllegalStateException("Closed entity placement");
        var previous = CURRENT.get();
        CURRENT.set(this);
        int firstPainting = paintingAnchors.size();
        return () -> {
            if (CURRENT.get() != this)
                throw new IllegalStateException("Unbalanced entity placement");
            try {
                for (int i = firstPainting; i < paintingAnchors.size(); i++)
                    pendingPaintings.remove(paintingAnchors.get(i).entity());
            } finally {
                paintingAnchors.subList(firstPainting, paintingAnchors.size()).clear();
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            }
        };
    }

    public static Optional<Entity> create(
            CompoundTag nbt, Level level, Function<CompoundTag, Optional<Entity>> factory) {
        var scope = CURRENT.get();
        if (scope == null || scope.level != level) return factory.apply(nbt);
        UUID token = EntityIdentity.read(nbt);
        try {
            var copy = EntityIdentity.placementCopy(nbt);
            if (copy.getString("id").equals("minecraft:painting")) {
                // The template has already translated Pos; stored Tile coordinates may still
                // belong to capture space. Seed a nearby temporary anchor for native loading;
                // beforeInsertion restores the exact anchor after native rotation/moveTo.
                var position = copy.getList("Pos", net.minecraft.nbt.Tag.TAG_DOUBLE);
                if (position.size() == 3) {
                    var near =
                            net.minecraft.core.BlockPos.containing(
                                    position.getDouble(0),
                                    position.getDouble(1),
                                    position.getDouble(2));
                    copy.putInt("TileX", near.getX());
                    copy.putInt("TileY", near.getY());
                    copy.putInt("TileZ", near.getZ());
                }
            }
            var result = factory.apply(copy);
            // Old manually authored mobs omit this field. Apply the historic default only
            // then, never after an explicit false was loaded from authored SNBT.
            if (!copy.contains("PersistenceRequired")
                    && result.orElse(null) instanceof net.minecraft.world.entity.Mob mob)
                mob.setPersistenceRequired();
            if (result.isPresent()
                    && result.get().getType() == net.minecraft.world.entity.EntityType.PAINTING) {
                var position = nbt.getList("Pos", net.minecraft.nbt.Tag.TAG_DOUBLE);
                if (position.size() == 3) {
                    var pending =
                            new PaintingAnchor(
                                    (net.minecraft.world.entity.decoration.Painting) result.get(),
                                    new net.minecraft.world.phys.Vec3(
                                            position.getDouble(0),
                                            position.getDouble(1),
                                            position.getDouble(2)));
                    scope.paintingAnchors.add(pending);
                    scope.pendingPaintings.put(result.get(), pending);
                }
            }
            if (token != null && scope.required.contains(token)) {
                if (result.isEmpty())
                    scope.failures.put(token, "Native factory returned no entity");
                else if (scope.mapped.putIfAbsent(token, result.get()) != null)
                    scope.failures.put(token, "Duplicate native entity token");
            }
            return result;
        } catch (RuntimeException | LinkageError error) {
            if (token != null && scope.required.contains(token))
                scope.failures.put(token, error.toString());
            if (error instanceof LinkageError)
                throw new IllegalStateException("Native entity factory linkage failure", error);
            throw error;
        }
    }

    /** Called at the existing native template insertion boundary, before world/provider events. */
    public static void beforeInsertion(Entity entity) {
        var scope = CURRENT.get();
        if (scope == null || scope.level != entity.level()) return;
        var pending = scope.pendingPaintings.remove(entity);
        if (pending != null) pending.restore();
    }

    public Entity require(UUID token) {
        if (closed || !level.getServer().isSameThread())
            throw new IllegalStateException("Entity mapping is callback/server scoped");
        var entity = mapped.get(token);
        if (failures.containsKey(token)
                || entity == null
                || entity.isRemoved()
                || level.getEntity(entity.getUUID()) != entity)
            throw new IllegalStateException(
                    "Required entity "
                            + token
                            + " missing after native placement: "
                            + failures.getOrDefault(token, "filtered, cancelled or removed"));
        return entity;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        mapped.clear();
        failures.clear();
        paintingAnchors.clear();
        pendingPaintings.clear();
    }
}
