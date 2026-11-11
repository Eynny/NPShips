package com.eynny.npships.naturalspawn;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import java.util.UUID;

/** One temporary Sable loading ticket. Sable owns the moving chunk footprint. */
public final class NaturalSpawnPhysicalChunkTracker {
    private final ServerSubLevel subLevel;
    private final long expirationGameTime;
    private boolean released;

    public NaturalSpawnPhysicalChunkTracker(ServerSubLevel subLevel, long expirationGameTime) {
        this.subLevel = java.util.Objects.requireNonNull(subLevel);
        this.expirationGameTime = expirationGameTime;
        SubLevelContainer.getContainer(subLevel.getLevel())
                .addForceLoadTicket(
                        subLevel, NaturalSpawnPhysicalChunkManager.TICKET_TYPE, expirationGameTime);
    }

    public UUID subLevelId() {
        return subLevel.getUniqueId();
    }

    public ServerSubLevel subLevel() {
        return subLevel;
    }

    public long expirationGameTime() {
        return expirationGameTime;
    }

    public boolean isReleased() {
        return released;
    }

    public boolean isExpired(long now) {
        return now >= expirationGameTime;
    }

    public void release() {
        if (released) return;
        released = true;
        SubLevelContainer.getContainer(subLevel.getLevel())
                .removeForceLoadTicket(
                        subLevel, NaturalSpawnPhysicalChunkManager.TICKET_TYPE, expirationGameTime);
    }
}
