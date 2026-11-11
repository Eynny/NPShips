package com.eynny.npships.integration.radars;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.OptionalCalls;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;
import java.util.function.Function;

/**
 * Independent adapter calling Radars' runtime API. No SavedData maps or upstream implementations
 * copied.
 */
public final class RadarPatch {
    private static final String PREFIX = "com.happysg.radar.block.behavior.networks.";

    private RadarPatch() {}

    public static Object network(ServerLevel level, boolean weapon) throws Exception {
        return OptionalCalls.call(
                Class.forName(PREFIX + (weapon ? "WeaponNetworkData" : "NetworkData")),
                "get",
                level);
    }

    public static JsonObject capture(
            ServerLevel level, BlockPos origin, BlockPos p, SchematicDocument doc)
            throws Exception {
        var patch = new JsonObject();
        patch.addProperty("kind", "radar");
        Object net = network(level, false), weapons = network(level, true);
        var group = OptionalCalls.call(net, "getGroup", level.dimension(), origin.offset(p));
        if (group != null) {
            var g = new JsonObject();
            add(g, "radar", (BlockPos) OptionalCalls.field(group, "radarPos"), origin, p, doc);
            Object kind = OptionalCalls.field(group, "radarKind");
            if (kind != null) g.addProperty("radar_kind", kind.toString());
            var monitors = new JsonArray();
            for (var pos : (Set<?>) OptionalCalls.field(group, "monitorEndpoints"))
                monitors.add(endpoint((BlockPos) pos, origin, p, doc));
            g.add("monitors", monitors);
            var controllers = new JsonArray();
            for (var pos : (Set<?>) OptionalCalls.field(group, "weaponEndpoints")) {
                var link = new JsonObject();
                link.add("controller", endpoint((BlockPos) pos, origin, p, doc));
                BlockPos mount =
                        (BlockPos)
                                OptionalCalls.call(
                                        weapons, "getMountForController", level.dimension(), pos);
                if (mount == null)
                    throw new IllegalArgumentException(
                            "Radar controller has no known weapon mount: " + pos);
                link.add("mount", endpoint(mount, origin, p, doc));
                controllers.add(link);
            }
            g.add("controllers", controllers);
            var links = new JsonArray();
            for (var pos : (Set<?>) OptionalCalls.field(group, "dataLinks")) {
                var link = new JsonObject();
                link.add("link", endpoint((BlockPos) pos, origin, p, doc));
                BlockPos target =
                        (BlockPos)
                                OptionalCalls.call(
                                        net, "peekEndpointForDataLink", level.dimension(), pos);
                if (target == null)
                    throw new IllegalArgumentException("Radar datalink has no target: " + pos);
                link.add("target", endpoint(target, origin, p, doc));
                links.add(link);
            }
            g.add("links", links);
            for (String field : List.of("targetingTag", "identificationTag", "detectionTag"))
                g.addProperty(field, ((CompoundTag) OptionalCalls.field(group, field)).toString());
            patch.add("network", g);
        }
        var weapon = OptionalCalls.call(weapons, "getGroup", level.dimension(), origin.offset(p));
        if (weapon != null) {
            var g = new JsonObject();
            for (String field : List.of("yawPos", "pitchPos", "firingPos"))
                add(g, field, (BlockPos) OptionalCalls.field(weapon, field), origin, p, doc);
            g.addProperty(
                    "targetingTag",
                    ((CompoundTag) OptionalCalls.field(weapon, "targetingTag")).toString());
            var links = new JsonArray();
            for (var pos : (Set<?>) OptionalCalls.field(weapon, "dataLinks"))
                links.add(endpoint((BlockPos) pos, origin, p, doc));
            g.add("links", links);
            patch.add("weapon", g);
        }
        var nbt = doc.blockEntityData(p);
        RadarFuzes.capture(patch, doc, p, origin);
        if (doc.blockName(p).equals("create_radar:identification_transponder")) {
            var ship =
                    OptionalCalls.call(
                            Class.forName("com.happysg.radar.compat.vs2.SableUtils"),
                            "getShipManagingPos",
                            level,
                            origin.offset(p));
            if (ship != null) {
                var record =
                        OptionalCalls.call(
                                Class.forName("com.happysg.radar.block.controller.id.IDManager"),
                                "getIDRecordByShip",
                                ship);
                if (record != null) {
                    var id = new JsonObject();
                    id.addProperty("name", (String) OptionalCalls.call(record, "name"));
                    id.addProperty("code", (String) OptionalCalls.call(record, "secretID"));
                    patch.add("identification", id);
                }
            }
        }
        if (doc.blockName(p).equals("create_radar:monitor")) {
            var controller = NbtUtils.readBlockPos(nbt, "Controller").orElse(origin.offset(p));
            patch.add("monitor_controller", endpoint(controller, origin, p, doc));
            patch.addProperty("monitor_size", Math.max(1, nbt.getInt("Size")));
        }
        var custom = nbt.getCompound("components").getCompound("minecraft:custom_data");
        for (String key : List.of("SelectedFiltererPos", "SelectedMountPos")) {
            var selected = NbtUtils.readBlockPos(custom, key);
            if (selected.isPresent()) patch.add(key, endpoint(selected.get(), origin, p, doc));
        }
        if (nbt.contains("LinkedShipId"))
            throw new IllegalArgumentException("Cross-ship Radar link is not supported at " + p);
        if (nbt.contains("TargetOffset")) {
            BlockPos delta = NbtUtils.readBlockPos(nbt, "TargetOffset").orElse(BlockPos.ZERO);
            if (!delta.equals(BlockPos.ZERO))
                patch.add("datalink", endpoint(origin.offset(p).offset(delta), origin, p, doc));
        }
        return patch;
    }

