package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchException;
import com.eynny.npships.api.patches.PatchException.Reason;
import com.eynny.npships.api.patches.PatchTarget;
import com.eynny.npships.api.patches.PatchType;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.EntityBlock;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * Target author-data support for the shared runtime. Provider JSON is never a native offset graph.
 */
public final class ExternalPatches {
    private ExternalPatches() {}

    /** Internal authoring view: an explicit JSON patch root is authoritative, even when empty. */
    public static List<JsonObject> authoringEntries(SchematicDocument doc) {
        var config = doc.configCopy();
        return config != null && config.has("patches")
                ? SchematicPatches.read(config)
                : doc.templatePatches();
    }

    /**
     * Internal accepted-edit boundary; materialize missing embedded entries without losing
     * extensions.
     */
    public static SchematicDocument putAuthoring(SchematicDocument doc, JsonObject patch) {
        return PatchDevices.put(authoringDocument(doc), patch);
    }

    public static SchematicDocument removeAuthoring(SchematicDocument doc, BlockPos pos) {
        var source = authoringDocument(doc);
        var config = SchematicPatches.move(source.configCopy(), pos, pos, false);
        var tag = source.nbtCopy();
        for (var value : tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var block = (net.minecraft.nbt.CompoundTag) value;
            if (SchematicPatches.blockPosition(block).equals(pos))
                block.putBoolean(SchematicPatches.UNPATCHED, true);
        }
        // External payload/native data has no built-in CC/Radar address-stripping contract.
        SchematicPatches.embed(tag, config);
        var next = new SchematicDocument(tag, config).inheritPatchValidation(source);
        SchematicPatches.validatePatches(next, false);
        return next;
    }

    /** Remove one selected instance, preserving sibling providers and native authored data. */
    public static SchematicDocument removeAuthoring(SchematicDocument doc, JsonObject selected) {
        var source = authoringDocument(doc);
        var entries = new java.util.ArrayList<>(authoringEntries(source));
        String key = PatchInstances.key(selected, false);
        if (!entries.removeIf(e -> PatchInstances.key(e, false).equals(key)))
            throw new IllegalArgumentException("Patch selection is stale: " + key);
        var config = source.configCopy();
        SchematicPatches.set(config, entries);
        var tag = source.nbtCopy();
        SchematicPatches.embed(tag, config);
        var next = new SchematicDocument(tag, config).inheritPatchValidation(source);
        SchematicPatches.validatePatches(next, false);
        return next;
    }

    private static SchematicDocument authoringDocument(SchematicDocument doc) {
        return doc.withConfig(SchematicPatches.authoringConfig(doc));
    }

    public static boolean external(JsonElement value) {
        if (!value.isJsonObject()) return false;
        var kind = value.getAsJsonObject().get("kind");
        return kind != null
                && kind.isJsonPrimitive()
                && kind.getAsJsonPrimitive().isString()
                && kind.getAsString().contains(":");
    }

    static void envelope(JsonObject patch) {
        envelope(patch, false);
    }

    static void envelope(JsonObject patch, boolean entity) {
        if (ResourceLocation.tryParse(patch.get("kind").getAsString()) == null)
            throw entity
                    ? EntityPatches.failure(
                            patch, Reason.INVALID_PAYLOAD, "Invalid namespaced type ID", null)
                    : failure(patch, Reason.INVALID_PAYLOAD, "Invalid namespaced type ID", null);
        try {
            var version = patch.getAsJsonPrimitive("payload_version");
            if (version == null
                    || !version.isNumber()
                    || version.getAsBigDecimal().intValueExact() < 1
                    || version.getAsBigDecimal().intValueExact() > 65535
                    || !patch.get("payload").isJsonObject())
                throw new IllegalArgumentException(
                        "Expected payload_version integer 1..65535 and payload object");
            int[] nodes = {0};
            shape(patch, 0, nodes);
            if (patch.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                throw new IllegalArgumentException("External patch exceeds 64 KiB");
            if (!entity
                    && patch.has("block_entity")
                    && ResourceLocation.tryParse(patch.get("block_entity").getAsString()) == null)
                throw new IllegalArgumentException("Invalid block_entity ID");
        } catch (RuntimeException error) {
            throw entity
                    ? EntityPatches.failure(
                            patch, Reason.INVALID_PAYLOAD, error.getMessage(), error)
                    : failure(patch, Reason.INVALID_PAYLOAD, error.getMessage(), error);
        }
    }

    private static void shape(JsonElement value, int depth, int[] nodes) {
        if (depth > 32 || ++nodes[0] > 8192)
            throw new IllegalArgumentException("External patch nesting/node limit exceeded");
        if (value.isJsonObject())
            for (var e : value.getAsJsonObject().entrySet()) shape(e.getValue(), depth + 1, nodes);
        else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) shape(e, depth + 1, nodes);
    }

