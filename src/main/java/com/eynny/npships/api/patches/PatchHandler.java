package com.eynny.npships.api.patches;

/** Per-spawn server-thread state, closed by the template/attempt. No rollback is promised. */
public interface PatchHandler extends AutoCloseable {
    /**
     * One host-owned stage step. Existing handlers receive their original three callbacks through
     * this bridge; new stages are no-ops unless explicitly implemented. Only preparation-capable
     * stages may return false to continue through the existing cooperative owner. Native mutation
     * and mandatory cleanup are atomic and cannot be made interruptible by this result.
     *
     * @param stage fixed host callback boundary
     * @param execution callback-local host execution context
     * @return whether this stage has completed its cooperative work
     * @throws Exception if this stage fails
     */
    default boolean step(PatchStage stage, PatchExecutionContext execution) throws Exception {
        java.util.Objects.requireNonNull(stage);
        java.util.Objects.requireNonNull(execution);
        switch (stage) {
            case PREPARE -> prepare(execution.context());
            case BEFORE_ASSEMBLY -> beforeAssembly(execution.context());
            case AFTER_PLACEMENT -> afterPlacement(execution.context());
            default -> {}
        }
        return true;
    }

    /**
     * Before placement: preflight/preparation only; do not mutate world blocks.
     *
     * @param context callback-scoped host context
     * @throws Exception if detached preparation fails
     */
    default void prepare(PatchContext context) throws Exception {}

    /**
     * Template blocks exist, native assembly has not started. Failure is terminal.
     *
     * @param context callback-scoped host context
     * @throws Exception if preassembly work fails
     */
    default void beforeAssembly(PatchContext context) throws Exception {}

    /**
     * Assembly/body placement completed. Block/BE targets use resolve(); ENTITY targets use
     * entity() only within this callback. Do not retain the context or live object.
     *
     * @param context callback-scoped host context
     * @throws Exception if restoration fails after native placement
     */
    default void afterPlacement(PatchContext context) throws Exception {}

    /**
     * Release temporary resources on success, failure or cancellation. Never roll back world data
     * here.
     *
     * @throws Exception if temporary resource release fails
     */
    @Override
    default void close() throws Exception {}
}
