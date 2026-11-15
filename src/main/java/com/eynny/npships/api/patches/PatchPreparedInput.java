package com.eynny.npships.api.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Optional server callback capability for the existing detached prepared native input. Writes are
 * available only in PREPARE_FINALIZE, before final native load and before world clearing. This
 * capability never edits the authored source, history or live world. Addon semantics remain its
 * own.
 */
public final class PatchPreparedInput implements AutoCloseable {
    /**
     * Host storage locations, not gameplay mechanism types. No fake target is required for records.
     */
    public sealed interface Target permits Block, Root {}

    /**
     * Declared schematic-local block serializer target.
     *
     * @param position declared schematic-local block cell
     */
    public record Block(BlockPos position) implements Target {
        /**
         * Copies the declared local cell coordinate.
         *
         * @param position declared schematic-local block cell
         */
        public Block {
            position = Objects.requireNonNull(position).immutable();
        }
    }

    /**
     * Existing stable authored root identity, enrolled by this binding; never a runtime UUID.
     *
     * @param token existing stable authored root token
     */
    public record Root(UUID token) implements Target {
        /**
         * Requires an existing stable authored root token.
         *
         * @param token existing stable authored root token
         */
        public Root {
            Objects.requireNonNull(token);
        }
    }

    /**
     * Explicit serializer field access declaration. The host freezes it per binding, verifies the
     * native type and target enrollment and rejects competing claims. It does not infer third-party
     * schema meaning or certify arbitrary foreign fields safe.
     *
     * @param target declared native or prepared serializer target
     * @param nativeType observed native or serializer type ID
     * @param fields claimed serializer field names
     */
    public record Claim(Target target, ResourceLocation nativeType, Set<String> fields) {
        /**
         * Copies a nonempty exclusive serializer field claim.
         *
         * @param target declared native or prepared serializer target
         * @param nativeType observed native or serializer type ID
         * @param fields claimed serializer field names
         */
        public Claim {
            Objects.requireNonNull(target);
            Objects.requireNonNull(nativeType);
            fields = Set.copyOf(fields);
            if (fields.isEmpty() || fields.stream().anyMatch(key -> key == null || key.isEmpty()))
                throw new IllegalArgumentException("Prepared field claims require named fields");
        }
    }

    /** Prepared-input compare-and-replace transaction outcome. */
    public enum Status {
        /** The owner accepted the complete proposal. */
        ACCEPTED,
        /** The proposal already matches current owner state. */
        UNCHANGED,
        /** The expected revision no longer matches. */
        STALE,
        /** The host could not admit this operation. */
        REFUSED
    }

    /**
     * Detached prepared transaction result and owner revision.
     *
     * @param status typed observation or transaction outcome
     * @param revision opaque owner revision
     * @param detail diagnostic detail
     */
    public record Result(Status status, UUID revision, String detail) {
        /**
         * Requires status, revision and diagnostic detail.
         *
         * @param status typed observation or transaction outcome
         * @param revision opaque owner revision
         * @param detail diagnostic detail
         */
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(revision);
            Objects.requireNonNull(detail);
        }

