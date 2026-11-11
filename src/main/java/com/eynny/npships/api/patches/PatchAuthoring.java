package com.eynny.npships.api.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Optional author-data capability of one registered provider. Callbacks inspect scoped inputs and
 * return detached proposals; only the existing document owner may validate and accept them. No
 * callback receives a mutable document, editor, arbitrary world access or rendering ownership.
 */
public interface PatchAuthoring {
    /**
     * Whether this code participates in automatic detached author-data callbacks. A code-only
     * provider may return false when it supplies other scoped capabilities and owns no generic
     * author records; it remains in the same provider registry and administrator policy. The
     * compatible default retains capture/transform/validation callbacks for existing addons.
     *
     * @return whether automatic author-data callbacks must be planned
     */
    default boolean automaticCallbacks() {
        return true;
    }

    /**
     * Optional stateless code for an actual authoring/CLIENT host; no native owner is retained.
     *
     * @param <T> host contract type
     * @param contract exact host capability interface
     * @return code capability, or empty
     */
    default <T> Optional<T> capability(Class<T> contract) {
        Objects.requireNonNull(contract);
        return Optional.empty();
    }

    /**
     * Code dependencies, frozen with the same provider registration as runtime capabilities.
     *
     * @return native dependency IDs
     */
    default Set<String> requiredMods() {
        return Set.of();
    }

    /**
     * Requests responsibility for exact host-advertised aspects before specialized source capture.
     * This pure discovery callback may inspect only the bounded context; it must not mutate the
     * source or retain callback capabilities. The host validates all claims, dependencies and
     * conflicts before running the selected capture work. A request is not a grant: the subsequent
     * {@link #capture(Context)} callback reads its validated claims through {@link
     * Context#captureClaims()}. The compatible default leaves native capture ownership unchanged.
     *
     * @param context callback-scoped original source and advertised capture aspects
     * @return detached requested claims, each referencing an exact advertised aspect token
     */
    default List<CaptureClaim> captureClaims(Context context) {
        return List.of();
    }

    /**
     * Read source capture capabilities when available; no capture is the compatible default.
     *
     * @param context callback-scoped host context
     * @return detached provider proposal
     */
    default Proposal capture(Context context) {
        return context.unchanged();
    }

    /**
     * Declares existing source cells and serializer fields this provider can transform. Claims
     * apply only to the current document operation, never capture discovery or live world state.
     * Each owner must already occur in this provider's named author data in {@link
     * Context#before()}. The host validates exact source states, operation mappings and competing
     * field claims before temporarily excluding claimed fields from its unknown-data transform
     * guard. A claim does not exempt unrelated fields or native identity data, and it never changes
     * the accepted document. The compatible default declares no owned native transformation.
     *
     * @param context callback-scoped operation and original document
     * @return detached source claims for this operation
     */
    default List<BlockStateClaim> blockStateClaims(Context context) {
        return List.of();
    }

    /**
     * Unknown provider semantics must never be guessed during a document operation.
     *
     * @param context callback-scoped host context
     * @return detached provider proposal
     */
    default Proposal transform(Context context) {
        var kind = context.operation().kind();
        if (!context.records().isEmpty()
                && kind != Kind.SAVE
                && kind != Kind.EXPORT
                && kind != Kind.VALIDATE)
            throw new IllegalArgumentException(
                    "Author data provider "
                            + context.records().getFirst().identity().type()
                            + " does not support "
                            + kind
                            + "; original data is unchanged");
        return context.unchanged();
    }

    /**
     * Pure detached validation. Envelope, native target and ownership checks remain host-owned.
     *
     * @param context callback-scoped host context
     */
    default void validate(Context context) {}

    /** Callback-local views and mappings must not escape their callback window. */
    interface Context {
        /**
         * Reads the actual operation and its exact scoped mapping.
         *
         * @return actual host operation
         */
        Operation operation();

        /**
         * Reads native/provider content before the operation.
         *
         * @return callback-scoped original document view
         */
        Document before();

