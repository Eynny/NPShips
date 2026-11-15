package com.eynny.npships.api.patches;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detached runtime instance, including document-scoped instances with no fake block target. Local
 * positions declare the complete permitted mapping footprint before native assembly; they are
 * neither a native body identity nor an arbitrary world-reference permission.
 *
 * @param identity stable provider instance/group/occurrence identity
 * @param payload privately copied provider payload
 * @param localPositions complete local footprint declared before assembly
 */
public record PatchBinding(
        PatchIdentity identity, JsonObject payload, Set<BlockPos> localPositions) {
    /**
     * Copies the payload and declared local mapping footprint.
     *
     * @param identity stable provider instance/group/occurrence identity
     * @param payload privately copied provider payload
     * @param localPositions complete local footprint declared before assembly
     */
    public PatchBinding {
        Objects.requireNonNull(identity);
        payload = Objects.requireNonNull(payload).deepCopy();
        localPositions =
                Objects.requireNonNull(localPositions).stream()
                        .map(position -> Objects.requireNonNull(position).immutable())
                        .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Copies this detached instance payload.
     *
     * @return a fresh detached payload
     */
    @Override
    public JsonObject payload() {
        return payload.deepCopy();
    }
}
