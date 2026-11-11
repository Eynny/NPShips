package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.geometry.AreaGeometry;
import com.eynny.npships.authoring.geometry.DirectAreaTransform;
import com.eynny.npships.authoring.geometry.DirectDeformation;
import com.eynny.npships.authoring.geometry.RigidAreaRotation;
import com.eynny.npships.authoring.geometry.ShapeDeformation;
import com.eynny.npships.authoring.io.AuthoringCreation;
import com.eynny.npships.authoring.io.ExportProfile;
import com.eynny.npships.diagnostics.EditorWorkMetrics;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** Pure area edit, suitable for a worker. Selection and undo remain on the client thread. */
public final class AreaEditOperation {
    public record Result(
            SchematicDocument document, BlockAreaLayers.State layers, String selection) {}

    public record Request(
            BlockAreaLayers.State snapshot,
            String id,
            SchematicRegions.Region region,
            JsonObject options,
            SchematicRegions.Contents contents,
            SchematicRegions.Shape shape,
            BlockState brush,
            ShapeDeformation deformation,
            boolean generated,
            boolean hollow,
            boolean sticky,
            boolean boundsOnly,
            List<AreaGeometry.Weighted> replacements,
            long seed,
            int turns,
            double originalWeight) {
        public Request {
            options = options.deepCopy();
            replacements = List.copyOf(replacements);
        }
    }

    private AreaEditOperation() {}

    public static Result apply(Request request) {
        return apply(request, false);
    }