        /**
         * Reads resulting native content for the operation.
         *
         * @return callback-scoped resulting document view
         */
        Document after();

        /**
         * Present only during an actual bounded server capture.
         *
         * @return bounded capture source, or null outside capture
         */
        @Nullable
        Source source();

        /**
         * Reads the host's bounded source-aspect offers for this capture operation. Tokens identify
         * this operation only and cannot be reused on a later capture. An empty position footprint
         * is valid for a document-scoped aspect; providers must not invent native targets. Ordinary
         * import, transform and custom older contexts advertise no capture aspects by default.
         *
         * @return detached immutable advertised aspects, or an empty list outside supported capture
         */
        default List<CaptureAspect> captureAspects() {
            return List.of();
        }

        /**
         * Reads this provider's host-validated capture grants in the subsequent capture callback.
         * The discovery callback has no granted claims. A granted claim requires a matching named
         * returned {@link Data} record and identity; it never exempts source safety or guarantees
         * that arbitrary addon code correctly restores its native semantics. Context access expires
         * with its callback even though detached records may be retained.
         *
         * @return detached immutable granted claims, or an empty list when none are granted
         */
        default List<CaptureClaim> captureClaims() {
            return List.of();
        }

        /**
         * Privately owned original import input, or null outside import; copied on each call.
         *
         * @return fresh original import copy, or null outside import
         */
        @Nullable
        CompoundTag importSource();

        /**
         * Detached records belonging to this provider, not a mutable whole-document collection.
         *
         * @return detached records owned by this provider
         */
        List<Data> records();

        /**
         * Detached current-version records under this provider's explicitly registered payload
         * schemas. Reading this view never changes authored data. Submit it in a proposal to make
         * an explicit undoable upgrade; undeclared record names retain their original version.
         * Existing custom contexts keep their original records unless the host implements this
         * optional capability.
         *
         * @return detached records under explicitly declared readable schemas
         */
        default List<Data> upgradedRecords() {
            return records();
        }

        /**
         * Opaque owner revision; a proposal cannot be accepted against a newer document.
         *
         * @return opaque current owner revision
         */
        UUID revision();

        /**
         * Creates a complete provider-local proposal for host acceptance.
         *
         * @param data complete provider-owned record list
         * @param edits complete provider-local native field edits
         * @return detached proposal bound to this context revision
         */
        Proposal propose(List<Data> data, List<FieldEdit> edits);

        /**
         * Creates one atomic proposal including provider-owned native state replacements. The
         * returned proposal retains the existing record shape; the host binds these detached state
         * edits to that exact proposal in this callback window. Return that same proposal rather
         * than reconstructing it. Every state edit needs a validated source claim, an actual mapped
         * destination and the exact unchanged {@link #after()} state. Replacements may change
         * properties of the same registered block, never geometry or BE ownership. All state, field
         * and author-data changes are accepted or refused together through the existing
         * document/history owner. Older custom contexts support only an empty state list.
         *
         * @param data complete provider-owned record list
         * @param edits complete provider-local native field edits
         * @param states complete provider-local native state edits
         * @return detached proposal bound to this callback and its revision
         * @throws UnsupportedOperationException if this context cannot accept nonempty state edits
         */
        default Proposal propose(
                List<Data> data, List<FieldEdit> edits, List<BlockStateEdit> states) {
            Objects.requireNonNull(states);
            if (!states.isEmpty())
                throw new UnsupportedOperationException(
                        "Provider-owned BlockState changes are unavailable in this authoring context");
            return propose(data, edits);
        }

        /**
         * Creates a proposal retaining current provider records with no field edits.
         *
         * @return detached proposal retaining current records
         */
        Proposal unchanged();
    }

    /** Indexed author document; an NBT read copies only the requested block. */
    interface Document {
        /**
         * Reads document extent in schematic-local cells.
         *
         * @return document extent in local cells
         */
        BlockPos size();

