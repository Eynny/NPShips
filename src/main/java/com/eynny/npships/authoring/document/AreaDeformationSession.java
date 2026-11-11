package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.geometry.AreaGeometry;
import com.eynny.npships.authoring.geometry.ShapeDeformation;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;

import java.util.BitSet;

/** Retains the input of applied operations separately from the editor's document undo history. */
public final class AreaDeformationSession {
    public static final String KEY = "npships:deformation_input";

    private AreaDeformationSession() {}

    public static SchematicDocument clean(SchematicDocument doc) {
        if (!doc.hasMetadata(KEY)) return doc;
        var tag = doc.nbtCopy();
        tag.remove(KEY);
        return new SchematicDocument(tag, doc.configCopy());
    }

    public static SchematicDocument carryInput(SchematicDocument from, SchematicDocument to) {
        if (!from.hasMetadata(KEY)) return to;
        var tag = to.nbtCopy();
        tag.put(KEY, from.metadataCompound(KEY));
        return new SchematicDocument(tag, to.configCopy());
    }

    private static ShapeDeformation warp(BlockAreaLayers.Layer layer) {
        return ShapeDeformation.decode(layer.settings().getAsJsonObject("deformation"));
    }

    public static boolean canReverse(BlockAreaLayers.Layer layer) {
        return layer != null && (!warp(layer).identity() || layer.source().hasMetadata(KEY));
    }

    public static boolean inverseOfApplied(
            BlockAreaLayers.Layer layer, ShapeDeformation requested) {
        if (requested.identity()) return false;
        var saved = layer.source().metadataCompound(KEY);
        if (saved.isEmpty()) return false;
        var last =
                ShapeDeformation.decode(
                        JsonStructure.parse(saved.getString("transform")).getAsJsonObject());
        var a = last.parameters();
        var b = requested.parameters();
        for (int i = 3; i < 7; i++) if (a[i] != 0 || b[i] != 0) return false;
        int rotations = 0;
        for (int i = 0; i < 3; i++) if (a[i] != 0) rotations++;
        if (rotations == 1 && last.direct().identity() && requested.direct().identity()) {
            for (int i = 0; i < 3; i++) if (Math.abs(a[i] + b[i]) > 1e-7) return false;
            return last.parameters(new double[7]).identity()
                    && requested.parameters(new double[7]).identity();
        }
        for (int i = 0; i < 7; i++) if (a[i] != 0 || b[i] != 0) return false;
        var d = last.direct();
        var next = requested.direct();
        if (d.identity() || next.identity() || d.mode() != next.mode()) return false;
        double inverse =
                (d.mode() == com.eynny.npships.authoring.geometry.DirectDeformation.Mode.TAPER
                                || d.mode()
                                        == com.eynny.npships.authoring.geometry.DirectDeformation
                                                .Mode.SCALE)
                        ? 10000 / d.amount()
                        : -d.amount();
        return Math.abs(inverse - next.amount()) < 1e-7 && d.amount(next.amount()).equals(next);
    }

    public static BlockAreaLayers.State inverseApplied(BlockAreaLayers.State state, String id) {
        return reverse(warp(state.get(id)).identity() ? state : neutral(state, id), id);
    }

