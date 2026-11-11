package com.eynny.npships.integration.computercraft;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import java.util.*;

/** Detached reference candidates, not a mechanism for creating CC network connections. */
public final class ComputerReferences {
    private ComputerReferences() {}

    public static SchematicDocument assign(
            SchematicDocument doc, BlockPos owner, BlockPos target, String name) {
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"))
            throw invalid("reference_name", "Invalid Lua reference name: " + name, name);
        if (!PatchDevices.kind(doc, owner).equals("computer")
                || !available(doc, owner).contains(target))
            throw invalid(
                    "reference_target",
                    "Device is not reachable from this computer.",
                    owner,
                    target);
        var source = doc.withConfig(SchematicPatches.authoringConfig(doc));
        var patch =
                SchematicPatches.read(source.configCopy()).stream()
                        .filter(
                                p ->
                                        !ExternalPatches.external(p)
                                                && p.get("kind").getAsString().equals("computer")
                                                && SchematicPatches.position(
                                                                p.getAsJsonArray("pos"))
                                                        .equals(owner))
                        .findFirst()
                        .map(JsonObject::deepCopy)
                        .orElseGet(() -> PatchDevices.create(source, owner));
        var refs = patch.has("references") ? patch.getAsJsonObject("references") : new JsonObject();
        var endpoint = new JsonObject();
        endpoint.add("offset", SchematicPatches.vector(target.subtract(owner)));
        endpoint.addProperty("block", doc.blockName(target));
        if (refs.has(name) && !refs.get(name).equals(endpoint))
            throw invalid(
                    "reference_exists",
                    "Reference already exists: " + name + ". Choose another name.",
                    name,
                    owner,
                    target);
        refs.add(name, endpoint);
        patch.add("references", refs);
        return PatchDevices.put(source, patch);
    }

    public static Set<BlockPos> available(SchematicDocument doc, BlockPos owner) {
        return available(doc, owner, states(doc));
    }

    public static Set<BlockPos> owners(SchematicDocument doc, BlockPos target) {
        var states = states(doc);
        var result = new HashSet<BlockPos>();
        states.forEach(
                (pos, state) -> {
                    if (PatchDevices.kind(state.getString("Name")).equals("computer")
                            && available(doc, pos, states).contains(target)) result.add(pos);
                });
        return Set.copyOf(result);
    }

    private static Map<BlockPos, CompoundTag> states(SchematicDocument doc) {
        var palette = doc.paletteCopy();
        var states = new HashMap<BlockPos, CompoundTag>();
        doc.blocks()
                .forEach(
                        (p, i) -> {
                            var state = palette.getCompound(i);
                            if (state.getString("Name").startsWith("computercraft:"))
                                states.put(p, state);
                        });
        return states;
    }

    private static Set<BlockPos> available(
            SchematicDocument doc, BlockPos owner, Map<BlockPos, CompoundTag> states) {
        var result = new HashSet<BlockPos>();
        var network = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        states.forEach(
                (p, state) -> {
                    // An ID may address a remote radio peer; it does not promise a route.
                    if (!p.equals(owner)
                            && PatchDevices.kind(state.getString("Name")).equals("computer"))
                        result.add(p);
                });
        for (var direction : Direction.values()) {
            var p = owner.relative(direction);
            var state = states.get(p);
            if (state == null) continue;
            String id = state.getString("Name");
            var properties = state.getCompound("Properties");
            boolean attached =
                    id.equals("computercraft:wired_modem_full")
                            || id.equals("computercraft:cable")
                                    && modem(state) == direction.getOpposite()
                            || id.contains("wireless_modem_")
                                    && properties
                                            .getString("facing")
                                            .equals(direction.getOpposite().getName());
            if (attached
                    || (!id.contains("modem")
                            && !id.equals("computercraft:cable")
                            && PatchDevices.kind(id).equals("peripheral"))) result.add(p);
            if (attached && wired(state)) {
                network.add(p);
                queue.add(p);
            }
        }
        while (!queue.isEmpty()) {
            var p = queue.remove();
            for (var face : Direction.values()) {
                var next = p.relative(face);
                if (connects(states.get(p), face)
                        && connects(states.get(next), face.getOpposite())
                        && network.add(next)) queue.add(next);
            }
        }
        for (var p : network) {
            var state = states.get(p);
            var props = state.getCompound("Properties");
            boolean shared =
                    state.getString("Name").equals("computercraft:wired_modem_full")
                            ? props.getString("peripheral").equals("true")
                            : props.getString("modem").endsWith("_peripheral");
            if (!shared) continue;
            var data = doc.blockEntityData(p);
            long addresses =
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
            // The current reference format has one address per target, not a full-modem port
            // selector.
            if (addresses == 1) {
                String key =
                        data.getAllKeys().stream()
                                .filter(
                                        k ->
                                                k.startsWith("PeripheralId")
                                                        && data.getInt(k) >= 0
                                                        && !data.getString(
                                                                        "PeripheralType"
                                                                                + k.substring(12))
                                                                .isEmpty())
                                .findFirst()
                                .orElseThrow();
                Direction face = modem(state);
                if (state.getString("Name").equals("computercraft:wired_modem_full")) {
                    try {
                        int index = Integer.parseInt(key.substring(12));
                        face = index >= 0 && index < 6 ? Direction.from3DDataValue(index) : null;
                    } catch (NumberFormatException ignored) {
                        face = null;
                    }
                }
                // A saved address alone is insufficient after deleting the attached device.
                if (face != null && !doc.blockName(p.relative(face)).equals("minecraft:air"))
                    result.add(p);
            }
        }
        return Set.copyOf(result);
    }

    private static Direction modem(CompoundTag state) {
        String value = state.getCompound("Properties").getString("modem");
        int end = value.indexOf('_');
        return Direction.byName(end < 0 ? value : value.substring(0, end));
    }

    private static boolean wired(CompoundTag state) {
        return state != null
                && (state.getString("Name").equals("computercraft:wired_modem_full")
                        || state.getString("Name").equals("computercraft:cable")
                                && state.getCompound("Properties")
                                        .getString("cable")
                                        .equals("true"));
    }

    private static boolean connects(CompoundTag state, Direction face) {
        return wired(state)
                && (state.getString("Name").equals("computercraft:wired_modem_full")
                        || modem(state) != face);
    }
}
