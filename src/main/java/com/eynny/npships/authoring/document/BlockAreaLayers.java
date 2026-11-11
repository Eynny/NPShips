package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.geometry.AreaGeometry;
import com.eynny.npships.authoring.geometry.DirectAreaTransform;
import com.eynny.npships.authoring.geometry.DirectDeformation;
import com.eynny.npships.authoring.geometry.RigidAreaRotation;
import com.eynny.npships.authoring.geometry.ShapeDeformation;
import com.eynny.npships.authoring.io.AuthoringCreation;
import com.eynny.npships.authoring.io.ExportProfile;
import com.eynny.npships.authoring.patches.EntityIdentity;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.PatchInstances;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Detached, ordered editing layers. A set mask bit with no block is an explicit air stroke. */
public final class BlockAreaLayers {
    public static final String KEY = "npships:editor_areas";
    private static final String EXTERNAL = "npships:external_target";
    private static final String AREA_TARGET = "npships:area_target";
    // Outside every supported parent/segment frame; used only until link cleanup below.
    private static final BlockPos MISSING_AREA_TARGET = new BlockPos(-1024, -1024, -1024);

    private BlockAreaLayers() {}

    public record Layer(
            String id,
            String name,
            SchematicRegions.Region region,
            SchematicDocument source,
            BitSet sourceMask,
            SchematicDocument content,
            BitSet mask,
            JsonObject settings,
            boolean applied) {
        public Layer {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(region);
            Objects.requireNonNull(source);
            Objects.requireNonNull(content);
            if (!source.assemblyPoints().isEmpty() || !content.assemblyPoints().isEmpty())
                throw new IllegalArgumentException(
                        "Assembly points belong to the whole document, not layer-local frames.");
            if (!content.size().equals(region.size()))
                throw new IllegalArgumentException(
                        "Area content dimensions do not match the region.");
            sourceMask = (BitSet) sourceMask.clone();
            mask = (BitSet) mask.clone();
            settings = settings == null ? new JsonObject() : JsonStructure.copy(settings);
            validateMask(source, sourceMask);
            validateMask(content, mask);
        }

        @Override
        public BitSet mask() {
            return (BitSet) mask.clone();
        }

        @Override
        public BitSet sourceMask() {
            return (BitSet) sourceMask.clone();
        }

        @Override
        public JsonObject settings() {
            return settings.deepCopy();
        }

        public boolean sticky() {
            return settings.has("sticky") && settings.get("sticky").getAsBoolean();
        }

        public boolean transparent() {
            return settings.has("transparent") && settings.get("transparent").getAsBoolean();
        }
    }

    public static final class State {
        private final SchematicDocument base;
        private final List<Layer> layers;
        private volatile SchematicDocument composed;
        private volatile State planesRefreshed;

        private State(SchematicDocument base, List<Layer> layers) {
            this.base = base;
            this.layers = List.copyOf(layers);
            if (layers.stream().filter(l -> !l.applied).count() > 1024)
                throw new IllegalArgumentException(
                        "At most 1024 editable block areas are supported.");
            for (var l : layers) l.region.validate(base);
        }

        /** Active layer edits cannot project arbitrary provider-owned data between frames. */
        void requireExtensionEdit(String operation) {
            if (SchematicExtensions.present(base)) SchematicExtensions.refuse(base, operation);
            for (var layer : layers) {
                if (SchematicExtensions.present(layer.source))
                    SchematicExtensions.refuse(layer.source, operation);
                if (SchematicExtensions.present(layer.content))
                    SchematicExtensions.refuse(layer.content, operation);
            }
        }

        public State mirrorCreatedRemoved(State before, int axes) {
            return mirrorCreatedRemoved(before, axes, base.size());
        }

        public State mirrorCreatedRemoved(State before, int axes, BlockPos mirrorSpan) {
            return mirrorCreatedRemoved(before, axes, mirrorSpan, null);
        }

        public State mirrorCreatedRemoved(
                State before,
                int axes,
                BlockPos mirrorSpan,
                net.minecraft.world.level.Level level) {
            if (axes == 0) return this;
            var result = new ArrayList<>(layers);
            for (var previous : before.layers())
                if (find(previous.id) == null) {
                    String group =
                            previous.settings.has("symmetry_group")
                                    ? previous.settings.get("symmetry_group").getAsString()
                                    : previous.id;
                    result.removeIf(
                            l ->
                                    !l.applied
                                            && (l.id.equals(group)
                                                    || Objects.equals(
                                                            l.settings.get("symmetry_group"),
                                                            new JsonPrimitive(group))));
                }
            for (var added : layers()) {
                if (added.settings.has("symmetry_group")) continue;
                var previous = before.find(added.id);
                if (previous != null
                        && previous.content == added.content
                        && previous.region.equals(added.region)) continue;
                if (previous != null
                        && result.stream()
                                .noneMatch(
                                        l ->
                                                Objects.equals(
                                                        l.settings.get("symmetry_group"),
                                                        new JsonPrimitive(added.id)))) continue;
                result.removeIf(
                        l ->
                                Objects.equals(
                                        l.settings.get("symmetry_group"),
                                        new JsonPrimitive(added.id)));
                var seen = new HashSet<SchematicRegions.Region>();
                seen.add(added.region);
                for (int mask = 1; mask < 8; mask++) {
                    if ((mask & axes) != mask) continue;
                    var r = SchematicSymmetry.region(added.region, mirrorSpan, mask);
                    if (!seen.add(r)) continue;
                    try {
                        r.validate(base);
                    } catch (IllegalArgumentException outside) {
                        continue;
                    }
                    var content =
                            SchematicSymmetry.mirrorCopy(duplicate(added.content), mask, level);
                    var owned = new BitSet();
                    for (int i = added.mask.nextSetBit(0); i >= 0; i = added.mask.nextSetBit(i + 1))
                        owned.set(
                                index(
                                        SchematicSymmetry.point(
                                                position(i, added.region.size()),
                                                added.region.size(),
                                                mask),
                                        added.region.size()));
                    var settings = added.settings();
                    settings.addProperty("symmetry_group", added.id);
                    settings.addProperty("generated", false);
                    settings.addProperty("shape", "NONE");
                    settings.remove("rotation_frame");
                    settings.remove("baked_shape_mask");
                    settings.add("deformation", ShapeDeformation.IDENTITY.encode());
                    if (settings.has(FittedPlaneLayers.KEY)) {
                        var plane = settings.getAsJsonObject(FittedPlaneLayers.KEY);
                        plane.add("origin", SchematicPatches.vector(r.origin()));
                        plane.add(
                                "seed",
                                SchematicPatches.vector(
                                        SchematicSymmetry.point(
                                                SchematicPatches.position(
                                                        plane.getAsJsonArray("seed")),
                                                base.size(),
                                                mask)));
                    }
                    result.add(
                            new Layer(
                                    UUID.randomUUID().toString(),
                                    added.name + " (" + mask + ")",
                                    r,
                                    content,
                                    owned,
                                    content,
                                    owned,
                                    settings,
                                    false));
                }
            }
            return new State(base, result);
        }

        public boolean hasLivePlanes() {
            return layers.stream()
                    .anyMatch(
                            l ->
                                    !l.applied
                                            && FittedPlaneLayers.plane(l)
                                            && !FittedPlaneLayers.locked(l));
        }

        /**
         * One worker pass. Later live planes may use earlier planes; self/forward dependencies are
         * excluded to avoid oscillation and accidental self-support.
         */
        public State refreshPlanes(String strictId) {
            if (!hasLivePlanes()) return this;
            if (planesRefreshed != null) {
                var strict = planesRefreshed.find(strictId);
                if (strict != null && strict.settings().has("plane_error"))
                    throw new IllegalArgumentException(
                            strict.settings().get("plane_error").getAsString());
                return planesRefreshed;
            }
            var support =
                    new ArrayList<Layer>(
                            layers.stream()
                                    .filter(
                                            l ->
                                                    l.applied
                                                            || !FittedPlaneLayers.plane(l)
                                                            || FittedPlaneLayers.locked(l))
                                    .toList());
            // Rebuilt surfaces are derived from all permanent edits, including air strokes.
            // Keep them after those edits, otherwise an old deletion punches the new surface
            // again even when its support has already disappeared.
            var result = new ArrayList<Layer>(support);
            for (var layer : layers) {
                var next = layer;
                if (!layer.applied
                        && FittedPlaneLayers.plane(layer)
                        && !FittedPlaneLayers.locked(layer)) {
                    try {
                        next = FittedPlaneLayers.rebuild(layer, overlay(base, support));
                    } catch (IllegalArgumentException ex) {
                        if (layer.id.equals(strictId)) throw ex;
                        var options = layer.settings();
                        options.addProperty("plane_error", ex.getMessage());
                        next =
                                new Layer(
                                        layer.id,
                                        layer.name,
                                        layer.region,
                                        layer.source,
                                        layer.sourceMask,
                                        layer.content,
                                        layer.mask,
                                        options,
                                        layer.applied);
                    }
                    support.add(next);
                    result.add(next);
                }
            }
            var rebuilt = new State(base, result);
            rebuilt.planesRefreshed = rebuilt;
            planesRefreshed = rebuilt;
            return rebuilt;
        }

        public SchematicDocument base() {
            return base;
        }

        /**
         * Editing equivalence includes hidden sources and masks, not just composed geometry.
         * Composition and fitted-plane caches are derived; their identities are not history.
         */
        public boolean sameContents(State other) {
            if (this == other) return true;
            if (other == null
                    || layers.size() != other.layers.size()
                    || !base.sameContents(other.base)) return false;
            for (int i = 0; i < layers.size(); i++) {
                var a = layers.get(i);
                var b = other.layers.get(i);
                if (a == b) continue;
                if (!a.id.equals(b.id)
                        || !a.name.equals(b.name)
                        || !a.region.equals(b.region)
                        || a.applied != b.applied
                        || !a.sourceMask.equals(b.sourceMask)
                        || !a.mask.equals(b.mask)
                        || !a.settings.equals(b.settings)
                        || !a.source.sameContents(b.source)
                        || !a.content.sameContents(b.content)) return false;
            }
            return true;
        }

        public List<Layer> layers() {
            return layers.stream().filter(l -> !l.applied).toList();
        }

        public List<Layer> allLayers() {
            return layers;
        }

        public Layer find(String id) {
            return layers.stream().filter(l -> l.id.equals(id)).findFirst().orElse(null);
        }

