package com.eynny.npships.ship;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.world.entity.Entity;

/**
 * Exact entity deletion authority during unfinished-body cleanup; inventory contents belong to the
 * body.
 */
public final class ConstructionProtection {
    private ConstructionProtection() {}

    /** The native non-destroy branch transports the exact foreign instance before plot removal. */
    public static boolean destroyWithBody(
            ServerSubLevel body, Entity entity, boolean nativeDecision) {
        if (!DirectSpawnJournal.pending(body.getLevel(), body.getUniqueId())) return nativeDecision;
        return DirectSpawnJournal.ownsEntity(body.getLevel(), body.getUniqueId(), entity.getUUID());
    }
}
