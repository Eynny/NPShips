package com.eynny.npships.integration.computercraft;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import java.util.*;

/** A detached wiring graph. Wireless peers are candidates, not a promise of radio delivery. */
public final class ComputerNetwork {
    public record Device(BlockPos pos, String type, String route, boolean peer) {}

    public record Port(BlockPos modem, BlockPos device, String suffix, String type) {}

    private final SchematicDocument doc;
    private final Map<BlockPos, CompoundTag> states = new HashMap<>();

    public ComputerNetwork(SchematicDocument doc) {
        this.doc = doc;
        var palette = doc.paletteCopy();
        if (palette.stream()
                .noneMatch(t -> ((CompoundTag) t).getString("Name").startsWith("computercraft:")))
            return;
        doc.blocks()
                .forEach(
                        (p, i) -> {
                            var state = palette.getCompound(i);
                            if (state.getString("Name").startsWith("computercraft:"))
                                states.put(p, state);
                        });
    }

    private String id(BlockPos p) {
        var s = states.get(p);
        return s == null ? "" : s.getString("Name");
    }

    private String prop(BlockPos p, String key) {
        var s = states.get(p);
        return s == null ? "" : s.getCompound("Properties").getString(key);
    }

    private Direction face(BlockPos p) {
        var name = prop(p, "modem");
        int end = name.indexOf('_');
        return Direction.byName(end < 0 ? name : name.substring(0, end));
    }

    public boolean wired(BlockPos p) {
        return id(p).equals("computercraft:wired_modem_full")
                || id(p).equals("computercraft:cable") && prop(p, "cable").equals("true");
    }

    public boolean shared(BlockPos p) {
        return id(p).equals("computercraft:wired_modem_full")
                ? prop(p, "peripheral").equals("true")
                : prop(p, "modem").endsWith("_peripheral");
    }

    private boolean port(BlockPos p, Direction d) {
        return wired(p) && (id(p).equals("computercraft:wired_modem_full") || face(p) != d);
    }

    private boolean attached(BlockPos device, BlockPos owner) {
        if (id(device).equals("computercraft:cable"))
            return face(device) != null && device.relative(face(device)).equals(owner);
        if (id(device).contains("wireless_modem")) {
            var face = Direction.byName(prop(device, "facing"));
            return face != null && device.relative(face).equals(owner);
        }
        return !type(device).isEmpty();
    }

    public String type(BlockPos p) {
        var name = id(p);
        if (name.isEmpty()) {
            // Verified native peripherals: their names must match the exporting modem's CC type.
            // Keep this detached; constructing arbitrary mod block entities needs a live world.
            return switch (doc.blockName(p)) {
                case "create:rotation_speed_controller" -> "Create_RotationSpeedController";
                case "create_radar:plane_radar" -> "create_radar:plane_radar";
                case "create_radar:radar_bearing" -> "create_radar:radar_bearing";
                case "create_radar:fire_controller" -> "create_radar:fire_controller";
                case "simulated:optical_sensor" -> "optical_sensor";
                case "simulated:altitude_sensor" -> "altitude_sensor";
                default -> "";
            };
        }
        if (name.startsWith("computercraft:monitor_")) return "monitor";
        if (name.startsWith("computercraft:computer_")) return "computer";
        if (name.startsWith("computercraft:turtle_")) return "turtle";
        if (name.contains("wireless_modem")
                || name.equals("computercraft:wired_modem_full")
                || name.equals("computercraft:cable") && face(p) != null) return "modem";
        return switch (name) {
            case "computercraft:disk_drive" -> "drive";
            case "computercraft:printer" -> "printer";
            case "computercraft:speaker" -> "speaker";
            case "computercraft:redstone_relay" -> "redstone_relay";
            default -> "";
        };
    }

    public Set<BlockPos> wires(BlockPos owner) {
        var result = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        for (var d : Direction.values()) {
            var p = owner.relative(d);
            if (!builtins(owner).containsKey(ComputerSides.name(doc, owner, p))
                    && wired(p)
                    && attached(p, owner)
                    && result.add(p)) queue.add(p);
        }
        while (!queue.isEmpty()) {
            var p = queue.remove();
            for (var d : Direction.values()) {
                var q = p.relative(d);
                if (port(p, d) && port(q, d.getOpposite()) && result.add(q)) queue.add(q);
            }
        }
        return result;
    }

    public List<Port> ports(BlockPos modem) {
        if (!shared(modem)) return List.of();
        var result = new ArrayList<Port>();
        boolean full = id(modem).equals("computercraft:wired_modem_full");
        for (var d : Direction.values()) {
            if (!full && face(modem) != d) continue;
            var p = modem.relative(d);
            var type = type(p);
            // CC excludes other wired modems from exported local peripherals.
            if (type.isEmpty()
                    || id(p).equals("computercraft:wired_modem_full")
                    || id(p).equals("computercraft:cable")) continue;
            if (!attached(p, modem)) continue;
            result.add(new Port(modem, p, full ? Integer.toString(d.get3DDataValue()) : "", type));
        }
        return result;
    }

