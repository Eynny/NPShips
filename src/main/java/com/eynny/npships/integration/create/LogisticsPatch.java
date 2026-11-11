package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;

import java.util.*;
import java.util.function.Function;

/** Captures omitted factory-panel graph fields; every spawned copy gets independent networks. */
public final class LogisticsPatch {
    private LogisticsPatch() {}

    // Shared immutable-registry probes run on both integrated-server and client editor threads.
    private static final Map<String, Boolean> TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean matches(String id) {
        var known = TYPES.get(id);
        if (known != null) return known;
        var name = net.minecraft.resources.ResourceLocation.tryParse(id);
        // Unknown authored IDs must not become process-lifetime negative cache entries.
        if (name == null
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(name))
            return false;
        return TYPES.computeIfAbsent(
                id,
                key -> {
                    var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(name);
                    if (!(block instanceof EntityBlock factory)) return false;
                    try {
                        var be = factory.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                        return be instanceof FactoryPanelBlockEntity
                                || be instanceof SmartBlockEntity smart
                                        && smart.getBehaviour(LogisticallyLinkedBehaviour.TYPE)
                                                != null;
                    } catch (RuntimeException e) {
                        return false;
                    }
                });
    }

    public static JsonObject capture(
            SchematicDocument doc, BlockPos p, BlockPos origin, CompoundTag full) {
        var patch = new JsonObject();
        patch.addProperty("kind", "logistics");
        var settings = new CompoundTag();
        var links = new JsonArray();
        for (String slot : full.getAllKeys())
            if (full.get(slot) instanceof CompoundTag panel && panel.hasUUID("Freq")) {
                var extra = new CompoundTag();
                for (String key :
                        List.of(
                                "Targeting",
                                "TargetedBy",
                                "TargetedByLinks",
                                "Craft",
                                "RecipeOutput",
                                "PromiseClearingInterval"))
                    if (panel.contains(key)) extra.put(key, panel.get(key).copy());
                for (String key : List.of("Targeting", "TargetedBy", "TargetedByLinks"))
                    if (extra.contains(key))
                        capturePositions(
                                extra.get(key),
                                new ArrayList<>(List.of(slot, key)),
                                doc,
                                p,
                                origin,
                                links);
                settings.put(slot, extra);
            }
        patch.addProperty("settings", settings.toString());
        patch.add("bindings", links);
        var networks = new JsonObject();
        frequencies(
                full,
                id -> {
                    var n =
                            Create.LOGISTICS.logisticsNetworks == null
                                    ? null
                                    : Create.LOGISTICS.logisticsNetworks.get(id);
                    var value = new JsonObject();
                    if (n != null) {
                        value.addProperty("locked", n.locked);
                        if (n.owner != null) value.addProperty("owner", n.owner.toString());
                    }
                    networks.add(id.toString(), value);
                });
        patch.add("networks", networks);
        return patch;
    }

    private static void capturePositions(
            Tag value,
            List<String> path,
            SchematicDocument doc,
            BlockPos p,
            BlockPos origin,
            JsonArray links) {
        if (!(value instanceof ListTag list))
            throw new IllegalArgumentException("Invalid panel connection list");
        for (var item : list) {
            var entry = ((CompoundTag) item).copy();
            var position =
                    entry.contains("position", Tag.TAG_COMPOUND)
                            ? entry.getCompound("position")
                            : entry;
            int[] a = position.getIntArray("pos");
            if (a.length != 3) throw new IllegalArgumentException("Invalid panel position");
            var target = new BlockPos(a[0], a[1], a[2]).subtract(origin);
            if (!doc.contains(target) || doc.blockName(target).equals("minecraft:air"))
                throw new IllegalArgumentException(
                        "Factory panel connection leaves exported area at " + p);
            position.remove("pos");
            var binding = new JsonObject();
            binding.addProperty("slot", path.get(0));
            binding.addProperty("field", path.get(1));
            binding.addProperty("entry", entry.toString());
            binding.add("target", PatchDevices.endpoint(doc, p, target));
            links.add(binding);
        }
    }

