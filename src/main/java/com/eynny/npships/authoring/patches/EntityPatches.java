package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchException;
import com.eynny.npships.api.patches.PatchTarget;
import com.eynny.npships.api.patches.PatchType;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.*;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Root-entity schema, detached validation and accepted edits. No world lookup or identity polling.
 */
public final class EntityPatches {
    public static final String KEY = "entity_patches",
            EMBEDDED = "npships:entity_patches",
            OCCURRENCE = "entity_occurrence";

    private EntityPatches() {}

    public static List<JsonObject> read(JsonObject config) {
        if (config == null || !config.has(KEY)) return List.of();
        var root = config.getAsJsonObject(KEY);
        int version =
                com.eynny.npships.authoring.validation.JsonNumbers.integer(
                        root.get("version"), "entity patch envelope");
        if (version != 2)
            throw new IllegalArgumentException(
                    "Unsupported entity_patches version; expected version 2");
        var list = root.getAsJsonArray("entries");
        if (list.size() > 1024) throw new IllegalArgumentException("Maximum 1024 entity patches");
        var result = new ArrayList<JsonObject>();
        for (var v : list) {
            var e = v.getAsJsonObject();
            ExternalPatches.envelope(e, true);
            if (ResourceLocation.tryParse(e.get("entity_type").getAsString()) == null)
                throw failure(
                        e, PatchException.Reason.INVALID_PAYLOAD, "Invalid entity_type", null);
            result.add(e.deepCopy());
        }
        PatchInstances.validateList(result, true, version);
        return List.copyOf(result);
    }

    public static int schema(SchematicDocument doc) {
        int configured = doc.patchSchema(true);
        if (configured != 0) return configured;
        var bytes = doc.entityPatchBytes();
        if (bytes.length == 0) return 2;
        return com.eynny.npships.authoring.validation.JsonNumbers.integer(
                JsonStructure.parse(new String(bytes, StandardCharsets.UTF_8))
                        .getAsJsonObject()
                        .getAsJsonObject(KEY)
                        .get("version"),
                KEY);
    }

    public static UUID token(JsonObject patch) {
        var text = patch.get("entity").getAsString();
        var value = UUID.fromString(text);
        if (!value.toString().equals(text))
            throw new IllegalArgumentException("Entity token must be canonical UUID");
        return value;
    }

    public static List<JsonObject> embedded(CompoundTag tag) {
        return tag.contains(EMBEDDED)
                ? read(
                        JsonStructure.parse(
                                        new String(
                                                tag.getByteArray(EMBEDDED), StandardCharsets.UTF_8))
                                .getAsJsonObject())
                : List.of();
    }

    public static List<JsonObject> entries(SchematicDocument doc) {
        var config = doc.configCopy();
        if (config != null && config.has(KEY)) return read(config);
        return readBytes(doc.entityPatchBytes());
    }

    private static List<JsonObject> readBytes(byte[] bytes) {
        return bytes.length == 0
                ? List.of()
                : read(
                        JsonStructure.parse(new String(bytes, StandardCharsets.UTF_8))
                                .getAsJsonObject());
    }

    public static void set(JsonObject config, List<JsonObject> entries) {
        var root = config.has(KEY) ? config.getAsJsonObject(KEY).deepCopy() : new JsonObject();
        int version = PatchInstances.storedVersion(root, entries, true);
        set(config, entries, root, version);
    }

    public static void set(JsonObject config, List<JsonObject> entries, int version) {
        var root = config.has(KEY) ? config.getAsJsonObject(KEY).deepCopy() : new JsonObject();
        set(config, entries, root, version);
    }

    private static void set(
            JsonObject config, List<JsonObject> entries, JsonObject root, int version) {
        var stored = PatchInstances.forWrite(root, entries, true, version);
        PatchInstances.validateList(stored, true, version);
        root.addProperty("version", version);
        var list = new JsonArray();
        stored.forEach(e -> list.add(e.deepCopy()));
        root.add("entries", list);
        config.add(KEY, root);
    }

