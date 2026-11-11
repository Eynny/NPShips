package com.eynny.npships.integration;

import static com.eynny.npships.authoring.patches.PatchDevices.targets;

import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.integration.aeronautics.*;
import com.eynny.npships.integration.cannons.CannonWelds;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

import java.util.*;

/** Known native graph grammar. Pure identity cleanup is mandatory, not disabled restoration. */
final class NativeGraphData {
    private NativeGraphData() {}

    static boolean owns(String type, String kind) {
        return type.equals(
                switch (kind) {
                    case "computer" -> "computercraft_computer";
                    case "peripheral" -> "computercraft_peripheral";
                    case "media" -> "computercraft_media";
                    case "radar" -> "radars_network";
                    case "balloon" -> "aeronautics_lifting_gas";
                    case "arm" -> "create_arm";
                    case "logistics" -> "create_logistics";
                    case "mechanism" -> "simulated_swivel";
                    case "rope", "spring", "docking" -> "simulated_" + kind;
                    default -> "";
                });
    }

    static void replacing(String type, JsonObject accepted, List<JsonObject> entries) {
        if (type.equals("simulated_" + accepted.get("kind").getAsString())
                && Set.of("rope", "docking", "spring")
                        .contains(accepted.get("kind").getAsString())) {
            var endpoints = new HashSet<BlockPos>();
            endpoints.add(SchematicPatches.position(accepted.getAsJsonArray("pos")));
            endpoints.addAll(targets(accepted));
            for (var previous : entries)
                if (previous.get("kind").equals(accepted.get("kind"))
                        && (endpoints.contains(
                                        SchematicPatches.position(previous.getAsJsonArray("pos")))
                                || targets(previous).stream().anyMatch(endpoints::contains))) {
                    previous.remove("target");
                    previous.remove("extension");
                }
        }
    }

    static void clear(String type, String sourceBlock, CompoundTag data) {
        boolean owns =
                switch (type) {
                    case "create_arm",
                                    "simulated_spring",
                                    "simulated_rope",
                                    "simulated_docking",
                                    "computercraft_computer",
                                    "radars_network" ->
                            !NativeDeviceDescriptions.kind(type, sourceBlock).isEmpty();
                    case "computercraft_peripheral" ->
                            sourceBlock.equals("computercraft:cable")
                                    || !NativeDeviceDescriptions.kind(type, sourceBlock).isEmpty();
                    default -> false;
                };
        if (!owns) return;
        switch (type) {
            case "create_arm" -> data.remove("InteractionPoints");
            case "simulated_spring" -> {
                if (data.contains("Goal") || data.contains("GoalSubLevel")) SpringPatch.strip(data);
            }
            case "simulated_rope" -> AeronauticsPatch.stripRope(data);
            case "simulated_docking" -> {
                if (data.contains("OtherConnector") || data.contains("OtherConnectorSubLevelId"))
                    DockingPatch.strip(data);
            }
            case "computercraft_computer" -> data.remove("ComputerId");
            case "computercraft_peripheral" -> {
                for (String key : List.copyOf(data.getAllKeys()))
                    if (key.startsWith("PeripheralId")) data.remove(key);
            }
            case "radars_network" -> {
                for (String key :
                        List.of(
                                "TargetOffset",
                                "LinkedShipId",
                                "TargetOffsetShip",
                                "LastKnownPos",
                                "SelectedFiltererPos",
                                "SelectedMountPos",
                                "monitorPos")) data.remove(key);
            }
            default -> {}
        }
    }

    static boolean brush(String type, String name, CompoundTag data) {
        if (type.equals("cbc_welds")) CannonWelds.detachedCopy(data);
        if (type.equals("simulated_docking") && DockingPatch.matches(name)) {
            DockingPatch.strip(data);
            return true;
        }
        if (type.equals("simulated_rope") && AeronauticsPatch.rope(name)) {
            AeronauticsPatch.stripRope(data);
            return true;
        }
        return false;
    }

    public static void removeTarget(JsonObject patch, BlockPos owner, BlockPos target) {
        if (ExternalPatches.external(patch)) return;
        for (String key :
                List.of(
                        "target",
                        "datalink",
                        "monitor_controller",
                        "SelectedFiltererPos",
                        "SelectedMountPos"))
            if (patch.has(key) && pointsTo(patch.get(key), owner, target)) patch.remove(key);
        if (patch.get("kind").getAsString().equals("arm") && patch.has("points")) {
            var points = patch.getAsJsonArray("points");
            for (int i = points.size() - 1; i >= 0; i--)
                if (pointsTo(points.get(i), owner, target)) points.remove(i);
        }
        if (patch.has("fuze_bindings")) {
            var bindings = patch.getAsJsonArray("fuze_bindings");
            for (int i = bindings.size() - 1; i >= 0; i--)
                if (pointsTo(bindings.get(i), owner, target)) bindings.remove(i);
        }
        if (patch.has("bindings")) {
            var bindings = patch.getAsJsonArray("bindings");
            for (int i = bindings.size() - 1; i >= 0; i--)
                if (pointsTo(bindings.get(i), owner, target)) bindings.remove(i);
        }
        if (patch.has("references")) {
            var refs = patch.getAsJsonObject("references");
            for (String key : List.copyOf(refs.keySet()))
                if (pointsTo(refs.get(key), owner, target)) refs.remove(key);
        }
        for (String name : List.of("network", "weapon"))
            if (patch.has(name)) {
                var group = patch.getAsJsonObject(name);
                for (String key : List.copyOf(group.keySet())) {
                    var v = group.get(key);
                    if (v == null || v.isJsonNull()) continue;
                    if (v.isJsonArray()) {
                        var a = v.getAsJsonArray();
                        for (int i = a.size() - 1; i >= 0; i--)
                            if (pointsTo(a.get(i), owner, target)) a.remove(i);
                    } else if (pointsTo(v, owner, target)) {
                        group.remove(key);
                        if (key.equals("radar")) group.remove("radar_kind");
                    }
                }
            }
    }

    private static boolean pointsTo(JsonElement e, BlockPos owner, BlockPos target) {
        if (ExternalPatches.external(e)) return false;
        if (e.isJsonObject()) {
            var o = e.getAsJsonObject();
            if (o.has("offset")
                    && owner.offset(SchematicPatches.position(o.getAsJsonArray("offset")))
                            .equals(target)) return true;
            for (var v : o.entrySet()) if (pointsTo(v.getValue(), owner, target)) return true;
        } else if (e.isJsonArray())
            for (var v : e.getAsJsonArray()) if (pointsTo(v, owner, target)) return true;
        return false;
    }
}
