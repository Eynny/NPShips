package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.google.gson.*;

import net.minecraft.core.BlockPos;

import java.util.*;

/** Shared document-only device descriptions and link validation for authoring and spawning. */
public final class PatchDevices {
    private PatchDevices() {}

    private static final class Descriptions {
        static final List<
                        Map.Entry<net.minecraft.resources.ResourceLocation, PatchDeviceDescription>>
                CODE = build();

        private static List<
                        Map.Entry<net.minecraft.resources.ResourceLocation, PatchDeviceDescription>>
                build() {
            var code =
                    new ArrayList<>(
                            PatchCode.sourceFormats(PatchDeviceDescription.class).entrySet());
            code.sort(Comparator.comparingInt(entry -> entry.getValue().defaultRank()));
            return List.copyOf(code);
        }
    }

    private static List<Map.Entry<net.minecraft.resources.ResourceLocation, PatchDeviceDescription>>
            descriptions() {
        return Descriptions.CODE;
    }

    public static String kind(String id) {
        for (var entry : descriptions()) {
            String kind = entry.getValue().kind(id);
            if (!kind.isEmpty()) return kind;
        }
        return "";
    }

    public static String kind(SchematicDocument doc, BlockPos p) {
        for (var entry : descriptions()) {
            String kind = entry.getValue().kind(doc, p);
            if (!kind.isEmpty()) return kind;
        }
        return "";
    }

    public static String block(SchematicDocument doc, BlockPos p) {
        return doc.blockName(p);
    }

    public static JsonObject endpoint(SchematicDocument doc, BlockPos owner, BlockPos target) {
        String id = block(doc, target);
        if (id.equals("minecraft:air"))
            throw new IllegalArgumentException("Missing target at " + target);
        var e = new JsonObject();
        e.add("offset", SchematicPatches.vector(target.subtract(owner)));
        e.addProperty("block", id);
        return e;
    }

    public static JsonObject create(SchematicDocument doc, BlockPos p) {
        for (var entry : descriptions()) {
            String kind = entry.getValue().kind(doc, p);
            if (kind.isEmpty()) continue;
            return entry.getValue().create(doc, p, kind);
        }
        throw new IllegalArgumentException("Unsupported device: " + doc.blockName(p));
    }

    public static void validateLinks(JsonObject patch, SchematicDocument doc) {
        if (ExternalPatches.external(patch)) return;
        if (patch.has("references"))
            for (String name : patch.getAsJsonObject("references").keySet())
                if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"))
                    throw new IllegalArgumentException("Invalid reference name: " + name);
        walk(patch, SchematicPatches.position(patch.getAsJsonArray("pos")), doc, 0);
    }