    private boolean wireless(BlockPos owner) {
        for (var d : Direction.values()) {
            var p = owner.relative(d);
            if (id(p).contains("wireless_modem") && attached(p, owner)) return true;
        }
        // Built-in turtle modems are not represented by a neighboring block.
        return builtins(owner).containsValue("modem");
    }

    public Map<String, String> builtins(BlockPos owner) {
        if (!id(owner).startsWith("computercraft:turtle_")) return Map.of();
        var data = doc.blockEntityData(owner);
        var result = new LinkedHashMap<String, String>();
        for (String side : List.of("Left", "Right")) {
            String key = side + "Upgrade";
            String upgrade =
                    data.contains(key, 8)
                            ? data.getString(key)
                            : data.getCompound(key).getString("id");
            if (upgrade.contains("wireless_modem"))
                result.put(side.toLowerCase(Locale.ROOT), "modem");
            else if (upgrade.equals("computercraft:speaker"))
                result.put(side.toLowerCase(Locale.ROOT), "speaker");
        }
        return Map.copyOf(result);
    }

    public List<Device> devices(BlockPos owner) {
        var result = new LinkedHashMap<BlockPos, Device>();
        for (var d : Direction.values()) {
            var p = owner.relative(d);
            if (!builtins(owner).containsKey(ComputerSides.name(doc, owner, p))
                    && attached(p, owner))
                result.put(p, new Device(p, type(p), ComputerSides.name(doc, owner, p), false));
        }
        var wires = wires(owner);
        for (var modem : wires.stream().sorted().toList())
            for (var port : ports(modem))
                if (!port.device().equals(owner))
                    result.putIfAbsent(
                            port.device(), new Device(port.device(), port.type(), "wired", false));
        if (wireless(owner))
            states.forEach(
                    (p, state) -> {
                        if (!p.equals(owner)
                                && PatchDevices.kind(state.getString("Name")).equals("computer")
                                && wireless(p))
                            result.putIfAbsent(p, new Device(p, type(p), "radio", true));
                    });
        return result.values().stream().sorted(Comparator.comparing(d -> d.pos())).toList();
    }

    /** Accept captured modem endpoints as well as the new device endpoints. */
    public Optional<Device> referenceDevice(BlockPos owner, BlockPos target) {
        var direct = devices(owner).stream().filter(d -> d.pos().equals(target)).findFirst();
        if (direct.isPresent()) return direct;
        // Older files name the exporting modem, rather than its attached device.
        if (wires(owner).contains(target)) {
            var ports = ports(target);
            var data = doc.blockEntityData(target);
            long names =
                    data.getAllKeys().stream()
                            .filter(
                                    k ->
                                            k.startsWith("PeripheralId")
                                                    && data.getInt(k) >= 0
                                                    && !data.getString(
                                                                    "PeripheralType"
                                                                            + k.substring(12))
                                                            .isEmpty())
                            .count();
            if (ports.size() == 1 && names == 1)
                return Optional.of(new Device(target, ports.getFirst().type(), "wired", false));
        }
        return Optional.empty();
    }

    /**
     * Preserve existing names through moves/rotations and allocate names for newly reachable
     * devices.
     */
    public static JsonObject usedReferences(JsonObject original) {
        // Lua may address a binding through single quotes, aliases or computed keys. Absence
        // of a literal is not proof that an automatically suggested name is unused.
        return original.deepCopy();
    }

    public JsonObject withReferences(JsonObject original) {
        var patch = original.deepCopy();
        var refs = patch.has("references") ? patch.getAsJsonObject("references") : new JsonObject();
        var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
        var devices = devices(owner);
        // Keep existing bindings, including currently unreachable ones, until explicitly removed.
        // Normal reference validation reports disconnected targets without silently changing code.
        for (var device : devices) {
            if (refs.entrySet().stream()
                    .anyMatch(
                            e ->
                                    owner.offset(
                                                    SchematicPatches.position(
                                                            e.getValue()
                                                                    .getAsJsonObject()
                                                                    .getAsJsonArray("offset")))
                                            .equals(device.pos()))) continue;
            String base = device.type().replace(':', '_'), name = base;
            int number = 2;
            while (refs.has(name)) name = base + "_" + number++;
            var endpoint = new JsonObject();
            endpoint.add("offset", SchematicPatches.vector(device.pos().subtract(owner)));
            endpoint.addProperty("block", doc.blockName(device.pos()));
            endpoint.addProperty("automatic", true);
            refs.add(name, endpoint);
        }
        patch.add("references", refs);
        return patch;
    }
}