        /**
         * Reads the serialized native block name at one local cell.
         *
         * @param position schematic-local cell
         * @return serialized native block name
         */
        String blockName(BlockPos position);

        /**
         * Copies native block-entity data for one local cell.
         *
         * @param position schematic-local cell
         * @return fresh detached native block data
         */
        CompoundTag blockData(BlockPos position);

        /**
         * Detached encoded BlockState, including properties.
         *
         * @param position schematic-local cell
         * @return fresh encoded native block state
         */
        CompoundTag blockState(BlockPos position);

        /**
         * Stable authored roots only; discovery never invents identities for legacy roots.
         *
         * @return stable authored root identities
         */
        List<UUID> entityRoots();

        /**
         * Copies just the selected root and its passenger tree, not all document entities.
         *
         * @param root stable authored root token
         * @return fresh selected root data, or empty when absent
         */
        Optional<CompoundTag> entityData(UUID root);

        /**
         * Iterates host-indexed local positions within this callback.
         *
         * @return callback-scoped position iterable
         */
        Iterable<BlockPos> positions();

        /**
         * Reads detached authored records belonging to one provider.
         *
         * @param type provider ID whose records are requested
         * @return detached records owned by the requested provider
         */
        List<Data> data(ResourceLocation type);
    }

    /** Bounded source selection; deliberately exposes no Level or BlockEntity getter. */
    interface Source {
        /**
         * Reads the bounded capture source dimension.
         *
         * @return source dimension ID
         */
        ResourceLocation dimension();

        /**
         * Reads the native world minimum corner of the capture selection.
         *
         * @return source selection minimum corner
         */
        BlockPos worldOrigin();

        /**
         * Copies one native value within the bounded capture selection.
         *
         * @param localPosition cell inside the bounded local capture selection
         * @return fresh detached native block data
         */
        CompoundTag blockData(BlockPos localPosition);

        /**
         * Reads one native block name within the bounded selection.
         *
         * @param localPosition cell inside the bounded local capture selection
         * @return serialized native block name
         */
        String blockName(BlockPos localPosition);
    }

    /**
     * Actual host operation; selection coordinates are local and optional where inapplicable.
     *
     * @param kind actual host document operation
     * @param mapping callback-scoped exact operation mapping
     * @param selectionOrigin local selection minimum corner, or null
     * @param selectionSize local selection extent, or null
     * @param occurrence operation occurrence identity, or null
     */
    record Operation(
            Kind kind,
            Mapping mapping,
            @Nullable BlockPos selectionOrigin,
            @Nullable BlockPos selectionSize,
            @Nullable UUID occurrence) {
        /**
         * Copies optional local selection coordinates and checks operation/mapping.
         *
         * @param kind actual host document operation
         * @param mapping callback-scoped exact operation mapping
         * @param selectionOrigin local selection minimum corner, or null
         * @param selectionSize local selection extent, or null
         * @param occurrence operation occurrence identity, or null
         */
        public Operation {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(mapping);
            if (selectionOrigin != null) selectionOrigin = selectionOrigin.immutable();
            if (selectionSize != null) selectionSize = selectionSize.immutable();
        }
    }

    /** Actual author-document operation performed by the host. */
    enum Kind {
        /** Capture. */
        CAPTURE,
        /** Import. */
        IMPORT,
        /** Copy. */
        COPY,
        /** Move. */
        MOVE,
        /** Turn. */
        TURN,
        /** Mirror. */
        MIRROR,
        /** Resize. */
        RESIZE,
        /** Crop. */
        CROP,
        /** Delete. */
        DELETE,
        /** Geometry. */
        GEOMETRY,
        /** Segment. */
        SEGMENT,
        /** Edit. */
        EDIT,
        /** Save. */
        SAVE,
        /** Export. */
        EXPORT,
        /** Validate. */
        VALIDATE
    }