    static PatchType type(JsonObject patch) {
        return PatchTypes.registered().get(ResourceLocation.parse(patch.get("kind").getAsString()));
    }

    static String unavailable(JsonObject patch) {
        var type = type(patch);
        if (type == null)
            return failure(patch, Reason.UNKNOWN_TYPE, "No registered provider", null).getMessage();
        for (String mod : type.requiredMods().stream().sorted().toList())
            if (!ModList.get().isLoaded(mod))
                return failure(patch, Reason.UNAVAILABLE_DEPENDENCY, "Missing mod " + mod, null)
                        .getMessage();
        return requiredProviderUnavailable(
                ResourceLocation.parse(patch.get("kind").getAsString()), new java.util.HashSet<>());
    }

    static String requiredProviderUnavailable(
            ResourceLocation id, java.util.Set<ResourceLocation> checked) {
        if (!checked.add(id)) return "";
        var provider = PatchTypes.provider(id);
        if (provider == null) return "Required patch provider is unavailable: " + id;
        for (String mod : provider.requiredMods())
            if (!ModList.get().isLoaded(mod)) return "Patch " + id + " requires missing mod " + mod;
        for (var dependency : provider.registration().requires()) {
            String missing = requiredProviderUnavailable(dependency, checked);
            if (!missing.isEmpty()) return missing;
        }
        return "";
    }

    static PatchTarget target(
            SchematicDocument doc, JsonObject patch, PatchType type, ListTag palette) {
        if (type.target() == PatchType.TargetKind.ENTITY)
            throw new IllegalArgumentException("ENTITY provider requires an entity target");
        var pos = SchematicPatches.position(patch.getAsJsonArray("pos"));
        var nativeTarget = nativeTarget(doc, pos, palette);
        var beType = nativeTarget.blockEntityType();
        if (type.target() == PatchType.TargetKind.BLOCK_ENTITY
                && (beType == null || !patch.has("block_entity")))
            throw new IllegalArgumentException(
                    "BlockEntity patch requires an explicit native block_entity ID");
        if (patch.has("block_entity")
                && !ResourceLocation.parse(patch.get("block_entity").getAsString()).equals(beType))
            throw new IllegalArgumentException(
                    "Native BlockEntity type does not match block_entity");
        return nativeTarget;
    }

