package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchIdentity;
import com.eynny.npships.api.patches.PatchTypes;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owned envelope identities. Provider payloads are opaque and are never remapped here. */
public final class PatchInstances {
    public static final String INSTANCE = "instance_id",
            GROUP = "group_id",
            OCCURRENCE = "occurrence_id";

    private PatchInstances() {}

    /** Identity of a current record or a newly created built-in draft. */
    public static PatchIdentity identity(JsonObject patch, boolean entity) {
        return identity(explicit(patch) ? patch : materialize(patch, entity), entity, 2);
    }

    public static PatchIdentity identity(JsonObject patch, boolean entity, int schemaVersion) {
        if (schemaVersion != 2)
            throw new IllegalArgumentException(
                    "Unsupported patch identity schema " + schemaVersion);
        String kind = patch.get("kind").getAsString();
        var type =
                kind.contains(":")
                        ? net.minecraft.resources.ResourceLocation.parse(kind)
                        : PatchTypes.legacyId(kind);
        if (type == null)
            throw new IllegalArgumentException("Unsupported built-in patch kind " + kind);
        var id = uuid(patch, INSTANCE);
        return new PatchIdentity(type, id, optional(patch, GROUP), optional(patch, OCCURRENCE));
    }

    public static String key(JsonObject patch, boolean entity) {
        var identity = identity(patch, entity);
        return identity.type() + "/" + identity.instanceId();
    }

    public static String key(JsonObject patch, boolean entity, int schemaVersion) {
        var identity = identity(patch, entity, schemaVersion);
        return identity.type() + "/" + identity.instanceId();
    }

    private static UUID initialNative(JsonObject patch, boolean entity) {
        String anchor =
                entity
                        ? EntityPatches.token(patch).toString()
                        : SchematicPatches.position(patch.getAsJsonArray("pos")).toShortString();
        return named(
                "native/"
                        + (entity ? "entity/" : "block/")
                        + patch.get("kind").getAsString()
                        + "/"
                        + anchor);
    }

