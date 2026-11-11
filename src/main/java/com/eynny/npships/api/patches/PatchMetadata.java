package com.eynny.npships.api.patches;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * Optional immutable display metadata of a registered provider, not an execution or conflict rule.
 * Use {@link #PatchMetadata(String, String)} for the usual name/description form. Optional
 * translation keys are resolved on the client, with readable fallbacks. Subjects are declarations,
 * not inferred Java behavior. Equal domain/id pairs indicate shared scope only.
 *
 * @param nameKey translation key, or empty for the fallback
 * @param name readable fallback name
 * @param descriptionKey translation key, or empty for the fallback
 * @param description readable fallback description
 * @param subjects bounded declared object/data domains; empty means unspecified, not no overlap
 */
public record PatchMetadata(
        String nameKey,
        String name,
        String descriptionKey,
        String description,
        List<Subject> subjects) {
    /**
     * Validates individual lengths, an 8192-character total and defensively copies subjects.
     *
     * @param nameKey translation key or empty
     * @param name readable fallback name
     * @param descriptionKey translation key or empty
     * @param description readable fallback description
     * @param subjects declared scopes, at most 64
     * @throws IllegalArgumentException for blank fallbacks, overlong text, duplicate or excess
     *     subjects
     * @throws NullPointerException for null arguments
     */
    public PatchMetadata {
        text(nameKey, 256, false);
        text(name, 256, true);
        text(descriptionKey, 256, false);
        text(description, 2048, true);
        subjects = List.copyOf(subjects);
        long characters =
                (long) nameKey.length()
                        + name.length()
                        + descriptionKey.length()
                        + description.length();
        for (var subject : subjects)
            characters +=
                    subject.domain().toString().length()
                            + subject.id().toString().length()
                            + subject.labelKey().length()
                            + subject.label().length();
        if (characters > 8192)
            throw new IllegalArgumentException("Provider display metadata exceeds 8192 characters");
        if (subjects.size() > 64
                || subjects.stream().map(v -> v.domain() + "/" + v.id()).distinct().count()
                        != subjects.size())
            throw new IllegalArgumentException("Invalid provider display subjects");
    }

    /**
     * Creates display metadata using only a name and description. The registered provider supplies
     * its ID and owning mod; translation keys and scope declarations are optional.
     *
     * @param name readable name, at most 256 characters
     * @param description what the patch does, at most 2048 characters
     * @throws IllegalArgumentException for blank or overlong text
     * @throws NullPointerException for null arguments
     */
    public PatchMetadata(String name, String description) {
        this("", name, "", description, List.of());
    }

    private static void text(String value, int max, boolean required) {
        Objects.requireNonNull(value);
        if (value.length() > max || (required && value.isBlank()))
            throw new IllegalArgumentException("Invalid provider display text");
    }

    /**
     * A provider-defined scope identity. It need not be a Minecraft block/entity or physical body.
     *
     * @param domain namespace-qualified kind of declared scope
     * @param id stable subject identifier within that domain
     * @param labelKey translation key, or empty for the fallback
     * @param label readable fallback name
     */
    public record Subject(
            ResourceLocation domain, ResourceLocation id, String labelKey, String label) {
        /**
         * Validates identifiers and text.
         *
         * @param domain namespace-qualified kind of scope
         * @param id stable subject identifier
         * @param labelKey translation key or empty
         * @param label readable fallback
         * @throws IllegalArgumentException for overlong identifiers or text, or a blank fallback
         * @throws NullPointerException for null arguments
         */
        public Subject {
            Objects.requireNonNull(domain);
            Objects.requireNonNull(id);
            if (domain.toString().length() > 256 || id.toString().length() > 256)
                throw new IllegalArgumentException("Subject identifier too long");
            text(labelKey, 256, false);
            text(label, 256, true);
        }
    }
}