    /**
     * Scoped exact host mapping; zero, one or multiple results describe actual operation output.
     */
    interface Mapping {
        /**
         * Maps a local cell to the actual zero, one or multiple output cells.
         *
         * @param position schematic-local cell
         * @return mapped output local cells
         */
        List<BlockPos> cells(BlockPos position);

        /**
         * Maps an exact local point to the actual operation outputs.
         *
         * @param position exact schematic-local input point
         * @return mapped output local points
         */
        List<Vec3> points(Vec3 position);

        /**
         * Maps authored provider identity through this operation.
         *
         * @param identity stable provider instance/group/occurrence identity
         * @return instance identity metadata, or null for legacy contexts
         */
        PatchIdentity identity(PatchIdentity identity);

        /**
         * Empty means no supported root mapping; providers must not guess a replacement.
         *
         * @param root stable authored root token
         * @return mapped root identity, or empty when unsupported
         */
        default Optional<UUID> root(UUID root) {
            return Optional.empty();
        }
    }

    /**
     * Provider-owned, versioned data; identity is neither a physical body nor a required target.
     *
     * @param name provider-owned record name
     * @param version positive provider payload version
     * @param identity stable provider instance/group/occurrence identity
     * @param payload privately copied provider payload
     */
    record Data(String name, int version, PatchIdentity identity, CompoundTag payload) {
        /**
         * Validates record identity/version/name and copies the provider payload.
         *
         * @param name provider-owned record name
         * @param version positive provider payload version
         * @param identity stable provider instance/group/occurrence identity
         * @param payload privately copied provider payload
         */
        public Data {
            Objects.requireNonNull(name);
            if (name.isEmpty() || name.length() > 256)
                throw new IllegalArgumentException(
                        "Author data name must contain 1..256 characters");
            if (version < 1)
                throw new IllegalArgumentException("Author data version must be positive");
            Objects.requireNonNull(identity);
            payload = Objects.requireNonNull(payload).copy();
        }

        /**
         * Copies provider-owned authored data.
         *
         * @return a fresh detached payload
         */
        @Override
        public CompoundTag payload() {
            return payload.copy();
        }
    }

    /**
     * One exact source serializer field belonging to an advertised capture aspect. Declaration
     * alone grants no native mutation; reserved ownership fields and overlapping footprints remain
     * subject to host validation.
     *
     * @param position source-selection-local cell
     * @param key top-level native serializer field name
     */
    record CaptureField(BlockPos position, String key) {
        /**
         * Copies source coordinates and validates the bounded serializer field name.
         *
         * @param position source-selection-local cell
         * @param key top-level native serializer field name
         * @throws IllegalArgumentException if the key does not contain 1..256 characters
         * @throws NullPointerException if either argument is null
         */
        public CaptureField {
            position = Objects.requireNonNull(position).immutable();
            Objects.requireNonNull(key);
            if (key.isEmpty() || key.length() > 256)
                throw new IllegalArgumentException(
                        "Capture field name must contain 1..256 characters");
        }
    }

    /**
     * Detached host advertisement of one specific source aspect. An addon may request only tokens
     * actually supplied by the current host callback; constructing this record cannot manufacture a
     * host grant. Positions and fields describe this aspect's bounded native footprint, not a
     * universal block/entity mechanic schema. Empty footprints remain representable for host
     * document-scoped aspects. Delegation never suppresses independent aspects on shared cells.
     *
     * @param token opaque token issued for this capture operation only
     * @param owner registered default capture owner
     * @param aspect stable namespaced source-aspect identifier
     * @param positions immutable source-selection-local inspection footprint
     * @param fields immutable native serializer field footprint
     * @param delegable whether the host permits replacement of this specific aspect
     */
    record CaptureAspect(
            UUID token,
            ResourceLocation owner,
            ResourceLocation aspect,
            List<BlockPos> positions,
            List<CaptureField> fields,
            boolean delegable) {
        /**
         * Copies the native footprint and checks required host-advertisement metadata.
         *
         * @param token opaque token issued for this capture operation only
         * @param owner registered default capture owner
         * @param aspect stable namespaced source-aspect identifier
         * @param positions source-selection-local inspection footprint
         * @param fields native serializer field footprint
         * @param delegable whether the host permits replacement of this specific aspect
         * @throws NullPointerException if a required argument or footprint entry is null
         */
        public CaptureAspect {
            Objects.requireNonNull(token);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(aspect);
            positions =
                    Objects.requireNonNull(positions).stream()
                            .map(position -> Objects.requireNonNull(position).immutable())
                            .toList();
            fields = List.copyOf(fields);
        }
    }

