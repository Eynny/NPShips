package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.geometry.AreaGeometry;
import com.eynny.npships.authoring.geometry.FittedPlane;
import com.eynny.npships.authoring.geometry.FittedSurface;
import com.eynny.npships.authoring.geometry.ShapeDeformation;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Persistent surface definition, separate from its last voxelized result. */
public final class FittedPlaneLayers {
    public static final String KEY = "fitted_plane";

    private FittedPlaneLayers() {}

    public static boolean plane(BlockAreaLayers.Layer l) {
        return l != null && l.settings().has(KEY);
    }

    public static boolean locked(BlockAreaLayers.Layer l) {
        return plane(l) && l.settings().getAsJsonObject(KEY).get("locked").getAsBoolean();
    }

    /** User-picked reference block, independent of the fitted section's bounding-box center. */
    public static BlockPos seed(BlockAreaLayers.Layer l) {
        return pos(l.settings().getAsJsonObject(KEY).getAsJsonArray("seed"));
    }

    public static JsonObject definition(
            BlockPos seed,
            FittedPlane.Orientation orientation,
            SchematicRegions.Region frame,
            BlockState brush) {
        var o = new JsonObject();
        o.add("seed", xyz(seed));
        o.addProperty("orientation", orientation.name());
        o.addProperty("locked", false);
        o.addProperty("material", NbtUtils.writeBlockState(brush).toString());
        frame(o, frame);
        o.add("stages", new JsonArray());
        return o;
    }

    public static SchematicRegions.Region frame(JsonObject o) {
        return new SchematicRegions.Region(
                pos(o.getAsJsonArray("origin")), pos(o.getAsJsonArray("size")));
    }

    private static void frame(JsonObject o, SchematicRegions.Region frame) {
        o.add("origin", xyz(frame.origin()));
        o.add("size", xyz(frame.size()));
    }

    private static JsonArray xyz(BlockPos p) {
        var a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    private static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    public static BlockAreaLayers.State toggleLock(BlockAreaLayers.State state, String id) {
        var l = state.get(id);
        var settings = l.settings();
        var o = settings.getAsJsonObject(KEY);
        var warp = ShapeDeformation.decode(settings.getAsJsonObject("deformation"));
        if (!warp.identity()) {
            var stage = new JsonObject();
            frame(stage, frame(o));
            stage.add("deformation", warp.encode());
            stage.addProperty("span_bend", !locked(l));
            o.getAsJsonArray("stages").add(stage);
        }
        if (o.getAsJsonArray("stages").size() > 64)
            throw new IllegalArgumentException(
                    "Apply this plane before adding further transformation stages.");
        o.addProperty("locked", !locked(l));
        frame(o, l.region());
        settings.remove("rotation_frame");
        settings.add("deformation", ShapeDeformation.IDENTITY.encode());
        return state.withSource(id, l.content(), l.mask())
                .replace(id, l.region(), l.content(), l.mask(), settings);
    }

    /** Keep the exact surface map while starting the next control at zero. */
    public static BlockAreaLayers.Layer commitTransform(BlockAreaLayers.Layer l) {
        var settings = l.settings();
        var o = settings.getAsJsonObject(KEY);
        var warp = ShapeDeformation.decode(settings.getAsJsonObject("deformation"));
        if (!warp.identity()) {
            var stage = new JsonObject();
            frame(stage, frame(o));
            stage.add("deformation", warp.encode());
            stage.addProperty("span_bend", !locked(l));
            o.getAsJsonArray("stages").add(stage);
        }
        if (o.getAsJsonArray("stages").size() > 64)
            throw new IllegalArgumentException("Too many plane transformation stages.");
        frame(o, l.region());
        settings.remove("rotation_frame");
        settings.add("deformation", ShapeDeformation.IDENTITY.encode());
        return new BlockAreaLayers.Layer(
                l.id(),
                l.name(),
                l.region(),
                l.content(),
                l.mask(),
                l.content(),
                l.mask(),
                settings,
                l.applied());
    }

    public static BlockAreaLayers.State edit(AreaEditOperation.Request request) {
        var state = request.snapshot();
        var old = state.get(request.id());
        var options = request.options().deepCopy();
        var o = options.getAsJsonObject(KEY);
        var delta = request.region().origin().subtract(old.region().origin());
        if (!delta.equals(BlockPos.ZERO)) translate(o, delta);
        var oldAngles =
                ShapeDeformation.decode(old.settings().getAsJsonObject("deformation")).parameters();
        var angles = request.deformation().parameters();
        for (int i = 0; i < 3; i++)
            if (Math.abs(angles[i] - oldAngles[i]) > 1e-8) options.addProperty("sticky", false);
        o.addProperty("material", NbtUtils.writeBlockState(request.brush()).toString());
        options.remove("rotation_frame");
        return state.replace(old.id(), old.region(), old.content(), old.mask(), options)
                .refreshPlanes(old.id());
    }

    public static BlockAreaLayers.Layer rebuild(
            BlockAreaLayers.Layer l, SchematicDocument background) {
        var settings = l.settings();
        settings.remove("plane_error");
        var o = settings.getAsJsonObject(KEY);
        var stages = stages(l);
        BlockState brush;
        try {
            brush =
                    NbtUtils.readBlockState(
                            BuiltInRegistries.BLOCK.asLookup(),
                            com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                    o.get("material").getAsString()));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid fitted plane material", ex);
        }
        var result =
                FittedSurface.build(
                        background,
                        pos(o.getAsJsonArray("seed")),
                        FittedPlane.Orientation.valueOf(o.get("orientation").getAsString()),
                        stages,
                        brush);
        // A rebuilt section may be much smaller than the original search frame. Rebase only
        // an idle transform: changing the frame of a live warp would change its surface.
        if (!locked(l)
                && ShapeDeformation.decode(settings.getAsJsonObject("deformation")).identity())
            frame(o, result.region());
        return new BlockAreaLayers.Layer(
                l.id(),
                l.name(),
                result.region(),
                AreaDeformationSession.carryInput(l.source(), result.geometry().content()),
                result.geometry().mask(),
                result.geometry().content(),
                result.geometry().mask(),
                settings,
                l.applied());
    }