    private static UUID named(String value) {
        return UUID.nameUUIDFromBytes(
                ("npships:patch/v2/" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID optional(JsonObject patch, String name) {
        return patch.has(name) ? uuid(patch, name) : null;
    }

    private static UUID uuid(JsonObject patch, String name) {
        var value = patch.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Patch " + name + " must be a canonical UUID");
        var text = value.getAsString();
        var id = UUID.fromString(text);
        if (!id.toString().equals(text))
            throw new IllegalArgumentException("Patch " + name + " must be a canonical UUID");
        return id;
    }

    public static boolean explicit(JsonObject patch) {
        return patch.has(INSTANCE);
    }

    /** Identify a newly created built-in draft; serialized records must already carry IDs. */
    public static JsonObject materialize(JsonObject patch, boolean entity) {
        return materialize(patch, entity, 2);
    }

    public static JsonObject materialize(JsonObject patch, boolean entity, int schemaVersion) {
        if (schemaVersion != 2) throw new IllegalArgumentException("Unsupported patch envelope");
        var result = patch.deepCopy();
        if (!explicit(result)) {
            if (entity
                    || ExternalPatches.external(result)
                    || result.has(GROUP)
                    || result.has(OCCURRENCE))
                throw new IllegalArgumentException("Patch requires an explicit instance_id");
            result.addProperty(INSTANCE, initialNative(result, false).toString());
        }
        identity(result, entity, 2);
        return result;
    }

    public static void stabilize(JsonObject patch, boolean entity) {
        var materialized = materialize(patch, entity);
        patch.add(INSTANCE, materialized.get(INSTANCE));
    }

    public static JsonObject newInstance(JsonObject patch) {
        var result = patch.deepCopy();
        result.addProperty(INSTANCE, UUID.randomUUID().toString());
        return result;
    }

    /**
     * New native records created by deterministic document normalization must avoid moved IDs.
     * Existing identity domains and passive reads are unchanged.
     */
    public static JsonObject materializeAddition(
            JsonObject draft, java.util.Collection<JsonObject> occupied) {
        var result = materialize(draft, false);
        var used = new HashSet<UUID>();
        for (var record : occupied) used.add(identity(record, false).instanceId());
        var original = identity(result, false).instanceId();
        var id = original;
        for (int salt = 1; used.contains(id); salt++)
            id =
                    UUID.nameUUIDFromBytes(
                            ("npships:native-addition/" + original + "/" + salt)
                                    .getBytes(StandardCharsets.UTF_8));
        result.addProperty(INSTANCE, id.toString());
        return result;
    }

    public static int version(List<JsonObject> entries, boolean entity) {
        return 2;
    }

    public static int storedVersion(JsonObject root, List<JsonObject> entries, boolean entity) {
        if (root.has("version")
                && com.eynny.npships.authoring.validation.JsonNumbers.integer(
                                root.get("version"), "patch envelope")
                        != 2) throw new IllegalArgumentException("Unsupported patch envelope");
        return 2;
    }

    public static List<JsonObject> forWrite(
            JsonObject oldRoot, List<JsonObject> entries, boolean entity, int version) {
        if (version != 2
                || oldRoot.has("version")
                        && com.eynny.npships.authoring.validation.JsonNumbers.integer(
                                        oldRoot.get("version"), "patch envelope")
                                != 2)
            throw new IllegalArgumentException("Unsupported patch envelope");
        return entries.stream().map(p -> materialize(p, entity)).toList();
    }

    public static void validateList(List<JsonObject> entries, boolean entity, int version) {
        if (version != 2) throw new IllegalArgumentException("Unsupported patch envelope");
        for (var patch : entries)
            if (!explicit(patch)) throw new IllegalArgumentException("Patch requires instance_id");
        validateDocument(entity ? List.of() : entries, entity ? entries : List.of());
    }

    private record GroupOwner(Object type, UUID occurrence) {}

    /**
     * Global IDs prevent an instance or a group from silently changing owners across target kinds.
     */
    public static void validateDocument(
            com.eynny.npships.authoring.document.SchematicDocument doc) {
        var ids = new HashSet<UUID>();
        var groups = new HashMap<UUID, GroupOwner>();
        var singles = new HashSet<String>();
        var nativeTargets = new HashSet<String>();
        var schemas = new HashMap<net.minecraft.core.BlockPos, Integer>();
        for (var patch : ExternalPatches.authoringEntries(doc)) {
            var pos = SchematicPatches.position(patch.getAsJsonArray("pos"));
            int version = schemas.computeIfAbsent(pos, p -> SchematicPatches.schema(doc, p));
            validateIdentity(patch, false, version, ids, groups, singles, nativeTargets);
        }
        int entitySchema = EntityPatches.schema(doc);
        for (var patch : EntityPatches.entries(doc))
            validateIdentity(patch, true, entitySchema, ids, groups, singles, nativeTargets);
    }

    /** Lists here contain current records or host-created built-in drafts. */
    public static void validateDocument(List<JsonObject> blocks, List<JsonObject> entities) {
        var ids = new HashSet<UUID>();
        var groups = new HashMap<UUID, GroupOwner>();
        var singles = new HashSet<String>();
        var nativeTargets = new HashSet<String>();
        for (var patch : blocks)
            validateIdentity(patch, false, 2, ids, groups, singles, nativeTargets);
        for (var patch : entities)
            validateIdentity(patch, true, 2, ids, groups, singles, nativeTargets);
    }

    private static void validateIdentity(
            JsonObject patch,
            boolean entity,
            int schemaVersion,
            HashSet<UUID> ids,
            Map<UUID, GroupOwner> groups,
            HashSet<String> singles,
            HashSet<String> nativeTargets) {
        var identity = identity(patch, entity, schemaVersion);
        if (!ids.add(identity.instanceId()))
            throw new IllegalArgumentException(
                    "Duplicate patch instance_id " + identity.instanceId());
        if (identity.groupId() != null) {
            var owner = new GroupOwner(identity.type(), identity.occurrenceId());
            var previous = groups.putIfAbsent(identity.groupId(), owner);
            if (previous != null && !previous.equals(owner))
                throw new IllegalArgumentException(
                        "Patch group_id mixes provider or occurrence owners: "
                                + identity.groupId());
        }
        var registration = PatchTypes.registration(identity.type());
        String target =
                entity
                        ? EntityPatches.token(patch).toString()
                        : SchematicPatches.position(patch.getAsJsonArray("pos")).toShortString();
        if (!entity && !ExternalPatches.external(patch) && !nativeTargets.add(target))
            throw new IllegalArgumentException(
                    "Only one built-in native record is supported at target " + target);
        if (registration != null
                && registration.multiplicity()
                        == com.eynny.npships.api.patches.PatchRegistration.Multiplicity
                                .SINGLE_PER_TARGET
                && !singles.add((entity ? "entity/" : "block/") + identity.type() + "/" + target))
            throw new IllegalArgumentException(
                    "Provider multiplicity allows only one patch instance per target: "
                            + identity.type());
    }

    /** One accepted copy transaction owns all envelope remaps, including cross-target groups. */
    public static final class Remap {
        private final Map<UUID, UUID> instances = new HashMap<>(),
                groups = new HashMap<>(),
                occurrences = new HashMap<>();

        private final Map<UUID, UUID> roots = new HashMap<>();

        public UUID occurrence(UUID source) {
            return occurrences.computeIfAbsent(source, ignored -> UUID.randomUUID());
        }

        /** Native authored roots and provider records share this accepted copy transaction. */
        public UUID root(UUID source) {
            return roots.computeIfAbsent(source, ignored -> UUID.randomUUID());
        }

        /** The actual cloned root, never an inferred coincident entity. */
        public java.util.Optional<UUID> mappedRoot(UUID source) {
            return java.util.Optional.ofNullable(roots.get(source));
        }

        public PatchIdentity remap(PatchIdentity source) {
            return new PatchIdentity(
                    source.type(),
                    instances.computeIfAbsent(source.instanceId(), ignored -> UUID.randomUUID()),
                    source.groupId() == null
                            ? null
                            : groups.computeIfAbsent(
                                    source.groupId(), ignored -> UUID.randomUUID()),
                    source.occurrenceId() == null ? null : occurrence(source.occurrenceId()));
        }

        public JsonObject copy(JsonObject patch, boolean entity) {
            return copy(patch, entity, 2);
        }

        public JsonObject copy(JsonObject patch, boolean entity, int schemaVersion) {
            var result = materialize(patch, entity, schemaVersion);
            var identity = remap(identity(result, entity));
            result.addProperty(INSTANCE, identity.instanceId().toString());
            if (identity.groupId() != null)
                result.addProperty(GROUP, identity.groupId().toString());
            if (identity.occurrenceId() != null)
                result.addProperty(OCCURRENCE, identity.occurrenceId().toString());
            return result;
        }
    }

    public static List<JsonObject> copy(List<JsonObject> entries, boolean entity, Remap remap) {
        var result = new ArrayList<JsonObject>(entries.size());
        for (var patch : entries) result.add(remap.copy(patch, entity));
        return List.copyOf(result);
    }

    /** Same deterministic envelope mapping for provider author data and patch records. */
    public static PatchIdentity compose(PatchIdentity source, UUID occurrence) {
        return new PatchIdentity(
                source.type(),
                named("instance/" + occurrence + "/" + source.instanceId()),
                source.groupId() == null
                        ? null
                        : named("group/" + occurrence + "/" + source.groupId()),
                occurrence);
    }

    /** Pure composition uses the accepted segment occurrence; it never allocates random IDs. */
    public static JsonObject compose(JsonObject patch, boolean entity, UUID occurrence) {
        return compose(patch, entity, occurrence, 2);
    }

    public static JsonObject compose(
            JsonObject patch, boolean entity, UUID occurrence, int sourceSchema) {
        var result = materialize(patch, entity, sourceSchema);
        var identity = identity(result, entity);
        result.addProperty(
                INSTANCE, named("instance/" + occurrence + "/" + identity.instanceId()).toString());
        if (identity.groupId() != null)
            result.addProperty(
                    GROUP, named("group/" + occurrence + "/" + identity.groupId()).toString());
        result.addProperty(OCCURRENCE, occurrence.toString());
        return result;
    }
}
