package com.eynny.npships.api.patches;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Detached instance identity; target ownership remains in the authored entry. Groups are not
 * bodies.
 *
 * @param type registered provider ID
 * @param instanceId stable authored instance identity
 * @param groupId authored group identity, or null; never a body ID
 * @param occurrenceId authored occurrence identity, or null
 */
public record PatchIdentity(
        ResourceLocation type,
        UUID instanceId,
        @Nullable UUID groupId,
        @Nullable UUID occurrenceId) {
    /**
     * Checks provider/instance identity without inventing physical body ownership.
     *
     * @param type registered provider ID
     * @param instanceId stable authored instance identity
     * @param groupId authored group identity, or null; never a body ID
     * @param occurrenceId authored occurrence identity, or null
     */
    public PatchIdentity {
        Objects.requireNonNull(type);
        Objects.requireNonNull(instanceId);
        if (type.toString().length() > 256)
            throw new IllegalArgumentException("Patch type ID exceeds 256 characters");
    }
}
