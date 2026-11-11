package com.eynny.npships.naturalspawn;

/** Terminal result of a complete opportunity, with no point economy or failure backoff. */
public enum NaturalSpawnOutcome {
    SUCCESS,
    NO_ELIGIBLE_PLAYER,
    NO_RELEVANT_PLAYER,
    NO_ELIGIBLE_CANDIDATE,
    PLAYER_TOO_CLOSE,
    ENVIRONMENT_EXHAUSTED,
    PHYSICAL_OVERLAP,
    CANCELLED,
    DISABLED,
    RECENT_SPAWN_COOLDOWN,
    PREPARATION_FAILED,
    PLACEMENT_FAILED,
    INTERNAL_ERROR
}