    /** Requested role for one exact host-advertised source aspect. */
    enum CaptureMode {
        /** Capture independent provider-owned data without suppressing the default owner. */
        COOPERATE,
        /** Replace only an explicitly delegable aspect after host ownership validation. */
        REPLACE
    }

    /**
     * Detached request to capture one advertised aspect into a named provider-owned author record.
     * The provider must return the specified record and exact identity in its accepted capture
     * proposal. Replacement additionally requires the host's declared runtime-restoration and
     * native-field ownership checks; a claim is not proof of successful native restoration. The
     * author identity need not correspond to a Minecraft block, entity or physical body.
     *
     * @param aspect exact host-issued token from the current capture operation
     * @param mode cooperative capture or explicit replacement request
     * @param record expected returned provider-owned author-data record name
     * @param owner expected returned provider-owned author-data identity
     */
    record CaptureClaim(UUID aspect, CaptureMode mode, String record, PatchIdentity owner) {
        /**
         * Validates the operation token, requested role and bounded expected record name.
         *
         * @param aspect exact host-issued token from the current capture operation
         * @param mode cooperative capture or explicit replacement request
         * @param record expected returned provider-owned author-data record name
         * @param owner expected returned provider-owned author-data identity
         * @throws IllegalArgumentException if the record name does not contain 1..256 characters
         * @throws NullPointerException if any argument is null
         */
        public CaptureClaim {
            Objects.requireNonNull(aspect);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(record);
            if (record.isEmpty() || record.length() > 256)
                throw new IllegalArgumentException(
                        "Capture record name must contain 1..256 characters");
            Objects.requireNonNull(owner);
        }
    }

    /**
     * Operation-local ownership declaration for an existing source cell. The expected state is the
     * exact encoded state in {@link Context#before()}, before the host's native transform. The
     * owner must be an existing named author-data identity of this provider. Claimed BE fields are
     * explicit top-level serializer names; native/host ownership fields remain protected. Disjoint
     * fields on a shared cell may have independent owners. A claim is neither a promise that
     * arbitrary foreign NBT is safe nor permission to replace a block or its BE type.
     *
     * @param owner existing provider-owned named author-data identity
     * @param source schematic-local existing source cell
     * @param expected exact encoded original source state
     * @param blockEntityFields explicitly owned top-level serializer fields
     */
    record BlockStateClaim(
            PatchIdentity owner,
            BlockPos source,
            CompoundTag expected,
            Set<String> blockEntityFields) {
        /**
         * Copies source coordinates, state and the explicit field declaration.
         *
         * @param owner existing provider-owned named author-data identity
         * @param source schematic-local existing source cell
         * @param expected exact encoded original source state
         * @param blockEntityFields explicitly owned top-level serializer fields
         * @throws IllegalArgumentException if a field name is empty
         * @throws NullPointerException if any argument or field name is null
         */
        public BlockStateClaim {
            Objects.requireNonNull(owner);
            source = Objects.requireNonNull(source).immutable();
            expected = Objects.requireNonNull(expected).copy();
            blockEntityFields = Set.copyOf(blockEntityFields);
            if (blockEntityFields.stream().anyMatch(String::isEmpty))
                throw new IllegalArgumentException(
                        "BlockState claim field names must not be empty");
        }

        /**
         * Copies the exact original source state.
         *
         * @return fresh detached original state
         */
        @Override
        public CompoundTag expected() {
            return expected.copy();
        }
    }

