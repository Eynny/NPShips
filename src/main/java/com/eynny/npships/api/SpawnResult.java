package com.eynny.npships.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Detached terminal values: no level, entity, template, Ship or Sable object survives here.
 *
 * @param jobId managed request identity
 * @param mode originating selection route
 * @param structureId selected canonical schematic ID, null before source selection
 * @param dimension target dimension ID, null before dimension selection
 * @param finalOrigin confirmed minimum corner, or null when unavailable
 * @param outcome terminal host outcome
 * @param reason bounded diagnostic text
 * @param placement confirmed placement kind, or UNCONFIRMED
 * @param physicalIds detached body IDs, complete only for successful physical placement
 */
public record SpawnResult(
        UUID jobId,
        Mode mode,
        @Nullable ResourceLocation structureId,
        @Nullable ResourceLocation dimension,
        @Nullable BlockPos finalOrigin,
        Outcome outcome,
        String reason,
        Placement placement,
        List<UUID> physicalIds) {
    /** Selection route that originally owned the attempt. */
    public enum Mode {
        /** Automatic. */
        AUTOMATIC,
        /** Manual. */
        MANUAL,
        /** External. */
        EXTERNAL
    }

    /** Placement information confirmed by the host. */
    public enum Placement {
        /** No complete placement kind was confirmed. */
        UNCONFIRMED,
        /** Ordinary world placement without physical bodies. */
        STATIC,
        /** Native physical placement with body identities. */
        PHYSICAL
    }

    /** Terminal result of the existing managed attempt owner. */
    public enum Outcome {
        /** Success. */
        SUCCESS,
        /** No eligible player. */
        NO_ELIGIBLE_PLAYER,
        /** No relevant player. */
        NO_RELEVANT_PLAYER,
        /** No eligible candidate. */
        NO_ELIGIBLE_CANDIDATE,
        /** Player too close. */
        PLAYER_TOO_CLOSE,
        /** Environment exhausted. */
        ENVIRONMENT_EXHAUSTED,
        /** Physical overlap. */
        PHYSICAL_OVERLAP,
        /** Cancelled. */
        CANCELLED,
        /** Disabled. */
        DISABLED,
        /** Recent spawn cooldown. */
        RECENT_SPAWN_COOLDOWN,
        /** Preparation failed. */
        PREPARATION_FAILED,
        /** Placement failed. */
        PLACEMENT_FAILED,
        /** Internal error. */
        INTERNAL_ERROR
    }

    /**
     * Copies mutable coordinates and lists, and bounds diagnostic text.
     *
     * @param jobId managed request identity
     * @param mode originating selection route
     * @param structureId selected canonical schematic ID, null before source selection
     * @param dimension target dimension ID, null before dimension selection
     * @param finalOrigin confirmed minimum corner, or null when unavailable
     * @param outcome terminal host outcome
     * @param reason bounded diagnostic text
     * @param placement confirmed placement kind, or UNCONFIRMED
     * @param physicalIds detached body IDs, complete only for successful physical placement
     */
    public SpawnResult {
        finalOrigin = finalOrigin == null ? null : finalOrigin.immutable();
        reason = bounded(reason);
        physicalIds = List.copyOf(physicalIds);
    }

    /**
     * Tests the terminal outcome.
     *
     * @return true only for SUCCESS
     */
    public boolean successful() {
        return outcome == Outcome.SUCCESS;
    }

    /**
     * Bounded single-line diagnostics; never exposes arbitrary third-party stack traces.
     *
     * @param value diagnostic text, with null treated as empty
     * @return single-line text of at most 384 UTF-16 units
     */
    public static String bounded(String value) {
        if (value == null) return "";
        value = value.replace('\n', ' ').replace('\r', ' ');
        return value.length() <= 384 ? value : value.substring(0, 384);
    }
}
