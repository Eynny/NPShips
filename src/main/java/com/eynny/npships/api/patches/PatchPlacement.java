package com.eynny.npships.api.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Final placement information supplied by a host capability after body positioning. Every accessor
 * is server-thread and callback scoped; do not retain this capability. Position/body values are
 * detached observations, not continuing native ownership or promises about subsequent world ticks.
 * Declared local positions and stable authored entity roots are the only resolvable targets.
 */
public final class PatchPlacement implements AutoCloseable {
    /** Owning-level coordinate system of the native position. */
    public enum Space {
        /** Ordinary owning-level world coordinates. */
        ORDINARY_WORLD,
        /** Reserved native physical plot coordinates. */
        NATIVE_PLOT
    }

    /** A successful coordinate mapping does not itself prove that a native block still exists. */
    public enum PositionStatus {
        /** The declared native target was observed and is available. */
        PRESENT,
        /** The owning native cell was observed as AIR. */
        EMPTY,
        /** The actual owning chunk was unavailable. */
        CHUNK_UNAVAILABLE,
        /** The expected native body was unavailable. */
        BODY_UNAVAILABLE,
        /** The native cell no longer belongs to the expected owner. */
        OWNERSHIP_CHANGED
    }

    /** Native body availability at the callback boundary. */
    public enum BodyStatus {
        /** The declared native target was observed and is available. */
        PRESENT,
        /** The native target is unavailable at this callback boundary. */
        UNAVAILABLE
    }

    /**
     * Native position uses the owning Level's block coordinate system, including reserved plot
     * coordinates. World position is the projected LOWER CORNER of that cell, never a silently
     * rounded block origin. State comes from the actual current owning chunk; AIR is explicit
     * EMPTY.
     *
     * @param sourceLocal declared schematic-local source cell
     * @param nativePosition actual owning-level native position
     * @param space native coordinate system
     * @param status typed observation or transaction outcome
     * @param bodyId native body identity
     * @param worldPosition projected ordinary-world position when observed
     * @param state actual native state when observed
     */
    public record Position(
            BlockPos sourceLocal,
            BlockPos nativePosition,
            Space space,
            PositionStatus status,
            Optional<UUID> bodyId,
            Optional<Vec3> worldPosition,
            Optional<BlockState> state) {
        /**
         * Copies local/native coordinates and checks observation consistency.
         *
         * @param sourceLocal declared schematic-local source cell
         * @param nativePosition actual owning-level native position
         * @param space native coordinate system
         * @param status typed observation or transaction outcome
         * @param bodyId native body identity
         * @param worldPosition projected ordinary-world position when observed
         * @param state actual native state when observed
         */
        public Position {
            sourceLocal = Objects.requireNonNull(sourceLocal).immutable();
            nativePosition = Objects.requireNonNull(nativePosition).immutable();
            Objects.requireNonNull(space);
            Objects.requireNonNull(status);
            Objects.requireNonNull(bodyId);
            Objects.requireNonNull(worldPosition);
            Objects.requireNonNull(state);
            boolean observed = status == PositionStatus.PRESENT || status == PositionStatus.EMPTY;
            if (observed != state.isPresent())
                throw new IllegalArgumentException("Only observed native cells have block state");
            if (observed && worldPosition.isEmpty())
                throw new IllegalArgumentException(
                        "An observed native cell needs its world position");
            if (state.isPresent() && (status == PositionStatus.EMPTY) != state.get().isAir())
                throw new IllegalArgumentException("Native AIR must be reported as EMPTY");
            if (space == Space.ORDINARY_WORLD && bodyId.isPresent())
                throw new IllegalArgumentException(
                        "Ordinary world coordinates have no native body");
        }
    }

    /**
     * A complete pose snapshot. Plot center is an identity/diagnostic anchor, not the transform's
     * translation. The matrix includes the native rotation point, orientation and scale.
     *
     * @param dimension target dimension ID
     * @param bodyId native body identity
     * @param runtimeId current native runtime identifier
     * @param plotCenter native plot diagnostic anchor
     * @param plotToWorld complete native plot-to-world matrix
     */
    public record BodyFrame(
            ResourceLocation dimension,
            UUID bodyId,
            int runtimeId,
            BlockPos plotCenter,
            Matrix4dc plotToWorld) {
        /**
         * Copies the native plot anchor and complete pose matrix.
         *
         * @param dimension target dimension ID
         * @param bodyId native body identity
         * @param runtimeId current native runtime identifier
         * @param plotCenter native plot diagnostic anchor
         * @param plotToWorld complete native plot-to-world matrix
         */
        public BodyFrame {
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(bodyId);
            plotCenter = Objects.requireNonNull(plotCenter).immutable();
            plotToWorld = new Matrix4d(Objects.requireNonNull(plotToWorld));
        }

        /**
         * Copies the complete native plot-to-world pose matrix.
         *
         * @return a fresh matrix owned by the caller
         */
        @Override
        public Matrix4dc plotToWorld() {
            return new Matrix4d(plotToWorld);
        }

        /**
         * Transform an explicit native-plot point without pretending it is a block coordinate.
         *
         * @param nativePlotPoint exact native-plot point to project
         * @return projected ordinary-world point
         */
        public Vec3 project(Vec3 nativePlotPoint) {
            Objects.requireNonNull(nativePlotPoint);
            var value =
                    plotToWorld.transformPosition(
                            new Vector3d(nativePlotPoint.x, nativePlotPoint.y, nativePlotPoint.z));
            return new Vec3(value.x, value.y, value.z);
        }
    }