    /**
     * Compare-and-replace an existing mapped native state within one author transaction. Unlike
     * {@link BlockStateClaim#expected()}, expected is the exact immutable {@link Context#after()}
     * state after the host's native transform. The source claim binds the original owner/cell;
     * target must be one of its actual mapped destinations. Two state edits at one destination
     * conflict even when their replacements agree. The host permits only valid properties of the
     * same registered block and checks retained BE data before one ordinary history acceptance.
     *
     * @param owner identity of the validated source claim
     * @param source existing claimed source cell
     * @param target existing actual mapped destination cell
     * @param expected exact encoded immutable candidate state
     * @param replacement proposed encoded state of the same registered block
     */
    record BlockStateEdit(
            PatchIdentity owner,
            BlockPos source,
            BlockPos target,
            CompoundTag expected,
            CompoundTag replacement) {
        /**
         * Copies coordinates and both compare-and-replace states.
         *
         * @param owner identity of the validated source claim
         * @param source existing claimed source cell
         * @param target existing actual mapped destination cell
         * @param expected exact encoded immutable candidate state
         * @param replacement proposed encoded state of the same registered block
         * @throws NullPointerException if any argument is null
         */
        public BlockStateEdit {
            Objects.requireNonNull(owner);
            source = Objects.requireNonNull(source).immutable();
            target = Objects.requireNonNull(target).immutable();
            expected = Objects.requireNonNull(expected).copy();
            replacement = Objects.requireNonNull(replacement).copy();
        }

        /**
         * Copies the exact expected candidate state.
         *
         * @return fresh detached expected candidate state
         */
        @Override
        public CompoundTag expected() {
            return expected.copy();
        }

        /**
         * Copies the proposed replacement state.
         *
         * @return fresh detached replacement state
         */
        @Override
        public CompoundTag replacement() {
            return replacement.copy();
        }
    }

    /**
     * Detached transaction; the host checks revision, provider ownership and all proposed edits.
     *
     * @param revision opaque owner revision
     * @param data complete provider-owned record list
     * @param edits complete provider-local native field edits
     */
    record Proposal(UUID revision, List<Data> data, List<FieldEdit> edits) {
        /**
         * Copies proposed provider records and field edits under the supplied revision.
         *
         * @param revision opaque owner revision
         * @param data complete provider-owned record list
         * @param edits complete provider-local native field edits
         */
        public Proposal {
            Objects.requireNonNull(revision);
            data = List.copyOf(data);
            edits = List.copyOf(edits);
        }
    }

    /**
     * Compare-and-replace one top-level BE field. Null expected means absent; null replacement
     * removes it. The host rejects native identity/coordinate and reserved owner fields.
     *
     * @param position schematic-local cell
     * @param key top-level serializer field name
     * @param expected expected prior value, or null for absence
     * @param replacement proposed value, or null to remove
     */
    record FieldEdit(
            BlockPos position, String key, @Nullable Tag expected, @Nullable Tag replacement) {
        /**
         * Copies mutable coordinates and compare-and-replace tag values.
         *
         * @param position schematic-local cell
         * @param key top-level serializer field name
         * @param expected expected prior value, or null for absence
         * @param replacement proposed value, or null to remove
         */
        public FieldEdit {
            position = Objects.requireNonNull(position).immutable();
            Objects.requireNonNull(key);
            if (key.isEmpty()) throw new IllegalArgumentException("BlockEntity field key is empty");
            if (expected != null) expected = expected.copy();
            if (replacement != null) replacement = replacement.copy();
        }

        /**
         * Copies the expected prior field value.
         *
         * @return fresh expected value, or null for absence
         */
        @Override
        public @Nullable Tag expected() {
            return expected == null ? null : expected.copy();
        }

        /**
         * Copies the proposed replacement field value.
         *
         * @return fresh replacement value, or null to remove
         */
        @Override
        public @Nullable Tag replacement() {
            return replacement == null ? null : replacement.copy();
        }
    }
}
