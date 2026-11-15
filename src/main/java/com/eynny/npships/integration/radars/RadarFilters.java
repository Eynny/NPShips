package com.eynny.npships.integration.radars;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.authoring.patches.SchematicResources;
import com.eynny.npships.integration.OptionalCalls;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.*;

/** Filter items and network settings are edited together, in native Radars formats. */
public final class RadarFilters {
    public static final List<String> KINDS = List.of("detection", "identification", "targeting");
    private static final List<String> ITEMS =
            List.of("radar_filter_item", "ident_filter_item", "target_filter_item");
    private static final List<String> TYPES =
            List.of(
                    "detectionfilter.DetectionFilterItem",
                    "identfilter.IdentFilterItem",
                    "targetfilter.TargetFilterItem");

    private RadarFilters() {}

    public static boolean accepts(int slot, ItemStack stack) {
        return slot >= 0
                && slot < 3
                && SchematicResources.instance(
                        stack.getItem(), "com.happysg.radar.item." + TYPES.get(slot));
    }

    public static ItemStack item(int slot) {
        return new ItemStack(
                BuiltInRegistries.ITEM.get(
                        ResourceLocation.fromNamespaceAndPath("create_radar", ITEMS.get(slot))));
    }

    public static CompoundTag defaults(int slot) {
        try {
            String kind = KINDS.get(slot);
            var type =
                    Class.forName(
                            "com.happysg.radar.block.behavior.networks.config."
                                    + Character.toUpperCase(kind.charAt(0))
                                    + kind.substring(1)
                                    + "Config");
            return (CompoundTag) OptionalCalls.call(type.getField("DEFAULT").get(null), "toTag");
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Unsupported Radars filters", e);
        }
    }

    public static CompoundTag read(ItemStack stack, int slot) {
        return read(
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(), slot);
    }

    private static CompoundTag read(CompoundTag custom, int slot) {
        var result = defaults(slot);
        String kind = KINDS.get(slot);
        if (custom.getCompound("Filters").contains(kind))
            result.merge(custom.getCompound("Filters").getCompound(kind));
        else if (custom.contains(kind)) result.merge(custom.getCompound(kind));
        else if (!custom.isEmpty()) result.merge(custom);
        return result;
    }

    /** Each network field must match its native fromTag reader, not the item wrapper. */
    public static CompoundTag networkTag(String field, CompoundTag settings) {
        if (settings.isEmpty()) return settings.copy(); // No filter keeps native defaults.
        String kind = field.substring(0, field.length() - 3);
        if (kind.equals("targeting")) {
            if (settings.contains(kind, Tag.TAG_COMPOUND)
                    || settings.getCompound("Filters").contains(kind, Tag.TAG_COMPOUND))
                return settings.copy();
            var wrapped = new CompoundTag();
            wrapped.put(kind, settings.copy());
            return wrapped;
        }
        // Detection/Identification read flat compounds. Accept an existing item-style
        // wrapper without discarding its unknown fields or mutating the author's source.
        var result = settings.copy();
        if (settings.contains(kind, Tag.TAG_COMPOUND)) result.merge(settings.getCompound(kind));
        else if (settings.getCompound("Filters").contains(kind, Tag.TAG_COMPOUND))
            result.merge(settings.getCompound("Filters").getCompound(kind));
        return result;
    }

    public static SchematicDocument set(
            SchematicDocument doc, BlockPos pos, int slot, CompoundTag settings, Level level) {
        var inventory = SchematicResources.open(doc, pos, level);
        var stack = inventory.get(slot);
        if (!accepts(slot, stack)) stack = item(slot);
        var custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var filters = custom.getCompound("Filters");
        filters.put(KINDS.get(slot), settings.copy());
        custom.put("Filters", filters);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        screenFlags(stack, slot, settings);
        return inventory.put(slot, stack.copyWithCount(1));
    }

    /** Radars' item menus read byte flags; its block/network reads the named Filters compound. */
    private static void screenFlags(ItemStack stack, int slot, CompoundTag settings) {
        if (slot == 1) return; // Identification menus already read the named compound.
        var fields =
                slot == 0
                        ? List.of(
                                "player",
                                "sable",
                                "contraption",
                                "mob",
                                "animal",
                                "projectile",
                                "item")
                        : List.of(
                                "player",
                                "contraption",
                                "mob",
                                "animal",
                                "projectile",
                                "lineSight",
                                "autoTarget");
        var flags = new boolean[fields.size()];
        for (int i = 0; i < flags.length; i++) flags[i] = settings.getBoolean(fields.get(i));
        try {
            OptionalCalls.call(
                    Class.forName("com.happysg.radar.networking.networkhandlers.BoolNBThelper"),
                    "saveBooleansAsBytes",
                    stack,
                    flags,
                    slot == 0 ? "detectBools" : "TargetBools");
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("Unsupported Radars filter item format", error);
        }
    }

