package com.eynny.npships.api.patches;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Optional, pure editor placement intent. Register through the existing adapter/authoring
 * capability method. This lets the ordinary block tool combine a requested state with an existing
 * cell, including selecting that occupied cell instead of its neighbour. It does not run during
 * world spawning or replace document/transform proposals. An optional declared native-data edit
 * belongs to the same explicit placement transaction. Other providers need not implement this
 * block-specific capability.
 *
 * <p>The host snapshots target IDs once, caches eligibility by immutable state pair per editor,
 * applies the captured provider policy, refuses competing eligible owners, and accepts a canonical
 * state of the same block through its ordinary document/history owner. No world, screen, document
 * or native mutable object is supplied. Callbacks must be deterministic and side-effect free.
 */
public interface PatchBlockPlacement {
    /**
     * Declares existing block IDs to index, without scanning all providers while picking.
     *
     * @return non-null snapshot-safe block IDs; unavailable optional IDs remain inert
     */
    Set<ResourceLocation> blocks();

    /**
     * Pure eligibility, based only on this immutable pair; the host may cache its result.
     *
     * @param existing accepted state at the occupied cell
     * @param requested state selected by the ordinary block tool
     * @return whether this provider owns the combination intent
     */
    boolean applies(BlockState existing, BlockState requested);

    /**
     * Computes a detached state proposal only for an eligible, enabled, unambiguous owner. The host
     * validates same-block/state and retained native data before acceptance. A thrown refusal
     * changes no host document; arbitrary Java side effects cannot be rolled back.
     *
     * @param existing accepted source state
     * @param requested requested brush state
     * @return non-null canonical state of the same block as existing
     */
    BlockState combine(BlockState existing, BlockState requested);

    /**
     * Declares top-level native data fields owned by this explicit placement intent. Host identity
     * fields and provider envelopes cannot be claimed. Unlisted fields must remain exactly equal.
     *
     * @return immutable/snapshot-safe field names; empty for state-only combinations
     */
    default Set<String> dataFields() {
        return Set.of();
    }

    /**
     * Proposes detached native data for the same existing target. No Level or world is supplied.
     * The host checks declared fields, source CAS, native compatibility and ordinary authoring
     * limits before one history transaction; a refusal leaves the accepted document unchanged.
     *
     * @param existing accepted source state
     * @param requested requested brush state
     * @param data detached source native data; never a shared mutable document value
     * @param registries callback-borrowed platform registries, never retained
     * @return non-null detached data, retaining every field outside {@link #dataFields()}
     */
    default net.minecraft.nbt.CompoundTag combineData(
            BlockState existing,
            BlockState requested,
            net.minecraft.nbt.CompoundTag data,
            net.minecraft.core.HolderLookup.Provider registries) {
        return data;
    }

    /**
     * Optional ordinary inventory selection after acceptance; it does not modify item contents.
     *
     * @return -1 for ordinary deselection, otherwise a nonnegative slot selected by the host
     */
    default int inventorySelection() {
        return -1;
    }
}
