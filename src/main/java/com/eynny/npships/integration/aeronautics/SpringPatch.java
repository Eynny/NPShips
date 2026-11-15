package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.JsonObject;

import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity;
import dev.simulated_team.simulated.content.items.spring.SpringItemHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.function.Function;

/** Pair-local coordinates are resolved after assembly; never reuse source body UUIDs. */
public final class SpringPatch {
    private SpringPatch() {}

    public static boolean matches(String id) {
        return id.equals("simulated:spring");
    }

    public static double length(JsonObject patch) {
        double value = patch.has("length") ? patch.get("length").getAsDouble() : 1;
        if (!Double.isFinite(value) || value < 1 || value > SpringItemHandler.MAX_LENGTH)
            throw new IllegalArgumentException(
                    "Spring length must be 1 .. " + SpringItemHandler.MAX_LENGTH);
        return value;
    }

    public static void strip(CompoundTag data) {
        data.remove("Goal");
        data.remove("GoalSubLevel");
        data.remove("Controller");
    }

    public static CompoundTag visual(JsonObject patch, BlockPos position) {
        var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
        var end =
                owner.offset(
                        SchematicPatches.position(
                                patch.getAsJsonObject("target").getAsJsonArray("offset")));
        var result = new CompoundTag();
        result.putBoolean("Controller", position.equals(owner));
        result.putLong("Goal", (position.equals(owner) ? end : owner).asLong());
        result.putDouble("DesiredLength", length(patch));
        return result;
    }

    public static JsonObject capture(
            ServerLevel level, BlockPos origin, BlockPos position, SchematicDocument doc) {
        return Native.capture(level, origin, position, doc);
    }

    public static void restore(
            ServerLevel level, JsonObject patch, Function<BlockPos, BlockPos> resolve) {
        Native.restore(level, patch, resolve);
    }

    /** Pure source validation must not verify optional native subclasses. */
    private static final class Native {
        static JsonObject capture(
                ServerLevel level, BlockPos origin, BlockPos position, SchematicDocument doc) {
            var result = new JsonObject();
            result.addProperty("kind", "spring");
            if (level.getBlockEntity(origin.offset(position)) instanceof SpringBlockEntity be) {
                var data = be.saveWithoutMetadata(level.registryAccess());
                result.addProperty(
                        "length",
                        Math.clamp(
                                data.getDouble("DesiredLength"), 1, SpringItemHandler.MAX_LENGTH));
                var paired = be.getPairedSpring();
                if (paired != null) {
                    var target = paired.getBlockPos().subtract(origin);
                    if (!doc.contains(target) || !matches(doc.blockName(target)))
                        throw new IllegalArgumentException(
                                "Include both spring endpoints in the schematic");
                    if (be.isController())
                        result.add("target", PatchDevices.endpoint(doc, position, target));
                } else if (paired == null && data.contains("Goal"))
                    throw new IllegalArgumentException("Spring counterpart is not loaded");
            }
            return result;
        }

        public static void restore(
                ServerLevel level, JsonObject patch, Function<BlockPos, BlockPos> resolve) {
            if (!patch.has("target")) return;
            var p = SchematicPatches.position(patch.getAsJsonArray("pos"));
            var target =
                    p.offset(
                            SchematicPatches.position(
                                    patch.getAsJsonObject("target").getAsJsonArray("offset")));
            if (!(level.getBlockEntity(resolve.apply(p)) instanceof SpringBlockEntity a)
                    || !(level.getBlockEntity(resolve.apply(target))
                            instanceof SpringBlockEntity b))
                throw new IllegalArgumentException("Missing spring endpoint after assembly");
            double value = length(patch);
            var aBody = Sable.HELPER.getContaining(a);
            var bBody = Sable.HELPER.getContaining(b);
            a.setController(true);
            b.setController(false);
            a.setDesiredLength(value);
            b.setDesiredLength(value);
            a.setPartnerPos(b.getBlockPos(), bBody == null ? null : bBody.getUniqueId());
            b.setPartnerPos(a.getBlockPos(), aBody == null ? null : aBody.getUniqueId());
            a.setChanged();
            b.setChanged();
        }
    }
}