    /** Complete native menu data in the prepared copy, including existing authored schematics. */
    public static void prepare(String block, CompoundTag data) {
        if (!block.equals("create_radar:network_filterer")) return;
        for (var value : data.getCompound("Inventory").getList("Items", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            int slot = entry.getInt("Slot");
            if (slot != 0 && slot != 2
                    || !entry.getString("id").equals("create_radar:" + ITEMS.get(slot))) continue;
            var components = entry.getCompound("components");
            var custom = components.getCompound("minecraft:custom_data");
            String kind = KINDS.get(slot);
            if (!custom.getCompound("Filters").contains(kind, Tag.TAG_COMPOUND)
                    && !custom.contains(kind, Tag.TAG_COMPOUND)) continue;
            // Do not decode/re-encode the author's other item components or unknown fields.
            var view = item(slot);
            view.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
            screenFlags(view, slot, read(view, slot));
            var complete =
                    view.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            components.put("minecraft:custom_data", complete);
            entry.put("components", components);
            String key = slot == 0 ? "detectBools" : "TargetBools";
            for (var cached : data.getList("SlotNbt", Tag.TAG_COMPOUND)) {
                var cache = (CompoundTag) cached;
                if (cache.getInt("Slot") == slot) {
                    var tag = cache.getCompound("Tag");
                    tag.put(key, complete.get(key).copy());
                    cache.put("Tag", tag);
                }
            }
        }
    }

    public static SchematicDocument synchronizeNative(SchematicDocument doc, BlockPos pos) {
        var patch =
                SchematicPatches.read(SchematicPatches.authoringConfig(doc)).stream()
                        .filter(
                                p ->
                                        !ExternalPatches.external(p)
                                                && p.get("kind").getAsString().equals("radar")
                                                && p.get("block")
                                                        .getAsString()
                                                        .equals(doc.blockName(pos))
                                                && SchematicPatches.position(
                                                                p.getAsJsonArray("pos"))
                                                        .equals(pos))
                        .toList();
        if (patch.isEmpty()) return doc; // Imported native data still needs its genuine capture.
        if (patch.size() != 1)
            throw new IllegalArgumentException("Select a native filter patch instance.");
        var selected = patch.getFirst();
        var network =
                selected.has("network") ? selected.getAsJsonObject("network") : new JsonObject();
        for (String key : List.of("monitors", "controllers", "links"))
            if (!network.has(key)) network.add(key, new JsonArray());
        var items =
                doc.blockEntityData(pos)
                        .getCompound("Inventory")
                        .getList("Items", Tag.TAG_COMPOUND);
        for (int slot = 0; slot < 3; slot++) {
            var custom = new CompoundTag();
            for (var value : items) {
                var item = (CompoundTag) value;
                if (item.getInt("Slot") == slot
                        && item.getString("id").equals("create_radar:" + ITEMS.get(slot)))
                    custom = item.getCompound("components").getCompound("minecraft:custom_data");
            }
            String field = KINDS.get(slot) + "Tag";
            network.addProperty(field, networkTag(field, read(custom, slot)).toString());
        }
        selected.add("network", network);
        return PatchDevices.put(doc, selected);
    }

    public static SchematicDocument synchronize(SchematicDocument doc, BlockPos pos, Level level) {
        var source = doc.withConfig(SchematicPatches.authoringConfig(doc));
        boolean known =
                SchematicPatches.read(source.configCopy()).stream()
                        .anyMatch(
                                p ->
                                        !ExternalPatches.external(p)
                                                && p.get("kind").getAsString().equals("radar")
                                                && p.get("block")
                                                        .getAsString()
                                                        .equals(doc.blockName(pos))
                                                && SchematicPatches.position(
                                                                p.getAsJsonArray("pos"))
                                                        .equals(pos));
        if (!known) source = PatchDevices.put(source, PatchDevices.create(source, pos));
        return synchronizeNative(source, pos);
    }
}
