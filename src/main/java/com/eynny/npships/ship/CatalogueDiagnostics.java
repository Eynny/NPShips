package com.eynny.npships.ship;

import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.List;

/** One build's bounded diagnostics; never retains resources, author payloads or stack traces. */
final class CatalogueDiagnostics {
    static final int DETAIL_LIMIT = 16;
    private final List<String> details = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private int rejected;

    void reject(String id, String origin, EntryFailure failure) {
        rejected++;
        if (details.size() < DETAIL_LIMIT)
            details.add(concise("excluded " + id + " [" + origin + "]: " + failure.getMessage()));
    }

    void detail(String message) {
        if (notices.size() < DETAIL_LIMIT) notices.add(concise(message));
    }

    NPShipsSchematicManager.CatalogueStatus finish(int valid) {
        // Invalid entries take priority over duplicate/override notices within the shared limit.
        for (String notice : notices) {
            if (details.size() >= DETAIL_LIMIT) break;
            details.add(notice);
        }
        for (String detail : details) LogUtils.getLogger().warn("[NPShips catalogue] {}", detail);
        if (rejected > 0)
            LogUtils.getLogger()
                    .warn(
                            "[NPShips catalogue] Published {} valid entries; excluded {}; {} representative diagnostics (limit {}). Repair sources and refresh to retry.",
                            valid,
                            rejected,
                            details.size(),
                            DETAIL_LIMIT);
        return new NPShipsSchematicManager.CatalogueStatus(false, rejected, List.copyOf(details));
    }

    static String concise(String message) {
        String safe = String.valueOf(message).replaceAll("[\\r\\n\\t]", " ");
        return safe.length() <= 1000 ? safe : safe.substring(0, 997) + "...";
    }

    /** Only known source/validation failures cross the per-entry isolation boundary. */
    static final class EntryFailure extends IllegalArgumentException {
        EntryFailure(String stage, Throwable cause) {
            super(
                    stage + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                    cause);
        }
    }
}
