package com.eynny.npships.api.patches;

/**
 * Fixed host boundaries. Ordering never moves a callback into a different stage. New constants are
 * appended for compatibility; enum ordinals are not the runtime execution sequence. Per-block and
 * per-record stages retain source-unit order; their dependency graph orders participating callbacks
 * within one unit, not completion of another unit or an unbound provider. Capability-only host
 * boundaries do not install a PatchHandler callback merely by appearing in ordering metadata.
 * AFTER_PLACEMENT is the global boundary after all native reference restoration. A code dependency
 * does not invent work.
 */
public enum PatchStage {
    /** Legacy author-validation boundary; register authoring through PatchAuthoring. */
    VALIDATION,
    /** Legacy author boundary; runtime adapters must use supported runtime stages. */
    AUTHOR_PREPARATION,
    /** Legacy pre-placement preparation callback bridge. */
    PREPARE,
    /** Legacy callback before native assembly. */
    BEFORE_ASSEMBLY,
    /** Global boundary after native reference restoration. */
    AFTER_PLACEMENT,
    /** Detached runtime preflight before handler resource acquisition. */
    INPUT_VALIDATION,
    /** Prepared per-block native serializer work. */
    PREPARE_BLOCK,
    /** Prepared native wire work. */
    PREPARE_WIRES,
    /** Prepared native reference work. */
    PREPARE_REFERENCES,
    /** Final detached prepared-input proposal boundary. */
    PREPARE_FINALIZE,
    /** Native template loading has completed. */
    TEMPLATE_LOADED,
    /** Boundary immediately before native assembly. */
    BEFORE_NATIVE_ASSEMBLY,
    /** Boundary before native body positioning. */
    BEFORE_BODY_PLACEMENT,
    /** Boundary after native body positioning. */
    AFTER_BODY_PLACEMENT,
    /** Per-record local native restoration. */
    RESTORE_LOCAL,
    /** Per-record native reference restoration. */
    RESTORE_REFERENCES,
    /** Boundary before native template placement. */
    BEFORE_NATIVE_PLACEMENT,
    /**
     * Server-authorized per-source-unit capture; detached extraction before document acceptance.
     */
    CAPTURE_BLOCK,
    /**
     * Existing detached BE defaults are available; specialized native data precedes block handlers.
     */
    PREPARE_NATIVE_DATA,
    /** Bodies have moved; native context is restored before body/reference callbacks. */
    RESTORE_NATIVE_CONTEXT,
    /** Existing delayed native repair boundary; it does not create another queue or deadline. */
    POST_PLACEMENT_MAINTENANCE
}