    private static PatchTarget nativeTarget(SchematicDocument doc, BlockPos pos, ListTag palette) {
        if (!doc.blocks().containsKey(pos))
            throw new IllegalArgumentException("Missing target block");
        var raw = palette.getCompound(doc.blocks().get(pos));
        var id = ResourceLocation.parse(raw.getString("Name"));
        if (!BuiltInRegistries.BLOCK.containsKey(id))
            throw new IllegalArgumentException("Unregistered target block " + id);
        var state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), raw);
        var data = doc.blockEntityData(pos);
        ResourceLocation beType = null;
        if (state.getBlock() instanceof EntityBlock factory) {
            var be = factory.newBlockEntity(pos, state);
            if (be != null && be.getType().isValid(state))
                beType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        }
        if (data.contains("id")
                && (beType == null || !data.getString("id").equals(beType.toString())))
            throw new IllegalArgumentException("Saved BlockEntity ID does not match native target");
        return new PatchTarget(pos, state, beType, data);
    }

    /** Internal editor bridge, not a public provider API. No provider factory is executed. */
    public static JsonObject authoringEntry(
            SchematicDocument doc, BlockPos pos, ResourceLocation id) {
        var type = PatchTypes.registered().get(id);
        if (type == null || type.target() == PatchType.TargetKind.ENTITY)
            throw new IllegalArgumentException("No block patch provider " + id);
        var target = nativeTarget(doc, pos, doc.paletteCopy());
        var patch = new JsonObject();
        patch.addProperty("kind", id.toString());
        var coordinates = new JsonArray();
        coordinates.add(pos.getX());
        coordinates.add(pos.getY());
        coordinates.add(pos.getZ());
        patch.add("pos", coordinates);
        patch.addProperty("block", doc.blockName(pos));
        if (target.blockEntityType() != null)
            patch.addProperty("block_entity", target.blockEntityType().toString());
        patch.addProperty("payload_version", type.version());
        patch.add("payload", new JsonObject());
        patch = PatchInstances.newInstance(patch);
        authoringTarget(doc, patch);
        return patch;
    }

    /**
     * Validate the envelope/native identity while allowing the payload to be repaired in a draft.
     */
    public static PatchTarget authoringTarget(SchematicDocument doc, JsonObject patch) {
        envelope(patch);
        String missing = unavailable(patch);
        if (!missing.isEmpty()) throw new IllegalArgumentException(missing);
        var type = type(patch);
        var pos = SchematicPatches.position(patch.getAsJsonArray("pos"));
        if (!doc.blockName(pos).equals(patch.get("block").getAsString()))
            throw new IllegalArgumentException("Saved block does not match native target");
        requireReadable(patch, false);
        return target(doc, patch, type, doc.paletteCopy());
    }

    /**
     * Missing code/dependencies remain opaque in authoring. Catalogue/preparation availability
     * rejects them.
     */
    static void validate(SchematicDocument doc, JsonObject patch, ListTag palette) {
        envelope(patch);
        if (!unavailable(patch).isEmpty()) return;
        var type = type(patch);
        try {
            type.validator()
                    .validate(target(doc, patch, type, palette), canonicalPayload(patch, false));
        } catch (PatchException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw failure(patch, Reason.INVALID_PAYLOAD, error.getMessage(), error);
        } catch (RuntimeException | LinkageError error) {
            throw failure(patch, Reason.VALIDATION_EXCEPTION, error.toString(), error);
        }
    }

    /**
     * A private execution/draft representation. No read or catalogue validation replaces authored
     * version, envelope extensions or payload. The provider alone defines historical conversion.
     */
    static JsonObject canonicalPayload(JsonObject patch, boolean entity) {
        var id = ResourceLocation.parse(patch.get("kind").getAsString());
        var payload =
                PayloadEvolution.target(
                        id,
                        patch.get("payload_version").getAsInt(),
                        patch.getAsJsonObject("payload"));
        if (patch.get("payload_version").getAsInt() != type(patch).version()) {
            var bounded = patch.deepCopy();
            bounded.addProperty("payload_version", type(patch).version());
            bounded.add("payload", payload);
            envelope(bounded, entity);
        }
        return payload;
    }

    private static void requireReadable(JsonObject patch, boolean entity) {
        var id = ResourceLocation.parse(patch.get("kind").getAsString());
        int version = patch.get("payload_version").getAsInt();
        if (!PayloadEvolution.readableTarget(id, version)) {
            String detail = "Unsupported provider payload version " + version;
            throw entity
                    ? EntityPatches.failure(patch, Reason.INVALID_PAYLOAD, detail, null)
                    : failure(patch, Reason.INVALID_PAYLOAD, detail, null);
        }
    }

    /** Explicit editor-draft conversion; only the existing accepted-edit owner can commit it. */
    public static JsonObject upgradePayload(JsonObject patch, boolean entity) {
        envelope(patch, entity);
        var converted = canonicalPayload(patch, entity);
        var copy = patch.deepCopy();
        copy.addProperty("payload_version", type(patch).version());
        copy.add("payload", converted);
        return copy;
    }

    static PatchException failure(JsonObject patch, Reason reason, String detail, Throwable cause) {
        return new PatchException(
                reason,
                patch.get("kind").getAsString(),
                SchematicPatches.position(patch.getAsJsonArray("pos")),
                detail,
                cause);
    }
}
