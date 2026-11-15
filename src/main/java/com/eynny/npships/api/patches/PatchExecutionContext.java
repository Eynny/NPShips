package com.eynny.npships.api.patches;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Callback-local host access, separate from immutable provider registration. Capabilities describe
 * actual host ownership, not a list of gameplay mechanism kinds. The host guards its resolver's
 * callback window and chooses which capabilities are available at each fixed stage. Do not retain
 * this context or capability objects; future editor/render owners are not supplied by this API.
 */
public final class PatchExecutionContext {
    private final PatchContext context;
    private final Function<Class<?>, ?> capabilities;

    /**
     * Retains borrowed context and callback-local capability resolution.
     *
     * @param context callback-scoped host context
     * @param capabilities callback-local host capability resolver
     */
    public PatchExecutionContext(PatchContext context, Function<Class<?>, ?> capabilities) {
        this.context = Objects.requireNonNull(context);
        this.capabilities = Objects.requireNonNull(capabilities);
    }

    /**
     * Borrows this host context without extending its callback lifetime.
     *
     * @return borrowed callback-local server context
     */
    public PatchContext context() {
        return context;
    }

    /**
     * Resolves an optional compatible host facet in this callback window.
     *
     * @param <T> requested host capability type
     * @param type requested host capability class
     * @return compatible host facet, or empty when unavailable
     */
    public <T> Optional<T> findCapability(Class<T> type) {
        Objects.requireNonNull(type);
        Object value = capabilities.apply(type);
        if (value == null) return Optional.empty();
        if (!type.isInstance(value))
            throw new IllegalStateException(
                    "Host returned an incompatible patch capability: " + type.getName());
        return Optional.of(type.cast(value));
    }

    /**
     * Resolves a required compatible host facet or refuses its absence.
     *
     * @param <T> requested host capability type
     * @param type requested host capability class
     * @return compatible required host facet
     */
    public <T> T requireCapability(Class<T> type) {
        return findCapability(type)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Required patch host capability is unavailable: "
                                                + type.getName()));
    }
}
