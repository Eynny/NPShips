package com.eynny.npships.api;

import java.util.Optional;

/**
 * Server-thread handle for one admitted automatic opportunity; native state is released at
 * collection.
 */
public interface AutomaticSpawnJob extends SpawnJob {
    /**
     * Returns the detached terminal result and observed cooldown bypass after collection.
     *
     * @return empty until the existing manager has collected completion
     * @throws IllegalStateException if used off the owning server thread or after that server is
     *     gone
     */
    Optional<NPShipsSpawnApi.AutomaticResult> automaticResult();
}
