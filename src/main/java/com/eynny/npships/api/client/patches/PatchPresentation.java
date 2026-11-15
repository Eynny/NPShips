package com.eynny.npships.api.client.patches;

import com.eynny.npships.api.patches.PatchTarget;
import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Client-only, stateless presentation descriptor. Callbacks receive detached data, never editor
 * sessions. Do not capture a Screen, world or document. NPShips owns widgets, drafts and commits.
 * Common validation is authoritative; callbacks must be pure apart from writing their payload copy.
 *
 * @param title display title, copied on construction
 * @param supports pure detached-target applicability test
 * @param createPayload pure detached default-payload factory
 * @param fields pure callback producing retained form fields
 */
public record PatchPresentation(
        Component title,
        Predicate<PatchTarget> supports,
        Function<PatchTarget, JsonObject> createPayload,
        BiFunction<PatchTarget, JsonObject, List<Field>> fields) {
    /** Maximum retained text fields in one declarative presentation. */
    public static final int MAX_FIELDS = 32;

    /**
     * Retains pure callbacks and copies the display title.
     *
     * @param title display title, copied on construction
     * @param supports pure detached-target applicability test
     * @param createPayload pure detached default-payload factory
     * @param fields pure callback producing retained form fields
     */
    public PatchPresentation {
        title = Objects.requireNonNull(title).copy();
        Objects.requireNonNull(supports);
        Objects.requireNonNull(createPayload);
        Objects.requireNonNull(fields);
    }

    /**
     * Copies the display title.
     *
     * @return a fresh display component
     */
    @Override
    public Component title() {
        return title.copy();
    }

    /**
     * A retained text input. write runs on Apply with a private copy of the original payload.
     *
     * @param label display label, copied on construction
     * @param hint display hint, copied on construction
     * @param initial initial retained text
     * @param maxLength retained UTF-16 text length limit, from 1 through 32767
     * @param write Apply callback operating on a private payload copy
     */
    public record Field(
            Component label,
            Component hint,
            String initial,
            int maxLength,
            BiConsumer<JsonObject, String> write) {
        /**
         * Copies labels and validates retained text length.
         *
         * @param label display label, copied on construction
         * @param hint display hint, copied on construction
         * @param initial initial retained text
         * @param maxLength retained UTF-16 text length limit, from 1 through 32767
         * @param write Apply callback operating on a private payload copy
         */
        public Field {
            label = Objects.requireNonNull(label).copy();
            hint = Objects.requireNonNull(hint).copy();
            Objects.requireNonNull(initial);
            Objects.requireNonNull(write);
            if (maxLength < 1 || maxLength > 32767 || initial.length() > maxLength)
                throw new IllegalArgumentException("Patch field length must be 1..32767");
        }

        /**
         * Copies the field label.
         *
         * @return a fresh label component
         */
        @Override
        public Component label() {
            return label.copy();
        }

        /**
         * Copies the field hint.
         *
         * @return a fresh hint component
         */
        @Override
        public Component hint() {
            return hint.copy();
        }
    }
}
