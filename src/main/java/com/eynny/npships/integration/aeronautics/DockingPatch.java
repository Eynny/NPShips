package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.JsonObject;

import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.function.Function;

public final class DockingPatch {
    private DockingPatch() {}

    public static boolean matches(String id) {
        return id.equals("simulated:docking_connector");
    }

    public static JsonObject capture(
            ServerLevel level, BlockPos origin, BlockPos p, SchematicDocument doc) {
        var patch = new JsonObject();
        patch.addProperty("kind", "docking");
        if (level.getBlockEntity(origin.offset(p)) instanceof DockingConnectorBlockEntity be) {
            var other = be.getOtherConnector();
            if (other != null) {
                var target = other.getBlockPos().subtract(origin);
                if (!doc.contains(target) || !matches(doc.blockName(target)))
                    throw new IllegalArgumentException(
                            "Include both docking connectors; external physical bodies cannot be exported as one block volume.");
                if (p.compareTo(target) < 0)
                    patch.add("target", PatchDevices.endpoint(doc, p, target));
            } else if (be.otherConnectorSubLevelId != null || be.otherConnectorPosition != null)
                throw new IllegalArgumentException("Docking counterpart is not loaded at " + p);
        }
        return patch;
    }

    public static void strip(CompoundTag data) {
        for (String key :
                java.util.List.of(
                        "OtherConnector",
                        "OtherConnectorSubLevelId",
                        "Target",
                        "Extension",
                        "Feet")) data.remove(key);
    }

    public static void restore(
            ServerLevel level, JsonObject patch, Function<BlockPos, BlockPos> resolve) {
        if (!patch.has("target")) return;
        var p = SchematicPatches.position(patch.getAsJsonArray("pos"));
        var target =
                p.offset(
                        SchematicPatches.position(
                                patch.getAsJsonObject("target").getAsJsonArray("offset")));
        if (!(level.getBlockEntity(resolve.apply(p)) instanceof DockingConnectorBlockEntity a)
                || !(level.getBlockEntity(resolve.apply(target))
                        instanceof DockingConnectorBlockEntity b))
            throw new IllegalArgumentException("Missing docking connector after placement");
        // Native pairing owns the constraint, new sublevel IDs, inventory and CC network joins.
        if (a.getOtherConnector() == b && b.getOtherConnector() == a) return;
        a.updateSignal();
        b.updateSignal();
        if (!a.powered || !b.powered)
            throw new IllegalArgumentException(
                    "Docking patch needs redstone power at both connectors.");
        a.extension.setValue(1);
        b.extension.setValue(1);
        a.pairTo(b);
        if (a.getOtherConnector() != b)
            throw new IllegalArgumentException(
                    "Docking rejected: connectors must face each other and belong to compatible separate bodies.");
    }
}
