package com.eynny.npships.authoring.patches;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

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

    default void afterBodies(BuiltinPatchState state) throws Exception {}

    /** Pure admission predicate, evaluated once for the captured plan, not once per source cell. */
    default boolean inspectBare(
            com.eynny.npships.api.patches.PatchPolicy policy,
            java.util.Set<net.minecraft.resources.ResourceLocation> planned) {
        return false;
    }

    /**
     * Detached post-placement code for the EXISTING host repair queue. It must not capture a
     * template, source graph, expired callback view or world. The actual native body is borrowed
     * when the queue executes; normal template close does not revoke that explicit handoff.
     */
    default java.util.function.Consumer<dev.ryanhcode.sable.sublevel.ServerSubLevel>
            delayedRepair() {
        return null;
    }
}
