package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.OptionalCalls;
import com.google.gson.*;
import com.mojang.serialization.*;

import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.*;
import java.util.function.Function;

/** Recreates live links and gas with native APIs after assembly; never reuses saved physics IDs. */
public final class AeronauticsPatch {
    private static final String GAS = "dev.eriksonn.aeronautics.content.blocks.hot_air.";

    private AeronauticsPatch() {}

    public static boolean rope(String id) {
        return id.equals("simulated:rope_connector") || id.equals("simulated:rope_winch");
    }

    private static final Map<String, Boolean> PROVIDERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean burner(String id) {
        var known = PROVIDERS.get(id);
        if (known != null) return known;
        var name = net.minecraft.resources.ResourceLocation.tryParse(id);
        // Unknown authored IDs must not become process-lifetime negative cache entries.
        if (name == null
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(name))
            return false;
        return PROVIDERS.computeIfAbsent(
                id,
                key -> {
                    var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(name);
                    if (!(block instanceof net.minecraft.world.level.block.EntityBlock factory))
                        return false;
                    try {
                        return Class.forName(GAS + "BlockEntityLiftingGasProvider")
                                .isInstance(
                                        factory.newBlockEntity(
                                                BlockPos.ZERO, block.defaultBlockState()));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return false;
                    }
                });
    }

    /** Current world values for the editor; no detached balloon simulation. */
    public static CompoundTag gasState(ServerLevel level, BlockPos pos) throws Exception {
        var be = level.getBlockEntity(pos);
        if (be == null || !Class.forName(GAS + "BlockEntityLiftingGasProvider").isInstance(be))
            throw new IllegalArgumentException(
                    "Point at a lifting gas provider inside the selected project.");
        Object balloon = OptionalCalls.call(be, "getBalloon");
        if (balloon == null)
            throw new IllegalArgumentException(
                    "No enclosed balloon connected to this burner. Finish its shell first.");
        double amount = 0;
        for (Object holder :
                (java.util.List<?>) OptionalCalls.call(balloon, "getLiftingGasHolders")) {
            var data = OptionalCalls.call(holder, "data");
            amount += ((Number) data.getClass().getField("amount").get(data)).doubleValue();
        }
        var state = new CompoundTag();
        state.put("pos", net.minecraft.nbt.NbtUtils.writeBlockPos(pos));
        state.putDouble(
                "capacity", ((Number) OptionalCalls.call(balloon, "getCapacity")).doubleValue());
        state.putDouble("amount", amount);
        return state;
    }

    @SuppressWarnings("unchecked")
    private static Codec<List<Object>> gasCodec() throws Exception {
        return ((Codec<Object>)
                        Class.forName(GAS + "lifting_gas.LiftingGasHolder")
                                .getField("CODEC")
                                .get(null))
                .listOf();
    }

    public static JsonObject capture(
            ServerLevel level,
            BlockPos origin,
            BlockPos p,
            SchematicDocument doc,
            Set<Object> captured)
            throws Exception {
        var be = level.getBlockEntity(origin.offset(p));
        if (be instanceof RopeStrandHolderBlockEntity holder) {
            var behavior = holder.getBehavior();
            var patch = new JsonObject();
            patch.addProperty("kind", "rope");
            var strand = behavior.getOwnedStrand();
            if (behavior.isAttached()) {
                var attached = behavior.getAttachedStrand();
                if (attached == null)
                    throw new IllegalArgumentException("Rope data is not loaded at " + p);
                for (var attachment : attached.getAttachments())
                    if (!doc.contains(attachment.blockAttachment().subtract(origin)))
                        throw new IllegalArgumentException(
                                "Include both rope endpoints in the export: " + p);
            }
            if (strand != null) {
                BlockPos other = null;
                for (var attachment : strand.getAttachments())
                    if (!attachment.blockAttachment().equals(origin.offset(p))) {
                        if (other != null)
                            throw new IllegalArgumentException(
                                    "Rope has more than two attachments.");
                        other = attachment.blockAttachment();
                    }
                if (other == null)
                    throw new IllegalArgumentException(
                            "Rope endpoint is not a saved block at " + p);
                patch.add("target", PatchDevices.endpoint(doc, p, other.subtract(origin)));
                patch.addProperty(
                        "extension", strand.getPoints().size() - 2 + strand.getExtension());
            }
            return patch;
        }
        if (be == null || !Class.forName(GAS + "BlockEntityLiftingGasProvider").isInstance(be))
            return null;
        Object balloon = OptionalCalls.call(be, "getBalloon");
        if (balloon == null || !captured.add(balloon)) return null;
        // Balloon.getBounds is rounded to storage tiles, not the actual gas volume.
        Object graph = OptionalCalls.call(balloon, "getGraph");
        Object[] layers = (Object[]) OptionalCalls.call(graph, "getAllLayers");
        for (Object row : layers)
            if (row != null)
                for (Object layer : (List<?>) row) {
                    @SuppressWarnings("unchecked")
                    var blocks =
                            (Iterator<BlockPos>) OptionalCalls.call(layer, "nonSolidBlockIterator");
                    while (blocks.hasNext())
                        if (!doc.contains(blocks.next().subtract(origin)))
                            throw new IllegalArgumentException(
                                    "Include the entire balloon in the export: " + p);
                }
        var patch = new JsonObject();
        patch.addProperty("kind", "balloon");
        @SuppressWarnings("unchecked")
        var holders = (List<Object>) OptionalCalls.call(balloon, "getLiftingGasHolders");
        patch.add("gas", gasCodec().encodeStart(JsonOps.INSTANCE, holders).getOrThrow());
        return patch;
    }

