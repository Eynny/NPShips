package com.eynny.npships.authoring;

import com.eynny.npships.authoring.document.BlockAreaLayers;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.authoring.validation.JsonNumbers;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.*;
import java.util.function.*;

/** Independent schematic coordinates. Marker identity never belongs to the block at that cell. */
public final class AssemblyPoints {
    public static final String KEY = "assembly_points";
    public static final int LIMIT = 128;
    public static final String EMBEDDED = "npships:assembly_points";

    /**
     * Assembly points belong only to a main schematic. Segment readers use a detached projection,
     * including saved layer documents, without parsing ignored point records or changing source.
     */
    public static SchematicDocument segmentDocument(CompoundTag source, JsonObject config) {
        var detachedConfig = config == null ? null : config.deepCopy();
        if (detachedConfig != null) detachedConfig.remove(KEY);
        return new SchematicDocument(projectDocument(source, true, 0), detachedConfig);
    }

    public static CompoundTag segmentTemplate(CompoundTag source) {
        return projectDocument(source, true, 0).copy();
    }

    /** Read-only validation view. Unchanged branches and all block/entity payloads stay shared. */
    public static CompoundTag validationTemplate(CompoundTag source) {
        return projectDocument(source, false, 0);
    }

    private static CompoundTag projectDocument(CompoundTag source, boolean segment, int depth) {
        requireDepth(depth);
        if (segment && source.contains(EMBEDDED)) depthOnly(source.get(EMBEDDED), depth + 1);
        var result =
                segment && source.contains(EMBEDDED)
                        ? replace(source, source, EMBEDDED, null)
                        : source;
        if (source.contains(StructureSegments.VARIANTS, Tag.TAG_COMPOUND)) {
            requireDepth(depth + 1);
            var variants = source.getCompound(StructureSegments.VARIANTS);
            var projected = variants;
            for (var id : variants.getAllKeys())
                if (variants.contains(id, Tag.TAG_COMPOUND))
                    projected =
                            replace(
                                    variants,
                                    projected,
                                    id,
                                    projectDocument(variants.getCompound(id), true, depth + 2));
            result = replace(source, result, StructureSegments.VARIANTS, projected);
        }
        if (source.contains(BlockAreaLayers.KEY, Tag.TAG_COMPOUND))
            result =
                    replace(
                            source,
                            result,
                            BlockAreaLayers.KEY,
                            projectLayers(
                                    source.getCompound(BlockAreaLayers.KEY), segment, depth + 1));
        return result;
    }

    private static CompoundTag projectLayers(CompoundTag source, boolean segment, int depth) {
        requireDepth(depth);
        var result = source;
        if (source.contains("base", Tag.TAG_COMPOUND))
            result =
                    replace(
                            source,
                            result,
                            "base",
                            projectPacked(source.getCompound("base"), segment, depth + 1));
        if (segment) result = projectConfig(source, result, "base_config", depth + 1);
        var storedLayers = source.get("layers");
        if (!(storedLayers instanceof net.minecraft.nbt.ListTag layers)
                || !layers.isEmpty() && layers.getElementType() != Tag.TAG_COMPOUND) return result;
        requireDepth(depth + 1);
        var projected = layers;
        for (int i = 0; i < layers.size(); i++) {
            requireDepth(depth + 2);
            var layer = layers.getCompound(i);
            var changed = layer;
            for (var key : List.of("content", "source"))
                if (layer.contains(key, Tag.TAG_COMPOUND))
                    changed =
                            replace(
                                    layer,
                                    changed,
                                    key,
                                    projectPacked(layer.getCompound(key), segment, depth + 3));
            if (segment) {
                changed = projectConfig(layer, changed, "config", depth + 3);
                changed = projectConfig(layer, changed, "source_config", depth + 3);
            }
            if (changed != layer) {
                if (projected == layers) {
                    projected = new net.minecraft.nbt.ListTag();
                    for (var value : layers) projected.add(value);
                }
                projected.set(i, changed);
            }
        }
        return replace(source, result, "layers", projected);
    }