    private static void add(
            JsonObject g,
            String key,
            BlockPos target,
            BlockPos origin,
            BlockPos p,
            SchematicDocument doc) {
        if (target != null) g.add(key, endpoint(target, origin, p, doc));
    }

    private static JsonObject endpoint(
            BlockPos target, BlockPos origin, BlockPos p, SchematicDocument doc) {
        BlockPos local = target.subtract(origin);
        Integer state = doc.blocks().get(local);
        if (state == null)
            throw new IllegalArgumentException(
                    "Radar link leaves the captured structure: "
                            + target
                            + ". Include or disconnect its endpoint.");
        var e = new JsonObject();
        e.add("offset", SchematicPatches.vector(local.subtract(p)));
        e.addProperty("block", doc.paletteCopy().getCompound(state).getString("Name"));
        return e;
    }

    public static void validate(JsonObject patch, SchematicDocument doc) {
        RadarFuzes.validate(patch, doc);
        if (patch.has("identification")) {
            if (!patch.get("block").getAsString().equals("create_radar:identification_transponder"))
                throw new IllegalArgumentException("Identification needs a transponder.");
            for (String key : List.of("name", "code"))
                if (patch.getAsJsonObject("identification").get(key).getAsString().length() > 256)
                    throw new IllegalArgumentException("Identification text too long.");
        }
        if (patch.has("monitor_controller")) monitorSize(patch);
        if (patch.has("network")) {
            var network = patch.getAsJsonObject("network");
            for (String key : List.of("monitors", "controllers", "links"))
                if (!network.has(key) || !network.get(key).isJsonArray())
                    throw new IllegalArgumentException("Radar network requires array: " + key);
            if (network.has("radar")
                    && (!network.has("radar_kind")
                            || !Set.of("BEARING", "STATIONARY")
                                    .contains(network.get("radar_kind").getAsString())))
                throw new IllegalArgumentException("Invalid radar_kind.");
            for (var c : network.getAsJsonArray("controllers")) {
                var e = c.getAsJsonObject();
                if (!e.has("controller") || !e.has("mount"))
                    throw new IllegalArgumentException(
                            "Controller link requires controller and mount.");
            }
            for (var c : network.getAsJsonArray("links")) {
                var e = c.getAsJsonObject();
                if (!e.has("link") || !e.has("target"))
                    throw new IllegalArgumentException("Datalink requires link and target.");
            }
        }
        if (patch.has("weapon") && !patch.getAsJsonObject("weapon").has("links"))
            throw new IllegalArgumentException("Weapon patch requires links array.");
        walk(patch, SchematicPatches.position(patch.getAsJsonArray("pos")), doc, 0);
    }

    private static int monitorSize(JsonObject patch) {
        var value = patch.get("monitor_size");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(
                    "Monitor size must be an integer from 1 through 512.");
        try {
            int size = value.getAsBigDecimal().intValueExact();
            if (size < 1 || size > 512) throw new ArithmeticException("Outside monitor size range");
            return size;
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Monitor size must be an integer from 1 through 512.", error);
        }
    }

