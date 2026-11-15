package com.eynny.npships.api.patches;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable code registration. Callbacks must not retain worlds or editor state.
 *
 * @param version supported target payload version from 1 through 65535
 * @param target native authored target kind
 * @param requiredMods required native mod IDs
 * @param validator pure deterministic detached-data validator
 * @param factory fresh per-owner factory
 * @param entityMode supported physical entity-root mode
 */
public record PatchType(
        int version,
        TargetKind target,
        Set<String> requiredMods,
        Validator validator,
        Factory factory,
        EntityMode entityMode) {
    /** Native authored target shape accepted by this provider. */
    public enum TargetKind {
        /** Native block target. */
        BLOCK,
        /** Native block-entity target. */
        BLOCK_ENTITY,
        /** Stable authored entity-root target. */
        ENTITY
    }

    /** Physical entity transforms retain the existing Mob-root-only contract. */
    public enum EntityMode {
        /** Only ordinary/static entity placement is supported. */
        STATIC_ONLY,
        /** Existing physical Mob-root entity contract. */
        MOB_ROOT
    }

    /**
     * Validates version/dependency metadata and retains registered callback code.
     *
     * @param version supported target payload version from 1 through 65535
     * @param target native authored target kind
     * @param requiredMods required native mod IDs
     * @param validator pure deterministic detached-data validator
     * @param factory fresh per-owner factory
     */
    public PatchType(
            int version,
            TargetKind target,
            Set<String> requiredMods,
            Validator validator,
            Factory factory) {
        this(version, target, requiredMods, validator, factory, EntityMode.STATIC_ONLY);
    }

    /**
     * Validates version/dependency metadata and retains registered callback code.
     *
     * @param version supported target payload version from 1 through 65535
     * @param target native authored target kind
     * @param requiredMods required native mod IDs
     * @param validator pure deterministic detached-data validator
     * @param factory fresh per-owner factory
     * @param entityMode supported physical entity-root mode
     */
    public PatchType {
        if (version < 1 || version > 65535)
            throw new IllegalArgumentException("Invalid patch schema version");
        Objects.requireNonNull(target);
        Objects.requireNonNull(entityMode);
        requiredMods = Set.copyOf(requiredMods);
        if (requiredMods.size() > 16
                || requiredMods.stream().anyMatch(s -> !s.matches("[a-z][a-z0-9_]{1,63}")))
            throw new IllegalArgumentException("Invalid patch dependency IDs");
        Objects.requireNonNull(validator);
        Objects.requireNonNull(factory);
    }

    /** Pure validator of one detached target payload. */
    @FunctionalInterface
    public interface Validator {
        /**
         * Pure deterministic detached-data validation. IllegalArgumentException means invalid
         * author data.
         *
         * @param target declared native or prepared serializer target
         * @param payload privately copied provider payload
         */
        void validate(PatchTarget target, JsonObject payload);
    }

    /** Factory of one fresh target-specific runtime handler. */
    @FunctionalInterface
    public interface Factory {
        /**
         * Return a fresh handler for this target and spawn. Payload is a private defensive copy.
         *
         * @param target declared native or prepared serializer target
         * @param payload privately copied provider payload
         * @return fresh per-binding handler
         * @throws Exception if fresh handler acquisition fails
         */
        PatchHandler create(PatchTarget target, JsonObject payload) throws Exception;
    }
}
