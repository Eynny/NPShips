package com.eynny.npships.integration.radars;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;

import net.minecraft.core.BlockPos;

import java.util.*;

/** Authoring graph. Groups live on their native anchors, irrespective of click direction. */
public final class RadarWiring {
    private RadarWiring() {}

    public static String role(String id) {
        return switch (id) {
            case "create_radar:network_filterer" -> "filter";
            case "create_radar:monitor" -> "monitor";
            case "create_radar:radar_bearing",
                            "create_radar:sonar_bearing",
                            "create_radar:plane_radar",
                            "create_radar:sky_radar" ->
                    "radar";
            case "createbigcannons:cannon_mount", "createbigcannons:fixed_cannon_mount" -> "mount";
            case "create_radar:auto_yaw_controller" -> "yawPos";
            case "create_radar:auto_pitch_controller" -> "pitchPos";
            case "create_radar:fire_controller" -> "firingPos";
            case "create_radar:data_link" -> "link";
            default -> "";
        };
    }

    private static boolean controller(String role) {
        return Set.of("yawPos", "pitchPos", "firingPos").contains(role);
    }

    /** Resolve a visible radar/monitor to its unique filterer for guided ammunition. */
    public static BlockPos filterFor(SchematicDocument doc, BlockPos device) {
        if (role(doc.blockName(device)).equals("filter")) return device;
        if (!Set.of("radar", "monitor").contains(role(doc.blockName(device)))) return null;
        var monitor = patch(doc, device);
        if (monitor.has("monitor_controller"))
            device = target(device, monitor.get("monitor_controller"));
        BlockPos found = null;
        for (var p : nativePatches(doc))
            if (p.has("network")) {
                var owner = SchematicPatches.position(p.getAsJsonArray("pos"));
                var network = p.getAsJsonObject("network");
                boolean match =
                        network.has("radar") && target(owner, network.get("radar")).equals(device);
                for (var m : network.getAsJsonArray("monitors"))
                    match |= target(owner, m).equals(device);
                if (match) {
                    if (found != null && !found.equals(owner)) return null;
                    found = owner;
                }
            }
        return found;
    }

    private static BlockPos attachment(SchematicDocument doc, BlockPos p) {
        var index = doc.blocks().get(p);
        if (index == null) return p;
        var facing =
                net.minecraft.core.Direction.byName(
                        doc.paletteCopy()
                                .getCompound(index)
                                .getCompound("Properties")
                                .getString("facing"));
        return p.relative(
                (facing == null ? net.minecraft.core.Direction.UP : facing).getOpposite());
    }

    public static boolean compatible(SchematicDocument doc, BlockPos a, BlockPos b) {
        if (role(doc.blockName(a)).equals("link")) a = attachment(doc, a);
        if (role(doc.blockName(b)).equals("link")) b = attachment(doc, b);
        if (a.equals(b)) return false;
        String x = role(doc.blockName(a)), y = role(doc.blockName(b));
        return x.equals("filter")
                        && Set.of(
                                        "radar",
                                        "monitor",
                                        "mount",
                                        "link",
                                        "yawPos",
                                        "pitchPos",
                                        "firingPos")
                                .contains(y)
                || y.equals("filter")
                        && Set.of(
                                        "radar",
                                        "monitor",
                                        "mount",
                                        "link",
                                        "yawPos",
                                        "pitchPos",
                                        "firingPos")
                                .contains(x)
                || x.equals("mount") && (controller(y) || y.equals("link"))
                || y.equals("mount") && (controller(x) || x.equals("link"));
    }

    private static JsonObject patch(SchematicDocument doc, BlockPos p) {
        return nativePatches(doc).stream()
                .filter(e -> SchematicPatches.position(e.getAsJsonArray("pos")).equals(p))
                .findFirst()
                .orElseGet(() -> PatchDevices.create(doc, p));
    }

    private static List<JsonObject> nativePatches(SchematicDocument doc) {
        return com.eynny.npships.authoring.patches.ExternalPatches.authoringEntries(doc).stream()
                .filter(patch -> !ExternalPatches.external(patch))
                .toList();
    }

    private static JsonObject network(JsonObject patch) {
        if (patch.has("network")) return patch.getAsJsonObject("network");
        var n = new JsonObject();
        for (String key : List.of("monitors", "controllers", "links")) n.add(key, new JsonArray());
        patch.add("network", n);
        return n;
    }

    private static JsonObject weapon(JsonObject patch) {
        if (patch.has("weapon")) return patch.getAsJsonObject("weapon");
        var w = new JsonObject();
        w.add("links", new JsonArray());
        patch.add("weapon", w);
        return w;
    }

    private static BlockPos target(BlockPos owner, JsonElement e) {
        return owner.offset(
                SchematicPatches.position(e.getAsJsonObject().getAsJsonArray("offset")));
    }

    private static void add(JsonArray list, JsonObject e) {
        if (!list.asList().contains(e)) list.add(e);
    }

