package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchPolicy;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Internal borrowed native-data boundary inside the one template executor. Code is stateless;
 * detached per-template facts live in BuiltinPatchState. This does not expose mutable native
 * templates to the public addon API or make mandatory world/identity safety optional.
 */
public interface PatchNativePreparation {
    boolean applies(BlockEntity entity, CompoundTag block);

    default void prepare(
            BuiltinPatchState state, BlockEntity entity, CompoundTag block, CompoundTag data)
            throws Exception {}

    /** Before the sole initial native load; only this detached block payload may be changed. */
    default void beforeInitialBlock(BuiltinPatchState state, BlockPos local, CompoundTag data)
            throws Exception {}

    /**
     * Receives fresh native block entities before any yield; each adapter checks its native type.
     */
    default void afterInitialBlock(BuiltinPatchState state, BlockPos local, BlockEntity entity)
            throws Exception {}

    default void afterBodies(BuiltinPatchState state) throws Exception {}

    /** Pure admission predicate, evaluated once for the captured plan, not once per source cell. */
    default boolean inspectBare(PatchPolicy policy, Set<ResourceLocation> planned) {
        return false;
    }

    /**
     * Optional internal continuation carried through the existing Consumer handoff unchanged. The
     * queue calls begin once and owns the cursor through completion, cancellation or stop.
     * Implementations must check the supplied allowance before each retained/native work unit.
     */
    interface CooperativeRepair extends Consumer<ServerSubLevel> {
        RepairCursor begin(ServerSubLevel body);

        /** Explicit synchronous compatibility entry; ordinary scheduled maintenance uses begin. */
        @Override
        default void accept(ServerSubLevel body) {
            try (var cursor = begin(body)) {
                while (!cursor.step(() -> true)) {}
            }
        }
    }

    interface RepairCursor extends AutoCloseable {
        boolean step(BooleanSupplier canContinue);

        /** Drop owned scratch; must not execute the unfinished repair or drain native work. */
        @Override
        void close();
    }

    /**
     * Detached post-placement code for the EXISTING host repair queue. It must not capture a
     * template, source graph, expired callback view or world. The actual native body is borrowed
     * when the queue executes; normal template close does not revoke that explicit handoff.
     */
    default Consumer<ServerSubLevel> delayedRepair() {
        return null;
    }
}
