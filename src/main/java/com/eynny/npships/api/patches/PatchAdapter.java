package com.eynny.npships.api.patches;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Process-lifetime code for document-scoped runtime bindings. Bind/validation must inspect detached
 * inputs only and remain safe when a declared native dependency is absent. Native work belongs to
 * fresh per-binding handlers after the host has checked policy, capabilities and dependencies. This
 * contract does not install editor, capture or rendering callbacks.
 */
public interface PatchAdapter {
    /**
     * Optional stateless capability code; no binding, mutable document or world is granted.
     * Resource-owning capabilities return factories, never retained native owners.
     *
     * @param <T> host contract type
     * @param contract exact host capability interface
     * @return code capability, or empty
     */
    default <T> java.util.Optional<T> capability(Class<T> contract) {
        java.util.Objects.requireNonNull(contract);
        return java.util.Optional.empty();
    }

    /**
     * Declares native mod dependencies checked before handler execution.
     *
     * @return native dependency IDs
     */
    Set<String> requiredMods();

    /**
     * Fixed host boundaries used by this adapter; copied at registration.
     *
     * @return declared fixed host boundaries
     */
    Set<PatchStage> stages();

    /**
     * Return stable, detached identities and explicitly declared mapping positions.
     *
     * @param context callback-scoped host context
     * @return stable detached runtime bindings
     */
    List<PatchBinding> bind(PatchExecutionContext context);

    /**
     * Pure deterministic detached-data validation, before any handler acquires resources.
     *
     * @param binding detached runtime binding
     */
    default void validate(PatchBinding binding) {}

    /**
     * Optional host facets required by this binding, frozen before handlers acquire resources.
     *
     * @param binding detached runtime binding
     * @return required host capability classes
     */
    default Set<Class<?>> requestedCapabilities(PatchBinding binding) {
        return Set.of();
    }

    /**
     * Existing authored ROOT identities only; no implicit enrollment or passenger mapping.
     *
     * @param binding detached runtime binding
     * @return declared authored roots and supported entity modes
     */
    default Map<UUID, PatchType.EntityMode> entityRoots(PatchBinding binding) {
        return Map.of();
    }

    /**
     * Exclusive prepared serializer fields; no claim grants geometry or native identity mutation.
     *
     * @param binding detached runtime binding
     * @return exclusive serializer field declarations
     */
    default List<PatchPreparedInput.Claim> preparedClaims(PatchBinding binding) {
        return List.of();
    }

    /**
     * Return a fresh non-null handler. No global world or preparation state is permitted.
     *
     * @param binding detached runtime binding
     * @param context callback-scoped host context
     * @return fresh per-binding handler
     * @throws Exception if handler acquisition fails; the host closes previously acquired handlers
     */
    PatchHandler create(PatchBinding binding, PatchExecutionContext context) throws Exception;
}
