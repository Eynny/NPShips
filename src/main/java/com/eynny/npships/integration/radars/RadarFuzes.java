package com.eynny.npships.integration.radars;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

import java.util.*;

/** Exact guided-fuze stacks, including nested CBC fuze components and container inventories. */
public final class RadarFuzes {
    private RadarFuzes() {}

    public record Stack(JsonArray path, CompoundTag data) {}

    public static List<Stack> stacks(CompoundTag data) {
        var out = new ArrayList<Stack>();
        scan(data, new JsonArray(), out, 0);
        return out;
    }

    private static void scan(Tag tag, JsonArray path, List<Stack> out, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Item data nested too deeply.");
        if (tag instanceof CompoundTag c) {
            if (c.getString("id").equals("create_radar:guided_fuze")) {
                out.add(new Stack(path, c));
                return;
            }
            for (String key : c.getAllKeys()) {
                var next = path.deepCopy();
                next.add(key);
                scan(c.get(key), next, out, depth + 1);
            }
        } else if (tag instanceof ListTag list)
            for (int i = 0; i < list.size(); i++) {
                var next = path.deepCopy();
                var entry = list.get(i);
                // Inventory slots survive reordering of the Items list.
                if (entry instanceof CompoundTag c && c.contains("Slot", Tag.TAG_ANY_NUMERIC)) {
                    var slot = new JsonObject();
                    slot.addProperty("slot", c.getInt("Slot"));
                    next.add(slot);
                } else next.add(i);
                scan(entry, next, out, depth + 1);
            }
    }

    public static CompoundTag stack(CompoundTag root, JsonArray path) {
        Tag value = root;
        for (var step : path) {
            if (step.isJsonObject() && value instanceof ListTag list) {
                int slot = step.getAsJsonObject().get("slot").getAsInt();
                value = null;
                for (var e : list)
                    if (e instanceof CompoundTag c
                            && c.contains("Slot")
                            && c.getInt("Slot") == slot) {
                        value = c;
                        break;
                    }
            } else if (step.isJsonPrimitive()
                    && step.getAsJsonPrimitive().isNumber()
                    && value instanceof ListTag list) {
                int i = step.getAsInt();
                value = i >= 0 && i < list.size() ? list.get(i) : null;
            } else if (step.isJsonPrimitive() && value instanceof CompoundTag c)
                value = c.get(step.getAsString());
            else value = null;
            if (value == null)
                throw new IllegalArgumentException("Guided fuze item moved or removed: " + path);
        }
        if (!(value instanceof CompoundTag c)
                || !c.getString("id").equals("create_radar:guided_fuze"))
            throw new IllegalArgumentException("Guided fuze missing: " + path);
        return c;
    }

    public static CompoundTag custom(CompoundTag stack) {
        return stack.getCompound("components").getCompound("minecraft:custom_data");
    }

    public static void bind(CompoundTag stack, BlockPos target) {
        var components = stack.getCompound("components");
        var data = custom(stack);
        data.remove("initialHeadingYaw");
        data.remove("valid");
        data.put("monitorPos", NbtUtils.writeBlockPos(target));
        components.put("minecraft:custom_data", data);
        stack.put("components", components);
    }

    public static void capture(
            JsonObject patch, SchematicDocument doc, BlockPos pos, BlockPos origin) {
        var bindings = new JsonArray();
        for (var stack : stacks(doc.blockEntityData(pos))) {
            var target = NbtUtils.readBlockPos(custom(stack.data()), "monitorPos");
            if (target.isEmpty()) continue;
            var local = target.get().subtract(origin);
            if (!doc.blockName(local).equals("create_radar:network_filterer"))
                throw new IllegalArgumentException(
                        "Guided fuze target is outside schematic or not a network filterer: "
                                + target.get());
            var binding = new JsonObject();
            binding.add("path", stack.path());
            binding.add("target", PatchDevices.endpoint(doc, pos, local));
            bindings.add(binding);
        }
        if (!bindings.isEmpty()) patch.add("fuze_bindings", bindings);
    }

    public static JsonObject bindAll(JsonObject patch, SchematicDocument doc, BlockPos target) {
        BlockPos owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
        var next = patch.deepCopy();
        var bindings = new JsonArray();
        for (var stack : stacks(doc.blockEntityData(owner))) {
            var b = new JsonObject();
            b.add("path", stack.path());
            b.add("target", PatchDevices.endpoint(doc, owner, target));
            bindings.add(b);
        }
        next.add("fuze_bindings", bindings);
        return next;
    }

    public static void validate(JsonObject patch, SchematicDocument doc) {
        if (!patch.has("fuze_bindings")) return;
        var data = doc.blockEntityData(SchematicPatches.position(patch.getAsJsonArray("pos")));
        var seen = new HashSet<String>();
        for (var e : patch.getAsJsonArray("fuze_bindings")) {
            var b = e.getAsJsonObject();
            stack(data, b.getAsJsonArray("path"));
            if (!seen.add(b.get("path").toString()))
                throw new IllegalArgumentException("Duplicate fuze binding.");
            if (!b.getAsJsonObject("target")
                    .get("block")
                    .getAsString()
                    .equals("create_radar:network_filterer"))
                throw new IllegalArgumentException("Guided fuzes require a Network Filterer.");
        }
    }

    public static void restore(
            CompoundTag data,
            JsonObject patch,
            java.util.function.Function<BlockPos, BlockPos> resolve) {
        if (!patch.has("fuze_bindings")) return;
        var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
        for (var e : patch.getAsJsonArray("fuze_bindings")) {
            var b = e.getAsJsonObject();
            bind(
                    stack(data, b.getAsJsonArray("path")),
                    resolve.apply(
                            owner.offset(
                                    SchematicPatches.position(
                                            b.getAsJsonObject("target")
                                                    .getAsJsonArray("offset")))));
        }
    }
}
