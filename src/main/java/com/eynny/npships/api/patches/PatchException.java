package com.eynny.npships.api.patches;

import net.minecraft.core.BlockPos;

/** Data/availability errors isolate a catalogue entry; runtime phases remain terminal. */
public final class PatchException extends IllegalArgumentException {
    /** Boundary at which provider data or execution was refused. */
    public enum Reason {
        /** Unknown type. */
        UNKNOWN_TYPE,
        /** Unavailable dependency. */
        UNAVAILABLE_DEPENDENCY,
        /** Invalid payload. */
        INVALID_PAYLOAD,
        /** Validation exception. */
        VALIDATION_EXCEPTION,
        /** Preparation failure. */
        PREPARATION_FAILURE,
        /** Before assembly failure. */
        BEFORE_ASSEMBLY_FAILURE,
        /** Restoration failure. */
        RESTORATION_FAILURE
    }

    /**
     * Typed boundary retained with this exception.
     *
     * @serial provider failure category
     */
    private final Reason reason;

    /**
     * Creates a bounded qualified provider diagnostic while retaining its cause.
     *
     * @param reason typed provider failure boundary
     * @param type qualified provider type description
     * @param target qualified local target description
     * @param detail diagnostic detail
     * @param cause original failure cause, or null
     */
    public PatchException(
            Reason reason, String type, BlockPos target, String detail, Throwable cause) {
        this(reason, type, target.toShortString(), detail, cause);
    }

    /**
     * Creates a bounded qualified provider diagnostic while retaining its cause.
     *
     * @param reason typed provider failure boundary
     * @param type qualified provider type description
     * @param target qualified local target description
     * @param detail diagnostic detail
     * @param cause original failure cause, or null
     */
    public PatchException(
            Reason reason, String type, String target, String detail, Throwable cause) {
        super("Patch " + type + " at " + target + " [" + reason + "]: " + concise(detail), cause);
        this.reason = reason;
    }

    private static String concise(String detail) {
        var text = String.valueOf(detail).replaceAll("[\\r\\n\\t]", " ");
        return text.length() > 768 ? text.substring(0, 768) + "..." : text;
    }

    /**
     * Reads the typed provider failure boundary.
     *
     * @return typed provider failure boundary
     */
    public Reason reason() {
        return reason;
    }
}