    private static CompoundTag projectPacked(CompoundTag packed, boolean segment, int depth) {
        requireDepth(depth);
        return packed.contains("metadata", Tag.TAG_COMPOUND)
                ? replace(
                        packed,
                        packed,
                        "metadata",
                        projectDocument(packed.getCompound("metadata"), segment, depth + 1))
                : projectDocument(packed, segment, depth);
    }

    private static CompoundTag projectConfig(
            CompoundTag source, CompoundTag current, String key, int depth) {
        if (!source.contains(key, Tag.TAG_STRING)) return current;
        requireDepth(depth);
        var parsed = JsonStructure.parse(source.getString(key));
        if (parsed.isJsonObject() && parsed.getAsJsonObject().has(KEY)) {
            parsed.getAsJsonObject().remove(KEY);
            return replace(
                    source, current, key, net.minecraft.nbt.StringTag.valueOf(parsed.toString()));
        }
        return current;
    }

    private static void requireDepth(int depth) {
        if (depth > 64)
            throw new IllegalArgumentException("Excessively nested schematic metadata.");
    }

    /** Ignored records still obey the native structural limit, without native field semantics. */
    private static void depthOnly(Tag tag, int depth) {
        requireDepth(depth);
        if (tag instanceof CompoundTag compound)
            for (var key : compound.getAllKeys()) depthOnly(compound.get(key), depth + 1);
        else if (tag instanceof net.minecraft.nbt.ListTag list)
            for (var value : list) depthOnly(value, depth + 1);
    }

    private static CompoundTag replace(
            CompoundTag source, CompoundTag current, String key, Tag value) {
        if (source.get(key) == value) return current;
        if (current == source) {
            current = new CompoundTag();
            for (var field : source.getAllKeys()) current.put(field, source.get(field));
        }
        if (value == null) current.remove(key);
        else current.put(key, value);
        return current;
    }