    /** Materialize the current input with zero new transformation, preserving live-plane stages. */
    private static BlockAreaLayers.State neutral(BlockAreaLayers.State state, String id) {
        var l = state.get(id);
        var o = l.settings();
        o.add("deformation", ShapeDeformation.IDENTITY.encode());
        var brush = Blocks.STONE.defaultBlockState();
        if (o.has("brush")) {
            // The current contents already own their material; generated sources need their brush.
            try {
                brush =
                        net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                                        BuiltInRegistries.BLOCK.asLookup(),
                                        o.get("brush").getAsString(),
                                        true)
                                .blockState();
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
                throw new IllegalArgumentException(ex);
            }
        }
        var weights = new java.util.ArrayList<AreaGeometry.Weighted>();
        if (flag(o, "random", false) && o.has("random_pool"))
            for (var entry : o.getAsJsonArray("random_pool")) {
                var e = entry.getAsJsonObject();
                try {
                    weights.add(
                            new AreaGeometry.Weighted(
                                    net.minecraft.commands.arguments.blocks.BlockStateParser
                                            .parseForBlock(
                                                    BuiltInRegistries.BLOCK.asLookup(),
                                                    e.get("block").getAsString(),
                                                    true)
                                            .blockState(),
                                    e.get("weight").getAsDouble()));
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
                    throw new IllegalArgumentException(ex);
                }
            }
        return AreaEditOperation.apply(
                        new AreaEditOperation.Request(
                                state,
                                id,
                                l.region(),
                                o,
                                new SchematicRegions.Contents(
                                        flag(o, "blocks", true),
                                        flag(o, "entities", true),
                                        flag(o, "zones", true)),
                                o.has("shape")
                                        ? SchematicRegions.Shape.valueOf(
                                                o.get("shape").getAsString())
                                        : SchematicRegions.Shape.NONE,
                                brush,
                                ShapeDeformation.IDENTITY,
                                flag(o, "generated", false),
                                flag(o, "hollow", false),
                                false,
                                false,
                                weights,
                                o.has("seed") ? o.get("seed").getAsLong() : 1,
                                o.has("generated_turns") ? o.get("generated_turns").getAsInt() : 0,
                                o.has("original_weight")
                                        ? o.get("original_weight").getAsDouble()
                                        : 1))
                .layers();
    }

    private static boolean flag(JsonObject o, String key, boolean fallback) {
        return o.has(key) ? o.get(key).getAsBoolean() : fallback;
    }

    private static JsonObject settled(JsonObject o) {
        var settings = o.deepCopy();
        settings.remove("rotation_frame");
        settings.remove("baked_shape_mask");
        settings.add("deformation", ShapeDeformation.IDENTITY.encode());
        settings.addProperty("generated", false);
        settings.addProperty("shape", "NONE");
        settings.addProperty("random", false);
        settings.addProperty("sticky", false);
        settings.addProperty("generated_turns", 0);
        return settings;
    }

    public static BlockAreaLayers.State apply(BlockAreaLayers.State state, String id) {
        var l = state.get(id);
        var transform = warp(l);
        if (l.settings().has("bounds_scale")) {
            var content = clean(l.content());
            var settings = settled(l.settings());
            settings.remove("bounds_scale");
            return state.withSource(id, carryInput(l.source(), content), l.mask())
                    .replace(id, l.region(), content, l.mask(), settings);
        }
        if (transform.identity()) return state;
        var before = neutral(state, id).get(id);
        var input = clean(before.content()).nbtCopy();
        var previous = l.source().metadataCompound(KEY);
        int depth = 0;
        long cells = input.getList("blocks", Tag.TAG_COMPOUND).size();
        for (var at = previous; !at.isEmpty(); at = at.getCompound("input").getCompound(KEY)) {
            depth++;
            cells += at.getCompound("input").getList("blocks", Tag.TAG_COMPOUND).size();
        }
        if (depth >= 32 || cells > 8_000_000)
            throw new IllegalArgumentException(
                    "Applied deformation inputs exceed the area limit. Apply the area before continuing.");
        if (!previous.isEmpty()) input.put(KEY, previous);
        var saved = new CompoundTag();
        saved.put("input", input);
        saved.putString(
                "config",
                before.content().hasConfig() ? before.content().configCopy().toString() : "");
        saved.putString("settings", settled(before.settings()).toString());
        saved.putLongArray("mask", before.mask().toLongArray());
        saved.put("origin", StructureSegments.ints(before.region().origin()));
        saved.put("output_origin", StructureSegments.ints(l.region().origin()));
        saved.put("output_size", StructureSegments.ints(l.region().size()));
        saved.putString("transform", transform.encode().toString());
        var content = clean(l.content());
        var source = content.nbtCopy();
        source.put(KEY, saved);
        var settings = settled(l.settings());
        if (FittedPlaneLayers.plane(l)) settings = FittedPlaneLayers.commitTransform(l).settings();
        return state.withSource(id, new SchematicDocument(source, content.configCopy()), l.mask())
                .replace(id, l.region(), content, l.mask(), settings);
    }

    public static BlockAreaLayers.State reverse(BlockAreaLayers.State state, String id) {
        var l = state.get(id);
        if (!warp(l).identity()) return neutral(state, id);
        var saved = l.source().metadataCompound(KEY);
        if (saved.isEmpty()) return state;
        var config = saved.getString("config");
        var source =
                new SchematicDocument(
                        saved.getCompound("input"),
                        config.isEmpty() ? null : JsonStructure.parse(config).getAsJsonObject());
        var settings = JsonStructure.parse(saved.getString("settings")).getAsJsonObject();
        var origin = saved.getList("origin", Tag.TAG_INT);
        var output = saved.getList("output_origin", Tag.TAG_INT);
        var delta =
                l.region()
                        .origin()
                        .subtract(
                                new BlockPos(output.getInt(0), output.getInt(1), output.getInt(2)));
        var region =
                new SchematicRegions.Region(
                        new BlockPos(origin.getInt(0), origin.getInt(1), origin.getInt(2))
                                .offset(delta),
                        source.size());
        if (settings.has(FittedPlaneLayers.KEY))
            FittedPlaneLayers.translate(settings.getAsJsonObject(FittedPlaneLayers.KEY), delta);
        var mask = BitSet.valueOf(saved.getLongArray("mask"));
        return state.withSource(id, source, mask)
                .replace(id, region, clean(source), mask, settings);
    }

    public static SchematicDocument turnInput(
            SchematicDocument from,
            SchematicDocument turned,
            BlockPos globalSize,
            boolean right,
            net.minecraft.world.level.Level level) {
        if (!from.hasMetadata(KEY)) return turned;
        var saved = from.metadataCompound(KEY);
        var config = saved.getString("config");
        var input =
                new SchematicDocument(
                        saved.getCompound("input"),
                        config.isEmpty() ? null : JsonStructure.parse(config).getAsJsonObject());
        var transformed =
                turnInput(
                        input,
                        SchematicTurn.turn(clean(input), right, level),
                        globalSize,
                        right,
                        level);
        var beforeOrigin = vector(saved, "origin");
        var outputOrigin = vector(saved, "output_origin");
        saved.put(
                "origin",
                StructureSegments.ints(
                        SchematicTurn.region(
                                        new SchematicRegions.Region(beforeOrigin, input.size()),
                                        globalSize,
                                        right)
                                .origin()));
        var out =
                SchematicTurn.region(
                        new SchematicRegions.Region(outputOrigin, vector(saved, "output_size")),
                        globalSize,
                        right);
        saved.put("output_origin", StructureSegments.ints(out.origin()));
        saved.put("output_size", StructureSegments.ints(out.size()));
        var settings = JsonStructure.parse(saved.getString("settings")).getAsJsonObject();
        if (settings.has(FittedPlaneLayers.KEY))
            FittedPlaneLayers.turn(
                    settings.getAsJsonObject(FittedPlaneLayers.KEY), globalSize, right);
        saved.putString("settings", settings.toString());
        saved.putString(
                "transform",
                ShapeDeformation.decode(
                                JsonStructure.parse(saved.getString("transform")).getAsJsonObject())
                        .turned(right)
                        .encode()
                        .toString());
        var mask = BitSet.valueOf(saved.getLongArray("mask"));
        var rotated = new BitSet();
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            var p = AreaGeometry.position(i, input.size());
            var q =
                    right
                            ? new BlockPos(input.size().getZ() - 1 - p.getZ(), p.getY(), p.getX())
                            : new BlockPos(p.getZ(), p.getY(), input.size().getX() - 1 - p.getX());
            rotated.set(AreaGeometry.index(q, transformed.size()));
        }
        saved.putLongArray("mask", rotated.toLongArray());
        saved.put("input", transformed.nbtCopy());
        saved.putString(
                "config", transformed.hasConfig() ? transformed.configCopy().toString() : "");
        var tag = turned.nbtCopy();
        tag.put(KEY, saved);
        return new SchematicDocument(tag, turned.configCopy());
    }

    private static BlockPos vector(CompoundTag tag, String key) {
        var v = tag.getList(key, Tag.TAG_INT);
        return new BlockPos(v.getInt(0), v.getInt(1), v.getInt(2));
    }
}