    /** Explicit invocation-local opt-out; existing callers always protect configured contents. */
    public static Result apply(Request request, boolean allowConfiguredLoss) {
        var snapshot = request.snapshot();
        var id = request.id();
        var region = request.region();
        var original = snapshot.get(id);
        if (!region.equals(original.region())
                || !request.deformation().identity()
                || Math.floorMod(request.turns(), 4) != 0
                || request.generated()
                || request.hollow()
                || !request.replacements().isEmpty()
                || !request.contents().blocks()
                || !request.contents().entities()
                || !request.contents().zones())
            snapshot.requireExtensionEdit("active area geometry/mask transformation");
        if (!region.equals(original.region())
                || !request.deformation().identity()
                || Math.floorMod(request.turns(), 4) != 0) {
            snapshot = snapshot.prepareForEdit(id);
            original = snapshot.get(id);
        }
        if (!request.boundsOnly()
                && request.region().equals(original.region())
                && AreaDeformationSession.inverseOfApplied(original, request.deformation()))
            return compose(AreaDeformationSession.inverseApplied(snapshot, id), id);
        boolean fixedPlane = FittedPlaneLayers.locked(original);
        if (fixedPlane && !region.size().equals(original.region().size()))
            throw new IllegalArgumentException(
                    "Unlock the fitted plane before resizing its bounds.");
        if (request.deformation().direct().mode() == DirectDeformation.Mode.SCALE
                && (request.boundsOnly() || original.settings().has("bounds_scale"))) {
            var options = request.options().deepCopy();
            options.remove(FittedPlaneLayers.KEY);
            options.remove("rotation_frame");
            options.remove("baked_shape_mask");
            options.addProperty("generated", false);
            options.addProperty("shape", "NONE");
            options.addProperty("random", false);
            options.addProperty("bounds_scale", true);
            if (!original.settings().has("bounds_scale")) {
                snapshot =
                        snapshot.withSource(
                                id,
                                AreaDeformationSession.carryInput(
                                        original.source(),
                                        AreaDeformationSession.clean(original.content())),
                                original.mask());
            }
            var baseline = snapshot.get(id);
            var geometry =
                    AreaGeometry.render(
                            AreaDeformationSession.clean(baseline.source()),
                            baseline.sourceMask(),
                            region.size(),
                            SchematicRegions.Shape.NONE,
                            false,
                            false,
                            request.brush(),
                            ShapeDeformation.IDENTITY,
                            List.of(),
                            request.seed(),
                            0,
                            1,
                            !options.has("continuous_surface")
                                    || options.get("continuous_surface").getAsBoolean(),
                            allowConfiguredLoss);
            return compose(
                    snapshot.replace(id, region, geometry.content(), geometry.mask(), options)
                            .promote(id),
                    id);
        }
        if (request.boundsOnly() || !region.size().equals(original.region().size())) {
            var expanded = snapshot.expandBounds(id, region);
            var expandedLayer = expanded.get(id);
            var expandedOptions = expandedLayer.settings().deepCopy();
            expandedOptions.addProperty("sticky", request.sticky());
            expanded =
                    expanded.replace(
                            id,
                            region,
                            expandedLayer.content(),
                            expandedLayer.mask(),
                            expandedOptions);
            if (request.sticky()) expanded = expanded.captureSticky(id, region);
            return compose(expanded.promote(id), id);
        }
        if (FittedPlaneLayers.plane(original)
                && request.deformation().direct().mode() == DirectDeformation.Mode.SCALE
                && !request.deformation().direct().identity()) {
            var options = request.options().deepCopy();
            options.remove(FittedPlaneLayers.KEY);
            options.remove("rotation_frame");
            options.remove("baked_shape_mask");
            options.addProperty("generated", false);
            options.addProperty("shape", "NONE");
            options.addProperty("random", false);
            var source =
                    AreaDeformationSession.carryInput(
                            original.source(), AreaDeformationSession.clean(original.content()));
            var detached =
                    snapshot.withSource(id, source, original.mask())
                            .replace(id, region, original.content(), original.mask(), options);
            return apply(
                    new Request(
                            detached,
                            id,
                            region,
                            options,
                            request.contents(),
                            SchematicRegions.Shape.NONE,
                            request.brush(),
                            request.deformation(),
                            false,
                            false,
                            request.sticky(),
                            false,
                            List.of(),
                            request.seed(),
                            request.turns(),
                            1),
                    allowConfiguredLoss);
        }
        if (FittedPlaneLayers.plane(original) && !FittedPlaneLayers.locked(original))
            return compose(FittedPlaneLayers.edit(request), id);
        var options = request.options().deepCopy();
        boolean continuous =
                !options.has("continuous_surface")
                        || options.get("continuous_surface").getAsBoolean();
        var angles = request.deformation().parameters();
        var oldAngles =
                ShapeDeformation.decode(original.settings().getAsJsonObject("deformation"))
                        .parameters();
        boolean rotating = false;
        for (int i = 0; i < 3; i++) rotating |= Math.abs(angles[i] - oldAngles[i]) > 1e-8;
        if (rotating) options.addProperty("sticky", false);
        boolean newShape =
                request.generated()
                        && original.settings().has("shape")
                        && !request.shape()
                                .name()
                                .equals(original.settings().get("shape").getAsString());
        if (newShape) options.remove("rotation_frame");
        var frame = newShape ? null : original.settings().getAsJsonObject("rotation_frame");
        // Legacy areas used a fitted deformation. Do not migrate their geometry on a
        // material/sticky toggle or translation; only a new rotation starts a rigid frame.
        var direct = request.deformation().direct();
        boolean directMode = direct.mode() != DirectDeformation.Mode.NONE;
        boolean framed = frame != null || rotating || directMode;
        var renderSize = region.size();
        var center = region.bounds().getCenter();
        if (frame != null) {
            var a = frame.getAsJsonArray("size");
            var c = frame.getAsJsonArray("center");
            var oldSize = original.region().size();
            renderSize =
                    new BlockPos(
                            Math.max(
                                    1,
                                    (int)
                                            Math.round(
                                                    a.get(0).getAsDouble()
                                                            * region.size().getX()
                                                            / oldSize.getX())),
                            Math.max(
                                    1,
                                    (int)
                                            Math.round(
                                                    a.get(1).getAsDouble()
                                                            * region.size().getY()
                                                            / oldSize.getY())),
                            Math.max(
                                    1,
                                    (int)
                                            Math.round(
                                                    a.get(2).getAsDouble()
                                                            * region.size().getZ()
                                                            / oldSize.getZ())));
            center =
                    new Vec3(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble())
                            .add(
                                    region.bounds()
                                            .getCenter()
                                            .subtract(original.region().bounds().getCenter()));
        }
        if (framed) {
            var f = new JsonObject();
            var a = new JsonArray();
            var c = new JsonArray();
            a.add(renderSize.getX());
            a.add(renderSize.getY());
            a.add(renderSize.getZ());
            c.add(center.x);
            c.add(center.y);
            c.add(center.z);
            f.add("size", a);
            f.add("center", c);
            options.add("rotation_frame", f);
        }
        var warpValues = angles.clone();
        if (framed) Arrays.fill(warpValues, 0, 3, 0);
        var warp = request.deformation().parameters(warpValues).direct(DirectDeformation.NONE);
        direct.validate(renderSize);
        var configured =
                snapshot.replace(
                        id, original.region(), original.content(), original.mask(), options);
        boolean capture =
                !rotating
                        && request.sticky()
                        && (!original.sticky() || !original.region().equals(region));
        // New sticky material is in the displayed coordinate frame. Rebase only when material
        // is actually absorbed; resampling it into the old rotated source would scale it twice.
        if (capture && frame != null) {
            var displayed =
                    AreaGeometry.resample(
                            original.content(),
                            original.mask(),
                            region.size(),
                            ShapeDeformation.IDENTITY,
                            continuous,
                            allowConfiguredLoss);
            var prepared =
                    configured
                            .withSource(id, displayed.content(), displayed.mask())
                            .replace(id, region, displayed.content(), displayed.mask(), options);
            var captured = prepared.captureSticky(id, region);
            if (captured != prepared) {
                var settled = captured.get(id);
                options.remove("rotation_frame");
                options.add("deformation", ShapeDeformation.IDENTITY.encode());
                options.addProperty("generated", false);
                options.addProperty("shape", "NONE");
                options.addProperty("random", false);
                return compose(
                        captured.replace(id, region, settled.content(), settled.mask(), options)
                                .promote(id),
                        id);
            }
            capture = false;
        }
        var next = capture ? configured.captureSticky(id, region) : configured;
        var layer = next.get(id);
        var source = AreaDeformationSession.clean(layer.source());
        var mask = layer.sourceMask();
        if (request.generated() && options.has("baked_shape_mask")) {
            var values = options.getAsJsonArray("baked_shape_mask");
            long[] words = new long[values.size()];
            for (int i = 0; i < words.length; i++) words[i] = values.get(i).getAsLong();
            var baked = BitSet.valueOf(words);
            var changes = new java.util.HashMap<BlockPos, net.minecraft.nbt.CompoundTag>();
            for (int i = baked.nextSetBit(0); i >= 0; i = baked.nextSetBit(i + 1))
                changes.put(AreaGeometry.position(i, source.size()), null);
            source = source.withBlocks(changes, source.paletteCopy(), source.configCopy());
            mask.andNot(baked);
            next = next.withSource(id, source, mask);
            options.remove("baked_shape_mask");
        }
        var contents = request.contents();
        if (!contents.blocks() || !contents.entities() || !contents.zones()) {
            source =
                    BlockAreaLayers.extract(
                            source,
                            new SchematicRegions.Region(BlockPos.ZERO, source.size()),
                            contents);
            if (!contents.blocks()) mask.clear();
        }
        var retained = source;
        boolean preserveSource = request.sticky() && !rotating;
        var targetSize = renderSize;
        boolean shellAfterRotation =
                framed && request.generated() && request.hollow() && !request.brush().isAir();
        var geometry =
                EditorWorkMetrics.measure(
                        EditorWorkMetrics.Phase.GEOMETRY,
                        () ->
                                AreaGeometry.render(
                                        retained,
                                        mask,
                                        targetSize,
                                        request.shape(),
                                        request.generated(),
                                        request.hollow() && !shellAfterRotation,
                                        request.brush(),
                                        warp,
                                        request.replacements(),
                                        request.seed(),
                                        request.turns(),
                                        request.originalWeight(),
                                        continuous,
                                        allowConfiguredLoss,
                                        preserveSource));
        if (framed) {
            var rotated =
                    directMode
                            ? DirectAreaTransform.apply(
                                    geometry,
                                    center,
                                    angles,
                                    direct,
                                    continuous,
                                    allowConfiguredLoss)
                            : RigidAreaRotation.apply(
                                    geometry, center, angles, continuous, allowConfiguredLoss);
            rotated.region().validate(snapshot.base());
            geometry = rotated.geometry();
            region = rotated.region();
            if (shellAfterRotation) {
                // A separate solid mask avoids hollowing captured blocks outside the procedural
                // shape.
                var empty = AuthoringCreation.empty(targetSize, ExportProfile.EMPTY);
                var solid =
                        AreaGeometry.render(
                                empty,
                                new BitSet(),
                                targetSize,
                                request.shape(),
                                true,
                                false,
                                Blocks.STONE.defaultBlockState(),
                                warp,
                                List.of(),
                                0,
                                request.turns(),
                                0,
                                continuous);
                var rotatedSolid =
                        (directMode
                                        ? DirectAreaTransform.apply(
                                                solid, center, angles, direct, continuous)
                                        : RigidAreaRotation.apply(
                                                solid, center, angles, continuous))
                                .geometry();
                geometry =
                        AreaGeometry.hollow(
                                geometry, AreaGeometry.occupied(rotatedSolid.content()));
            }
        }
        if (fixedPlane) {
            geometry = FittedPlaneLayers.keepBounds(geometry, region, request.region());
            region = request.region();
            var offset = region.origin().subtract(original.region().origin());
            if (!offset.equals(BlockPos.ZERO))
                FittedPlaneLayers.translate(options.getAsJsonObject(FittedPlaneLayers.KEY), offset);
        }
        next = next.replace(id, region, geometry.content(), geometry.mask(), options).promote(id);
        return compose(next, framed ? id : null);
    }

    private static Result compose(BlockAreaLayers.State layers, String selection) {
        return EditorWorkMetrics.measure(
                EditorWorkMetrics.Phase.COMPOSE,
                () -> {
                    var refreshed = layers.refreshPlanes(null);
                    return new Result(refreshed.compose(), refreshed, selection);
                });
    }
}
