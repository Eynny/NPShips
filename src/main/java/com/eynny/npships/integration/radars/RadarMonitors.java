package com.eynny.npships.integration.radars;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.OptionalCalls;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;

import java.util.*;

/** Detached square-screen layout; never runs world placement callbacks or radar ticks. */
public final class RadarMonitors {
    private RadarMonitors() {}

    private static final Set<SchematicDocument> NORMALIZED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public record Panel(BlockPos controller, int size, int column, int row) {
        public String shape() {
            if (size == 1) return "single";
            String vertical = row == 0 ? "lower" : row == size - 1 ? "upper" : "middle";
            String horizontal = column == 0 ? "right" : column == size - 1 ? "left" : "center";
            return vertical.equals("middle") && horizontal.equals("center")
                    ? "center"
                    : vertical + "_" + horizontal;
        }
    }

    public static int maximumSize() {
        if (!net.neoforged.fml.ModList.get().isLoaded("create_radar"))
            throw new IllegalStateException("Radars is unavailable; cannot determine monitor size");
        try {
            // Resolving all RadarConfig methods also loads its client-only config screen on a
            // server.
            var owner = Class.forName("com.happysg.radar.config.RadarConfig");
            var type = Class.forName("com.happysg.radar.config.server.RadarServerConfig");
            var config =
                    java.lang.invoke.MethodHandles.publicLookup()
                            .findStatic(
                                    owner, "server", java.lang.invoke.MethodType.methodType(type))
                            .invoke();
            var value =
                    (net.createmod.catnip.config.ConfigBase.ConfigInt)
                            OptionalCalls.field(config, "monitorMaxSize");
            return Math.max(1, Math.min(SchematicDocument.MAX_SIDE, value.get()));
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot read Radars monitorMaxSize", e);
        }
    }