    public static void embed(CompoundTag tag, JsonObject config) {
        if (config == null || !config.has(KEY)) return;
        read(config);
        var only = new JsonObject();
        only.add(KEY, config.get(KEY).deepCopy());
        tag.putByteArray(EMBEDDED, only.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static PatchException failure(
            JsonObject patch, PatchException.Reason reason, String detail, Throwable cause) {
        return new PatchException(
                reason,
                patch.get("kind").getAsString(),
                "entity " + (patch.has("entity") ? patch.get("entity").getAsString() : "new root"),
                detail,
                cause);
    }

    public static PatchType type(JsonObject patch) {
        return ExternalPatches.type(patch);
    }

    public static String unavailable(JsonObject patch) {
        try {
            requireAvailable(patch);
            return "";
        } catch (PatchException e) {
            return e.getMessage();
        }
    }

    private static void requireAvailable(JsonObject patch) {
        var type = type(patch);
        if (type == null)
            throw failure(
                    patch,
                    PatchException.Reason.UNKNOWN_TYPE,
                    "No registered entity provider",
                    null);
        if (type.target() != PatchType.TargetKind.ENTITY)
            throw failure(
                    patch,
                    PatchException.Reason.INVALID_PAYLOAD,
                    "Provider is not an ENTITY type",
                    null);
        for (var mod : type.requiredMods().stream().sorted().toList())
            if (!net.neoforged.fml.ModList.get().isLoaded(mod))
                throw failure(
                        patch,
                        PatchException.Reason.UNAVAILABLE_DEPENDENCY,
                        "Missing mod " + mod,
                        null);
        String dependency =
                ExternalPatches.requiredProviderUnavailable(
                        ResourceLocation.parse(patch.get("kind").getAsString()), new HashSet<>());
        if (!dependency.isEmpty())
            throw failure(patch, PatchException.Reason.UNAVAILABLE_DEPENDENCY, dependency, null);
    }

    public static PatchTarget target(SchematicDocument doc, int index) {
        return target(doc.entitiesCopy().getCompound(index));
    }

    private static PatchTarget target(CompoundTag entry) {
        var nbt = entry.getCompound("nbt");
        var id = ResourceLocation.tryParse(nbt.getString("id"));
        if (id == null
                || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                || !BuiltInRegistries.ENTITY_TYPE.get(id).canSerialize())
            throw new IllegalArgumentException("Unavailable native entity type " + id);
        if (nbt.contains("Passengers")) {
            if (!(nbt.get("Passengers") instanceof ListTag passengers))
                throw new IllegalArgumentException("Malformed native Passengers list");
            if (!passengers.isEmpty())
                throw new IllegalArgumentException(
                        "Patched entity targets do not support roots with Passengers");
        }
        var p = entry.getList("pos", Tag.TAG_DOUBLE);
        return PatchTarget.forEntity(
                new PatchTarget.EntityTarget(
                        1,
                        EntityIdentity.read(nbt),
                        id,
                        new Vec3(p.getDouble(0), p.getDouble(1), p.getDouble(2))),
                EntityIdentity.placementCopy(nbt));
    }

    public static int index(SchematicDocument doc, UUID token) {
        int found = -1;
        var entries = doc.entitiesCopy();
        for (int i = 0; i < entries.size(); i++)
            if (token.equals(EntityIdentity.read(entries.getCompound(i).getCompound("nbt")))) {
                if (found != -1)
                    throw new IllegalArgumentException("Duplicate entity identity " + token);
                found = i;
            }
        if (found < 0) throw new IllegalArgumentException("Missing authored entity " + token);
        return found;
    }

    public static PatchTarget target(SchematicDocument doc, JsonObject patch) {
        var result = target(doc, index(doc, token(patch)));
        if (!result.entity()
                .type()
                .equals(ResourceLocation.parse(patch.get("entity_type").getAsString())))
            throw new IllegalArgumentException(
                    "Entity type changed; remove or repair its patch explicitly");
        return result;
    }

    private static Map<UUID, CompoundTag> roots(SchematicDocument doc) {
        var result = new HashMap<UUID, CompoundTag>();
        for (var entry : doc.entitiesCopy()) {
            var root = (CompoundTag) entry;
            var id = EntityIdentity.read(root.getCompound("nbt"));
            if (id != null && result.putIfAbsent(id, root) != null)
                throw new IllegalArgumentException("Duplicate entity identity " + id);
        }
        return result;
    }

    public static Map<UUID, PatchTarget> targets(SchematicDocument doc) {
        var roots = roots(doc);
        var result = new HashMap<UUID, PatchTarget>();
        for (var patch : entries(doc)) {
            var id = token(patch);
            var root = roots.get(id);
            if (root == null) throw new IllegalArgumentException("Missing entity " + id);
            result.put(id, target(root));
        }
        return Map.copyOf(result);
    }

    public static void validate(SchematicDocument doc, boolean executable) {
        var roots = roots(doc);
        var patches = entries(doc);
        PatchInstances.validateDocument(doc);
        boolean physical = executable && !patches.isEmpty() && authoredPhysical(doc);
        for (var patch : patches) {
            try {
                var root = roots.get(token(patch));
                if (root == null)
                    throw new IllegalArgumentException("Missing authored entity " + token(patch));
                if (!java.util.Objects.equals(
                        ResourceLocation.tryParse(root.getCompound("nbt").getString("id")),
                        ResourceLocation.tryParse(patch.get("entity_type").getAsString())))
                    throw new IllegalArgumentException(
                            "Entity type changed; remove its patch explicitly");
                String missing = unavailable(patch);
                if (!missing.isEmpty()) {
                    if (executable) requireAvailable(patch);
                    else continue;
                }
                var target = target(root);
                var type = type(patch);
                type.validator().validate(target, ExternalPatches.canonicalPayload(patch, true));
                if (physical && type.entityMode() == PatchType.EntityMode.STATIC_ONLY)
                    throw new IllegalArgumentException(
                            "STATIC_ONLY entity provider cannot target an authored physical assembly");
            } catch (PatchException e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw failure(patch, PatchException.Reason.INVALID_PAYLOAD, e.getMessage(), e);
            } catch (RuntimeException | LinkageError e) {
                throw failure(patch, PatchException.Reason.VALIDATION_EXCEPTION, e.toString(), e);
            }
        }
    }

    static boolean authoredPhysical(SchematicDocument doc) {
        return com.eynny.npships.authoring.AssemblyEligibility.hasAssemblyPoints(doc);
    }

    public static JsonObject authoringEntry(SchematicDocument doc, int index, ResourceLocation id) {
        var type = PatchTypes.registered().get(id);
        if (type == null || type.target() != PatchType.TargetKind.ENTITY)
            throw new IllegalArgumentException("Not an entity provider");
        var target = target(doc, index);
        var e = new JsonObject();
        e.addProperty("kind", id.toString());
        e.addProperty("entity_type", target.entity().type().toString());
        if (target.entity().token() != null)
            e.addProperty("entity", target.entity().token().toString());
        e.addProperty("payload_version", type.version());
        e.add("payload", new JsonObject());
        e = PatchInstances.newInstance(e);
        String missing = unavailable(e);
        if (!missing.isEmpty()) throw new IllegalArgumentException(missing);
        return e;
    }

    public static SchematicDocument put(SchematicDocument doc, int index, JsonObject patch) {
        var list = doc.entitiesCopy();
        var data = list.getCompound(index).getCompound("nbt");
        var id = EntityIdentity.read(data);
        if (id == null) {
            id = UUID.randomUUID();
            EntityIdentity.put(data, id);
        }
        var copy = patch.deepCopy();
        copy.addProperty("entity", id.toString());
        int sourceSchema = schema(doc);
        var entries = new ArrayList<JsonObject>();
        for (var existing : entries(doc))
            entries.add(PatchInstances.materialize(existing, true, sourceSchema));
        var key = id;
        if (!PatchInstances.explicit(copy)) {
            var kind = copy.get("kind");
            var previous =
                    entries.stream()
                            .filter(e -> token(e).equals(key) && e.get("kind").equals(kind))
                            .toList();
            if (previous.size() > 1)
                throw new IllegalArgumentException("Select a patch instance explicitly");
            copy =
                    previous.isEmpty()
                            ? PatchInstances.newInstance(copy)
                            : PatchInstances.materialize(copy, true);
            if (!previous.isEmpty() && PatchInstances.explicit(previous.getFirst()))
                copy.addProperty(
                        PatchInstances.INSTANCE,
                        PatchInstances.identity(previous.getFirst(), true).instanceId().toString());
        }
        var replacement = copy;
        entries.removeIf(
                e -> PatchInstances.key(e, true).equals(PatchInstances.key(replacement, true)));
        entries.add(copy);
        var config = doc.configCopy();
        if (config == null) config = new JsonObject();
        set(config, entries);
        var result = doc.withEntities(list).withConfig(config);
        validate(result, false);
        return result;
    }

    public static SchematicDocument remove(SchematicDocument doc, int index) {
        var id = EntityIdentity.read(doc.entitiesCopy().getCompound(index).getCompound("nbt"));
        var list = new ArrayList<>(entries(doc));
        list.removeIf(e -> token(e).equals(id));
        var config = doc.configCopy();
        if (config == null) config = new JsonObject();
        set(config, list);
        return doc.withConfig(config);
    }

    public static SchematicDocument remove(SchematicDocument doc, int index, UUID instance) {
        var id = EntityIdentity.read(doc.entitiesCopy().getCompound(index).getCompound("nbt"));
        var list = new ArrayList<>(entries(doc));
        int sourceSchema = schema(doc);
        list.removeIf(
                e ->
                        token(e).equals(id)
                                && PatchInstances.identity(e, true, sourceSchema)
                                        .instanceId()
                                        .equals(instance));
        var config = doc.configCopy();
        if (config == null) config = new JsonObject();
        set(config, list);
        return doc.withConfig(config);
    }

    /** Existing root edits cannot remove/change identity via arbitrary SNBT replacement. */
    public static void preserve(CompoundTag previous, CompoundTag edited) {
        var id = EntityIdentity.read(previous);
        if (id != null)
            edited.put(EntityIdentity.KEY, previous.getCompound(EntityIdentity.KEY).copy());
    }

    /** Prune only roots actually removed by a geometry/edit operation. No new identities. */
    public static JsonObject prune(CompoundTag tag, JsonObject config) {
        if ((config == null || !config.has(KEY)) && !tag.contains(EMBEDDED)) return config;
        var surviving = new HashSet<UUID>();
        for (var e : tag.getList("entities", 10)) {
            var id = EntityIdentity.read(((CompoundTag) e).getCompound("nbt"));
            if (id != null) surviving.add(id);
        }
        var result = config == null ? embeddedConfig(tag) : config.deepCopy();
        var list = new ArrayList<>(result.has(KEY) ? read(result) : embedded(tag));
        list.removeIf(e -> !surviving.contains(token(e)));
        set(result, list);
        embed(tag, result);
        return result;
    }

    /**
     * Called by a user copy operation, never by geometry preview. Remap only owned envelope IDs.
     */
    public static CompoundTag cloneRoot(
            CompoundTag entry, List<JsonObject> source, List<JsonObject> destination) {
        return cloneRoot(entry, source, destination, new PatchInstances.Remap());
    }

    public static CompoundTag cloneRoot(
            CompoundTag entry,
            List<JsonObject> source,
            List<JsonObject> destination,
            PatchInstances.Remap remap) {
        return cloneRoot(entry, source, destination, remap, 2);
    }

    public static CompoundTag cloneRoot(
            CompoundTag entry,
            List<JsonObject> source,
            List<JsonObject> destination,
            PatchInstances.Remap remap,
            int sourceSchema) {
        var copy = entry.copy();
        var data = copy.getCompound("nbt");
        var old = EntityIdentity.read(data);
        if (old != null) {
            var fresh = remap.root(old);
            EntityIdentity.put(data, fresh);
            for (var p : source)
                if (token(p).equals(old)) {
                    var e = remap.copy(p, true, sourceSchema);
                    e.addProperty("entity", fresh.toString());
                    destination.add(e);
                }
        }
        return copy;
    }

    public static void composeOccurrence(CompoundTag part, UUID occurrence) {
        var list = new ArrayList<>(embedded(part));
        var embeddedRoot = embeddedConfig(part);
        int sourceSchema =
                embeddedRoot.has(KEY)
                        ? embeddedRoot.getAsJsonObject(KEY).get("version").getAsInt()
                        : 2;
        var remap = new HashMap<UUID, UUID>();
        for (var e : part.getList("entities", 10)) {
            var nbt = ((CompoundTag) e).getCompound("nbt");
            var id = EntityIdentity.read(nbt);
            if (id != null) {
                var composed = EntityIdentity.composed(occurrence, id);
                remap.put(id, composed);
                EntityIdentity.put(nbt, composed);
            }
        }
        var composedEntries = new ArrayList<JsonObject>();
        for (var e : list) {
            var mapped = remap.get(token(e));
            if (mapped == null)
                throw new IllegalArgumentException("Missing variant entity patch target");
            var copy = PatchInstances.compose(e, true, occurrence, sourceSchema);
            copy.addProperty("entity", mapped.toString());
            composedEntries.add(copy);
        }
        if (!list.isEmpty()) {
            var config = embeddedConfig(part);
            set(config, composedEntries);
            embed(part, config);
        }
        for (var value : part.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            var patches = SchematicPatches.embeddedEntries(block);
            if (SchematicPatches.schema(block) == 2)
                SchematicPatches.writeEmbedded(
                        block,
                        patches.stream()
                                .map(p -> PatchInstances.compose(p, false, occurrence))
                                .toList());
        }
    }

    public static SchematicDocument afterEntities(
            SchematicDocument before, SchematicDocument next) {
        var patches = entries(before);
        if (patches.isEmpty()) return next;
        var live = new HashSet<UUID>();
        for (var e : next.entitiesCopy()) {
            var id = EntityIdentity.read(((CompoundTag) e).getCompound("nbt"));
            if (id != null) live.add(id);
        }
        var config = next.configCopy();
        if (config == null) config = new JsonObject();
        var kept = new ArrayList<>(patches);
        kept.removeIf(e -> !live.contains(token(e)));
        set(config, kept);
        var result = next.withConfig(config);
        validate(result, false);
        return result;
    }

    /** Parent variant acceptance allocates occurrence IDs once, never during composition/Save. */
    public static void ensureOccurrences(CompoundTag tag, JsonObject config) {
        if (config == null || !config.has("segments")) return;
        for (var value : config.getAsJsonArray("segments")) {
            var segment = value.getAsJsonObject();
            boolean needs = false;
            if (segment.has("pool"))
                for (var choice : segment.getAsJsonArray("pool")) {
                    var part =
                            tag.getCompound(com.eynny.npships.authoring.StructureSegments.VARIANTS)
                                    .getCompound(
                                            choice.getAsJsonObject().get("variant").getAsString());
                    for (var e : part.getList("entities", 10))
                        if (EntityIdentity.read(((CompoundTag) e).getCompound("nbt")) != null)
                            needs = true;
                }
            if (!needs && segment.has("pool"))
                for (var choice : segment.getAsJsonArray("pool")) {
                    var part =
                            tag.getCompound(com.eynny.npships.authoring.StructureSegments.VARIANTS)
                                    .getCompound(
                                            choice.getAsJsonObject().get("variant").getAsString());
                    if (SchematicPatches.identified(part)
                            || part.contains(
                                    com.eynny.npships.authoring.document.SchematicExtensions.KEY))
                        needs = true;
                }
            if (needs && !segment.has(OCCURRENCE))
                segment.addProperty(OCCURRENCE, UUID.randomUUID().toString());
        }
    }

    public static JsonObject embeddedConfig(CompoundTag tag) {
        return tag.contains(EMBEDDED)
                ? JsonStructure.parse(
                                new String(tag.getByteArray(EMBEDDED), StandardCharsets.UTF_8))
                        .getAsJsonObject()
                : new JsonObject();
    }

    public static void cloneAll(CompoundTag tag, JsonObject config) {
        cloneAll(tag, config, new PatchInstances.Remap());
    }

    public static void cloneAll(CompoundTag tag, JsonObject config, PatchInstances.Remap remap) {
        if (config != null && config.has("segments"))
            for (var segment : config.getAsJsonArray("segments")) {
                var entry = segment.getAsJsonObject();
                if (entry.has(OCCURRENCE))
                    entry.addProperty(
                            OCCURRENCE,
                            remap.occurrence(UUID.fromString(entry.get(OCCURRENCE).getAsString()))
                                    .toString());
            }
        var source = config != null && config.has(KEY) ? read(config) : embedded(tag);
        var sourceRoot = config != null && config.has(KEY) ? config : embeddedConfig(tag);
        int sourceSchema =
                sourceRoot.has(KEY) ? sourceRoot.getAsJsonObject(KEY).get("version").getAsInt() : 2;
        var copied = new ArrayList<JsonObject>();
        var roots = new ListTag();
        for (var e : tag.getList("entities", 10))
            roots.add(cloneRoot((CompoundTag) e, source, copied, remap, sourceSchema));
        tag.put("entities", roots);
        if (config != null && (!copied.isEmpty() || config.has(KEY))) set(config, copied);
        if (!copied.isEmpty() || tag.contains(EMBEDDED)) {
            var c = embeddedConfig(tag);
            set(c, copied);
            embed(tag, c);
        }
    }

    public static SchematicDocument edited(SchematicDocument source, ListTag entities, int index) {
        if (index >= 0 && index < entities.size()) {
            var before = source.entitiesCopy().getCompound(index).getCompound("nbt");
            var after = entities.getCompound(index).getCompound("nbt");
            var token = EntityIdentity.read(before);
            if (token != null
                    && !java.util.Objects.equals(
                            ResourceLocation.tryParse(before.getString("id")),
                            ResourceLocation.tryParse(after.getString("id")))) {
                var entries = new ArrayList<>(entries(source));
                boolean changed = false;
                for (var patch : entries)
                    if (token(patch).equals(token)) {
                        if (!unavailable(patch).isEmpty())
                            throw new IllegalArgumentException(
                                    "Remove unavailable entity patch before changing entity type");
                        patch.addProperty(
                                "entity_type",
                                ResourceLocation.parse(after.getString("id")).toString());
                        changed = true;
                    }
                if (changed) {
                    var config = source.configCopy();
                    if (config == null) config = new JsonObject();
                    set(config, entries);
                    source = source.withConfig(config);
                }
            }
        }
        return source.withEntities(entities);
    }

    public static SchematicDocument document(CompoundTag tag, JsonObject config) {
        return new SchematicDocument(tag, prune(tag, config));
    }
}