    /** Declared inserted root availability at the callback boundary. */
    public enum EntityStatus {
        /** The declared native target was observed and is available. */
        PRESENT,
        /** The native target is unavailable at this callback boundary. */
        UNAVAILABLE
    }

    /**
     * Detached observation of one declared authored ROOT, never a passenger or nearby entity.
     * Native and projected world positions are explicit. This observation does not infer that a Mob
     * belongs to any particular body, nor does it grant continued access to the native object.
     *
     * @param authoredRoot stable authored root token
     * @param status typed observation or transaction outcome
     * @param runtimeUuid inserted root runtime UUID when present
     * @param nativeType observed native or serializer type ID
     * @param nativePosition actual owning-level native position
     * @param worldPosition projected ordinary-world position when observed
     */
    public record EntityState(
            UUID authoredRoot,
            EntityStatus status,
            Optional<UUID> runtimeUuid,
            Optional<ResourceLocation> nativeType,
            Optional<Vec3> nativePosition,
            Optional<Vec3> worldPosition) {
        /**
         * Checks that only present observations carry native entity details.
         *
         * @param authoredRoot stable authored root token
         * @param status typed observation or transaction outcome
         * @param runtimeUuid inserted root runtime UUID when present
         * @param nativeType observed native or serializer type ID
         * @param nativePosition actual owning-level native position
         * @param worldPosition projected ordinary-world position when observed
         */
        public EntityState {
            Objects.requireNonNull(authoredRoot);
            Objects.requireNonNull(status);
            Objects.requireNonNull(runtimeUuid);
            Objects.requireNonNull(nativeType);
            Objects.requireNonNull(nativePosition);
            Objects.requireNonNull(worldPosition);
            boolean present = status == EntityStatus.PRESENT;
            if (present != runtimeUuid.isPresent()
                    || present != nativeType.isPresent()
                    || present != nativePosition.isPresent()
                    || present != worldPosition.isPresent())
                throw new IllegalArgumentException(
                        "Only an inserted native root has an entity observation");
        }
    }

    /**
     * Owned body identity stays visible if its native object becomes unavailable during callback.
     *
     * @param id namespaced identity
     * @param status typed observation or transaction outcome
     * @param frame detached native pose when present
     */
    public record Body(UUID id, BodyStatus status, Optional<BodyFrame> frame) {
        /**
         * Checks that the optional pose belongs to this body identity.
         *
         * @param id namespaced identity
         * @param status typed observation or transaction outcome
         * @param frame detached native pose when present
         */
        public Body {
            Objects.requireNonNull(id);
            Objects.requireNonNull(status);
            Objects.requireNonNull(frame);
            if ((status == BodyStatus.PRESENT) != frame.isPresent())
                throw new IllegalArgumentException(
                        "Only a present native body has a pose snapshot");
            if (frame.isPresent() && !id.equals(frame.get().bodyId()))
                throw new IllegalArgumentException("Body pose belongs to another identity");
        }
    }

    /** Outcome of an optional instance-local record read. */
    public enum RecordStatus {
        /** A fresh detached record is transferred to the caller. */
        AVAILABLE,
        /** This instance has no record under the requested name. */
        ABSENT,
        /** Optional detached record copying could not be admitted. */
        REFUSED
    }

    /**
     * One final read result. AVAILABLE transfers a fresh detached payload to the caller. Its
     * mutable NBT accessor intentionally returns that caller-owned value without another hidden
     * copy or reservation. Mutating it cannot change host state or any subsequent read. REFUSED
     * means the optional detached read could not be admitted; it does not itself abort native
     * restoration.
     *
     * @param status typed observation or transaction outcome
     * @param payload privately copied provider payload
     * @param detail diagnostic detail
     */
    public record RecordRead(RecordStatus status, Optional<CompoundTag> payload, String detail) {
        /**
         * Checks status/payload consistency and bounds the diagnostic.
         *
         * @param status typed observation or transaction outcome
         * @param payload privately copied provider payload
         * @param detail diagnostic detail
         */
        public RecordRead {
            Objects.requireNonNull(status);
            Objects.requireNonNull(payload);
            detail =
                    Objects.requireNonNull(detail)
                            .replace('\n', ' ')
                            .replace('\r', ' ')
                            .replace('\t', ' ');
            if (detail.length() > 384) detail = detail.substring(0, 384);
            if ((status == RecordStatus.AVAILABLE) != payload.isPresent())
                throw new IllegalArgumentException("Only AVAILABLE record reads have a payload");
            if (status == RecordStatus.REFUSED && detail.isBlank())
                throw new IllegalArgumentException("A refused record read needs a reason");
        }
    }

