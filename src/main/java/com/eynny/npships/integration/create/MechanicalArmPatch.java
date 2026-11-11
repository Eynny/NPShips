package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;
import com.simibubi.create.content.kinetics.mechanicalArm.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.function.Function;

public final class MechanicalArmPatch {
    private MechanicalArmPatch() {}

    public static JsonObject capture(SchematicDocument doc, BlockPos owner, CompoundTag data) {
        var patch = new JsonObject();
        patch.addProperty("kind", "arm");
        patch.addProperty("block", "create:mechanical_arm");
        patch.add("pos", SchematicPatches.vector(owner));
        patch.addProperty(
                "selection_mode",
                ArmBlockEntity.SelectionMode.values()[
                        Math.max(0, Math.min(2, data.getInt("ScrollValue")))]
                        .name());
        var points = new JsonArray();
        for (var value : data.getList("InteractionPoints", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) value;
            var offset =
                    NbtUtils.readBlockPos(tag, "Pos")
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Arm point has no position"));
            var point = PatchDevices.endpoint(doc, owner, owner.offset(offset));
            point.addProperty("data", tag.toString());
            points.add(point);
        }
        patch.add("points", points);
        return patch;
    }

    public static boolean compatible(Level view, BlockPos owner, BlockPos target) {
        return !owner.equals(target)
                && target.closerThan(owner, ArmBlockEntity.getRange())
                && ArmInteractionPoint.isInteractable(view, target, view.getBlockState(target));
    }

    public static JsonObject connect(
            SchematicDocument doc, JsonObject original, Level view, BlockPos target, boolean take) {
        var owner = SchematicPatches.position(original.getAsJsonArray("pos"));
        if (!compatible(view, owner, target))
            throw new IllegalArgumentException("Arm target is unsupported or out of range.");
        var point = ArmInteractionPoint.create(view, target, view.getBlockState(target));
        if (point == null)
            throw new IllegalArgumentException("Cannot create arm interaction point.");
        if ((point.getMode() == ArmInteractionPoint.Mode.TAKE) != take) point.cycleMode();
        if ((point.getMode() == ArmInteractionPoint.Mode.TAKE) != take)
            throw new IllegalArgumentException(
                    "This arm point does not support that transfer direction.");
        var patch = original.deepCopy();
        var points = patch.getAsJsonArray("points");
        var entry = PatchDevices.endpoint(doc, owner, target);
        entry.addProperty("data", point.serialize(owner).toString());
        for (int i = 0; i < points.size(); i++)
            if (SchematicPatches.position(points.get(i).getAsJsonObject().getAsJsonArray("offset"))
                    .equals(target.subtract(owner))) {
                points.set(i, entry);
                return patch;
            }
        points.add(entry);
        return patch;
    }

    public static void validate(JsonObject patch) {
        if (!patch.get("block").getAsString().equals("create:mechanical_arm"))
            throw new IllegalArgumentException("Arm patch requires a mechanical arm.");
        ArmBlockEntity.SelectionMode.valueOf(patch.get("selection_mode").getAsString());
        var seen = new HashSet<BlockPos>();
        var points = patch.getAsJsonArray("points");
        if (points == null || points.size() > 1024)
            throw new IllegalArgumentException("Invalid arm point list.");
        for (var value : points) {
            var point = value.getAsJsonObject();
            var offset = SchematicPatches.position(point.getAsJsonArray("offset"));
            if (offset.equals(BlockPos.ZERO) || !seen.add(offset))
                throw new IllegalArgumentException("Duplicate or self-referencing arm point.");
            var data = data(point);
            ArmInteractionPoint.Mode.valueOf(data.getString("Mode"));
            var id = net.minecraft.resources.ResourceLocation.tryParse(data.getString("Type"));
            if (id == null
                    || !com.simibubi.create.api.registry.CreateBuiltInRegistries
                            .ARM_INTERACTION_POINT_TYPE
                            .containsKey(id))
                throw new IllegalArgumentException("Unknown arm interaction type.");
        }
    }

    private static CompoundTag data(JsonObject point) {
        try {
            return com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                    point.get("data").getAsString());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid arm point NBT", e);
        }
    }

    public static void write(
            JsonObject patch, CompoundTag data, Function<BlockPos, BlockPos> resolve) {
        var local = SchematicPatches.position(patch.getAsJsonArray("pos"));
        var owner = resolve.apply(local);
        var points = new ListTag();
        for (var value : patch.getAsJsonArray("points")) {
            var point = value.getAsJsonObject();
            var tag = data(point);
            var target =
                    resolve.apply(
                            local.offset(
                                    SchematicPatches.position(point.getAsJsonArray("offset"))));
            tag.put("Pos", NbtUtils.writeBlockPos(target.subtract(owner)));
            points.add(tag);
        }
        data.put("InteractionPoints", points);
        data.putInt(
                "ScrollValue",
                ArmBlockEntity.SelectionMode.valueOf(patch.get("selection_mode").getAsString())
                        .ordinal());
        data.putString(
                "Phase",
                data.getCompound("HeldItem").isEmpty() ? "SEARCH_INPUTS" : "SEARCH_OUTPUTS");
        data.putInt("TargetPointIndex", -1);
        data.putFloat("MovementProgress", 0);
    }

    public static void restore(
            ServerLevel level, JsonObject patch, Function<BlockPos, BlockPos> resolve) {
        var owner = resolve.apply(SchematicPatches.position(patch.getAsJsonArray("pos")));
        var be = level.getBlockEntity(owner);
        if (!(be instanceof ArmBlockEntity arm))
            throw new IllegalStateException("Missing mechanical arm after assembly: " + owner);
        var data = arm.saveWithFullMetadata(level.registryAccess());
        write(patch, data, resolve);
        arm.loadWithComponents(data, level.registryAccess());
        ((com.eynny.npships.mixin.NPShipsArmAccessor) arm).npships$updateInteractionPoints(true);
        arm.setChanged();
        arm.sendData();
    }
}
