package com.eynny.npships.api.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Callback-scoped server context. Resolve accepts this patch's declared local target only.
 * Assembled targets resolve to native plot positions; static/unassembled targets resolve to origin
 * plus local position. Arbitrary payload links remain opaque.
 *
 * @param level borrowed target server level
 * @param origin native minimum corner, copied on construction
 * @param resolve callback-scoped declared-target resolver
 * @param entityAccess callback-scoped declared-root supplier
 */
public record PatchContext(
        ServerLevel level,
        BlockPos origin,
        Function<BlockPos, BlockPos> resolve,
        java.util.function.Supplier<net.minecraft.world.entity.Entity> entityAccess) {
    /**
     * Existing block callback constructor remains binary compatible.
     *
     * @param level borrowed target server level
     * @param origin native minimum corner, copied on construction
     * @param resolve callback-scoped declared-target resolver
     */
    public PatchContext(ServerLevel level, BlockPos origin, Function<BlockPos, BlockPos> resolve) {
        this(
                level,
                origin,
                resolve,
                () -> {
                    throw new IllegalStateException("No mapped entity in this callback phase");
                });
    }

    /**
     * Additive identity metadata preserves the original four-component record pattern.
     *
     * @param level borrowed target server level
     * @param origin native minimum corner, copied on construction
     * @param resolve callback-scoped declared-target resolver
     * @param entityAccess callback-scoped declared-root supplier
     * @param identity stable provider instance/group/occurrence identity
     */
    public PatchContext(
            ServerLevel level,
            BlockPos origin,
            Function<BlockPos, BlockPos> resolve,
            java.util.function.Supplier<net.minecraft.world.entity.Entity> entityAccess,
            @Nullable PatchIdentity identity) {
        this(level, origin, new IdentifiedResolver(resolve, identity), entityAccess);
    }

    /**
     * Per-instance metadata owned by this callback's resolver; absent in legacy contexts.
     *
     * @return instance identity metadata, or null for legacy contexts
     */
    public @Nullable PatchIdentity identity() {
        return resolve instanceof IdentifiedResolver identified ? identified.identity() : null;
    }

    /**
     * Only for the declared ENTITY target during its synchronous afterPlacement callback.
     *
     * @return borrowed live root, valid only during this callback
     */
    public net.minecraft.world.entity.Entity entity() {
        return entityAccess.get();
    }

    /**
     * Copies the origin; resolver and entity supplier remain borrowed callback access.
     *
     * @param level borrowed target server level
     * @param origin native minimum corner, copied on construction
     * @param resolve callback-scoped declared-target resolver
     * @param entityAccess callback-scoped declared-root supplier
     */
    public PatchContext {
        origin = origin.immutable();
    }

    private record IdentifiedResolver(
            Function<BlockPos, BlockPos> delegate, @Nullable PatchIdentity identity)
            implements Function<BlockPos, BlockPos> {
        @Override
        public BlockPos apply(BlockPos position) {
            return delegate.apply(position);
        }
    }
}