        public Layer get(String id) {
            return layers.stream()
                    .filter(l -> l.id.equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown block area: " + id));
        }

        public SchematicDocument compose() {
            var result = composed;
            if (result == null) {
                result = overlay(base, layers);
                composed = result;
            }
            return result;
        }

        public State create(
                SchematicRegions.Region region,
                SchematicRegions.Contents contents,
                boolean captureExisting) {
            requireExtensionEdit("layer creation/capture");
            region.validate(base);
            var local =
                    captureExisting ? extract(compose(), region, contents) : empty(region.size());
            var nextBase = base;
            String id = UUID.randomUUID().toString();
            if (captureExisting) {
                // Transfer only base ownership. Existing area layers keep independent
                // contents/order.
                nextBase = erase(base, region, contents);
                if (contents.blocks()) nextBase = markAreaReferences(nextBase, id, region);
            }
            var list = new ArrayList<Layer>();
            for (var old : layers)
                list.add(captureExisting ? transfer(old, region, contents, id) : old);
            var settings = new JsonObject();
            settings.addProperty("sticky", false);
            settings.addProperty("generated", true);
            settings.addProperty("shape", "NONE");
            list.add(
                    new Layer(
                            id,
                            "Area " + (layers().size() + 1),
                            region,
                            local,
                            mask(local),
                            local,
                            mask(local),
                            settings,
                            false));
            return new State(nextBase, list);
        }

        /** Transfer ownership of one existing block, preserving world contents and NBT. */
        public State membership(String id, BlockPos position, boolean include) {
            var selected = get(id);
            if (!base.contains(position)) return this;
            var worldCell = new SchematicRegions.Region(position, new BlockPos(1, 1, 1));
            var blocksOnly = new SchematicRegions.Contents(true, false, false);
            if (include && !selected.region.contains(position)) {
                BlockPos lo = min(selected.region.origin(), position);
                BlockPos hi =
                        max(
                                selected.region.origin().offset(selected.region.size()),
                                position.offset(1, 1, 1));
                return expandBounds(id, new SchematicRegions.Region(lo, hi.subtract(lo)))
                        .membership(id, position, true);
            }
            if (!selected.region.contains(position)) return this;
            var local = position.subtract(selected.region.origin());
            if (!include && !selected.mask.get(index(local, selected.region.size()))) return this;
            var visible = compose();
            if (include && visible.blockName(position).equals("minecraft:air")) return this;
            var piece =
                    include
                            ? extract(visible, worldCell, blocksOnly)
                            : extract(
                                    selected.content,
                                    new SchematicRegions.Region(local, new BlockPos(1, 1, 1)),
                                    blocksOnly);
            var list = new ArrayList<Layer>();
            for (var layer : layers) {
                if (layer != selected && !include) {
                    list.add(layer);
                    continue;
                }
                if (!layer.region.contains(position)) {
                    list.add(layer);
                    continue;
                }
                BlockPos lp = position.subtract(layer.region.origin());
                var content =
                        erase(
                                layer.content,
                                new SchematicRegions.Region(lp, new BlockPos(1, 1, 1)),
                                blocksOnly);
                var mask = layer.mask();
                mask.clear(index(lp, layer.region.size()));
                if (layer == selected && include) {
                    var cell =
                            new Layer(
                                    "membership",
                                    "",
                                    new SchematicRegions.Region(lp, new BlockPos(1, 1, 1)),
                                    piece,
                                    mask(piece),
                                    piece,
                                    mask(piece),
                                    new JsonObject(),
                                    true);
                    content = overlay(content, List.of(cell));
                    mask.set(index(lp, layer.region.size()));
                }
                var settings = layer.settings();
                settings.addProperty("shape", "NONE");
                settings.addProperty("generated", false);
                settings.remove("fitted_plane");
                settings.remove("baked_shape_mask");
                settings.add("deformation", ShapeDeformation.IDENTITY.encode());
                list.add(
                        new Layer(
                                layer.id,
                                layer.name,
                                layer.region,
                                content,
                                mask,
                                content,
                                mask,
                                settings,
                                layer.applied));
            }
            var nextBase = erase(base, worldCell, blocksOnly);
            if (!include) {
                var cell =
                        new Layer(
                                "membership",
                                "",
                                worldCell,
                                piece,
                                mask(piece),
                                piece,
                                mask(piece),
                                new JsonObject(),
                                true);
                nextBase = overlay(nextBase, List.of(cell));
            }
            return new State(nextBase, list);
        }

        public State withBaseConfig(JsonObject config) {
            return new State(base.withConfig(config), layers);
        }

        /**
         * Preserve imported object ownership even where its cells overlap the base or other areas.
         */
        public State importObject(
                String name, SchematicRegions.Region region, SchematicDocument content) {
            return importObject(name, region, content, UUID.randomUUID().toString());
        }

        public State importObject(
                String name, SchematicRegions.Region region, SchematicDocument content, String id) {
            requireExtensionEdit("layer import");
            if (SchematicExtensions.present(content))
                SchematicExtensions.refuse(content, "layer import");
            region.validate(base);
            if (!region.size().equals(content.size()))
                throw new IllegalArgumentException("Imported area size mismatch");
            var settings = new JsonObject();
            settings.addProperty("sticky", false);
            settings.addProperty("generated", true);
            settings.addProperty("shape", "NONE");
            if (content.blocks().keySet().stream()
                    .anyMatch(
                            p ->
                                    content.blockEntryCopy(p)
                                            .contains(
                                                    com.eynny.npships.authoring.io.SubLevelImport
                                                            .GROUP)))
                settings.addProperty(com.eynny.npships.authoring.io.SubLevelImport.GROUP, true);
            var list = new ArrayList<>(layers);
            list.add(
                    new Layer(
                            id,
                            name,
                            region,
                            content,
                            mask(content),
                            content,
                            mask(content),
                            settings,
                            false));
            return new State(base, list);
        }

        /** Clear the area and later manual/normalization strokes inside its bounds. */
        public State clearContents(String id, JsonObject options) {
            var selected = get(id);
            var blank = empty(selected.region.size());
            var next =
                    withSource(id, blank, new BitSet())
                            .replace(id, selected.region, blank, new BitSet(), options);
            var list = new ArrayList<Layer>();
            for (var layer : next.layers) {
                if (!layer.applied || !flag(layer.settings, "edit_stroke")) {
                    list.add(layer);
                    continue;
                }
                var lo = max(layer.region.origin(), selected.region.origin());
                var hi =
                        min(
                                layer.region.origin().offset(layer.region.size()),
                                selected.region.origin().offset(selected.region.size()));
                var size = hi.subtract(lo);
                if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
                    list.add(layer);
                    continue;
                }
                var clipped = new SchematicRegions.Region(lo.subtract(layer.region.origin()), size);
                var content =
                        erase(
                                layer.content,
                                clipped,
                                new SchematicRegions.Contents(true, true, true));
                var mask = layer.mask();
                for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1))
                    if (clipped.contains(position(i, layer.region.size()))) mask.clear(i);
                list.add(
                        new Layer(
                                layer.id,
                                layer.name,
                                layer.region,
                                content,
                                mask,
                                content,
                                mask,
                                layer.settings,
                                layer.applied));
            }
            return new State(next.base, list);
        }

        /** Verify the editable layer exists; instance identities are already explicit. */
        public State prepareForEdit(String id) {
            get(id);
            return this;
        }

        public State replace(
                String id,
                SchematicRegions.Region region,
                SchematicDocument content,
                BitSet mask,
                JsonObject settings) {
            region.validate(base);
            var old = get(id);
            if (!region.equals(old.region)
                    || !content.sameContents(old.content)
                    || !mask.equals(old.mask)) {
                requireExtensionEdit("layer replacement/movement");
                if (SchematicExtensions.present(content))
                    SchematicExtensions.refuse(content, "layer replacement/movement");
            }
            var source = old.source;
            var list = new ArrayList<>(layers);
            list.set(
                    list.indexOf(old),
                    new Layer(
                            id,
                            old.name,
                            region,
                            source,
                            old.sourceMask,
                            content,
                            mask,
                            settings,
                            old.applied));
            var next = new State(base, list);
            if (region.equals(old.region)
                    && content == old.content
                    && mask.equals(old.mask)
                    && flag(settings, "replace_config") == flag(old.settings, "replace_config")
                    && flag(settings, "replace_entities") == flag(old.settings, "replace_entities"))
                next.composed = composed == null ? null : composed.historySnapshot();
            return next;
        }

        /** Enlarge bounds in world coordinates without resampling or moving existing contents. */
        public State expandBounds(String id, SchematicRegions.Region region) {
            var old = get(id);
            region.validate(base);
            var placed = relative(old, region.origin());
            var content = overlay(empty(region.size()), List.of(placed));
            var keptEntities = new ListTag();
            for (var value : content.entitiesCopy())
                if (entityInside(
                        (CompoundTag) value,
                        new SchematicRegions.Region(BlockPos.ZERO, region.size())))
                    keptEntities.add(value);
            content = content.withEntities(keptEntities);
            var mask =
                    translateMask(
                            old.mask,
                            old.region.size(),
                            old.region.origin().subtract(region.origin()),
                            region.size());
            var settings = old.settings();
            if (settings.has("baked_shape_mask")
                    || flag(settings, "generated")
                            && settings.has("shape")
                            && !settings.get("shape").getAsString().equals("NONE")) {
                // Remember which baked cells belonged to the figure, so selecting a new figure
                // can replace it without deleting separately captured source blocks.
                var figure = old.mask();
                if (settings.has("baked_shape_mask")) {
                    var values = settings.getAsJsonArray("baked_shape_mask");
                    long[] words = new long[values.size()];
                    for (int i = 0; i < words.length; i++) words[i] = values.get(i).getAsLong();
                    figure = BitSet.valueOf(words);
                } else {
                    var warp = ShapeDeformation.decode(settings.getAsJsonObject("deformation"));
                    var targetSize = old.region.size();
                    var angles = warp.parameters();
                    var frame = settings.getAsJsonObject("rotation_frame");
                    if (frame != null) {
                        var a = frame.getAsJsonArray("size");
                        targetSize =
                                new BlockPos(
                                        a.get(0).getAsInt(),
                                        a.get(1).getAsInt(),
                                        a.get(2).getAsInt());
                        var unrotated = angles.clone();
                        Arrays.fill(unrotated, 0, 3, 0);
                        warp = warp.parameters(unrotated);
                    }
                    var direct = warp.direct();
                    var retained =
                            AreaGeometry.resample(
                                    old.source,
                                    old.sourceMask,
                                    targetSize,
                                    warp.direct(DirectDeformation.NONE),
                                    !settings.has("continuous_surface")
                                            || settings.get("continuous_surface").getAsBoolean());
                    if (frame != null) {
                        var c = frame.getAsJsonArray("center");
                        var center =
                                new Vec3(
                                        c.get(0).getAsDouble(),
                                        c.get(1).getAsDouble(),
                                        c.get(2).getAsDouble());
                        retained =
                                direct.mode() == DirectDeformation.Mode.NONE
                                        ? RigidAreaRotation.apply(
                                                        retained,
                                                        old.region.bounds().getCenter(),
                                                        angles,
                                                        !settings.has("continuous_surface")
                                                                || settings.get(
                                                                                "continuous_surface")
                                                                        .getAsBoolean())
                                                .geometry()
                                        : DirectAreaTransform.apply(
                                                        retained,
                                                        center,
                                                        angles,
                                                        direct,
                                                        !settings.has("continuous_surface")
                                                                || settings.get(
                                                                                "continuous_surface")
                                                                        .getAsBoolean())
                                                .geometry();
                    }
                    figure.andNot(retained.mask());
                }
                figure =
                        translateMask(
                                figure,
                                old.region.size(),
                                old.region.origin().subtract(region.origin()),
                                region.size());
                var encoded = new JsonArray();
                for (long word : figure.toLongArray()) encoded.add(word);
                settings.add("baked_shape_mask", encoded);
            }
            settings.addProperty("random", false);
            settings.remove("deformation");
            settings.remove("rotation_frame");
            settings.remove(FittedPlaneLayers.KEY);
            var spilled = old.mask();
            for (int i = spilled.nextSetBit(0); i >= 0; i = spilled.nextSetBit(i + 1))
                if (region.contains(old.region.origin().offset(position(i, old.region.size()))))
                    spilled.clear(i);
            var outsideEntities = new ListTag();
            for (var value : old.content.entitiesCopy())
                if (!entityInside(
                        translateEntity((CompoundTag) value, old.region.origin()), region))
                    outsideEntities.add(value.copy());
            var list = new ArrayList<>(layers);
            list.remove(old);
            if (!spilled.isEmpty() || !outsideEntities.isEmpty()) {
                var released = old.content.withEntities(outsideEntities);
                list.add(
                        new Layer(
                                UUID.randomUUID().toString(),
                                "Released area contents",
                                old.region,
                                released,
                                spilled,
                                released,
                                spilled,
                                new JsonObject(),
                                true));
            }
            list.add(
                    new Layer(id, old.name, region, content, mask, content, mask, settings, false));
            return new State(base, list);
        }

        public State withSource(String id, SchematicDocument source, BitSet sourceMask) {
            var old = get(id);
            var list = new ArrayList<>(layers);
            list.set(
                    list.indexOf(old),
                    new Layer(
                            id,
                            old.name,
                            old.region,
                            source,
                            sourceMask,
                            old.content,
                            old.mask,
                            old.settings,
                            old.applied));
            return new State(base, list);
        }

        public State rename(String id, String name) {
            if (name.isBlank() || name.length() > 80)
                throw new IllegalArgumentException("Area name must contain 1..80 characters.");
            var old = get(id);
            var list = new ArrayList<>(layers);
            list.set(
                    list.indexOf(old),
                    new Layer(
                            id,
                            name,
                            old.region,
                            old.source,
                            old.sourceMask,
                            old.content,
                            old.mask,
                            old.settings,
                            old.applied));
            return new State(base, list);
        }

        public State promote(String id) {
            var old = get(id);
            var list = new ArrayList<>(layers);
            list.remove(old);
            // Selecting a layer changes its priority among areas, not the history of manual edits.
            // In particular, selecting supports to build a plane must not resurrect deleted cells.
            int at = list.size();
            while (at > 0
                    && list.get(at - 1).applied
                    && flag(list.get(at - 1).settings, "edit_stroke")) at--;
            list.add(at, old);
            return list.equals(layers) ? this : new State(base, list);
        }

        public State remove(String id) {
            requireExtensionEdit("layer removal");
            var old = get(id);
            var list = new ArrayList<>(layers);
            list.remove(old);
            var next = new State(base, list);
            return next.layers().isEmpty()
                    ? new State(cleanEmpty(next.compose()), List.of())
                    : next;
        }

        public State apply(String id) {
            var old = get(id);
            int sourceIndex = layers.indexOf(old);
            // Only an enclosing lower layer can absorb this area. Independent nested or
            // partially overlapping areas retain their own input, settings and priority.
            var targets =
                    layers.subList(0, sourceIndex).stream()
                            .filter(l -> !l.applied && l.region.contains(old.region))
                            .toList();
            var masks = new LinkedHashMap<String, BitSet>();
            masks.put("", new BitSet());
            for (var target : targets) masks.put(target.id, new BitSet());
            for (int i = old.mask.nextSetBit(0); i >= 0; i = old.mask.nextSetBit(i + 1)) {
                var world = old.region.origin().offset(position(i, old.region.size()));
                String owner = "";
                for (var target : targets)
                    if (target.region
                            .bounds()
                            .contains(net.minecraft.world.phys.Vec3.atCenterOf(world)))
                        owner = target.id;
                masks.get(owner).set(i);
            }
            var list = new ArrayList<>(layers);
            list.remove(old);
            for (var entry : masks.entrySet()) {
                var positions = new HashSet<BlockPos>();
                var blocks = new ListTag();
                for (int i = entry.getValue().nextSetBit(0);
                        i >= 0;
                        i = entry.getValue().nextSetBit(i + 1)) {
                    var p = position(i, old.region.size());
                    positions.add(p);
                    var block = old.content.blockEntryCopy(p);
                    if (block != null) blocks.add(block);
                }
                var entities = new ListTag();
                for (var raw : old.content.entitiesCopy()) {
                    var e = (CompoundTag) raw;
                    var xyz = e.getList("pos", 6);
                    var point =
                            new net.minecraft.world.phys.Vec3(
                                    xyz.getDouble(0) + old.region.origin().getX(),
                                    xyz.getDouble(1) + old.region.origin().getY(),
                                    xyz.getDouble(2) + old.region.origin().getZ());
                    String owner = "";
                    for (var target : targets)
                        if (target.region.bounds().contains(point)) owner = target.id;
                    if (owner.equals(entry.getKey())) entities.add(e.copy());
                }
                var nbt = old.content.metadataCopy();
                nbt.put("blocks", blocks);
                nbt.put("entities", entities);
                var fragmentConfig = localConfig(old.content.configCopy(), positions);
                for (String zoneKey : List.of("segments", "crew"))
                    if (old.content.configCopy() != null && old.content.configCopy().has(zoneKey)) {
                        var ownedZones = new JsonArray();
                        for (var zoneEntry : old.content.configCopy().getAsJsonArray(zoneKey)) {
                            var zone = zoneEntry.getAsJsonObject();
                            var world =
                                    old.region
                                            .origin()
                                            .offset(
                                                    SchematicPatches.position(
                                                            zone.getAsJsonArray("origin")));
                            String owner = "";
                            for (var target : targets)
                                if (target.region
                                        .bounds()
                                        .contains(net.minecraft.world.phys.Vec3.atCenterOf(world)))
                                    owner = target.id;
                            if (owner.equals(entry.getKey())) ownedZones.add(zone.deepCopy());
                        }
                        if (!ownedZones.isEmpty()) fragmentConfig.add(zoneKey, ownedZones);
                    }
                if (entry.getValue().isEmpty()
                        && entities.isEmpty()
                        && !fragmentConfig.has("crew")
                        && !fragmentConfig.has("segments")) continue;
                var fragment = new SchematicDocument(nbt, fragmentConfig);
                var incoming =
                        new Layer(
                                old.id,
                                old.name,
                                old.region,
                                fragment,
                                entry.getValue(),
                                fragment,
                                entry.getValue(),
                                new JsonObject(),
                                true);
                if (entry.getKey().isEmpty()) {
                    // Free fragments keep the source's original place below higher layers.
                    list.add(sourceIndex, incoming);
                    continue;
                }
                var target = get(entry.getKey());
                var merged =
                        overlay(
                                empty(target.region.size()),
                                List.of(
                                        relative(target, target.region.origin()),
                                        relative(incoming, target.region.origin())));
                var mask = target.mask();
                mask.or(
                        translateMask(
                                entry.getValue(),
                                old.region.size(),
                                old.region.origin().subtract(target.region.origin()),
                                target.region.size()));
                var options = target.settings();
                options.addProperty("generated", false);
                options.addProperty("shape", "NONE");
                options.remove("rotation_frame");
                options.remove("deformation");
                options.remove("baked_shape_mask");
                options.addProperty("random", false);
                options.remove(FittedPlaneLayers.KEY);
                list.set(
                        list.indexOf(target),
                        new Layer(
                                target.id,
                                target.name,
                                target.region,
                                merged,
                                mask,
                                merged,
                                mask,
                                options,
                                false));
            }
            var next = new State(base, list);
            return next.layers().isEmpty() ? new State(next.compose(), List.of()) : next;
        }

        /**
         * Absorb enclosed permanent blocks with incoming priority. Other areas remain independent.
         */
        public State captureSticky(String id, SchematicRegions.Region region) {
            var old = get(id);
            region.validate(base);
            if (!old.sticky()) return this;
            var canonical =
                    AreaGeometry.resample(
                            old.source,
                            old.sourceMask,
                            region.size(),
                            ShapeDeformation.IDENTITY,
                            !old.settings.has("continuous_surface")
                                    || old.settings.get("continuous_surface").getAsBoolean());
            var content = canonical.content();
            var owned = canonical.mask();
            var permanent = overlay(base, layers.stream().filter(Layer::applied).toList());
            var incoming =
                    extract(permanent, region, new SchematicRegions.Contents(true, true, false));
            var additions = mask(incoming);
            // Capturing transfers permanent material, including cells already owned by this
            // area. The captured block and its data replace the previous local cell together.
            if (additions.isEmpty() && incoming.entityCount() == 0) return this;
            var layer =
                    new Layer(
                            "incoming",
                            "",
                            new SchematicRegions.Region(BlockPos.ZERO, region.size()),
                            incoming,
                            additions,
                            incoming,
                            additions,
                            new JsonObject(),
                            true);
            var merged = overlay(content, List.of(layer));
            owned.or(additions);
            var nextBase = eraseMask(base, region, additions, false);
            if (incoming.entityCount() > 0) {
                var tag = nextBase.nbtCopy();
                var entities = new ListTag();
                for (var value : nextBase.entitiesCopy())
                    if (!entityInside((CompoundTag) value, region)) entities.add(value);
                tag.put("entities", entities);
                nextBase = new SchematicDocument(tag, nextBase.configCopy());
            }
            var list = new ArrayList<Layer>();
            for (var existing : layers)
                list.add(
                        existing == old
                                ? new Layer(
                                        id,
                                        old.name,
                                        region,
                                        merged,
                                        owned,
                                        merged,
                                        owned,
                                        old.settings,
                                        old.applied)
                                : existing.applied
                                        ? transferMask(existing, region, additions)
                                        : existing);
            return new State(nextBase, list);
        }

        public State merge(String sourceId, String targetId, boolean subtract) {
            requireExtensionEdit("layer merge/subtraction");
            if (sourceId.equals(targetId))
                throw new IllegalArgumentException("Choose another area.");
            var source = get(sourceId);
            var target = get(targetId);
            var region = subtract ? target.region : union(source.region, target.region);
            region.validate(base);
            var targetPlaced = relative(target, region.origin());
            var sourcePlaced = relative(source, region.origin());
            var own =
                    translateMask(
                            target.mask,
                            target.region.size(),
                            target.region.origin().subtract(region.origin()),
                            region.size());
            var incoming =
                    translateMask(
                            subtract ? mask(source.content) : source.mask,
                            source.region.size(),
                            source.region.origin().subtract(region.origin()),
                            region.size());
            if (subtract) {
                var occupied = mask(target.content);
                occupied.and(incoming);
                if (occupied.isEmpty())
                    throw new IllegalArgumentException("The areas have no overlapping blocks.");
            }
            SchematicDocument merged;
            if (subtract) {
                merged = overlay(empty(region.size()), List.of(targetPlaced));
                merged =
                        eraseMask(
                                merged,
                                new SchematicRegions.Region(BlockPos.ZERO, region.size()),
                                incoming);
                own.or(incoming);
            } else {
                merged = overlay(empty(region.size()), List.of(targetPlaced, sourcePlaced));
                own.or(incoming);
            }
            var settings = target.settings();
            settings.addProperty("generated", false);
            settings.remove("deformation");
            settings.remove("rotation_frame");
            settings.remove(FittedPlaneLayers.KEY);
            settings.addProperty("random", false);
            var list = new ArrayList<>(layers);
            list.remove(source);
            list.remove(target);
            if (subtract) {
                for (int i = 0; i < list.size(); i++) {
                    var stroke = list.get(i);
                    if (stroke.applied && flag(stroke.settings, "edit_stroke"))
                        list.set(i, excludeCutCells(stroke, region, incoming));
                }
            }
            int at = list.size();
            while (at > 0
                    && list.get(at - 1).applied
                    && flag(list.get(at - 1).settings, "edit_stroke")) at--;
            list.add(
                    at,
                    new Layer(
                            target.id,
                            target.name,
                            region,
                            merged,
                            own,
                            merged,
                            own,
                            settings,
                            false));
            return new State(base, list);
        }

        /**
         * Cut occupied source cells from the permanent schematic, preserving other editable areas.
         */
        public State subtractFromBase(String sourceId) {
            var source = get(sourceId);
            var cut = mask(source.content);
            var list = new ArrayList<>(layers);
            list.remove(source);
            for (int i = 0; i < list.size(); i++) {
                var layer = list.get(i);
                if (layer.applied) list.set(i, excludeCutCells(layer, source.region, cut));
            }
            return new State(eraseMask(base, source.region, cut), list);
        }

        /** Cut each occupied cutter cell from its highest owning layer, otherwise from the base. */
        public State subtractTop(String sourceId) {
            var source = get(sourceId);
            var cut = mask(source.content);
            var list = new ArrayList<>(layers);
            list.remove(source);
            var cuts = new java.util.HashMap<String, BitSet>();
            var baseCut = new BitSet();
            for (int i = cut.nextSetBit(0); i >= 0; i = cut.nextSetBit(i + 1)) {
                var world = source.region.origin().offset(position(i, source.region.size()));
                boolean owned = false;
                for (int j = list.size() - 1; j >= 0; j--) {
                    var layer = list.get(j);
                    if (!layer.region.contains(world)) continue;
                    var local = world.subtract(layer.region.origin());
                    int cell = index(local, layer.region.size());
                    if (!layer.mask.get(cell)
                            || layer.content.blockName(local).equals("minecraft:air")) continue;
                    cuts.computeIfAbsent(layer.id, unused -> new BitSet()).set(cell);
                    owned = true;
                    break;
                }
                if (!owned) baseCut.set(i);
            }
            for (int j = 0; j < list.size(); j++) {
                var layer = list.get(j);
                var cells = cuts.get(layer.id);
                if (cells == null) continue;
                var content =
                        eraseMask(
                                layer.content,
                                new SchematicRegions.Region(BlockPos.ZERO, layer.region.size()),
                                cells);
                var own = layer.mask();
                own.or(cells); // Explicit air keeps lower layers from showing through the cut.
                var settings = layer.settings();
                settings.addProperty("generated", false);
                settings.addProperty("random", false);
                settings.remove("deformation");
                settings.remove("rotation_frame");
                settings.remove("baked_shape_mask");
                settings.remove(FittedPlaneLayers.KEY);
                list.set(
                        j,
                        new Layer(
                                layer.id,
                                layer.name,
                                layer.region,
                                content,
                                own,
                                content,
                                own,
                                settings,
                                layer.applied));
            }
            return new State(eraseMask(base, source.region, baseCut), list);
        }

        /** Precommit guard shared by retained synchronization and the accepted editor owner. */
        public void requireConfiguration(SchematicDocument candidate) {
            if (!layers.isEmpty()) requireConfigurationObject(candidate);
        }

        /** Record accepted edits above retained areas without mutating their original frames. */
        public State synchronize(SchematicDocument before, SchematicDocument after) {
            if (before == after) return this;
            if (layers.isEmpty()) return new State(after, List.of());
            requireConfiguration(after);
            if (!base.size().equals(after.size()))
                throw new IllegalArgumentException(
                        "Resize block areas with the schematic before synchronizing.");
            // Accepted provider data belongs to the document frame, never an Edit stroke.
            var nextBase =
                    base.hasMetadata(SchematicExtensions.KEY)
                                    || after.hasMetadata(SchematicExtensions.KEY)
                            ? base.withExtensionMetadata(
                                    after.metadataReadOnly(SchematicExtensions.KEY))
                            : base;
            var beforeConfig = before.configCopy();
            var afterConfig = after.configCopy();
            var baseConfig = nextBase.configCopy();
            if (baseConfig == null) baseConfig = new JsonObject();
            String pointsKey = com.eynny.npships.authoring.AssemblyPoints.KEY;
            if (afterConfig.has(pointsKey))
                baseConfig.add(pointsKey, afterConfig.get(pointsKey).deepCopy());
            else baseConfig.remove(pointsKey);
            nextBase = nextBase.withConfig(baseConfig);
            // Independent global coordinates do not belong to a layer-local Edit stroke.
            if (beforeConfig != null) beforeConfig.remove(pointsKey);
            afterConfig.remove(pointsKey);
            var changed = after.changedBlocks(before, SchematicDocument.MAX_BLOCKS);
            if (changed == null) {
                changed = new HashSet<>(before.blocks().keySet());
                changed.addAll(after.blocks().keySet());
            }
            boolean entities = !before.entitiesCopy().equals(after.entitiesCopy());
            boolean config = !Objects.equals(beforeConfig, afterConfig);
            if (changed.isEmpty() && !entities && !config)
                return nextBase == base ? this : new State(nextBase, layers);
            var list = new ArrayList<>(layers);
            var mask = new BitSet();
            if (!list.isEmpty()) {
                var previous = list.getLast();
                // Preserve unsupported saved local frames so Save can still refuse them.
                if (previous.applied
                        && flag(previous.settings, "edit_stroke")
                        && !previous.source.hasMetadata(SchematicExtensions.KEY)
                        && !previous.content.hasMetadata(SchematicExtensions.KEY)) {
                    list.removeLast();
                    mask.or(previous.mask);
                    entities |= flag(previous.settings, "replace_entities");
                    config |= flag(previous.settings, "replace_config");
                }
            }
            for (var p : changed) mask.set(index(p, after.size()));
            var tag = after.metadataCopy();
            tag.remove(KEY);
            tag.remove(SchematicExtensions.KEY);
            tag.remove(com.eynny.npships.authoring.AssemblyPoints.EMBEDDED);
            var blocks = new ListTag();
            var positions = new HashSet<BlockPos>();
            for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
                var p = position(i, after.size());
                positions.add(p);
                var block = after.blockEntryCopy(p);
                if (block != null) blocks.add(block);
            }
            tag.put("blocks", blocks);
            if (!entities) tag.put("entities", new ListTag());
            var settings = new JsonObject();
            settings.addProperty("replace_config", config);
            settings.addProperty("replace_entities", entities);
            settings.addProperty("edit_stroke", true);
            var stroke =
                    new SchematicDocument(
                            tag, config ? afterConfig : localConfig(after.configCopy(), positions));
            list.add(
                    new Layer(
                            UUID.randomUUID().toString(),
                            "Edit",
                            new SchematicRegions.Region(BlockPos.ZERO, base.size()),
                            stroke,
                            mask,
                            stroke,
                            mask,
                            settings,
                            true));
            return new State(nextBase, list);
        }

        public State turn(boolean right, net.minecraft.world.level.Level level) {
            if (!layers.isEmpty()) requireExtensionEdit("whole rotation with retained layers");
            var list = new ArrayList<Layer>();
            for (var l : layers) {
                var oldContent = l.content;
                var oldSource = l.source;
                var content =
                        turnReferences(
                                SchematicTurn.turn(oldContent, right, level),
                                layers,
                                base.size(),
                                right);
                var source =
                        l.source == l.content
                                ? content
                                : turnReferences(
                                        SchematicTurn.turn(oldSource, right, level),
                                        layers,
                                        base.size(),
                                        right);
                source =
                        AreaDeformationSession.turnInput(
                                l.source, source, base.size(), right, level);
                var settings = l.settings();
                if (settings.has("baked_shape_mask")) {
                    var values = settings.getAsJsonArray("baked_shape_mask");
                    long[] words = new long[values.size()];
                    for (int i = 0; i < words.length; i++) words[i] = values.get(i).getAsLong();
                    var rotated = turnMask(BitSet.valueOf(words), l.source.size(), right);
                    var encoded = new JsonArray();
                    for (long word : rotated.toLongArray()) encoded.add(word);
                    settings.add("baked_shape_mask", encoded);
                }
                if (settings.has(FittedPlaneLayers.KEY))
                    FittedPlaneLayers.turn(
                            settings.getAsJsonObject(FittedPlaneLayers.KEY), base.size(), right);
                if (settings.has("rotation_frame")) {
                    var f = settings.getAsJsonObject("rotation_frame");
                    var a = f.getAsJsonArray("size");
                    var c = f.getAsJsonArray("center");
                    var dims = new JsonArray();
                    dims.add(a.get(2));
                    dims.add(a.get(1));
                    dims.add(a.get(0));
                    var center = new JsonArray();
                    center.add(
                            right
                                    ? base.size().getZ() - c.get(2).getAsDouble()
                                    : c.get(2).getAsDouble());
                    center.add(c.get(1));
                    center.add(
                            right
                                    ? c.get(0).getAsDouble()
                                    : base.size().getX() - c.get(0).getAsDouble());
                    f.add("size", dims);
                    f.add("center", center);
                }
                list.add(
                        new Layer(
                                l.id,
                                l.name,
                                SchematicTurn.region(l.region, base.size(), right),
                                source,
                                turnMask(l.sourceMask, l.source.size(), right),
                                content,
                                turnMask(l.mask, l.content.size(), right),
                                settings,
                                l.applied));
            }
            return new State(
                    turnReferences(
                            SchematicTurn.turn(base, right, level), layers, base.size(), right),
                    list);
        }

        /** Called only after the normal schematic resize/destructive-change confirmation. */
        public State reframe(BlockPos origin, BlockPos size) {
            if (!layers.isEmpty()) requireExtensionEdit("whole reframe with retained layers");
            var list = new ArrayList<Layer>();
            for (var l : layers) {
                var shifted = l.region.origin().subtract(origin);
                var lo = max(shifted, BlockPos.ZERO);
                var hi = min(shifted.offset(l.region.size()), size);
                var extent = hi.subtract(lo);
                if (extent.getX() <= 0 || extent.getY() <= 0 || extent.getZ() <= 0) continue;
                var cropOrigin = lo.subtract(shifted);
                var content = SchematicReframe.whole(l.content, cropOrigin, extent).result();
                // After cropping, rendered contents become the new undeformed source to avoid
                // restoring clipped cells.
                var mask = translateMask(l.mask, l.region.size(), cropOrigin.multiply(-1), extent);
                var settings = l.settings();
                settings.addProperty("generated", false);
                settings.remove("baked_shape_mask");
                settings.remove("deformation");
                settings.remove("rotation_frame");
                settings.remove(FittedPlaneLayers.KEY);
                list.add(
                        new Layer(
                                l.id,
                                l.name,
                                new SchematicRegions.Region(lo, extent),
                                content,
                                mask,
                                content,
                                mask,
                                settings,
                                l.applied));
            }
            return new State(SchematicReframe.whole(base, origin, size).result(), list);
        }

        public SchematicDocument save() {
            var flat = compose();
            if (layers.isEmpty()) return strip(flat);
            var data = new CompoundTag();
            data.putInt("version", 1);
            data.put("base", packDocument(base));
            data.putString("base_config", json(base.configCopy()));
            var entries = new ListTag();
            for (var layer : layers) {
                var item = new CompoundTag();
                item.putString("id", layer.id);
                item.putString("name", layer.name);
                item.put("origin", StructureSegments.ints(layer.region.origin()));
                item.put("content", packDocument(layer.content));
                item.putString("config", json(layer.content.configCopy()));
                item.putLongArray("mask", layer.mask.toLongArray());
                if (layer.source != layer.content) {
                    item.put("source", packDocument(layer.source));
                    item.putString("source_config", json(layer.source.configCopy()));
                    item.putLongArray("source_mask", layer.sourceMask.toLongArray());
                }
                item.putString("settings", layer.settings.toString());
                item.putBoolean("applied", layer.applied);
                entries.add(item);
            }
            data.put("layers", entries);
            var tag = flat.nbtCopy();
            tag.put(KEY, data);
            return new SchematicDocument(tag, flat.configCopy()).inheritPatchValidation(flat);
        }
    }

    public static State load(SchematicDocument document) {
        var tag = document.nbtCopy();
        if (!tag.contains(KEY, Tag.TAG_COMPOUND)) return new State(document, List.of());
        var data = tag.getCompound(KEY);
        if (data.getInt("version") != 1)
            throw new IllegalArgumentException("Unsupported saved block-area version.");
        var base =
                unpackDocument(data.getCompound("base"), parse(data.getString("base_config")))
                        .inheritPatchValidation(document);
        // Public whole-document metadata edits can arrive after the retained snapshot was saved.
        // The accepted global marker field wins over an older private layer-base snapshot.
        var acceptedConfig = document.configCopy();
        var baseConfig = base.configCopy();
        String pointsKey = com.eynny.npships.authoring.AssemblyPoints.KEY;
        if (acceptedConfig != null && acceptedConfig.has(pointsKey)
                || baseConfig != null && baseConfig.has(pointsKey)) {
            if (baseConfig == null) baseConfig = new JsonObject();
            if (acceptedConfig != null && acceptedConfig.has(pointsKey))
                baseConfig.add(pointsKey, acceptedConfig.get(pointsKey).deepCopy());
            else baseConfig.remove(pointsKey);
            base = base.withConfig(baseConfig);
        }
        // Global provider data belongs to the global base, not a layer-local coordinate frame.
        if (document.hasMetadata(SchematicExtensions.KEY)) {
            var global = document.metadataCompound(SchematicExtensions.KEY);
            if (base.hasMetadata(SchematicExtensions.KEY)
                    && !base.metadataCompound(SchematicExtensions.KEY).equals(global))
                throw new IllegalArgumentException(
                        "Conflicting global/base author-data envelopes in saved layers; original is unchanged");
            base = base.withExtensionMetadata(global);
        }
        var layers = new ArrayList<Layer>();
        var ids = new HashSet<String>();
        for (var value : data.getList("layers", Tag.TAG_COMPOUND)) {
            var item = (CompoundTag) value;
            var content =
                    unpackDocument(item.getCompound("content"), parse(item.getString("config")));
            var source =
                    item.contains("source")
                            ? unpackDocument(
                                    item.getCompound("source"),
                                    parse(item.getString("source_config")))
                            : content;
            var mask = BitSet.valueOf(item.getLongArray("mask"));
            var sourceMask =
                    item.contains("source")
                            ? BitSet.valueOf(item.getLongArray("source_mask"))
                            : mask;
            var id = item.getString("id");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate block-area ID.");
            layers.add(
                    new Layer(
                            id,
                            item.getString("name"),
                            new SchematicRegions.Region(
                                    vector(item.getList("origin", Tag.TAG_INT)), content.size()),
                            source,
                            sourceMask,
                            content,
                            mask,
                            parse(item.getString("settings")),
                            item.getBoolean("applied")));
        }
        return new State(base, layers);
    }

    public static int index(BlockPos p, BlockPos size) {
        return (p.getY() * size.getZ() + p.getZ()) * size.getX() + p.getX();
    }

    public static BlockPos position(int index, BlockPos size) {
        return new BlockPos(
                index % size.getX(),
                index / (size.getX() * size.getZ()),
                index / size.getX() % size.getZ());
    }

    public static BitSet mask(SchematicDocument document) {
        var result = new BitSet();
        for (var p : document.blocks().keySet())
            if (!document.blockName(p).equals("minecraft:air"))
                result.set(index(p, document.size()));
        return result;
    }

    /**
     * Copy local contents without reusing native CC identities or links outside the copied object.
     */
    public static SchematicDocument duplicate(SchematicDocument source) {
        if (SchematicExtensions.present(source))
            SchematicExtensions.refuse(
                    source,
                    "standalone layer duplication; use supported document-region copy instead");
        var tag = source.nbtCopy();
        tag.remove(KEY);
        var config = SchematicPatches.authoringConfig(source);
        var patchRemap = new PatchInstances.Remap();
        var patches = PatchInstances.copy(SchematicPatches.read(config), false, patchRemap);
        for (var patch : patches) {
            var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
            for (var target : PatchDevices.targets(patch))
                if (!source.contains(target) || !source.blocks().containsKey(target))
                    PatchDevices.removeTarget(patch, owner, target);
            clearReferenceMarkers(patch);
            com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.copied(
                    patch,
                    com.eynny.npships.authoring.patches.PatchSourceOperations.CopyMode.LAYER);
        }
        setPatches(config, patches);
        EntityPatches.cloneAll(tag, config, patchRemap);
        for (var value : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            if (block.contains("nbt"))
                PatchDevices.clearAddresses(
                        source.blockName(SchematicPatches.blockPosition(block)),
                        block.getCompound("nbt"));
        }
        for (var value : tag.getList("entities", Tag.TAG_COMPOUND))
            clearEntityIdentity(((CompoundTag) value).getCompound("nbt"));
        SchematicPatches.embed(tag, config);
        return EntityPatches.document(tag, config);
    }

    public static BitSet resizeMask(BitSet mask, BlockPos oldSize, BlockPos size) {
        return translateMask(mask, oldSize, BlockPos.ZERO, size);
    }

    private static BitSet turnMask(BitSet mask, BlockPos size, boolean right) {
        var turned = new BlockPos(size.getZ(), size.getY(), size.getX());
        var out = new BitSet();
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            var p = position(i, size);
            var q =
                    right
                            ? new BlockPos(size.getZ() - 1 - p.getZ(), p.getY(), p.getX())
                            : new BlockPos(p.getZ(), p.getY(), size.getX() - 1 - p.getX());
            out.set(index(q, turned));
        }
        return out;
    }

    private static BitSet translateMask(BitSet mask, BlockPos from, BlockPos delta, BlockPos size) {
        var result = new BitSet();
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            var p = position(i, from).offset(delta);
            if (inside(p, size)) result.set(index(p, size));
        }
        return result;
    }

    public static SchematicDocument reframeLocal(SchematicDocument source, BlockPos size) {
        if (!source.assemblyPoints().isEmpty())
            throw new IllegalArgumentException("Reframe assembly points with the whole document.");
        if (source.size().equals(size)) return source;
        if (SchematicExtensions.present(source))
            SchematicExtensions.refuse(
                    source, "standalone layer resize; use whole-document reframe instead");
        var tag = source.metadataCopy();
        tag.put("size", StructureSegments.ints(size));
        var blocks = new ListTag();
        for (var p : source.blocks().keySet())
            if (inside(p, size)) blocks.add(source.blockEntryCopy(p));
        tag.put("blocks", blocks);
        var region = new SchematicRegions.Region(BlockPos.ZERO, size);
        var entities = new ListTag();
        for (var value : source.entitiesCopy())
            if (entityInside((CompoundTag) value, region)) entities.add(value);
        tag.put("entities", entities);
        var config =
                localConfig(
                        source.configCopy(),
                        source.blocks().keySet().stream()
                                .filter(p -> inside(p, size))
                                .collect(java.util.stream.Collectors.toSet()));
        if (source.hasConfig())
            for (String key : List.of("segments", "crew"))
                if (source.configCopy().has(key)) {
                    var list = new JsonArray();
                    for (var value : source.configCopy().getAsJsonArray(key)) {
                        var o = value.getAsJsonObject();
                        if (region.contains(
                                new SchematicRegions.Region(
                                        SchematicPatches.position(o.getAsJsonArray("origin")),
                                        SchematicPatches.position(o.getAsJsonArray("size")))))
                            list.add(o);
                    }
                    config.add(key, list);
                }
        return EntityPatches.document(tag, config);
    }

    private static int patchSchema(SchematicDocument doc) {
        int version = doc.patchSchema(false);
        if (version != 0 && version != 2)
            throw new IllegalArgumentException("Unsupported patch version");
        for (var patch : doc.templatePatches())
            SchematicPatches.schema(doc, SchematicPatches.position(patch.getAsJsonArray("pos")));
        return 2;
    }

    private static int patchSchema(JsonObject config) {
        if (config != null && config.has("patches")) SchematicPatches.read(config);
        return 2;
    }

    private static void requireConfigurationObject(SchematicDocument document) {
        if (!document.hasConfig())
            throw new IllegalArgumentException(
                    "Retained block areas require an object configuration. Use {} to clear its settings.");
    }

    private static SchematicDocument overlay(SchematicDocument base, List<Layer> layers) {
        if (layers.isEmpty()) return base;
        var tag = base.metadataCopy();
        tag.remove(KEY);
        var palette = base.paletteCopy();
        var indices = new HashMap<CompoundTag, Integer>();
        for (int i = 0; i < palette.size(); i++) indices.put(palette.getCompound(i), i);
        var blocks = new LinkedHashMap<BlockPos, CompoundTag>();
        for (var p : base.blocks().keySet()) blocks.put(p, base.blockEntryCopy(p));
        var config = base.configCopy();
        if (config == null) config = new JsonObject();
        var entities = base.entitiesCopy();
        int blockVersion = patchSchema(base), entityVersion = EntityPatches.schema(base);
        for (var layer : layers) {
            blockVersion = Math.max(blockVersion, patchSchema(layer.content));
            entityVersion = Math.max(entityVersion, EntityPatches.schema(layer.content));
        }
        var entityPatches = new LinkedHashMap<String, JsonObject>();
        for (var patch : EntityPatches.entries(base)) {
            if (entityVersion == 2)
                patch = PatchInstances.materialize(patch, true, EntityPatches.schema(base));
            entityPatches.put(PatchInstances.key(patch, true, entityVersion), patch);
        }
        var geometryPatches = new LinkedHashMap<String, JsonObject>();
        for (var patch :
                com.eynny.npships.authoring.patches.ExternalPatches.authoringEntries(base)) {
            if (blockVersion == 2)
                patch =
                        PatchInstances.materialize(
                                patch,
                                false,
                                SchematicPatches.schema(
                                        base,
                                        SchematicPatches.position(patch.getAsJsonArray("pos"))));
            geometryPatches.put(PatchInstances.key(patch, false, blockVersion), patch);
        }
        for (var layer : layers) {
            var changed = new HashSet<BlockPos>();
            var localPalette = layer.content.paletteCopy();
            for (int i = layer.mask.nextSetBit(0); i >= 0; i = layer.mask.nextSetBit(i + 1)) {
                var local = position(i, layer.region.size());
                var p = layer.region.origin().offset(local);
                if (!base.contains(p)) continue;
                changed.add(p);
                var b = layer.content.blockEntryCopy(local);
                if (b == null || layer.content.blockName(local).equals("minecraft:air")) {
                    blocks.remove(p);
                    continue;
                }
                var state = localPalette.getCompound(b.getInt("state"));
                var stateIndex = indices.get(state);
                if (stateIndex == null) {
                    stateIndex = palette.size();
                    palette.add(state);
                    indices.put(state, stateIndex);
                }
                b.putInt("state", stateIndex);
                b.put("pos", StructureSegments.ints(p));
                if (b.contains("nbt")) {
                    var n = b.getCompound("nbt");
                    n.putInt("x", p.getX());
                    n.putInt("y", p.getY());
                    n.putInt("z", p.getZ());
                }
                String physicalKey = com.eynny.npships.authoring.io.SubLevelImport.GROUP;
                if (flag(layer.settings, physicalKey)) b.putString(physicalKey, layer.id);
                else if (!b.contains(physicalKey)
                        && blocks.containsKey(p)
                        && blocks.get(p).contains(physicalKey))
                    b.putString(physicalKey, blocks.get(p).getString(physicalKey));
                else if (flag(layer.settings, "edit_stroke") && !blocks.containsKey(p)) {
                    for (var owner : layers) {
                        if (owner == layer) break;
                        if (flag(owner.settings, physicalKey) && owner.region.contains(p))
                            b.putString(physicalKey, owner.id);
                    }
                }
                blocks.put(p, b);
            }
            // Track device ownership independently of old whole-config snapshots in edit strokes.
            geometryPatches
                    .values()
                    .removeIf(
                            p ->
                                    changed.contains(
                                            SchematicPatches.position(p.getAsJsonArray("pos"))));
            for (var patch :
                    com.eynny.npships.authoring.patches.ExternalPatches.authoringEntries(
                            layer.content)) {
                var local = SchematicPatches.position(patch.getAsJsonArray("pos"));
                if (blockVersion == 2)
                    patch =
                            PatchInstances.materialize(
                                    patch, false, SchematicPatches.schema(layer.content, local));
                if (!inside(local, layer.region.size())
                        || !layer.mask.get(index(local, layer.region.size()))) continue;
                var global = local.offset(layer.region.origin());
                resolveExternal(patch, global);
                patch.add("pos", SchematicPatches.vector(global));
                geometryPatches.put(PatchInstances.key(patch, false, blockVersion), patch);
            }
            boolean replaceConfig = flag(layer.settings, "replace_config");
            if (replaceConfig) {
                requireConfigurationObject(layer.content);
                config = layer.content.configCopy();
            } else
                config =
                        mergeConfig(
                                config,
                                layer.content.configCopy(),
                                layer.region.origin(),
                                changed,
                                layer.mask,
                                layer.region.size());
            if (flag(layer.settings, "replace_entities")) entities = layer.content.entitiesCopy();
            else
                for (var value : layer.content.entitiesCopy())
                    entities.add(translateEntity((CompoundTag) value, layer.region.origin()));
            if (flag(layer.settings, "replace_entities")) entityPatches.clear();
            var rootIds = new HashSet<java.util.UUID>();
            for (var value : layer.content.entitiesCopy()) {
                var id = EntityIdentity.read(((CompoundTag) value).getCompound("nbt"));
                if (id != null) rootIds.add(id);
            }
            for (var patch : EntityPatches.entries(layer.content))
                if (rootIds.contains(EntityPatches.token(patch))) {
                    if (entityVersion == 2)
                        patch =
                                PatchInstances.materialize(
                                        patch, true, EntityPatches.schema(layer.content));
                    entityPatches.put(PatchInstances.key(patch, true, entityVersion), patch);
                }
            var sourceVariants = layer.content.extraCompoundCopy(StructureSegments.VARIANTS);
            var variants = tag.getCompound(StructureSegments.VARIANTS);
            for (var key : sourceVariants.getAllKeys())
                variants.put(key, sourceVariants.get(key).copy());
            if (!variants.isEmpty()) tag.put(StructureSegments.VARIANTS, variants);
        }
        if (blocks.size() > SchematicDocument.MAX_BLOCKS)
            throw new IllegalArgumentException("Combined areas exceed the schematic block limit.");
        var list = new ListTag();
        blocks.values().forEach(list::add);
        tag.put("blocks", list);
        tag.put("palette", palette);
        tag.remove("palettes");
        tag.put("entities", entities);
        if (!entityPatches.isEmpty() || config.has(EntityPatches.KEY))
            EntityPatches.set(config, new ArrayList<>(entityPatches.values()), entityVersion);
        // Remove endpoints covered by an air stroke instead of retaining dangling device links.
        var patches = new ArrayList<>(SchematicPatches.read(config));
        patches.removeIf(
                p -> {
                    var owner = SchematicPatches.position(p.getAsJsonArray("pos"));
                    var block = blocks.get(owner);
                    return block == null
                            || block.getBoolean(SchematicPatches.UNPATCHED)
                            || !palette.getCompound(block.getInt("state"))
                                    .getString("Name")
                                    .equals(p.get("block").getAsString());
                });
        var owners = new HashSet<String>();
        for (var patch : patches) owners.add(PatchInstances.key(patch, false, blockVersion));
        for (var candidate : geometryPatches.entrySet()) {
            var block =
                    blocks.get(
                            SchematicPatches.position(candidate.getValue().getAsJsonArray("pos")));
            if (block != null
                    && !block.getBoolean(SchematicPatches.UNPATCHED)
                    && !owners.contains(candidate.getKey())
                    && palette.getCompound(block.getInt("state"))
                            .getString("Name")
                            .equals(candidate.getValue().get("block").getAsString())) {
                owners.add(candidate.getKey());
                patches.add(candidate.getValue());
            }
        }
        for (var patch : patches) {
            var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
            resolveAreaReference(patch, owner, layers);
            for (var end : PatchDevices.targets(patch))
                if (end.equals(MISSING_AREA_TARGET)
                        || !blocks.containsKey(end)
                                && (!base.hasPatchValidation() || base.contains(end)))
                    PatchDevices.removeTarget(patch, owner, end);
            // The grouped writer below embeds every instance at an anchor exactly once.
        }
        String pointsKey = com.eynny.npships.authoring.AssemblyPoints.KEY;
        var authoritativeConfig = base.configCopy();
        if (authoritativeConfig != null && authoritativeConfig.has(pointsKey))
            config.add(pointsKey, authoritativeConfig.get(pointsKey).deepCopy());
        else {
            config.remove(pointsKey);
            tag.remove(com.eynny.npships.authoring.AssemblyPoints.EMBEDDED);
        }
        SchematicPatches.set(config, patches, blockVersion);
        SchematicPatches.embed(tag, config);
        return EntityPatches.document(tag, config).inheritPatchValidation(base);
    }

    private static JsonObject mergeConfig(
            JsonObject target,
            JsonObject local,
            BlockPos origin,
            Set<BlockPos> changed,
            BitSet mask,
            BlockPos size) {
        var result = target.deepCopy();
        var loot = new ArrayList<LootAssignments.Entry>();
        for (var e : LootAssignments.read(target)) if (!changed.contains(e.pos())) loot.add(e);
        for (var e : LootAssignments.read(local))
            if (inside(e.pos(), size) && mask.get(index(e.pos(), size)))
                loot.add(new LootAssignments.Entry(e.pos().offset(origin), e.table(), e.rolls()));
        result.remove(LootAssignments.KEY);
        for (var e : loot) result = LootAssignments.assign(result, e.pos(), e.table(), e.rolls());
        int targetVersion = patchSchema(target), localVersion = patchSchema(local);
        int patchVersion = Math.max(targetVersion, localVersion);
        var patches = new ArrayList<JsonObject>();
        for (var p : SchematicPatches.read(target))
            if (!changed.contains(SchematicPatches.position(p.getAsJsonArray("pos"))))
                patches.add(
                        patchVersion == 2
                                ? PatchInstances.materialize(p, false, targetVersion)
                                : p);
        for (var p : SchematicPatches.read(local)) {
            var owner = SchematicPatches.position(p.getAsJsonArray("pos"));
            if (!inside(owner, size) || !mask.get(index(owner, size))) continue;
            var global = owner.offset(origin);
            if (patchVersion == 2) p = PatchInstances.materialize(p, false, localVersion);
            resolveExternal(p, global);
            p.add("pos", SchematicPatches.vector(global));
            patches.add(p);
        }
        SchematicPatches.set(result, patches, patchVersion);
        if (local != null) {
            for (var e : EmbeddedLoot.read(local).entrySet())
                result = EmbeddedLoot.assign(result, e.getKey(), e.getValue().getAsJsonObject());
            for (String key : List.of("segments", "crew"))
                if (local.has(key)) {
                    var byId = new LinkedHashMap<String, JsonObject>();
                    if (result.has(key))
                        for (var v : result.getAsJsonArray(key)) {
                            var o = v.getAsJsonObject();
                            byId.put(o.get("id").getAsString(), o);
                        }
                    for (var v : local.getAsJsonArray(key)) {
                        var o = v.getAsJsonObject().deepCopy();
                        o.add(
                                "origin",
                                SchematicPatches.vector(
                                        SchematicPatches.position(o.getAsJsonArray("origin"))
                                                .offset(origin)));
                        byId.put(o.get("id").getAsString(), o);
                    }
                    var values = new JsonArray();
                    byId.values().forEach(values::add);
                    result.add(key, values);
                }
        }
        return result;
    }

    public static SchematicDocument extract(
            SchematicDocument doc,
            SchematicRegions.Region region,
            SchematicRegions.Contents contents) {
        if (SchematicExtensions.present(doc))
            SchematicExtensions.refuse(
                    doc, "layer-local extraction; use whole-document crop instead");
        region.validate(doc);
        var tag = doc.metadataCopy();
        tag.remove(KEY);
        tag.remove(com.eynny.npships.authoring.AssemblyPoints.EMBEDDED);
        tag.put("size", StructureSegments.ints(region.size()));
        var blocks = new ListTag();
        var positions = new HashSet<BlockPos>();
        if (contents.blocks())
            for (var p : doc.blocks().keySet())
                if (region.contains(p)) {
                    var b = doc.blockEntryCopy(p);
                    var local = p.subtract(region.origin());
                    b.put("pos", StructureSegments.ints(local));
                    if (b.contains("nbt")) {
                        var n = b.getCompound("nbt");
                        n.putInt("x", local.getX());
                        n.putInt("y", local.getY());
                        n.putInt("z", local.getZ());
                    }
                    blocks.add(b);
                    positions.add(p);
                }
        tag.put("blocks", blocks);
        var entities = new ListTag();
        if (contents.entities())
            for (var value : doc.entitiesCopy()) {
                var e = (CompoundTag) value;
                if (entityInside(e, region))
                    entities.add(translateEntity(e, region.origin().multiply(-1)));
            }
        tag.put("entities", entities);
        var config = new JsonObject();
        for (var entry : EmbeddedLoot.read(doc.configCopy()).entrySet())
            config =
                    EmbeddedLoot.assign(config, entry.getKey(), entry.getValue().getAsJsonObject());
        for (var entry : LootAssignments.read(doc.configCopy()))
            if (positions.contains(entry.pos()))
                config =
                        LootAssignments.assign(
                                config,
                                entry.pos().subtract(region.origin()),
                                entry.table(),
                                entry.rolls());
        var patches = new ArrayList<JsonObject>();
        var selected =
                com.eynny.npships.authoring.patches.ExternalPatches.authoringEntries(doc).stream()
                        .filter(
                                p ->
                                        positions.contains(
                                                SchematicPatches.position(p.getAsJsonArray("pos"))))
                        .toList();
        int patchVersion = 2;
        for (var patch : selected)
            patchVersion =
                    Math.max(
                            patchVersion,
                            SchematicPatches.schema(
                                    doc, SchematicPatches.position(patch.getAsJsonArray("pos"))));
        for (var patch : selected) {
            var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
            if (patchVersion == 2)
                patch =
                        PatchInstances.materialize(
                                patch, false, SchematicPatches.schema(doc, owner));
            markExternal(patch, owner, region);
            patch.add("pos", SchematicPatches.vector(owner.subtract(region.origin())));
            patches.add(patch);
        }
        SchematicPatches.set(config, patches, patchVersion);
        if (contents.zones() && doc.hasConfig())
            for (String key : List.of("segments", "crew"))
                if (doc.configCopy().has(key)) {
                    var values = new JsonArray();
                    for (var value : doc.configCopy().getAsJsonArray(key)) {
                        var o = value.getAsJsonObject();
                        var bounds =
                                new SchematicRegions.Region(
                                        SchematicPatches.position(o.getAsJsonArray("origin")),
                                        SchematicPatches.position(o.getAsJsonArray("size")));
                        if (region.contains(bounds)) {
                            o = o.deepCopy();
                            o.add(
                                    "origin",
                                    SchematicPatches.vector(
                                            bounds.origin().subtract(region.origin())));
                            values.add(o);
                        }
                    }
                    config.add(key, values);
                }
        return EntityPatches.document(tag, config);
    }

    private static SchematicDocument erase(
            SchematicDocument base,
            SchematicRegions.Region region,
            SchematicRegions.Contents contents) {
        var mask = new BitSet();
        if (contents.blocks())
            for (var p : base.blocks().keySet())
                if (region.contains(p)) mask.set(index(p.subtract(region.origin()), region.size()));
        var result = eraseMask(base, region, mask, false);
        var tag = result.nbtCopy();
        var config = result.configCopy();
        if (contents.entities()) {
            var entities = new ListTag();
            for (var value : base.entitiesCopy())
                if (!entityInside((CompoundTag) value, region)) entities.add(value);
            tag.put("entities", entities);
        }
        if (contents.zones())
            for (String key : List.of("segments", "crew"))
                if (config.has(key)) {
                    var keep = new JsonArray();
                    for (var value : config.getAsJsonArray(key)) {
                        var o = value.getAsJsonObject();
                        if (!region.contains(
                                new SchematicRegions.Region(
                                        SchematicPatches.position(o.getAsJsonArray("origin")),
                                        SchematicPatches.position(o.getAsJsonArray("size")))))
                            keep.add(o);
                    }
                    config.add(key, keep);
                }
        return EntityPatches.document(tag, config);
    }

    private static SchematicDocument eraseMask(
            SchematicDocument base, SchematicRegions.Region region, BitSet mask) {
        return eraseMask(base, region, mask, true);
    }

    private static SchematicDocument eraseMask(
            SchematicDocument base,
            SchematicRegions.Region region,
            BitSet mask,
            boolean removeLinks) {
        var changes = new HashMap<BlockPos, CompoundTag>();
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            var p = region.origin().offset(position(i, region.size()));
            if (base.contains(p)) changes.put(p, null);
        }
        var config = base.configCopy();
        if (config == null) config = new JsonObject();
        var loot = LootAssignments.read(config);
        config.remove(LootAssignments.KEY);
        for (var e : loot)
            if (!changes.containsKey(e.pos()))
                config = LootAssignments.assign(config, e.pos(), e.table(), e.rolls());
        var patches = new ArrayList<JsonObject>();
        for (var p : SchematicPatches.read(config)) {
            var owner = SchematicPatches.position(p.getAsJsonArray("pos"));
            if (changes.containsKey(owner)) continue;
            if (removeLinks)
                for (var end : PatchDevices.targets(p))
                    if (changes.containsKey(end)) PatchDevices.removeTarget(p, owner, end);
            patches.add(p);
        }
        setPatches(config, patches);
        return base.withBlocks(changes, base.paletteCopy(), config);
    }

    private static JsonObject localConfig(JsonObject config, Set<BlockPos> positions) {
        var result = new JsonObject();
        if (config == null) return result;
        for (var e : LootAssignments.read(config))
            if (positions.contains(e.pos()))
                result = LootAssignments.assign(result, e.pos(), e.table(), e.rolls());
        var patches = new ArrayList<JsonObject>();
        for (var p : SchematicPatches.read(config))
            if (positions.contains(SchematicPatches.position(p.getAsJsonArray("pos"))))
                patches.add(p);
        setPatches(result, patches);
        for (var e : EmbeddedLoot.read(config).entrySet())
            result = EmbeddedLoot.assign(result, e.getKey(), e.getValue().getAsJsonObject());
        return result;
    }

    private static Layer relative(Layer layer, BlockPos origin) {
        return new Layer(
                layer.id,
                layer.name,
                new SchematicRegions.Region(
                        layer.region.origin().subtract(origin), layer.region.size()),
                layer.source,
                layer.sourceMask,
                layer.content,
                layer.mask,
                layer.settings,
                true);
    }

    private static Layer transfer(
            Layer layer,
            SchematicRegions.Region region,
            SchematicRegions.Contents contents,
            String newId) {
        var lo = max(layer.region.origin(), region.origin());
        var hi =
                min(
                        layer.region.origin().offset(layer.region.size()),
                        region.origin().offset(region.size()));
        var size = hi.subtract(lo);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return layer;
        var clipped = new SchematicRegions.Region(lo.subtract(layer.region.origin()), size);
        var content = erase(layer.content, clipped, contents);
        var mask = layer.mask();
        if (contents.blocks())
            for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1))
                if (clipped.contains(position(i, layer.region.size()))) mask.clear(i);
        content =
                markAreaReferences(
                        content,
                        newId,
                        new SchematicRegions.Region(
                                region.origin().subtract(layer.region.origin()), region.size()));
        var settings = layer.settings();
        if (!layer.applied) {
            settings.addProperty("generated", false);
            settings.addProperty("shape", "NONE");
            settings.remove("deformation");
            settings.remove("rotation_frame");
            settings.remove("baked_shape_mask");
            settings.remove(FittedPlaneLayers.KEY);
        }
        return new Layer(
                layer.id,
                layer.name,
                layer.region,
                content,
                mask,
                content,
                mask,
                settings,
                layer.applied);
    }

    /** Cutting blocks must not discard unrelated entities stored in a manual-edit layer. */
    private static Layer excludeCutCells(Layer layer, SchematicRegions.Region region, BitSet cut) {
        var mask = layer.mask();
        for (int i = cut.nextSetBit(0); i >= 0; i = cut.nextSetBit(i + 1)) {
            var world = region.origin().offset(position(i, region.size()));
            if (layer.region.contains(world))
                mask.clear(index(world.subtract(layer.region.origin()), layer.region.size()));
        }
        return new Layer(
                layer.id,
                layer.name,
                layer.region,
                layer.source,
                layer.sourceMask,
                layer.content,
                mask,
                layer.settings,
                layer.applied);
    }

    private static Layer transferMask(
            Layer layer, SchematicRegions.Region region, BitSet captured) {
        var localMask = new BitSet();
        for (int i = captured.nextSetBit(0); i >= 0; i = captured.nextSetBit(i + 1)) {
            var world = region.origin().offset(position(i, region.size()));
            if (layer.region.contains(world))
                localMask.set(index(world.subtract(layer.region.origin()), layer.region.size()));
        }
        var content =
                eraseMask(
                        layer.content,
                        new SchematicRegions.Region(BlockPos.ZERO, layer.region.size()),
                        localMask,
                        false);
        var mask = layer.mask();
        mask.andNot(localMask);
        if (layer.content.entityCount() > 0) {
            var tag = content.nbtCopy();
            var entities = new ListTag();
            for (var value : layer.content.entitiesCopy()) {
                var world = entityPosition((CompoundTag) value).offset(layer.region.origin());
                if (!region.contains(world)) entities.add(value);
            }
            tag.put("entities", entities);
            content = EntityPatches.document(tag, content.configCopy());
        }
        return new Layer(
                layer.id,
                layer.name,
                layer.region,
                content,
                mask,
                content,
                mask,
                layer.settings,
                layer.applied);
    }

    private static SchematicRegions.Region union(
            SchematicRegions.Region a, SchematicRegions.Region b) {
        var p =
                new BlockPos(
                        Math.min(a.origin().getX(), b.origin().getX()),
                        Math.min(a.origin().getY(), b.origin().getY()),
                        Math.min(a.origin().getZ(), b.origin().getZ()));
        var hi =
                new BlockPos(
                        Math.max(
                                a.origin().getX() + a.size().getX(),
                                b.origin().getX() + b.size().getX()),
                        Math.max(
                                a.origin().getY() + a.size().getY(),
                                b.origin().getY() + b.size().getY()),
                        Math.max(
                                a.origin().getZ() + a.size().getZ(),
                                b.origin().getZ() + b.size().getZ()));
        return new SchematicRegions.Region(p, hi.subtract(p));
    }

    private static void validateMask(SchematicDocument doc, BitSet mask) {
        long volume = (long) doc.size().getX() * doc.size().getY() * doc.size().getZ();
        if (mask.length() > volume)
            throw new IllegalArgumentException("Block-area mask is outside its bounds.");
    }

    private static boolean inside(BlockPos p, BlockPos size) {
        return p.getX() >= 0
                && p.getY() >= 0
                && p.getZ() >= 0
                && p.getX() < size.getX()
                && p.getY() < size.getY()
                && p.getZ() < size.getZ();
    }

    private static boolean flag(JsonObject settings, String key) {
        return settings.has(key) && settings.get(key).getAsBoolean();
    }

    private static SchematicDocument empty(BlockPos size) {
        return AuthoringCreation.empty(size, ExportProfile.EMPTY);
    }

    private static SchematicDocument strip(SchematicDocument doc) {
        var tag = doc.nbtCopy();
        if (!tag.contains(KEY)) return doc;
        tag.remove(KEY);
        return EntityPatches.document(tag, doc.configCopy()).inheritPatchValidation(doc);
    }

    private static String json(JsonObject value) {
        return value == null ? "null" : value.toString();
    }

    private static JsonObject parse(String text) {
        var element = JsonStructure.parse(text);
        return element.isJsonNull() ? null : element.getAsJsonObject();
    }

    private static BlockPos vector(ListTag list) {
        return new BlockPos(list.getInt(0), list.getInt(1), list.getInt(2));
    }

    private static BlockPos entityPosition(CompoundTag tag) {
        var p = tag.getList("pos", Tag.TAG_DOUBLE);
        return BlockPos.containing(p.getDouble(0), p.getDouble(1), p.getDouble(2));
    }

    private static boolean entityInside(CompoundTag tag, SchematicRegions.Region region) {
        var data = tag.getCompound("nbt");
        var p = tag.getList("pos", Tag.TAG_DOUBLE);
        var box =
                SchematicGlue.bounds(
                        data,
                        new net.minecraft.world.phys.Vec3(
                                p.getDouble(0), p.getDouble(1), p.getDouble(2)));
        if (box != null)
            return region.bounds().contains(box.minX, box.minY, box.minZ)
                    && region.bounds().contains(box.maxX - 1e-7, box.maxY - 1e-7, box.maxZ - 1e-7);
        return region.contains(entityPosition(tag));
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
    }

    private static CompoundTag translateEntity(CompoundTag original, BlockPos delta) {
        var e = original.copy();
        var from = e.getList("pos", Tag.TAG_DOUBLE);
        var pos = new ListTag();
        pos.add(DoubleTag.valueOf(from.getDouble(0) + delta.getX()));
        pos.add(DoubleTag.valueOf(from.getDouble(1) + delta.getY()));
        pos.add(DoubleTag.valueOf(from.getDouble(2) + delta.getZ()));
        e.put("pos", pos);
        var point = new Vec3(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2));
        e.put(
                "blockPos",
                StructureSegments.ints(PreviewEntityData.translatedAnchor(e, point, delta)));
        return e;
    }

    private static void setPatches(JsonObject config, List<JsonObject> entries) {
        SchematicPatches.set(config, entries);
    }

    private static void markExternal(
            JsonElement value, BlockPos owner, SchematicRegions.Region region) {
        if (com.eynny.npships.authoring.patches.ExternalPatches.external(value)) return;
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (o.has("offset")) {
                var p = owner.offset(SchematicPatches.position(o.getAsJsonArray("offset")));
                if (!region.contains(p)) o.add(EXTERNAL, SchematicPatches.vector(p));
            }
            for (var e : o.entrySet())
                if (!e.getKey().equals(EXTERNAL)) markExternal(e.getValue(), owner, region);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) markExternal(e, owner, region);
    }

    private static void resolveExternal(JsonElement value, BlockPos owner) {
        if (com.eynny.npships.authoring.patches.ExternalPatches.external(value)) return;
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (o.has(EXTERNAL)) {
                o.add(
                        "offset",
                        SchematicPatches.vector(
                                SchematicPatches.position(o.getAsJsonArray(EXTERNAL))
                                        .subtract(owner)));
                o.remove(EXTERNAL);
            }
            for (var e : o.entrySet()) resolveExternal(e.getValue(), owner);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) resolveExternal(e, owner);
    }

    private static SchematicDocument markAreaReferences(
            SchematicDocument document, String id, SchematicRegions.Region region) {
        var config = document.configCopy();
        var patches = SchematicPatches.read(config);
        for (var patch : patches)
            markAreaReference(
                    patch, SchematicPatches.position(patch.getAsJsonArray("pos")), id, region);
        setPatches(config, patches);
        return document.withBlocks(Map.of(), document.paletteCopy(), config);
    }

    private static void markAreaReference(
            JsonElement value, BlockPos owner, String id, SchematicRegions.Region region) {
        if (com.eynny.npships.authoring.patches.ExternalPatches.external(value)) return;
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (o.has("offset")) {
                var target = owner.offset(SchematicPatches.position(o.getAsJsonArray("offset")));
                if (region.contains(target)) {
                    var reference = new JsonObject();
                    reference.addProperty("area", id);
                    reference.add("pos", SchematicPatches.vector(target.subtract(region.origin())));
                    o.add(AREA_TARGET, reference);
                }
            }
            for (var e : o.entrySet())
                if (!e.getKey().equals(AREA_TARGET))
                    markAreaReference(e.getValue(), owner, id, region);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) markAreaReference(e, owner, id, region);
    }

    private static void resolveAreaReference(
            JsonElement value, BlockPos owner, List<Layer> layers) {
        if (com.eynny.npships.authoring.patches.ExternalPatches.external(value)) return;
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (o.has(AREA_TARGET)) {
                var ref = o.getAsJsonObject(AREA_TARGET);
                var layer =
                        layers.stream()
                                .filter(l -> l.id.equals(ref.get("area").getAsString()))
                                .findFirst()
                                .orElse(null);
                var target =
                        layer == null
                                ? MISSING_AREA_TARGET
                                : layer.region
                                        .origin()
                                        .offset(
                                                SchematicPatches.position(
                                                        ref.getAsJsonArray("pos")));
                o.add("offset", SchematicPatches.vector(target.subtract(owner)));
                o.remove(AREA_TARGET);
            }
            for (var e : o.entrySet()) resolveAreaReference(e.getValue(), owner, layers);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) resolveAreaReference(e, owner, layers);
    }

    private static SchematicDocument cleanEmpty(SchematicDocument doc) {
        var config = doc.configCopy();
        var patches = SchematicPatches.read(config);
        if (patches.isEmpty()) return doc;
        var kept = new ArrayList<JsonObject>();
        for (var patch : patches) {
            var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
            if (!doc.blocks().containsKey(owner)) continue;
            resolveAreaReference(patch, owner, List.of());
            for (var target : PatchDevices.targets(patch))
                if (target.equals(MISSING_AREA_TARGET)
                        || !doc.blocks().containsKey(target)
                                && (!doc.hasPatchValidation() || doc.contains(target)))
                    PatchDevices.removeTarget(patch, owner, target);
            kept.add(patch);
        }
        setPatches(config, kept);
        var changes = new HashMap<BlockPos, CompoundTag>();
        SchematicBlocks.embedChanges(doc, changes, config);
        return doc.withBlocks(changes, doc.paletteCopy(), config);
    }

    private static SchematicDocument turnReferences(
            SchematicDocument document, List<Layer> layers, BlockPos size, boolean right) {
        var config = document.configCopy();
        if (config == null) return document;
        turnReferenceData(config, layers, size, right);
        return document.withBlocks(Map.of(), document.paletteCopy(), config);
    }

    private static void turnReferenceData(
            JsonElement value, List<Layer> layers, BlockPos size, boolean right) {
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (ExternalPatches.external(o)) return;
            if (o.has(EXTERNAL))
                o.add(
                        EXTERNAL,
                        SchematicPatches.vector(
                                turnPoint(
                                        SchematicPatches.position(o.getAsJsonArray(EXTERNAL)),
                                        size,
                                        right)));
            if (o.has(AREA_TARGET)) {
                var ref = o.getAsJsonObject(AREA_TARGET);
                var layer =
                        layers.stream()
                                .filter(l -> l.id.equals(ref.get("area").getAsString()))
                                .findFirst()
                                .orElse(null);
                if (layer != null)
                    ref.add(
                            "pos",
                            SchematicPatches.vector(
                                    turnPoint(
                                            SchematicPatches.position(ref.getAsJsonArray("pos")),
                                            layer.region.size(),
                                            right)));
            }
            for (var e : o.entrySet())
                if (!Set.of(EXTERNAL, AREA_TARGET).contains(e.getKey()))
                    turnReferenceData(e.getValue(), layers, size, right);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) turnReferenceData(e, layers, size, right);
    }

    private static BlockPos turnPoint(BlockPos p, BlockPos size, boolean right) {
        return right
                ? new BlockPos(size.getZ() - 1 - p.getZ(), p.getY(), p.getX())
                : new BlockPos(p.getZ(), p.getY(), size.getX() - 1 - p.getX());
    }

    private static void clearReferenceMarkers(JsonElement value) {
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (ExternalPatches.external(o)) return;
            o.remove(EXTERNAL);
            o.remove(AREA_TARGET);
            for (var e : o.entrySet()) clearReferenceMarkers(e.getValue());
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) clearReferenceMarkers(e);
    }

    private static void clearEntityIdentity(CompoundTag data) {
        data.remove("UUID");
        data.remove("UUIDMost");
        data.remove("UUIDLeast");
        for (var child : data.getList("Passengers", Tag.TAG_COMPOUND))
            clearEntityIdentity((CompoundTag) child);
    }

    /**
     * Hidden editing snapshots avoid repeating vanilla's compound/list allocation for every voxel.
     */
    private static CompoundTag packDocument(SchematicDocument document) {
        var tag = new CompoundTag();
        tag.put("metadata", document.metadataCopy());
        var positions = new int[document.blocks().size()];
        var states = new int[positions.length];
        var extras = new ListTag();
        int i = 0;
        for (var entry : document.blocks().entrySet()) {
            positions[i] = index(entry.getKey(), document.size());
            states[i] = entry.getValue();
            var block = document.blockEntryCopy(entry.getKey());
            block.remove("pos");
            block.remove("state");
            if (!block.isEmpty()) {
                var value = new CompoundTag();
                value.putInt("index", i);
                value.put("data", block);
                extras.add(value);
            }
            i++;
        }
        tag.putIntArray("positions", positions);
        tag.putIntArray("states", states);
        tag.put("extras", extras);
        return tag;
    }

    private static SchematicDocument unpackDocument(CompoundTag packed, JsonObject config) {
        if (!packed.contains("metadata", Tag.TAG_COMPOUND))
            return new SchematicDocument(packed, config);
        var tag = packed.getCompound("metadata").copy();
        var size = vector(tag.getList("size", Tag.TAG_INT));
        var positions = packed.getIntArray("positions");
        var states = packed.getIntArray("states");
        if (positions.length != states.length || positions.length > SchematicDocument.MAX_BLOCKS)
            throw new IllegalArgumentException("Invalid packed area blocks.");
        var extras = new HashMap<Integer, CompoundTag>();
        for (var value : packed.getList("extras", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            int index = entry.getInt("index");
            if (index < 0
                    || index >= positions.length
                    || extras.put(index, entry.getCompound("data")) != null)
                throw new IllegalArgumentException("Invalid packed block metadata.");
        }
        var blocks = new ListTag();
        for (int i = 0; i < positions.length; i++) {
            var block = extras.containsKey(i) ? extras.get(i).copy() : new CompoundTag();
            block.put("pos", StructureSegments.ints(position(positions[i], size)));
            block.putInt("state", states[i]);
            blocks.add(block);
        }
        tag.put("blocks", blocks);
        return EntityPatches.document(tag, config);
    }
}