    private final Thread thread = Thread.currentThread();
    private Supplier<List<UUID>> bodies;
    private Function<BlockPos, Position> positions;
    private Function<UUID, Body> frames;
    private Function<UUID, Entity> roots;
    private Function<UUID, EntityState> entities;
    private Function<ResourceLocation, RecordRead> records;

    /**
     * Host constructor. Suppliers must guard EVERY access to the actual callback window, thread and
     * this instance's declared permissions. Record suppliers reserve a detached copy before
     * returning AVAILABLE or report a typed refusal.
     *
     * @param bodies guarded complete body-ID supplier
     * @param positions guarded declared-position resolver
     * @param frames guarded owned-body pose resolver
     * @param roots guarded declared-root borrower
     * @param entities guarded declared-root observation resolver
     * @param records guarded instance-local record reader
     */
    public PatchPlacement(
            Supplier<List<UUID>> bodies,
            Function<BlockPos, Position> positions,
            Function<UUID, Body> frames,
            Function<UUID, Entity> roots,
            Function<UUID, EntityState> entities,
            Function<ResourceLocation, RecordRead> records) {
        this.bodies = Objects.requireNonNull(bodies);
        this.positions = Objects.requireNonNull(positions);
        this.frames = Objects.requireNonNull(frames);
        this.roots = Objects.requireNonNull(roots);
        this.entities = Objects.requireNonNull(entities);
        this.records = Objects.requireNonNull(records);
    }

    /**
     * Complete UUID union of bodies produced by this placement, including nested native children.
     *
     * @return detached complete owned-body identity union
     */
    public List<UUID> bodies() {
        require();
        return List.copyOf(bodies.get());
    }

    /**
     * Refuses a position outside this instance's declared local footprint. No world search.
     *
     * @param declaredLocal cell inside this instance mapping footprint
     * @return detached observation of the declared local cell
     */
    public Position position(BlockPos declaredLocal) {
        require();
        return Objects.requireNonNull(positions.apply(Objects.requireNonNull(declaredLocal)));
    }

    /**
     * Refuses a body not in this placement's complete owned union. Never looks up by proximity.
     *
     * @param ownedBody identity in this placement owned-body union
     * @return detached observation of the owned body
     */
    public Body body(UUID ownedBody) {
        require();
        return Objects.requireNonNull(frames.apply(Objects.requireNonNull(ownedBody)));
    }

    /**
     * Borrow the actual inserted root during this callback only. The token is authored identity,
     * distinct from the returned runtime UUID. No passengers, entity scans or permanent ownership.
     * Missing, filtered, removed, replaced or undeclared roots refuse before returning a value.
     *
     * @param declaredAuthoredRoot root explicitly enrolled by this instance
     * @return the live inserted root entity for this callback
     */
    public Entity root(UUID declaredAuthoredRoot) {
        require();
        return Objects.requireNonNull(roots.apply(Objects.requireNonNull(declaredAuthoredRoot)));
    }

    /**
     * Typed observation; unavailable roots are never replaced by positional/type searches.
     *
     * @param declaredAuthoredRoot root explicitly enrolled by this instance
     * @return detached observation of the declared authored root
     */
    public EntityState entity(UUID declaredAuthoredRoot) {
        require();
        return Objects.requireNonNull(entities.apply(Objects.requireNonNull(declaredAuthoredRoot)));
    }

    private void require() {
        if (bodies == null || Thread.currentThread() != thread)
            throw new IllegalStateException("Final placement capability expired or wrong thread");
    }

    /** The host closes each borrowed wrapper in its callback finally block. DTOs stay detached. */
    @Override
    public void close() {
        if (Thread.currentThread() != thread)
            throw new IllegalStateException("Final placement capability wrong thread");
        bodies = null;
        positions = null;
        frames = null;
        roots = null;
        entities = null;
        records = null;
    }

    /**
     * Read one namespaced ephemeral record prepared by THIS patch instance. No source persistence
     * or cross-instance visibility. A copy-admission refusal after placement is explicit REFUSED,
     * not an automatic restoration exception. Providers decide whether their own restoration needs
     * it.
     *
     * @param name provider-owned record name
     * @return detached instance-local record read
     */
    public RecordRead record(ResourceLocation name) {
        require();
        return Objects.requireNonNull(records.apply(Objects.requireNonNull(name)));
    }
}