    private static List<FittedSurface.Stage> stages(BlockAreaLayers.Layer l) {
        var settings = l.settings();
        var o = settings.getAsJsonObject(KEY);
        var stages = new ArrayList<FittedSurface.Stage>();
        for (var v : o.getAsJsonArray("stages")) {
            var stage = v.getAsJsonObject();
            stages.add(
                    new FittedSurface.Stage(
                            frame(stage),
                            ShapeDeformation.decode(stage.getAsJsonObject("deformation")),
                            !stage.has("span_bend") || stage.get("span_bend").getAsBoolean()));
        }
        stages.add(
                new FittedSurface.Stage(
                        frame(o),
                        ShapeDeformation.decode(settings.getAsJsonObject("deformation")),
                        !locked(l)));
        return stages;
    }

    /** A nearby actual surface cell, or no marker if the reference was clipped/erased. */
    public static BlockPos marker(BlockAreaLayers.Layer l, SchematicDocument displayed) {
        if (!plane(l) || l.settings().has("plane_error")) return null;
        var point = FittedSurface.transform(stages(l)).apply(Vec3.atCenterOf(seed(l)));
        var at = BlockPos.containing(point);
        BlockPos nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    var p = at.offset(dx, dy, dz);
                    var local = p.subtract(l.region().origin());
                    if (!l.content().contains(local) || !displayed.contains(p)) continue;
                    var material = l.content().blockName(local);
                    if (material.equals("minecraft:air")
                            || material.equals("minecraft:cave_air")
                            || material.equals("minecraft:void_air")
                            || !displayed.blockName(p).equals(material)) continue;
                    double d = Vec3.atCenterOf(p).distanceToSqr(point);
                    if (d < distance) {
                        nearest = p;
                        distance = d;
                    }
                }
        return nearest;
    }

    public static void translate(JsonObject o, BlockPos offset) {
        o.add("seed", xyz(pos(o.getAsJsonArray("seed")).offset(offset)));
        var f = frame(o);
        frame(o, new SchematicRegions.Region(f.origin().offset(offset), f.size()));
        for (var v : o.getAsJsonArray("stages")) {
            var stage = v.getAsJsonObject();
            var sf = frame(stage);
            frame(stage, new SchematicRegions.Region(sf.origin().offset(offset), sf.size()));
        }
    }

    public static void turn(JsonObject o, BlockPos size, boolean right) {
        var seed = pos(o.getAsJsonArray("seed"));
        o.add(
                "seed",
                xyz(
                        right
                                ? new BlockPos(
                                        size.getZ() - 1 - seed.getZ(), seed.getY(), seed.getX())
                                : new BlockPos(
                                        seed.getZ(), seed.getY(), size.getX() - 1 - seed.getX())));
        var orientation = FittedPlane.Orientation.valueOf(o.get("orientation").getAsString());
        if (orientation != FittedPlane.Orientation.XZ)
            o.addProperty("orientation", orientation == FittedPlane.Orientation.XY ? "YZ" : "XY");
        frame(o, SchematicTurn.region(frame(o), size, right));
        for (var v : o.getAsJsonArray("stages")) {
            var stage = v.getAsJsonObject();
            frame(stage, SchematicTurn.region(frame(stage), size, right));
            stage.add(
                    "deformation",
                    ShapeDeformation.decode(stage.getAsJsonObject("deformation"))
                            .turned(right)
                            .encode());
        }
    }

    public static AreaGeometry.Result keepBounds(
            AreaGeometry.Result geometry,
            SchematicRegions.Region actual,
            SchematicRegions.Region fixed) {
        if (!fixed.contains(actual))
            throw new IllegalArgumentException(
                    "The plane size is locked. Unlock it before rotating or deforming beyond its bounds.");
        if (actual.equals(fixed)) return geometry;
        var offset = actual.origin().subtract(fixed.origin());
        var mask = new BitSet();
        for (int i = geometry.mask().nextSetBit(0); i >= 0; i = geometry.mask().nextSetBit(i + 1))
            mask.set(
                    AreaGeometry.index(
                            AreaGeometry.position(i, actual.size()).offset(offset), fixed.size()));
        var document =
                SchematicReframe.whole(geometry.content(), offset.multiply(-1), fixed.size())
                        .result();
        return new AreaGeometry.Result(document, mask);
    }
}