    public static void validate(JsonObject patch) throws Exception {
        if (patch.get("kind").getAsString().equals("balloon")) {
            if (patch.has("fill_volume")) {
                double n = patch.get("fill_volume").getAsDouble();
                if (!Double.isFinite(n) || n < 0 || n > 64000000)
                    throw new IllegalArgumentException("Invalid balloon fill volume.");
            } else {
                var holders = gasCodec().parse(JsonOps.INSTANCE, patch.get("gas")).getOrThrow();
                if (holders.size() > 32)
                    throw new IllegalArgumentException("Too many lifting gases.");
                for (var h : holders) {
                    var data = OptionalCalls.call(h, "data");
                    for (String key : List.of("amount", "target", "nudge")) {
                        double n = ((Number) OptionalCalls.field(data, key)).doubleValue();
                        if (!Double.isFinite(n)
                                || Math.abs(n) > 64000000
                                || !key.equals("nudge") && n < 0)
                            throw new IllegalArgumentException("Invalid lifting gas " + key);
                    }
                }
            }
        }
        if (patch.has("extension")) {
            double n = patch.get("extension").getAsDouble();
            if (!Double.isFinite(n) || n < 0 || n > 4096)
                throw new IllegalArgumentException("Invalid rope extension.");
        }
    }

    public static void stripRope(CompoundTag data) {
        for (String key : List.of("OwnStrand", "HasRopeAttached", "Strand")) data.remove(key);
    }

    public static void restore(
            ServerLevel level,
            JsonObject patch,
            Function<BlockPos, BlockPos> resolve,
            Set<Object> restored)
            throws Exception {
        BlockPos p = SchematicPatches.position(patch.getAsJsonArray("pos"));
        Object be = level.getBlockEntity(resolve.apply(p));
        if (patch.get("kind").getAsString().equals("rope")) {
            if (!patch.has("target")) return;
            BlockPos target =
                    p.offset(
                            SchematicPatches.position(
                                    patch.getAsJsonObject("target").getAsJsonArray("offset")));
            if (!(be instanceof RopeStrandHolderBlockEntity owner)
                    || !(level.getBlockEntity(resolve.apply(target))
                            instanceof RopeStrandHolderBlockEntity end))
                throw new IllegalArgumentException("Missing rope attachment after assembly.");
            if (owner.getBehavior().isAttached() || end.getBehavior().isAttached())
                throw new IllegalArgumentException("Rope attachment already in use.");
            if (!owner.getBehavior().createRope(end.getBehavior(), false))
                throw new IllegalArgumentException(
                        "Rope connection rejected by Simulated (check distance).");
            // Native creation chooses current attachment positions and a fresh UUID. Extension can
            // only increase slack.
            if (patch.has("extension")) {
                var strand = owner.getBehavior().getOwnedStrand();
                double desired =
                        Math.max(
                                patch.get("extension").getAsDouble(),
                                strand.getPoints().size() - 2 + strand.getExtension());
                var world =
                        dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(
                                level, owner.getBehavior().getAttachmentPoint());
                while (strand.getPoints().size() < Math.floor(desired) + 2)
                    strand.addPoint(new org.joml.Vector3d(world.x, world.y, world.z));
                strand.updateFirstSegmentExtension(desired - (strand.getPoints().size() - 2));
            }
            return;
        }
        if (be == null || !Class.forName(GAS + "BlockEntityLiftingGasProvider").isInstance(be))
            throw new IllegalArgumentException("Missing balloon burner after assembly.");
        OptionalCalls.call(be, "doRaycast");
        OptionalCalls.call(be, "tryJoinBalloon");
        OptionalCalls.call(be, "tryCreateBalloon");
        Object balloon = OptionalCalls.call(be, "getBalloon");
        if (balloon == null)
            throw new IllegalArgumentException(
                    "Burner has no enclosed balloon after placement: "
                            + p
                            + " world="
                            + resolve.apply(p)
                            + " cast="
                            + OptionalCalls.call(be, "getCastPosition")
                            + " above="
                            + level.getBlockState(resolve.apply(p).above())
                            + " roof="
                            + level.getBlockState(resolve.apply(p).above(5)));
        if (!restored.add(balloon)) return;
        List<Object> gas;
        if (patch.has("fill_volume")) {
            double volume =
                    Math.min(
                            patch.get("fill_volume").getAsDouble(),
                            ((Number) OptionalCalls.call(balloon, "getCapacity")).doubleValue());
            Object type = OptionalCalls.call(be, "getLiftingGasType");
            Object data =
                    Class.forName(GAS + "lifting_gas.LiftingGasData")
                            .getConstructor(double.class, double.class, double.class)
                            .newInstance(volume, volume, 0d);
            gas =
                    List.of(
                            Class.forName(GAS + "lifting_gas.LiftingGasHolder")
                                    .getConstructors()[0]
                                    .newInstance(type, data));
        } else gas = gasCodec().parse(JsonOps.INSTANCE, patch.get("gas")).getOrThrow();
        Object map =
                OptionalCalls.call(
                        Class.forName(GAS + "balloon.map.BalloonMap").getField("MAP").get(null),
                        "get",
                        level);
        Object saved =
                OptionalCalls.call(
                        Class.forName(GAS + "balloon.map.BalloonMap"), "saveBalloon", balloon);
        Object translated =
                saved.getClass()
                        .getConstructors()[0]
                        .newInstance(
                                OptionalCalls.call(saved, "bounds"),
                                OptionalCalls.call(saved, "controllerPos"),
                                gas);
        OptionalCalls.call(balloon, "loadFrom", translated);
        OptionalCalls.call(balloon, "updateGasAmounts");
        OptionalCalls.call(map, "markDirty");
    }
}
