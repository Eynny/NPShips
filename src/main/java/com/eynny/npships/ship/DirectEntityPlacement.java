package com.eynny.npships.ship;

import com.eynny.npships.authoring.patches.PatchCode;
import com.eynny.npships.authoring.patches.PatchVolumeFormat;
import com.eynny.npships.resources.NbtWorkCursor;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;

/** One authored wrapper's bounded preparation. The construction owner admits every ready root. */
public final class DirectEntityPlacement implements AutoCloseable {
    public interface Mapping {
        Level source();

        UUID bodyFor(BlockPos local);

        BlockPos offset(UUID body);

        Iterator<Map.Entry<BlockPos, UUID>> members();

        Vec3 worldPoint(Vec3 local);

        double yaw();
    }

    @FunctionalInterface
    public interface NativeScope {
        AutoCloseable open() throws Exception;
    }

    public record Ready(Entity root, PatchVolumeFormat volume) {
        /** Called by the existing owner only after exact native entity admission succeeds. */
        public void admitted() {
            if (!(root.level() instanceof ServerLevel level)
                    || level.getEntity(root.getUUID()) != root)
                throw new IllegalStateException("Direct entity was not admitted exactly");
            if (volume != null) volume.admitted(root);
        }
    }

    private record Node(CompoundTag data, Vec3 local, BlockPos anchor, Entity vehicle, int depth) {}

    private final ServerLevel level;
    private final Mapping mapping;
    private final LongConsumer reserve;
    private final NativeScope nativeScope;
    private NbtWorkCursor copying;
    private CompoundTag wrapper, volumeData;
    private Vec3 captureToLocal;
    private long envelopeMutableBytes;
    private PatchVolumeFormat volume;
    private AABB sourceBounds;
    private Iterator<Map.Entry<BlockPos, UUID>> members;
    private Map<UUID, AABB> clips = new LinkedHashMap<>();
    private Iterator<Map.Entry<UUID, AABB>> fragments;
    private ArrayDeque<Node> pending = new ArrayDeque<>();
    private List<Entity> unhanded = new ArrayList<>();
    private Node active;
    private Entity activeEntity, treeRoot;
    private ListTag children;
    private int childIndex;
    private Iterator<BlockPos> supports;
    private UUID attachmentBody;
    private Ready ready;
    private boolean complete, cleaning, closed;

