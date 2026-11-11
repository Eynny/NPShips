package com.eynny.npships.api.patches;

import com.google.gson.JsonObject;

import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Optional payload readers of one provider; versions never change a host envelope or identity.
 *
 * @param target target JSON schema, or null when inapplicable
 * @param authorData named provider-owned NBT schemas
 */
public record PatchPayloads(
        @Nullable Schema<JsonObject> target, Map<String, Schema<CompoundTag>> authorData) {
    /**
     * Copies named schemas and requires at least one schema.
     *
     * @param target target JSON schema, or null when inapplicable
     * @param authorData named provider-owned NBT schemas
     */
    public PatchPayloads {
        var names = new TreeMap<String, Schema<CompoundTag>>();
        Objects.requireNonNull(authorData)
                .forEach(
                        (name, schema) -> {
                            if (name == null || name.isEmpty() || name.length() > 256)
                                throw new IllegalArgumentException(
                                        "Invalid provider author-data name");
                            names.put(name, Objects.requireNonNull(schema));
                        });
        authorData = Collections.unmodifiableMap(names);
        if (target == null && authorData.isEmpty())
            throw new IllegalArgumentException("At least one payload schema is required");
    }

    /**
     * One explicit older-version route directly to current. Callbacks must be deterministic,
     * detached, side-effect-free and bounded; no world or document is supplied. The validator and
     * converter receive separate defensive inputs. Unknown fields are the provider's
     * responsibility.
     *
     * @param <T> detached payload type, JSON or named NBT as registered
     * @param validator pure deterministic detached-data validator
     * @param converter pure direct older-to-current converter
     */
    public record Reader<T>(Consumer<T> validator, UnaryOperator<T> converter) {
        /**
         * Requires both pure conversion callbacks.
         *
         * @param validator pure deterministic detached-data validator
         * @param converter pure direct older-to-current converter
         */
        public Reader {
            Objects.requireNonNull(validator);
            Objects.requireNonNull(converter);
        }
    }

    /**
     * Exact supported versions, not a range or an automatic migration chain. Current data is only
     * validated; it is never passed through an older-version converter. A converter's result must
     * pass both this current validator and the provider's normal target/document validation.
     *
     * @param <T> detached payload type, JSON or named NBT as registered
     * @param currentVersion positive current provider payload version
     * @param currentValidator pure current-version validator
     * @param older exact direct readers for declared older versions
     */
    public record Schema<T>(
            int currentVersion, Consumer<T> currentValidator, Map<Integer, Reader<T>> older) {
        /**
         * Validates exact older-version routes and copies their lookup map.
         *
         * @param currentVersion positive current provider payload version
         * @param currentValidator pure current-version validator
         * @param older exact direct readers for declared older versions
         */
        public Schema {
            if (currentVersion < 1)
                throw new IllegalArgumentException("Payload version must be positive");
            Objects.requireNonNull(currentValidator);
            var readers = new TreeMap<Integer, Reader<T>>();
            Objects.requireNonNull(older)
                    .forEach(
                            (version, reader) -> {
                                if (version == null || version < 1 || version >= currentVersion)
                                    throw new IllegalArgumentException(
                                            "Historic payload versions must precede current");
                                readers.put(version, Objects.requireNonNull(reader));
                            });
            older = Collections.unmodifiableMap(readers);
        }

        /**
         * Tests membership in the exact readable version set.
         *
         * @param version positive provider payload version
         * @return whether this exact version was declared readable
         */
        public boolean readable(int version) {
            return version == currentVersion || older.containsKey(version);
        }

        /**
         * Lists current and explicitly declared older versions.
         *
         * @return immutable exact readable version set
         */
        public Set<Integer> readableVersions() {
            var versions = new TreeSet<>(older.keySet());
            versions.add(currentVersion);
            return Collections.unmodifiableSet(versions);
        }
    }

    /**
     * Qualified version refusal; it is distinct from absent code, dependencies or server policy.
     */
    public static final class UnsupportedVersionException extends IllegalArgumentException {
        /**
         * Refused source and declared current payload versions, respectively.
         *
         * @serial exact sourceVersion or currentVersion of this refusal
         */
        private final int sourceVersion, currentVersion;

        /**
         * Exact declared readable versions retained with the refusal.
         *
         * @serial immutable readable version set
         */
        private final Set<Integer> readableVersions;

        /**
         * Captures the unsupported source version and readable version set.
         *
         * @param subject qualified provider payload subject
         * @param sourceVersion refused source payload version
         * @param currentVersion positive current provider payload version
         * @param readableVersions exact supported payload versions
         */
        public UnsupportedVersionException(
                String subject,
                int sourceVersion,
                int currentVersion,
                Set<Integer> readableVersions) {
            super(
                    "Unsupported payload version "
                            + sourceVersion
                            + " for "
                            + subject
                            + "; current="
                            + currentVersion
                            + ", readable="
                            + readableVersions.stream().sorted().limit(16).toList()
                            + (readableVersions.size() > 16
                                    ? " (" + readableVersions.size() + " total)"
                                    : ""));
            this.sourceVersion = sourceVersion;
            this.currentVersion = currentVersion;
            this.readableVersions = Set.copyOf(readableVersions);
        }

        /**
         * Reads the refused payload version.
         *
         * @return refused source payload version
         */
        public int sourceVersion() {
            return sourceVersion;
        }

        /**
         * Reads the declared current payload version.
         *
         * @return current provider payload version
         */
        public int currentVersion() {
            return currentVersion;
        }

        /**
         * Reads the exact declared readable version set.
         *
         * @return immutable exact readable version set
         */
        public Set<Integer> readableVersions() {
            return readableVersions;
        }
    }
}
