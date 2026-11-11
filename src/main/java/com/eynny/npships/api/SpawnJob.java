package com.eynny.npships.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-thread handle. The manager alone advances/collects it; no tick or mutable native access.
 */
public interface SpawnJob {
    /**
     * Returns the stable identity of this managed request.
     *
     * @return request identity, independent of physical bodies
     */
    UUID id();

    /**
     * True once the manager has collected the terminal result.
     *
     * @return whether terminal collection has finished
     */
    boolean isFinished();

    /**
     * Returns detached values after terminal collection.
     *
     * @return terminal result, or empty while active
     */
    Optional<SpawnResult> result();

    /**
     * Idempotent cancellation; not rollback. Notification follows terminal collection.
     *
     * @return whether cancellation was requested for an unfinished job
     */
    boolean cancel();
}