    private static void walk(JsonElement value, BlockPos owner, SchematicDocument doc, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Device links nested too deeply.");
        if (ExternalPatches.external(value)) return;
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (o.has("offset")) {
                BlockPos target =
                        owner.offset(SchematicPatches.position(o.getAsJsonArray("offset")));
                if (!doc.contains(target)
                        || !block(doc, target).equals(o.get("block").getAsString()))
                    throw new IllegalArgumentException(
                            "Device link missing or changed at " + target);
            }
            for (var e : o.entrySet()) walk(e.getValue(), owner, doc, depth + 1);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) walk(e, owner, doc, depth + 1);
    }

    public static List<BlockPos> targets(JsonObject patch) {
        var result = new ArrayList<BlockPos>();
        collect(patch, SchematicPatches.position(patch.getAsJsonArray("pos")), result);
        return result;
    }

    private static void collect(JsonElement value, BlockPos owner, List<BlockPos> result) {
        if (ExternalPatches.external(value)) return;
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (o.has("offset"))
                result.add(owner.offset(SchematicPatches.position(o.getAsJsonArray("offset"))));
            for (var e : o.entrySet()) collect(e.getValue(), owner, result);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) collect(e, owner, result);
    }

    public static JsonObject copy(SchematicDocument doc, JsonObject source, BlockPos to) {
        return copy(doc, source, to, new PatchInstances.Remap());
    }

    public static JsonObject copy(
            SchematicDocument doc, JsonObject source, BlockPos to, PatchInstances.Remap remap) {
        var copy = remap.copy(source, false);
        BlockPos from = SchematicPatches.position(copy.getAsJsonArray("pos"));
        translate(copy, to.subtract(from));
        copy.add("pos", SchematicPatches.vector(to));
        for (var code : PatchSourceOperationsDispatch.all()) code.copied(copy);
        return copy;
    }

    /** One graph edit, including both rope holders and supported referenced devices. */
    public static SchematicDocument put(SchematicDocument doc, JsonObject patch) {
        // Resolve accepted instance ownership before graph mutation or upsert.
        return putIdentified(doc.withConfig(SchematicPatches.authoringConfig(doc)), patch);
    }

    private static SchematicDocument putIdentified(SchematicDocument doc, JsonObject patch) {
        BlockPos owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
        var entries = new ArrayList<>(SchematicPatches.read(doc.configCopy()));
        var accepted = patch.deepCopy();
        if (!PatchInstances.explicit(accepted)) {
            var previous =
                    entries.stream()
                            .filter(
                                    e ->
                                            SchematicPatches.position(e.getAsJsonArray("pos"))
                                                            .equals(owner)
                                                    && e.get("kind").equals(patch.get("kind")))
                            .toList();
            if (previous.size() > 1)
                throw new IllegalArgumentException("Select a patch instance explicitly");
            if (!previous.isEmpty() && PatchInstances.explicit(previous.getFirst()))
                accepted.addProperty(
                        PatchInstances.INSTANCE,
                        PatchInstances.identity(previous.getFirst(), false)
                                .instanceId()
                                .toString());
            else
                accepted =
                        previous.isEmpty()
                                ? PatchInstances.newInstance(accepted)
                                : PatchInstances.materialize(accepted, false);
        }
        for (var code : PatchSourceOperationsDispatch.all()) code.replacing(accepted, entries);
        var replacement = accepted;
        entries.removeIf(
                e -> PatchInstances.key(e, false).equals(PatchInstances.key(replacement, false)));
        entries.add(accepted);
        for (var target : targets(accepted))
            if (entries.stream()
                            .noneMatch(
                                    e ->
                                            !ExternalPatches.external(e)
                                                    && SchematicPatches.position(
                                                                    e.getAsJsonArray("pos"))
                                                            .equals(target))
                    && !kind(doc, target).isEmpty())
                entries.add(PatchInstances.newInstance(create(doc, target)));
        var config = doc.configCopy();
        if (config == null) config = new JsonObject();
        SchematicPatches.set(config, entries);
        var changes = new HashMap<BlockPos, net.minecraft.nbt.CompoundTag>();
        com.eynny.npships.authoring.document.SchematicBlocks.embedChanges(doc, changes, config);
        var next = doc.withBlocks(changes, doc.paletteCopy(), config);
        SchematicPatches.validatePatches(next, false);
        return next;
    }

    /** Explicit deletion clears incoming relations and native addresses, but keeps the block. */
    public static SchematicDocument remove(SchematicDocument doc, BlockPos pos) {
        var config = SchematicPatches.move(SchematicPatches.authoringConfig(doc), pos, pos, false);
        var tag = doc.nbtCopy();
        for (var value : tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var block = (net.minecraft.nbt.CompoundTag) value;
            if (SchematicPatches.blockPosition(block).equals(pos)) {
                block.putBoolean(SchematicPatches.UNPATCHED, true);
                var data = block.getCompound("nbt");
                clearAddresses(doc.blockName(pos), data);
                block.put("nbt", data);
            }
        }
        SchematicPatches.embed(tag, config);
        return new SchematicDocument(tag, config);
    }

    /** Clear only addresses owned by this native source root; nested foreign fields stay opaque. */
    public static void clearAddresses(String sourceBlock, net.minecraft.nbt.CompoundTag data) {
        for (var code : PatchSourceOperationsDispatch.all()) code.clearAddresses(sourceBlock, data);
    }

    public static List<JsonObject> incoming(SchematicDocument doc, BlockPos target) {
        return ExternalPatches.authoringEntries(doc).stream()
                .filter(e -> targets(e).contains(target))
                .toList();
    }

    private static void translate(JsonElement e, BlockPos delta) {
        if (ExternalPatches.external(e)) return;
        if (e.isJsonObject()) {
            var o = e.getAsJsonObject();
            if (o.has("offset"))
                o.add(
                        "offset",
                        SchematicPatches.vector(
                                SchematicPatches.position(o.getAsJsonArray("offset"))
                                        .subtract(delta)));
            for (var v : o.entrySet())
                if (!v.getKey().equals("offset")) translate(v.getValue(), delta);
        } else if (e.isJsonArray()) for (var v : e.getAsJsonArray()) translate(v, delta);
    }

    /** Drop an entire relation if any of its endpoints is removed, retaining unrelated groups. */
    public static void removeTarget(JsonObject patch, BlockPos owner, BlockPos target) {
        if (ExternalPatches.external(patch)) return;
        for (var code : PatchSourceOperationsDispatch.all())
            code.removeTarget(patch, owner, target);
    }
}