    public static JsonObject restore(net.minecraft.nbt.CompoundTag tag, JsonObject config) {
        if (config != null && config.has(KEY) || !tag.contains(EMBEDDED)) return config;
        if (!tag.contains(EMBEDDED, net.minecraft.nbt.Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid embedded assembly points.");
        var stored = tag.getCompound(EMBEDDED);
        if (!stored.contains("json", net.minecraft.nbt.Tag.TAG_STRING)
                || stored.getString("json").length() > 1_048_576)
            throw new IllegalArgumentException("Invalid embedded assembly points.");
        var decoded = JsonStructure.parse(stored.getString("json"));
        if (decoded.isJsonArray() && decoded.getAsJsonArray().isEmpty()) return config;
        var result = config == null ? new JsonObject() : config;
        result.add(KEY, decoded);
        read(result);
        return result;
    }

    public static void embed(net.minecraft.nbt.CompoundTag tag, JsonObject config) {
        if (config == null) return;
        read(config);
        if (!config.has(KEY)) return;
        if (config.getAsJsonArray(KEY).isEmpty()) {
            clear(tag);
            return;
        }
        String json = config.get(KEY).toString();
        if (json.length() > 1_048_576)
            throw new IllegalArgumentException("Assembly-point metadata is too large.");
        var stored = tag.getCompound(EMBEDDED).copy();
        stored.putString("json", json);
        tag.put(EMBEDDED, stored);
    }

    /** Full document serialization owns removal; partial patch writers may omit the point field. */
    public static void serialize(CompoundTag tag, JsonObject config) {
        if (config != null && config.has(KEY)) embed(tag, config);
        else clear(tag);
    }

    private static void clear(CompoundTag tag) {
        if (!tag.contains(EMBEDDED)) return;
        var siblings = tag.getCompound(EMBEDDED).copy();
        siblings.remove("json");
        if (siblings.isEmpty()) tag.remove(EMBEDDED);
        else {
            siblings.putString("json", "[]");
            tag.put(EMBEDDED, siblings);
        }
    }

    private AssemblyPoints() {}

    public record Point(UUID id, BlockPos position) {
        public Point {
            Objects.requireNonNull(id);
            position = Objects.requireNonNull(position).immutable();
        }
    }

    public static List<Point> read(JsonObject config) {
        if (config == null || !config.has(KEY)) return List.of();
        if (!config.get(KEY).isJsonArray())
            throw new IllegalArgumentException("Assembly points must be an array.");
        var array = config.getAsJsonArray(KEY);
        if (array.size() > LIMIT)
            throw new IllegalArgumentException("Too many assembly points (maximum 128).");
        var result = new ArrayList<Point>();
        var ids = new HashSet<UUID>();
        for (var value : array) {
            if (!value.isJsonObject())
                throw new IllegalArgumentException("Invalid assembly point.");
            var entry = value.getAsJsonObject();
            if (!entry.has("id")
                    || !entry.get("id").isJsonPrimitive()
                    || !entry.getAsJsonPrimitive("id").isString())
                throw new IllegalArgumentException("Assembly point needs an ID.");
            var id = UUID.fromString(entry.get("id").getAsString());
            if (!id.toString().equals(entry.get("id").getAsString()))
                throw new IllegalArgumentException("Assembly point ID must be a canonical UUID.");
            if (!ids.add(id))
                throw new IllegalArgumentException("Duplicate assembly point ID: " + id);
            if (!entry.has("pos")
                    || !entry.get("pos").isJsonArray()
                    || entry.getAsJsonArray("pos").size() != 3)
                throw new IllegalArgumentException("Assembly point needs three coordinates.");
            var p = entry.getAsJsonArray("pos");
            result.add(
                    new Point(
                            id,
                            new BlockPos(
                                    JsonNumbers.integer(p.get(0), "assembly point X"),
                                    JsonNumbers.integer(p.get(1), "assembly point Y"),
                                    JsonNumbers.integer(p.get(2), "assembly point Z"))));
        }
        return List.copyOf(result);
    }

    public static void validate(SchematicDocument doc) {
        for (var point : doc.assemblyPoints())
            if (!doc.contains(point.position()))
                throw new IllegalArgumentException(
                        "Assembly point outside schematic: " + point.position().toShortString());
    }

    public static boolean empty(SchematicDocument doc, Point point) {
        String name = doc.blockName(point.position());
        return name.equals("minecraft:air")
                || name.equals("minecraft:cave_air")
                || name.equals("minecraft:void_air");
    }

    public static SchematicDocument put(SchematicDocument doc, UUID id, BlockPos position) {
        if (position != null && !doc.contains(position))
            throw new IllegalArgumentException("Assembly point must be inside the schematic.");
        var config = doc.configCopy();
        if (config == null) config = new JsonObject();
        var points = read(config);
        if (id != null && points.stream().noneMatch(p -> p.id().equals(id)))
            throw new IllegalArgumentException("Assembly point selection changed.");
        if (id == null && position == null) return doc;
        if (id == null && points.size() >= LIMIT)
            throw new IllegalArgumentException("Too many assembly points (maximum 128).");
        var output = new JsonArray();
        if (config.has(KEY))
            for (var value : config.getAsJsonArray(KEY)) {
                var entry = value.getAsJsonObject();
                if (id != null && entry.get("id").getAsString().equals(id.toString())) {
                    if (position != null) {
                        var changed = entry.deepCopy();
                        changed.add("pos", SchematicPatches.vector(position));
                        output.add(changed);
                    }
                } else output.add(entry);
            }
        if (id == null) {
            var entry = new JsonObject();
            entry.addProperty("id", UUID.randomUUID().toString());
            entry.add("pos", SchematicPatches.vector(position));
            output.add(entry);
        }
        set(config, output);
        return doc.withConfig(config);
    }

    public static SchematicDocument copy(
            SchematicDocument document, JsonObject prototype, BlockPos position) {
        var next = put(document, null, position);
        var config = next.configCopy();
        var list = config.getAsJsonArray(KEY);
        var created = list.get(list.size() - 1).getAsJsonObject();
        var copied = prototype.deepCopy();
        copied.add("id", created.get("id"));
        copied.add("pos", created.get("pos"));
        list.set(list.size() - 1, copied);
        read(config);
        return next.withConfig(config);
    }

    public static void set(JsonObject config, JsonArray points) {
        config.add(KEY, points);
        read(config);
    }

    /** Transform coordinates even at empty cells; never infer marker membership from blocks. */
    public static void map(
            JsonObject config, UnaryOperator<BlockPos> transform, Predicate<BlockPos> retain) {
        if (config == null || !config.has(KEY)) return;
        var points = read(config);
        var output = new JsonArray();
        int i = 0;
        for (var value : config.getAsJsonArray(KEY)) {
            var p = points.get(i++);
            if (!retain.test(p.position())) continue;
            var entry = value.getAsJsonObject().deepCopy();
            entry.add("pos", SchematicPatches.vector(transform.apply(p.position())));
            output.add(entry);
        }
        set(config, output);
    }

    /** Region copies create identities; moves retain them, including markers over empty cells. */
    public static void overlay(
            JsonObject target,
            JsonObject source,
            Predicate<BlockPos> selected,
            Predicate<BlockPos> erased,
            BlockPos offset,
            boolean move) {
        var output = new JsonArray();
        var old = read(target);
        int i = 0;
        if (target.has(KEY))
            for (var entry : target.getAsJsonArray(KEY))
                if (!erased.test(old.get(i++).position())) output.add(entry);
        var copied = read(source);
        i = 0;
        if (source != null && source.has(KEY))
            for (var value : source.getAsJsonArray(KEY)) {
                var p = copied.get(i++);
                if (!selected.test(p.position())) continue;
                var entry = value.getAsJsonObject().deepCopy();
                if (!move) entry.addProperty("id", UUID.randomUUID().toString());
                entry.add("pos", SchematicPatches.vector(p.position().offset(offset)));
                output.add(entry);
            }
        set(target, output);
    }

    public static void mirrorDelta(
            JsonObject before, JsonObject after, JsonObject output, BlockPos span, int axes) {
        var old = read(before);
        var next = read(after);
        var result = output.has(KEY) ? output.getAsJsonArray(KEY).deepCopy() : new JsonArray();
        var ids = new HashSet<UUID>();
        for (var p : old) ids.add(p.id());
        for (var p : next) ids.add(p.id());
        for (var id : ids) {
            var a = old.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
            var b = next.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
            if (Objects.equals(a, b)) continue;
            for (int mask = 1; mask < 8; mask++) {
                if ((mask & axes) != mask) continue;
                UUID copy =
                        UUID.nameUUIDFromBytes(
                                ("npships:assembly-point-mirror:" + id + ":" + mask)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                UUID removeId = copy;
                result.asList()
                        .removeIf(
                                e ->
                                        e.getAsJsonObject()
                                                .get("id")
                                                .getAsString()
                                                .equals(removeId.toString()));
                if (b == null) continue;
                var position =
                        com.eynny.npships.authoring.document.SchematicSymmetry.point(
                                b.position(), span, mask);
                if (position.equals(b.position())
                        || position.getX() < 0
                        || position.getY() < 0
                        || position.getZ() < 0
                        || position.getX() >= span.getX()
                        || position.getY() >= span.getY()
                        || position.getZ() >= span.getZ()) continue;
                if (read(arrayConfig(result)).stream().anyMatch(p -> p.position().equals(position)))
                    continue;
                var source =
                        after.getAsJsonArray(KEY).asList().stream()
                                .map(JsonElement::getAsJsonObject)
                                .filter(e -> e.get("id").getAsString().equals(id.toString()))
                                .findFirst()
                                .orElseThrow()
                                .deepCopy();
                source.addProperty("id", copy.toString());
                source.add("pos", SchematicPatches.vector(position));
                result.add(source);
            }
        }
        if (!result.isEmpty() || output.has(KEY)) set(output, result);
    }

    private static JsonObject arrayConfig(JsonArray list) {
        var result = new JsonObject();
        result.add(KEY, list);
        return result;
    }
}