    public static Map<BlockPos, Panel> layout(SchematicDocument doc, int limit) {
        var palette = doc.paletteCopy();
        var facing = new HashMap<BlockPos, Direction>();
        for (var e : doc.blocks().entrySet()) {
            var state = palette.getCompound(e.getValue());
            if (!state.getString("Name").equals("create_radar:monitor")) continue;
            var dir = Direction.byName(state.getCompound("Properties").getString("facing"));
            if (dir != null && dir.getAxis().isHorizontal()) facing.put(e.getKey(), dir);
        }
        var result = new HashMap<BlockPos, Panel>();
        for (var dir : Direction.Plane.HORIZONTAL) {
            var step = dir.getClockWise();
            var order =
                    facing.keySet().stream()
                            .filter(p -> facing.get(p) == dir)
                            .sorted(
                                    Comparator.<BlockPos>comparingInt(BlockPos::getY)
                                            .thenComparingInt(
                                                    p ->
                                                            p.getX() * step.getStepX()
                                                                    + p.getZ() * step.getStepZ())
                                            .thenComparingInt(BlockPos::getX)
                                            .thenComparingInt(BlockPos::getZ)
                                            .reversed())
                            .toList();
            var sizes = new HashMap<BlockPos, Integer>();
            for (var p : order)
                sizes.put(
                        p,
                        Math.min(
                                limit,
                                1
                                        + Math.min(
                                                sizes.getOrDefault(p.above(), 0),
                                                Math.min(
                                                        sizes.getOrDefault(p.relative(step), 0),
                                                        sizes.getOrDefault(
                                                                p.above().relative(step), 0)))));
            var anchors = new ArrayList<>(order);
            anchors.sort(
                    Comparator.<BlockPos>comparingInt(sizes::get)
                            .reversed()
                            .thenComparingInt(BlockPos::getY)
                            .thenComparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getZ));
            for (var anchor : anchors) {
                if (result.containsKey(anchor)) continue;
                int n = sizes.get(anchor);
                // Largest squares first; cells already claimed by another screen cannot be reused.
                boolean free = false;
                while (!free) {
                    free = true;
                    for (int row = 0; row < n && free; row++)
                        for (int col = 0; col < n; col++)
                            if (result.containsKey(anchor.above(row).relative(step, col))) {
                                free = false;
                                break;
                            }
                    if (!free) n--;
                }
                for (int row = 0; row < n; row++)
                    for (int col = 0; col < n; col++)
                        result.put(
                                anchor.above(row).relative(step, col),
                                new Panel(anchor, n, col, row));
            }
        }
        return result;
    }

    public static SchematicDocument normalize(SchematicDocument source) {
        // No installed native owner means there is no authoritative layout/config to normalize.
        if (!net.neoforged.fml.ModList.get().isLoaded("create_radar")) return source;
        if (NORMALIZED.contains(source)) return source;
        var cursor = source;
        boolean monitorChanged = false;
        for (int step = 0; step < 64 && cursor != null; step++, cursor = cursor.localParent()) {
            if (NORMALIZED.contains(cursor)) {
                if (!monitorChanged
                        && Objects.equals(monitorPatches(source), monitorPatches(cursor))) {
                    NORMALIZED.add(source);
                    return source;
                }
                break;
            }
            var parent = cursor.localParent();
            for (var p : cursor.localChanges())
                monitorChanged |=
                        cursor.blockName(p).equals("create_radar:monitor")
                                || parent != null
                                        && parent.blockName(p).equals("create_radar:monitor");
        }
        // Most documents contain no monitors; avoid even accessing the optional config in that
        // case.
        if (source.paletteCopy().stream()
                .noneMatch(
                        t -> ((CompoundTag) t).getString("Name").equals("create_radar:monitor"))) {
            NORMALIZED.add(source);
            return source;
        }
        if (source.hasMultiplePalettes()) {
            NORMALIZED.add(source);
            return source;
        }
        var panels = layout(source, maximumSize());
        if (panels.isEmpty()) {
            NORMALIZED.add(source);
            return source;
        }
        var tag = source.nbtCopy();
        if (!tag.contains("palette")) {
            tag.put("palette", source.paletteCopy());
            tag.remove("palettes");
        }
        var palette = tag.getList("palette", Tag.TAG_COMPOUND);
        var original = ExternalPatches.authoringEntries(source);
        var entries = new LinkedHashMap<BlockPos, JsonObject>();
        for (var patch : original)
            if (!ExternalPatches.external(patch))
                entries.put(SchematicPatches.position(patch.getAsJsonArray("pos")), patch);
        for (var value : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            var pos = SchematicPatches.blockPosition(block);
            var panel = panels.get(pos);
            if (panel == null) continue;
            var state = palette.getCompound(block.getInt("state")).copy();
            var properties = state.getCompound("Properties");
            properties.putString("shape", panel.shape());
            state.put("Properties", properties);
            int index = palette.indexOf(state);
            if (index < 0) {
                index = palette.size();
                palette.add(state);
            }
            block.putInt("state", index);
            var data = block.getCompound("nbt");
            data.put("Controller", NbtUtils.writeBlockPos(panel.controller()));
            data.putInt("Size", panel.size());
            block.put("nbt", data);
            if (block.getBoolean(SchematicPatches.UNPATCHED) && !entries.containsKey(pos)) continue;
            var patch = entries.computeIfAbsent(pos, p -> PatchDevices.create(source, p));
            if (com.eynny.npships.authoring.patches.ExternalPatches.external(patch)) continue;
            patch.add("monitor_controller", PatchDevices.endpoint(source, pos, panel.controller()));
            patch.addProperty("monitor_size", panel.size());
        }
        for (var patch : entries.values())
            if (!com.eynny.npships.authoring.patches.ExternalPatches.external(patch)
                    && patch.has("network")) {
                var net = patch.getAsJsonObject("network");
                var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
                var monitors = new JsonArray();
                for (var entry : net.getAsJsonArray("monitors")) {
                    var e = entry.getAsJsonObject();
                    var p = owner.offset(SchematicPatches.position(e.getAsJsonArray("offset")));
                    var panel = panels.get(p);
                    var next =
                            panel == null
                                    ? e
                                    : PatchDevices.endpoint(source, owner, panel.controller());
                    if (!monitors.asList().contains(next)) monitors.add(next);
                }
                net.add("monitors", monitors);
                for (var value : net.getAsJsonArray("links")) {
                    var pair = value.getAsJsonObject();
                    var p =
                            owner.offset(
                                    SchematicPatches.position(
                                            pair.getAsJsonObject("target")
                                                    .getAsJsonArray("offset")));
                    var panel = panels.get(p);
                    if (panel != null)
                        pair.add(
                                "target", PatchDevices.endpoint(source, owner, panel.controller()));
                }
            }
        var config = source.hasConfig() ? source.configCopy() : new JsonObject();
        var updated = mergePatches(original, entries);
        writePatches(source, config, updated);
        SchematicPatches.embed(tag, config);
        var result = new SchematicDocument(tag, source.hasConfig() ? config : null);
        NORMALIZED.add(result);
        return result;
    }

    /** Native graph edits own one legacy slot; external siblings retain their order and fields. */
    static List<JsonObject> mergePatches(
            List<JsonObject> original, Map<BlockPos, JsonObject> nativeEntries) {
        var remaining = new LinkedHashMap<>(nativeEntries);
        var result = new ArrayList<JsonObject>();
        for (var patch : original) {
            if (ExternalPatches.external(patch)) result.add(patch);
            else {
                var replacement =
                        remaining.remove(SchematicPatches.position(patch.getAsJsonArray("pos")));
                if (replacement != null) result.add(replacement);
            }
        }
        result.addAll(remaining.values());
        return result;
    }

    /** Native normalization preserves each current instance and all external siblings. */
    static void writePatches(
            SchematicDocument source, JsonObject config, List<JsonObject> entries) {
        var occupied = new ArrayList<>(ExternalPatches.authoringEntries(source));
        var accepted = new ArrayList<JsonObject>();
        for (var entry : entries) {
            var value =
                    !com.eynny.npships.authoring.patches.PatchInstances.explicit(entry)
                                    && !ExternalPatches.external(entry)
                            ? com.eynny.npships.authoring.patches.PatchInstances
                                    .materializeAddition(entry, occupied)
                            : entry;
            accepted.add(value);
            occupied.add(value);
        }
        SchematicPatches.set(config, accepted);
    }

    private static List<JsonObject> monitorPatches(SchematicDocument doc) {
        return ExternalPatches.authoringEntries(doc).stream()
                .filter(
                        patch ->
                                !ExternalPatches.external(patch)
                                        && (patch.has("network")
                                                || patch.get("block")
                                                        .getAsString()
                                                        .equals("create_radar:monitor")))
                .toList();
    }
}