    public static SchematicDocument connect(SchematicDocument doc, BlockPos a, BlockPos b) {
        doc = RadarMonitors.normalize(doc);
        // Embedded-only documents need the same complete authoring list before an upsert.
        if (!doc.hasConfig() || !doc.configCopy().has("patches")) {
            var config = doc.hasConfig() ? doc.configCopy() : new JsonObject();
            RadarMonitors.writePatches(doc, config, doc.templatePatches());
            doc = doc.withConfig(config);
        }
        var explicitLinks = new HashSet<BlockPos>();
        if (role(doc.blockName(a)).equals("link")) explicitLinks.add(a);
        if (role(doc.blockName(b)).equals("link")) explicitLinks.add(b);
        if (!compatible(doc, a, b))
            throw new IllegalArgumentException("These devices cannot be connected.");
        if (role(doc.blockName(a)).equals("link")) a = attachment(doc, a);
        if (role(doc.blockName(b)).equals("link")) b = attachment(doc, b);
        String x = role(doc.blockName(a)), y = role(doc.blockName(b));
        if (y.equals("filter") || !x.equals("filter") && y.equals("mount")) {
            var swap = a;
            a = b;
            b = swap;
            String r = x;
            x = y;
            y = r;
        }
        BlockPos peer = b;
        var p = patch(doc, a);
        if (x.equals("mount")) {
            var w = weapon(p);
            if (y.equals("link")) add(w.getAsJsonArray("links"), PatchDevices.endpoint(doc, a, b));
            else {
                // A controller belongs to one mount. Require disconnecting before reassigning.
                for (var other : nativePatches(doc))
                    if (other.has("weapon")
                            && !SchematicPatches.position(other.getAsJsonArray("pos")).equals(a)) {
                        var ow = other.getAsJsonObject("weapon");
                        if (ow.has(y)
                                && target(
                                                SchematicPatches.position(
                                                        other.getAsJsonArray("pos")),
                                                ow.get(y))
                                        .equals(b))
                            throw new IllegalArgumentException(
                                    "Controller already belongs to another cannon mount. Disconnect it first.");
                    }
                w.add(y, PatchDevices.endpoint(doc, a, b));
            }
        } else {
            var n = network(p);
            switch (y) {
                case "radar" -> {
                    n.add("radar", PatchDevices.endpoint(doc, a, b));
                    n.addProperty(
                            "radar_kind",
                            doc.blockName(b).endsWith("bearing") ? "BEARING" : "STATIONARY");
                }
                case "monitor" -> {
                    var monitor = patch(doc, b);
                    BlockPos root =
                            monitor.has("monitor_controller")
                                    ? target(b, monitor.get("monitor_controller"))
                                    : b;
                    add(n.getAsJsonArray("monitors"), PatchDevices.endpoint(doc, a, root));
                }
                case "link" -> {
                    var pair = new JsonObject();
                    pair.add("link", PatchDevices.endpoint(doc, a, b));
                    pair.add("target", PatchDevices.endpoint(doc, a, a));
                    add(n.getAsJsonArray("links"), pair);
                }
                default -> {
                    BlockPos mount = b, control = null;
                    if (controller(y)) {
                        control = b;
                        mount = null;
                        for (var other : nativePatches(doc))
                            if (other.has("weapon")) {
                                var w = other.getAsJsonObject("weapon");
                                var owner = SchematicPatches.position(other.getAsJsonArray("pos"));
                                if (w.has(y) && target(owner, w.get(y)).equals(b)) {
                                    mount = owner;
                                    break;
                                }
                            }
                    } else {
                        var w = weapon(patch(doc, b));
                        for (String key : List.of("pitchPos", "yawPos", "firingPos"))
                            if (w.has(key)) {
                                control = target(b, w.get(key));
                                break;
                            }
                    }
                    if (mount == null || control == null)
                        throw new IllegalArgumentException(
                                "Connect the cannon mount to its controller first, then connect it to the Network Filterer.");
                    peer = control;
                    var pair = new JsonObject();
                    pair.add("controller", PatchDevices.endpoint(doc, a, control));
                    pair.add("mount", PatchDevices.endpoint(doc, a, mount));
                    add(n.getAsJsonArray("controllers"), pair);
                }
            }
        }
        doc = PatchDevices.put(doc, p);
        // Only links of the requested device participate, never all previous group members.
        var endpoints = new HashSet<BlockPos>();
        endpoints.add(peer);
        var panels = RadarMonitors.layout(doc, RadarMonitors.maximumSize());
        if (y.equals("monitor")) {
            var panel = panels.get(peer);
            if (panel != null)
                for (var e : panels.entrySet())
                    if (e.getValue().controller().equals(panel.controller()))
                        endpoints.add(e.getKey());
        }
        var all = SchematicPatches.read(doc.configCopy());
        String groupKey = x.equals("filter") ? "network" : "weapon";
        var assigned = new HashSet<BlockPos>();
        var own = new HashSet<BlockPos>();
        for (var e : all)
            for (String key : List.of("network", "weapon"))
                if (!ExternalPatches.external(e) && e.has(key)) {
                    var owner = SchematicPatches.position(e.getAsJsonArray("pos"));
                    for (var item : e.getAsJsonObject(key).getAsJsonArray("links")) {
                        var link =
                                target(
                                        owner,
                                        key.equals("network")
                                                ? item.getAsJsonObject().get("link")
                                                : item);
                        assigned.add(link);
                        if (owner.equals(a) && key.equals(groupKey)) own.add(link);
                    }
                }
        var selected = new LinkedHashSet<BlockPos>();
        if (!explicitLinks.isEmpty()) selected.addAll(explicitLinks);
        else {
            final var currentDoc = doc;
            final String style = x.equals("filter") ? "radar" : "controller";
            var candidates =
                    doc.blocks().keySet().stream()
                            .filter(
                                    pos ->
                                            role(currentDoc.blockName(pos)).equals("link")
                                                    && endpoints.contains(
                                                            attachment(currentDoc, pos))
                                                    && (own.contains(pos)
                                                            || !assigned.contains(pos)))
                            .sorted(
                                    Comparator.<BlockPos>comparingInt(
                                                    pos ->
                                                            own.contains(pos)
                                                                    ? 0
                                                                    : style.equals(
                                                                                    currentDoc
                                                                                            .paletteCopy()
                                                                                            .getCompound(
                                                                                                    currentDoc
                                                                                                            .blocks()
                                                                                                            .get(
                                                                                                                    pos))
                                                                                            .getCompound(
                                                                                                    "Properties")
                                                                                            .getString(
                                                                                                    "link_style"))
                                                                            ? 1
                                                                            : 2)
                                            .thenComparingInt(BlockPos::getY)
                                            .thenComparingInt(BlockPos::getX)
                                            .thenComparingInt(BlockPos::getZ))
                            .toList();
            // A pitch controller may carry a weapon link and a separate radar-network link.
            if (controller(role(doc.blockName(peer)))) {
                if (!candidates.isEmpty()) selected.add(candidates.getFirst());
            } else selected.addAll(candidates);
        }
        var entries = new LinkedHashMap<BlockPos, JsonObject>();
        for (var e : all)
            if (!ExternalPatches.external(e))
                entries.put(SchematicPatches.position(e.getAsJsonArray("pos")), e);
        p = entries.get(a);
        for (var e : entries.entrySet())
            for (String key : List.of("network", "weapon"))
                if (!com.eynny.npships.authoring.patches.ExternalPatches.external(e.getValue())
                        && e.getValue().has(key)) {
                    var links = e.getValue().getAsJsonObject(key).getAsJsonArray("links");
                    for (int i = links.size() - 1; i >= 0; i--)
                        if (selected.contains(
                                target(
                                        e.getKey(),
                                        key.equals("network")
                                                ? links.get(i).getAsJsonObject().get("link")
                                                : links.get(i)))) links.remove(i);
                }
        var tag = doc.nbtCopy();
        var palette = tag.getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (var value : tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var entry = (net.minecraft.nbt.CompoundTag) value;
            var link = SchematicPatches.blockPosition(entry);
            if (!selected.contains(link)) continue;
            var device = attachment(doc, link);
            var panel = panels.get(device);
            if (panel != null) device = panel.controller();
            if (x.equals("filter")) {
                var pair = new JsonObject();
                pair.add("link", PatchDevices.endpoint(doc, a, link));
                pair.add("target", PatchDevices.endpoint(doc, a, device));
                add(network(p).getAsJsonArray("links"), pair);
            } else add(weapon(p).getAsJsonArray("links"), PatchDevices.endpoint(doc, a, link));
            var lp = entries.get(link);
            if (lp == null) {
                lp = PatchDevices.create(doc, link);
                entries.put(link, lp);
            }
            if (com.eynny.npships.authoring.patches.ExternalPatches.external(lp))
                throw new IllegalArgumentException("Conflicting external patch at " + link);
            lp.remove("datalink");
            lp.remove("SelectedFiltererPos");
            lp.remove("SelectedMountPos");
            lp.add(
                    x.equals("filter") ? "SelectedFiltererPos" : "SelectedMountPos",
                    PatchDevices.endpoint(doc, link, a));
            var state = palette.getCompound(entry.getInt("state")).copy();
            var properties = state.getCompound("Properties");
            if (properties.contains("link_style")) {
                properties.putString("link_style", x.equals("filter") ? "radar" : "controller");
                state.put("Properties", properties);
                int index = palette.indexOf(state);
                if (index < 0) {
                    index = palette.size();
                    palette.add(state);
                }
                entry.putInt("state", index);
            }
        }
        var config = doc.configCopy();
        RadarMonitors.writePatches(doc, config, RadarMonitors.mergePatches(all, entries));
        SchematicPatches.embed(tag, config);
        SchematicPatches.validate(tag);
        return new SchematicDocument(tag, config);
    }
}