    public static void preflight(ServerLevel level) throws Exception {
        Object net = network(level, false), weapon = network(level, true);
        for (String method :
                List.of(
                        "getOrCreateGroup",
                        "attachRadar",
                        "attachMonitor",
                        "attachWeaponEndpoint",
                        "addDataLinkToGroup")) requireMethod(net, method);
        for (String method : List.of("getOrCreateGroup", "tryMergeIntoGroup", "addDataLinkToGroup"))
            requireMethod(weapon, method);
        var group = Class.forName(PREFIX + "NetworkData$Group");
        for (String field : List.of("targetingTag", "identificationTag", "detectionTag"))
            group.getField(field);
    }

    private static void requireMethod(Object object, String name) throws NoSuchMethodException {
        if (Arrays.stream(object.getClass().getMethods()).noneMatch(m -> m.getName().equals(name)))
            throw new NoSuchMethodException("Unsupported Radars API: " + name);
    }

    private static void walk(JsonElement value, BlockPos owner, SchematicDocument doc, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Radar patch is too deeply nested.");
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("offset")) {
                BlockPos target =
                        owner.offset(SchematicPatches.position(object.getAsJsonArray("offset")));
                Integer index = doc.blocks().get(target);
                if (index == null
                        || !doc.paletteCopy()
                                .getCompound(index)
                                .getString("Name")
                                .equals(object.get("block").getAsString()))
                    throw new IllegalArgumentException(
                            "Radar endpoint missing or changed at " + target);
            }
            for (var e : object.entrySet()) {
                if (e.getKey().endsWith("Tag"))
                    try {
                        com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                e.getValue().getAsString());
                    } catch (Exception error) {
                        throw new IllegalArgumentException(
                                "Invalid radar settings: " + e.getKey(), error);
                    }
                walk(e.getValue(), owner, doc, depth + 1);
            }
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) walk(e, owner, doc, depth + 1);
    }

    public static void restore(
            ServerLevel level, JsonObject patch, Function<BlockPos, BlockPos> resolve)
            throws Exception {
        BlockPos local = SchematicPatches.position(patch.getAsJsonArray("pos")),
                owner = resolve.apply(local);
        Function<JsonElement, BlockPos> endpoint =
                e ->
                        e == null
                                ? null
                                : resolve.apply(
                                        local.offset(
                                                SchematicPatches.position(
                                                        e.getAsJsonObject()
                                                                .getAsJsonArray("offset"))));
        if (patch.has("weapon")) {
            var g = patch.getAsJsonObject("weapon");
            var net = network(level, true);
            var group = OptionalCalls.call(net, "getOrCreateGroup", level.dimension(), owner);
            if (!(boolean)
                    OptionalCalls.call(
                            net,
                            "tryMergeIntoGroup",
                            group,
                            endpoint.apply(g.get("yawPos")),
                            endpoint.apply(g.get("pitchPos")),
                            endpoint.apply(g.get("firingPos"))))
                throw new IllegalStateException("Radar weapon link was rejected.");
            settings(group, g);
            for (var link : g.getAsJsonArray("links"))
                OptionalCalls.call(net, "addDataLinkToGroup", group, endpoint.apply(link));
            ((SavedData) net).setDirty();
        }
        if (patch.has("network")) {
            var g = patch.getAsJsonObject("network");
            var net = network(level, false);
            var group = OptionalCalls.call(net, "getOrCreateGroup", level.dimension(), owner);
            if (g.has("radar")) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object kind =
                        Enum.valueOf(
                                (Class) Class.forName(PREFIX + "NetworkData$RadarKind"),
                                g.get("radar_kind").getAsString());
                OptionalCalls.call(net, "attachRadar", group, endpoint.apply(g.get("radar")), kind);
            }
            for (var monitor : g.getAsJsonArray("monitors"))
                OptionalCalls.call(net, "attachMonitor", level, group, endpoint.apply(monitor));
            for (var c : g.getAsJsonArray("controllers")) {
                var link = c.getAsJsonObject();
                OptionalCalls.call(
                        net,
                        "attachWeaponEndpoint",
                        group,
                        endpoint.apply(link.get("controller")),
                        endpoint.apply(link.get("mount")));
            }
            for (var c : g.getAsJsonArray("links")) {
                var link = c.getAsJsonObject();
                OptionalCalls.call(
                        net,
                        "addDataLinkToGroup",
                        group,
                        endpoint.apply(link.get("link")),
                        endpoint.apply(link.get("target")));
            }
            settings(group, g);
            ((SavedData) net).setDirty();
        }
        if (patch.has("datalink")) {
            var be = level.getBlockEntity(owner);
            if (be == null) throw new IllegalStateException("Missing datalink after assembly.");
            var nbt = be.saveWithFullMetadata(level.registryAccess());
            nbt.put(
                    "TargetOffset",
                    NbtUtils.writeBlockPos(endpoint.apply(patch.get("datalink")).subtract(owner)));
            nbt.remove("LinkedShipId");
            be.loadWithComponents(nbt, level.registryAccess());
            be.setChanged();
        }
    }

    /** Starts one local record edit so registered Radar/fuze actions share one native save/load. */
    public static LocalState beginLocal(
            ServerLevel level, JsonObject patch, Function<BlockPos, BlockPos> resolve)
            throws Exception {
        BlockPos local = SchematicPatches.position(patch.getAsJsonArray("pos")),
                owner = resolve.apply(local);
        if (patch.has("identification")) {
            var ship =
                    OptionalCalls.call(
                            Class.forName("com.happysg.radar.compat.vs2.SableUtils"),
                            "getShipManagingPos",
                            level,
                            owner);
            if (ship != null) {
                var identification = patch.getAsJsonObject("identification");
                OptionalCalls.call(
                        Class.forName("com.happysg.radar.block.controller.id.IDManager"),
                        "addIDRecord",
                        OptionalCalls.call(ship, "getUniqueId"),
                        identification.get("code").getAsString(),
                        identification.get("name").getAsString());
            }
        }
        if (patch.get("block").getAsString().equals("create_radar:identification_transponder"))
            return null;
        var be = level.getBlockEntity(owner);
        if (be == null)
            throw new IllegalStateException("Missing Radar device after assembly: " + owner);
        if (patch.has("monitor_controller")) {
            var target =
                    resolve.apply(
                            local.offset(
                                    SchematicPatches.position(
                                            patch.getAsJsonObject("monitor_controller")
                                                    .getAsJsonArray("offset"))));
            var data = be.saveWithFullMetadata(level.registryAccess());
            data.put("Controller", NbtUtils.writeBlockPos(target));
            data.putInt("Size", monitorSize(patch));
            be.loadWithComponents(data, level.registryAccess());
            be.setChanged();
            OptionalCalls.call(be, "sendData");
        }
        return new LocalState(level, patch, resolve, owner, be);
    }

    /**
     * Short-lived native record buffer. No registry dispatch or world/global cache is stored here.
     */
    public static final class LocalState {
        private final ServerLevel level;
        private final JsonObject patch;
        private final Function<BlockPos, BlockPos> resolve;
        private final BlockPos owner;
        private final net.minecraft.world.level.block.entity.BlockEntity be;
        private final CompoundTag data;
        private boolean changed, completed;

        private LocalState(
                ServerLevel level,
                JsonObject patch,
                Function<BlockPos, BlockPos> resolve,
                BlockPos owner,
                net.minecraft.world.level.block.entity.BlockEntity be) {
            this.level = level;
            this.patch = patch;
            this.resolve = resolve;
            this.owner = owner;
            this.be = be;
            data = be.saveWithFullMetadata(level.registryAccess());
        }

        public void restoreFuzes() {
            if (completed) throw new IllegalStateException("Radar local edit already committed");
            changed |= patch.has("fuze_bindings");
            RadarFuzes.restore(data, patch, resolve);
        }

        public void commit() {
            if (completed) return;
            completed = true;
            var local = SchematicPatches.position(patch.getAsJsonArray("pos"));
            var components = data.getCompound("components");
            var custom = components.getCompound("minecraft:custom_data");
            for (String key : List.of("SelectedFiltererPos", "SelectedMountPos"))
                if (patch.has(key)) {
                    var target =
                            resolve.apply(
                                    local.offset(
                                            SchematicPatches.position(
                                                    patch.getAsJsonObject(key)
                                                            .getAsJsonArray("offset"))));
                    custom.put(key, NbtUtils.writeBlockPos(target));
                    changed = true;
                }
            if (changed) {
                components.put("minecraft:custom_data", custom);
                data.put("components", components);
                be.loadWithComponents(data, level.registryAccess());
                be.setChanged();
                level.sendBlockUpdated(owner, be.getBlockState(), be.getBlockState(), 3);
            }
        }
    }

    private static void settings(Object group, JsonObject g) throws Exception {
        for (String name : List.of("targetingTag", "identificationTag", "detectionTag"))
            if (g.has(name))
                group.getClass()
                        .getField(name)
                        .set(
                                group,
                                RadarFilters.networkTag(
                                        name,
                                        com.eynny.npships.authoring.document.SnbtText
                                                .nativeCompound(g.get(name).getAsString())));
    }
}