    public DirectEntityPlacement(
            ServerLevel level,
            CompoundTag wrapper,
            Mapping mapping,
            LongConsumer reserve,
            NativeScope nativeScope) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Direct entities require server-thread ownership");
        this.level = level;
        this.mapping = mapping;
        this.reserve = reserve;
        this.nativeScope = nativeScope;
        // The initial mutable envelope and EntityPlacementScope's per-node native copy both count.
        copying =
                NbtWorkCursor.copy(wrapper, bytes -> reserve.accept(Math.multiplyExact(bytes, 2)));
    }

    /** One bounded source/copy unit or indivisible native factory; no private ticker or budget. */
    public boolean step() throws Exception {
        requireOpen();
        if (ready != null)
            throw new IllegalStateException("Ready root must be handed to its owner");
        if (complete) return true;
        if (copying != null) {
            int[] units = {0};
            if (!copying.step(() -> units[0]++ == 0)) return false;
            wrapper = (CompoundTag) copying.copyResult();
            envelopeMutableBytes = copying.payload().mutableBytes();
            copying.close();
            copying = null;
            initialize();
            return false;
        }
        if (volume != null) return volumeStep();
        if (supports != null) {
            if (supports.hasNext()) {
                var local = supports.next();
                var body = mapping.bodyFor(local);
                if (body == null)
                    throw new IllegalArgumentException(
                            "Native hanging support has no body: " + local);
                if (attachmentBody != null && !attachmentBody.equals(body))
                    throw new IllegalArgumentException(
                            "Native hanging support crosses physical bodies");
                attachmentBody = body;
                return false;
            }
            if (attachmentBody == null)
                throw new IllegalArgumentException("Native hanging support is empty");
            var offset = requireOffset(attachmentBody);
            var attached = (BlockAttachedEntity) activeEntity;
            var target = attached.getPos().offset(offset);
            // Native setPos takes an attachment cell, not the visual center of an even painting.
            attached.setPos(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
            supports = null;
            finishNode();
            return false;
        }
        if (children != null) {
            if (childIndex < children.size()) {
                var child = children.getCompound(childIndex++);
                var position = optionalPosition(child, "Pos");
                var local = position == null ? active.local() : position.add(captureToLocal);
                BlockPos anchor = null;
                if (child.contains("TileX", Tag.TAG_INT)
                        && child.contains("TileY", Tag.TAG_INT)
                        && child.contains("TileZ", Tag.TAG_INT))
                    anchor =
                            BlockPos.containing(
                                    new Vec3(
                                                    child.getInt("TileX"),
                                                    child.getInt("TileY"),
                                                    child.getInt("TileZ"))
                                            .add(captureToLocal));
                reserve.accept(8L * Long.BYTES);
                pending.addLast(new Node(child, local, anchor, activeEntity, active.depth() + 1));
                return false;
            }
            children = null;
            active = null;
            activeEntity = null;
            return false;
        }
        if (!pending.isEmpty()) {
            active = pending.removeFirst();
            if (active.depth() > 64)
                throw new IllegalArgumentException("Excessive native passenger depth");
            createNode();
            return false;
        }
        ready = new Ready(treeRoot, null);
        complete = true;
        return true;
    }

    private void initialize() {
        var data = wrapper.getCompound("nbt");
        var local = requiredPosition(wrapper, "pos");
        var captured = optionalPosition(data, "Pos");
        captureToLocal = captured == null ? Vec3.ZERO : local.subtract(captured);
        for (var entry : PatchCode.sourceFormats(PatchVolumeFormat.class).entrySet())
            if (entry.getValue().type().equals(data.getString("id"))) {
                volume = PatchCode.requireNativeVolume(entry);
                break;
            }
        if (volume != null) {
            if (!data.getList("Passengers", Tag.TAG_COMPOUND).isEmpty())
                throw new IllegalArgumentException("A clipped native volume cannot own passengers");
            sourceBounds = volume.bounds(data, local);
            if (sourceBounds == null
                    || !Double.isFinite(sourceBounds.getSize())
                    || sourceBounds.getXsize() <= 0
                    || sourceBounds.getYsize() <= 0
                    || sourceBounds.getZsize() <= 0)
                throw new IllegalArgumentException("Invalid authored native volume bounds");
            volumeData = data;
            members = mapping.members();
            return;
        }
        pending.add(new Node(data, local, anchor(wrapper), null, 0));
    }

    private boolean volumeStep() throws Exception {
        if (members != null) {
            if (members.hasNext()) {
                var member = members.next();
                var local = member.getKey();
                if (!sourceBounds.contains(Vec3.atCenterOf(local))) return false;
                if (member.getValue() == null || mapping.source().getBlockState(local).isAir())
                    throw new IllegalStateException(
                            "Volume membership does not identify a source body");
                var cell = new AABB(local).intersect(sourceBounds);
                var previous = clips.get(member.getValue());
                if (previous == null) reserve.accept(12L * Long.BYTES);
                clips.put(member.getValue(), previous == null ? cell : previous.minmax(cell));
                return false;
            }
            members = null;
            if (clips.isEmpty())
                throw new IllegalArgumentException("Native volume contains no owned source blocks");
            fragments = clips.entrySet().iterator();
            return false;
        }
        if (fragments.hasNext()) {
            var fragment = fragments.next();
            var offset = requireOffset(fragment.getKey());
            var bounds = fragment.getValue().move(Vec3.atLowerCornerOf(offset));
            // Codec restoration, not a generic setPos, owns its extent and bottom-centre anchor.
            // Per-fragment helper/scope/native-codec copies are admitted before native loading.
            reserve.accept(Math.multiplyExact(envelopeMutableBytes, 3));
            var data = volumeData.copy();
            freshIdentity(data);
            data.put(
                    "Pos",
                    vector(
                            new Vec3(
                                    (bounds.minX + bounds.maxX) / 2,
                                    bounds.minY,
                                    (bounds.minZ + bounds.maxZ) / 2)));
            Entity entity;
            try (var scope = nativeScope.open()) {
                entity =
                        EntityPlacementScope.create(
                                        data,
                                        level,
                                        nativeData ->
                                                Optional.of(
                                                        volume.restore(level, bounds, nativeData)))
                                .orElseThrow();
                EntityPlacementScope.beforeInsertion(entity);
            }
            reserve.accept(2L * Long.BYTES);
            unhanded.add(entity);
            ready = new Ready(entity, volume);
            complete = !fragments.hasNext();
            return complete;
        }
        complete = true;
        return true;
    }

    private void createNode() throws Exception {
        var data = active.data();
        children = data.getList("Passengers", Tag.TAG_COMPOUND);
        data.remove("Passengers");
        childIndex = 0;
        freshIdentity(data);
        data.put("Pos", vector(active.local()));
        try (var scope = nativeScope.open()) {
            activeEntity =
                    EntityPlacementScope.create(
                                    data,
                                    level,
                                    nativeData -> {
                                        var entity =
                                                EntityType.by(nativeData)
                                                        .map(type -> type.create(level))
                                                        .orElse(null);
                                        if (entity == null) return Optional.empty();
                                        if (entity instanceof BlockAttachedEntity) {
                                            if (active.anchor() == null)
                                                throw new IllegalArgumentException(
                                                        "Native attached entity requires its authored anchor");
                                            nativeData.putInt("TileX", active.anchor().getX());
                                            nativeData.putInt("TileY", active.anchor().getY());
                                            nativeData.putInt("TileZ", active.anchor().getZ());
                                        }
                                        entity.load(nativeData);
                                        return Optional.of(entity);
                                    })
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Cannot create entity '"
                                                            + data.getString("id")
                                                            + "' at "
                                                            + active.local()
                                                            + (data.getString("id").isBlank()
                                                                    ? ": missing entity type id"
                                                                    : ": type is unavailable or its factory returned no entity")));
            EntityPlacementScope.beforeInsertion(activeEntity);
        }
        reserve.accept(2L * Long.BYTES);
        unhanded.add(activeEntity);
        if (activeEntity instanceof BlockAttachedEntity attached) {
            var anchor = active.anchor();
            attached.setPos(anchor.getX() + .5, anchor.getY() + .5, anchor.getZ() + .5);
            if (attached instanceof HangingEntity hanging) {
                var box =
                        ((com.eynny.npships.mixin.NPShipsHangingSupportAccessor) hanging)
                                .npships$supportBox();
                supports =
                        BlockPos.betweenClosed(
                                        BlockPos.containing(box.minX, box.minY, box.minZ),
                                        BlockPos.containing(box.maxX, box.maxY, box.maxZ))
                                .iterator();
            } else supports = List.of(anchor).iterator();
            attachmentBody = null;
            return;
        }
        var target = mapping.worldPoint(active.local());
        float yaw = ShipRotation.rotateYaw(activeEntity.getYRot(), mapping.yaw());
        float head = ShipRotation.rotateYaw(activeEntity.getYHeadRot(), mapping.yaw());
        float body =
                activeEntity instanceof LivingEntity living
                        ? ShipRotation.rotateYaw(living.yBodyRot, mapping.yaw())
                        : yaw;
        var velocity = activeEntity.getDeltaMovement();
        var rotated = new Vector3d(velocity.x, velocity.y, velocity.z).rotateY(mapping.yaw());
        activeEntity.moveTo(target.x, target.y, target.z, yaw, activeEntity.getXRot());
        activeEntity.setYHeadRot(head);
        activeEntity.setYBodyRot(body);
        activeEntity.setDeltaMovement(rotated.x, rotated.y, rotated.z);
        finishNode();
    }

    private void finishNode() {
        if (active.vehicle() == null) treeRoot = activeEntity;
        else if (!activeEntity.startRiding(active.vehicle(), true))
            throw new IllegalArgumentException("Native passenger attachment refused");
    }

    public Ready takeReady() {
        requireOpen();
        var value = ready;
        if (value == null) return null;
        ready = null;
        // Transfer the entire passenger tree by reference, without clearing every node slot.
        if (value.volume() == null) unhanded = new ArrayList<>();
        else {
            if (unhanded.size() != 1 || unhanded.getFirst() != value.root())
                throw new IllegalStateException("Native volume handoff lost exact ownership");
            unhanded.removeLast();
        }
        return value;
    }

    private BlockPos requireOffset(UUID body) {
        var offset = mapping.offset(body);
        if (offset == null)
            throw new IllegalStateException("Native entity body has no final plot mapping");
        return offset;
    }

    private static BlockPos anchor(CompoundTag wrapper) {
        var at = wrapper.getList("blockPos", Tag.TAG_INT);
        if (at.size() != 3) return null;
        return new BlockPos(at.getInt(0), at.getInt(1), at.getInt(2));
    }

    private static Vec3 optionalPosition(CompoundTag tag, String key) {
        if (!tag.contains(key)) return null;
        return requiredPosition(tag, key);
    }

    private static Vec3 requiredPosition(CompoundTag tag, String key) {
        var values = tag.getList(key, Tag.TAG_DOUBLE);
        if (values.size() != 3)
            throw new IllegalArgumentException("Invalid native entity position: " + key);
        var result = new Vec3(values.getDouble(0), values.getDouble(1), values.getDouble(2));
        if (!Double.isFinite(result.x) || !Double.isFinite(result.y) || !Double.isFinite(result.z))
            throw new IllegalArgumentException("Non-finite native entity position");
        return result;
    }

    private static ListTag vector(Vec3 point) {
        var result = new ListTag();
        result.add(DoubleTag.valueOf(point.x));
        result.add(DoubleTag.valueOf(point.y));
        result.add(DoubleTag.valueOf(point.z));
        return result;
    }

    private static void freshIdentity(CompoundTag data) {
        data.remove("UUID");
        data.remove("UUIDMost");
        data.remove("UUIDLeast");
    }

    private void requireOpen() {
        if (closed || cleaning)
            throw new IllegalStateException("Direct entity preparation is closed");
    }

    /** One unhanded native object per cleanup unit, driven by the existing attempt owner. */
    public boolean stepCleanup() {
        if (closed) return true;
        cleaning = true;
        if (copying != null) {
            copying.close();
            copying = null;
            return false;
        }
        if (!unhanded.isEmpty()) {
            var entity = unhanded.getLast();
            // Before handoff none is admitted. Never remove a foreign/replaced world UUID.
            if (level.getEntity(entity.getUUID()) == null && !entity.isRemoved()) entity.discard();
            unhanded.removeLast();
            return false;
        }
        // Drop private scratch references; collection.clear would walk a large passenger graph.
        unhanded = List.of();
        pending = new ArrayDeque<>();
        clips = Map.of();
        wrapper = volumeData = null;
        active = null;
        activeEntity = treeRoot = null;
        children = null;
        supports = null;
        members = null;
        fragments = null;
        ready = null;
        closed = true;
        return true;
    }

    /** Explicit fixture/shutdown drain. Normal construction cancellation uses stepCleanup. */
    @Override
    public void close() {
        while (!stepCleanup()) {}
    }
}
