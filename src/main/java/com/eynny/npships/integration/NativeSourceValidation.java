package com.eynny.npships.integration;

import static com.eynny.npships.authoring.patches.SchematicPatches.position;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.aeronautics.AeronauticsPatch;
import com.eynny.npships.integration.aeronautics.DockingPatch;
import com.eynny.npships.integration.aeronautics.SpringPatch;
import com.eynny.npships.integration.computercraft.ComputerMedia;
import com.eynny.npships.integration.create.LogisticsPatch;
import com.eynny.npships.integration.create.MechanismPatch;
import com.eynny.npships.integration.radars.RadarFuzes;
import com.eynny.npships.integration.radars.RadarPatch;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Set;
import java.util.function.Function;

/** Native source validators used by the registered patch providers. */
public final class NativeSourceValidation {
    public static void references(SchematicDocument doc, java.util.List<JsonObject> all) {
        var devices = new HashMap<BlockPos, String>();
        for (var e : all)
            if (!ExternalPatches.external(e))
                devices.put(position(e.getAsJsonArray("pos")), e.get("kind").getAsString());
        var network =
                all.stream().anyMatch(e -> !ExternalPatches.external(e) && e.has("references"))
                        ? new com.eynny.npships.integration.computercraft.ComputerNetwork(doc)
                        : null;
        for (var e : all)
            if (!ExternalPatches.external(e) && e.has("references"))
                for (var ref : e.getAsJsonObject("references").entrySet()) {
                    BlockPos target =
                            position(e.getAsJsonArray("pos"))
                                    .offset(
                                            position(
                                                    ref.getValue()
                                                            .getAsJsonObject()
                                                            .getAsJsonArray("offset")));
                    if (!Set.of("computer", "peripheral").contains(devices.getOrDefault(target, ""))
                            && (doc.blockName(target).startsWith("computercraft:")
                                    || network.referenceDevice(
                                                    position(e.getAsJsonArray("pos")), target)
                                            .isEmpty()))
                        throw new IllegalArgumentException(
                                "Named reference '"
                                        + ref.getKey()
                                        + "' needs CC patch data at "
                                        + target);
                }
    }

    /** Exact detached native scalar/pair guards; source safety does not depend on enable policy. */
    public static void validate(
            String provider,
            SchematicDocument doc,
            JsonObject patch,
            java.util.Set<net.minecraft.core.BlockPos> ropeEnds) {
        validate(provider, doc, patch);
        String kind = patch.get("kind").getAsString();
        var p = SchematicPatches.position(patch.getAsJsonArray("pos"));
        if (java.util.Set.of("computer", "peripheral").contains(kind)
                && provider.equals("computercraft_" + kind)
                && !com.eynny.npships.authoring.patches.PatchDevices.kind(doc, p).equals(kind))
            throw new IllegalArgumentException("Patch type does not match its device: " + p);
        if (!java.util.Set.of("spring", "docking", "rope", "mechanism", "balloon").contains(kind)
                || !com.eynny.npships.api.patches.PatchTypes.legacyId(kind)
                        .getPath()
                        .equals(provider)) return;
        if (kind.equals("spring")) {
            SpringPatch.length(patch);
            if (!SpringPatch.matches(patch.get("block").getAsString()))
                throw new IllegalArgumentException("Spring patch requires spring endpoints");
            if (patch.has("target")) {
                var end = patch.getAsJsonObject("target");
                var target = p.offset(position(end.getAsJsonArray("offset")));
                if (!SpringPatch.matches(end.get("block").getAsString())
                        || target.equals(p)
                        || !ropeEnds.add(p)
                        || !ropeEnds.add(target))
                    throw new IllegalArgumentException("Spring endpoints must form separate pairs");
            }
        }
        if (kind.equals("docking")) {
            if (!DockingPatch.matches(patch.get("block").getAsString()))
                throw new IllegalArgumentException("Docking patch requires a docking connector");
            if (patch.has("target")) {
                var end = patch.getAsJsonObject("target");
                var target = p.offset(position(end.getAsJsonArray("offset")));
                if (!DockingPatch.matches(end.get("block").getAsString())
                        || target.equals(p)
                        || !ropeEnds.add(p)
                        || !ropeEnds.add(target))
                    throw new IllegalArgumentException(
                            "Docking connectors must form separate pairs");
            }
        }
        if (kind.equals("mechanism") && !MechanismPatch.matches(patch.get("block").getAsString()))
            throw new IllegalArgumentException("Unsupported assembly target");
        if (kind.equals("rope")) {
            if (!AeronauticsPatch.rope(patch.get("block").getAsString()))
                throw new IllegalArgumentException("Rope patch requires a rope holder.");
            if (patch.has("target")) {
                var end = patch.getAsJsonObject("target");
                var target = p.offset(position(end.getAsJsonArray("offset")));
                if (!AeronauticsPatch.rope(end.get("block").getAsString())
                        || target.equals(p)
                        || !ropeEnds.add(p)
                        || !ropeEnds.add(target))
                    throw new IllegalArgumentException(
                            "Rope holders must form separate pairs: " + p);
            }
        }
        if (kind.equals("balloon") && !AeronauticsPatch.burner(patch.get("block").getAsString()))
            throw new IllegalArgumentException(
                    "Balloon patch requires an Aeronautics gas provider.");
        if (Set.of("rope", "balloon").contains(patch.get("kind").getAsString()))
            try {
                AeronauticsPatch.validate(patch);
            } catch (Exception e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
    }

    private NativeSourceValidation() {}

    public static void validate(String provider, SchematicDocument doc, JsonObject patch) {
        String kind = patch.get("kind").getAsString();
        switch (provider) {
            case "computercraft_media" -> ComputerMedia.validate(patch);
            case "radars_guided_fuzes" -> RadarFuzes.validate(patch, doc);
            case "radars_network" -> {
                if (kind.equals("radar")) RadarPatch.validate(patch, doc);
            }
            case "create_logistics" -> {
                if (!kind.equals("logistics")) return;
                if (!LogisticsPatch.matches(patch.get("block").getAsString()))
                    throw new IllegalArgumentException("Not a logistics block");
                var p = SchematicPatches.position(patch.getAsJsonArray("pos"));
                // blockEntityData returns a detached copy. Native write validates that scratch
                // graph; no accepted document, live BE or native world is exposed or retained.
                LogisticsPatch.write(
                        patch, doc.blockEntityData(p), Function.identity(), new HashMap<>());
            }
            default -> {}
        }
    }
}
