package com.eynny.npships.lifecycle;

import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.server.level.ServerLevel;

import java.util.UUID;
import java.util.stream.Collectors;

/** Event-only diagnostics: call path identifies code origin, not necessarily the initial cause. */
public final class NPShipsRemovalDiagnostics {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private NPShipsRemovalDiagnostics() {}

    public static void log(ServerLevel level, UUID id, String event, String reason) {
        if (!NPShipsRuntimeConfig.developerModeEnabled()) {
            boolean trackedRemoval = false;
            if (event.equals("SABLE_REMOVAL_EVENT")) {
                var tracked = NPShipsSavedData.peek(level);
                trackedRemoval = tracked != null && tracked.isTracked(id);
            }
            if (trackedRemoval || event.equals("HOLDING_DELETE_SUBMITTED"))
                LOGGER.info(
                        "[NPShips] Structure {} in {}: {}",
                        id,
                        level.dimension().location(),
                        event);
            return;
        }
        var data = NPShipsSavedData.peek(level);
        String path =
                StackWalker.getInstance()
                        .walk(
                                frames ->
                                        frames.skip(1)
                                                .limit(20)
                                                .map(Object::toString)
                                                .collect(Collectors.joining(" <- ")));
        LOGGER.info(
                "[NPShips Removal] event={} uuid={} dimension={} tick={} tracked={} reason={} thread={} callPath={}",
                event,
                id,
                level.dimension().location(),
                level.getGameTime(),
                data != null && data.isTracked(id),
                reason,
                Thread.currentThread().getName(),
                path);
    }
}