    private static void frequencies(CompoundTag data, java.util.function.Consumer<UUID> action) {
        if (data.hasUUID("Freq")) action.accept(data.getUUID("Freq"));
        for (String key : data.getAllKeys())
            if (data.get(key) instanceof CompoundTag child) frequencies(child, action);
    }

    public static void write(
            JsonObject patch,
            CompoundTag data,
            Function<BlockPos, BlockPos> resolve,
            Map<UUID, UUID> networks) {
        try {
            var settings =
                    com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                            patch.get("settings").getAsString());
            var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
            for (String slot : settings.getAllKeys())
                for (String key : List.of("Targeting", "TargetedBy", "TargetedByLinks"))
                    settings.getCompound(slot).put(key, new ListTag());
            for (var value : patch.getAsJsonArray("bindings")) {
                var binding = value.getAsJsonObject();
                String slot = binding.get("slot").getAsString(),
                        field = binding.get("field").getAsString();
                if (!settings.contains(slot, Tag.TAG_COMPOUND)
                        || !List.of("Targeting", "TargetedBy", "TargetedByLinks").contains(field))
                    throw new IllegalArgumentException("Invalid panel connection field");
                var entry =
                        com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                binding.get("entry").getAsString());
                var node =
                        entry.contains("position", Tag.TAG_COMPOUND)
                                ? entry.getCompound("position")
                                : entry;
                var pos =
                        resolve.apply(
                                owner.offset(
                                        SchematicPatches.position(
                                                binding.getAsJsonObject("target")
                                                        .getAsJsonArray("offset"))));
                node.putIntArray("pos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
                settings.getCompound(slot).getList(field, Tag.TAG_COMPOUND).add(entry);
            }
            for (String key : settings.getAllKeys()) {
                var panel = data.getCompound(key);
                panel.merge(settings.getCompound(key));
                data.put(key, panel);
            }
            remap(data, networks);
            data.remove("Added");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid factory panel patch: " + e.getMessage(), e);
        }
    }

    private static void remap(CompoundTag data, Map<UUID, UUID> networks) {
        if (data.hasUUID("Freq")) {
            UUID old = data.getUUID("Freq");
            if (!networks.containsValue(old))
                data.putUUID("Freq", networks.computeIfAbsent(old, key -> UUID.randomUUID()));
        }
        for (String key : List.copyOf(data.getAllKeys()))
            if (data.get(key) instanceof CompoundTag child) remap(child, networks);
    }

    public static void finish(
            ServerLevel level,
            JsonObject patch,
            Function<BlockPos, BlockPos> resolve,
            Map<UUID, UUID> networks) {
        var p = resolve.apply(SchematicPatches.position(patch.getAsJsonArray("pos")));
        var be = level.getBlockEntity(p);
        if (be == null) throw new IllegalArgumentException("Missing logistics device at " + p);
        var data = be.saveWithFullMetadata(level.registryAccess());
        write(patch, data, resolve, networks);
        be.loadWithComponents(data, level.registryAccess());
        be.setChanged();
        if (patch.has("networks"))
            for (var entry : patch.getAsJsonObject("networks").entrySet()) {
                UUID id = networks.get(UUID.fromString(entry.getKey()));
                if (id == null) continue;
                var network =
                        Create.LOGISTICS.logisticsNetworks.computeIfAbsent(
                                id,
                                com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork
                                        ::new);
                var value = entry.getValue().getAsJsonObject();
                network.locked = value.has("locked") && value.get("locked").getAsBoolean();
                network.owner =
                        value.has("owner")
                                ? UUID.fromString(value.get("owner").getAsString())
                                : null;
            }
        Create.LOGISTICS.markDirty();
        level.sendBlockUpdated(p, be.getBlockState(), be.getBlockState(), 3);
    }
}