        /**
         * Tests whether the transaction was accepted or already unchanged.
         *
         * @return true for ACCEPTED or UNCHANGED
         */
        public boolean accepted() {
            return status == Status.ACCEPTED || status == Status.UNCHANGED;
        }
    }

    /** Callback-local staging writer. Tags are borrowed during each call and copied by the host. */
    public interface Changes {
        /**
         * Stages one exclusively claimed serializer-field compare-and-replace.
         *
         * @param target declared native or prepared serializer target
         * @param field claimed top-level serializer field
         * @param expected expected prior value, or null for absence
         * @param replacement proposed value, or null to remove
         */
        void replace(
                Target target, String field, @Nullable Tag expected, @Nullable Tag replacement);

        /**
         * Namespaced, instance-local ephemeral data; never serialized into the author's source.
         *
         * @param name provider-owned record name
         * @param expected expected prior value, or null for absence
         * @param replacement proposed value, or null to remove
         */
        void record(
                ResourceLocation name,
                @Nullable CompoundTag expected,
                @Nullable CompoundTag replacement);
    }

    /** Host implementation only; addons obtain this capability through PatchExecutionContext. */
    public interface Access {
        /**
         * Reads the current prepared-input owner revision.
         *
         * @return opaque current owner revision
         */
        UUID revision();

        /**
         * Reads the enrolled target serializer identity.
         *
         * @param target declared native or prepared serializer target
         * @return native serializer identity
         */
        ResourceLocation nativeType(Target target);

        /**
         * Copies one claimed serializer field under host admission.
         *
         * @param target declared native or prepared serializer target
         * @param key top-level serializer field name
         * @return detached field value, or null when absent
         */
        @Nullable
        Tag field(Target target, String key);

        /**
         * Copies one instance-local ephemeral record under host admission.
         *
         * @param name provider-owned record name
         * @return fresh detached record, or empty when absent
         */
        Optional<CompoundTag> record(ResourceLocation name);

        /**
         * Validates and accepts one complete prepared-input transaction in its allowed phase.
         *
         * @param expectedRevision opaque revision against which the transaction is proposed
         * @param changes synchronous transaction staging callback
         * @return typed outcome and resulting revision
         */
        Result apply(UUID expectedRevision, Consumer<Changes> changes);
    }

    private final Thread owner = Thread.currentThread();
    private Access access;

    /**
     * Host-side construction. The supplying access must enforce its callback and phase window.
     *
     * @param access guarded callback/phase-local host access
     */
    public PatchPreparedInput(Access access) {
        this.access = Objects.requireNonNull(access);
    }

    private Access require() {
        if (access == null || Thread.currentThread() != owner)
            throw new IllegalStateException("Prepared input callback expired or wrong thread");
        return access;
    }

    /**
     * Reads the prepared-input revision in this callback window.
     *
     * @return opaque current owner revision
     */
    public UUID revision() {
        return require().revision();
    }

    /**
     * Reads an enrolled target native serializer identity.
     *
     * @param target declared native or prepared serializer target
     * @return native serializer identity
     */
    public ResourceLocation nativeType(Target target) {
        return require().nativeType(Objects.requireNonNull(target));
    }

    /**
     * Selected detached value; the host reserves its declared copy capacity before copying.
     *
     * @param target declared native or prepared serializer target
     * @param key top-level serializer field name
     * @return detached field value, or null when absent
     */
    public @Nullable Tag field(Target target, String key) {
        return require().field(Objects.requireNonNull(target), Objects.requireNonNull(key));
    }

    /**
     * Copies an instance-local ephemeral record through guarded host access.
     *
     * @param name provider-owned record name
     * @return fresh detached record, or empty when absent
     */
    public Optional<CompoundTag> record(ResourceLocation name) {
        return require().record(Objects.requireNonNull(name));
    }

    /**
     * Stages a complete proposal and rechecks callback validity before commit.
     *
     * @param expectedRevision opaque revision against which the transaction is proposed
     * @param changes synchronous transaction staging callback
     * @return typed outcome and resulting revision
     */
    public Result apply(UUID expectedRevision, Consumer<Changes> changes) {
        Objects.requireNonNull(expectedRevision);
        Objects.requireNonNull(changes);
        var current = require();
        return current.apply(
                expectedRevision,
                writer -> {
                    changes.accept(writer);
                    // Closing this borrowed capability reentrantly must not commit its unfinished
                    // proposal.
                    if (require() != current)
                        throw new IllegalStateException("Prepared capability changed");
                });
    }

    /**
     * Host finally block invalidates the window and drops all captured template/backend aliases.
     */
    @Override
    public void close() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Prepared capability wrong thread");
        access = null;
    }
}
