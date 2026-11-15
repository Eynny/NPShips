package com.eynny.npships.authoring.document;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.*;

/** Native item data only. Does not infer items from arbitrary provider payload fields. */
public final class ItemSettings {
    public static final int MAX_TEXT = 2 * 1024 * 1024;

    private ItemSettings() {}

    /**
     * The native persistent codec admits count1..99; slot insertion limits are a separate owner.
     */
    public static ItemStack parse(String text, HolderLookup.Provider registries) {
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("Item SNBT exceeds 2 MiB");
        try {
            var data = com.eynny.npships.authoring.document.SnbtText.parse(text);
            var item = parse(data, registries);
            for (var key : data.getAllKeys())
                if (!Set.of("id", "count", "components").contains(key))
                    throw new IllegalArgumentException(
                            "Native item codec does not retain root field: "
                                    + key
                                    + "; use the complete object SNBT to preserve extra source fields");
            return item;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }

    public static ItemStack parse(CompoundTag data, HolderLookup.Provider registries) {
        bounded(data);
        if (data.isEmpty()) return ItemStack.EMPTY;
        validateCount(data);
        var patch = data.getCompound("components");
        for (var key : patch.getAllKeys())
            if (key.startsWith("!") && patch.contains(key.substring(1)))
                throw new IllegalArgumentException(
                        "Component is both set and removed: " + key.substring(1));
        var item =
                ItemStack.CODEC
                        .parse(registries.createSerializationContext(NbtOps.INSTANCE), data)
                        .getOrThrow(IllegalArgumentException::new);
        if (item.isEmpty()
                || item.getCount() != (!data.contains("count") ? 1 : data.getInt("count")))
            throw new IllegalArgumentException("Native item count was not preserved");
        // Unlike STRICT_CODEC, component validity does not prohibit native oversized held stacks.
        ItemStack.validateComponents(item.getComponents())
                .getOrThrow(IllegalArgumentException::new);
        return item;
    }

    private static void validateCount(CompoundTag data) {
        var count = data.get("count");
        if (count != null
                && (!(count instanceof NumericTag number)
                        || !Double.isFinite(number.getAsDouble())
                        || number.getAsDouble() != Math.rint(number.getAsDouble())
                        || number.getAsDouble() < 1
                        || number.getAsDouble() > 99))
            throw new IllegalArgumentException(
                    "Native item count must be an integer from1 to99; {} clears the item");
    }

    /**
     * Validate changed native item roles before accepting text; never normalize or replace source.
     */
    public static void validateChanges(
            CompoundTag before,
            CompoundTag after,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            HolderLookup.Provider registries) {
        validateChanges(before, after, subject, registries, nativeItemPaths(subject, registries));
    }

    /**
     * Exact serializer/declaration positions; spelling inside arbitrary provider data is opaque.
     */
    public static Set<List<String>> nativeItemPaths(
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            HolderLookup.Provider registries) {
        var paths = new LinkedHashSet<List<String>>();
        if (subject.entity()) {
            // These root lists are the existing native mob equipment contract, including the
            // explicit NPShips spawn-override copy. Arbitrary NeoForgeData records are not roots.
            for (String key : List.of("HandItems", "ArmorItems")) {
                paths.add(List.of(key, "[]"));
                paths.add(List.of("NeoForgeData", "npships_initial_spawn", key, "[]"));
            }
        } else {
            var id = net.minecraft.resources.ResourceLocation.tryParse(subject.id());
            var block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block instanceof net.minecraft.world.level.block.EntityBlock factory) {
                var entity =
                        factory.newBlockEntity(
                                net.minecraft.core.BlockPos.ZERO, block.defaultBlockState());
                if (entity != null)
                    paths.addAll(NativeSerializationFields.of(entity.getClass()).itemPaths());
            }
        }
        return Set.copyOf(paths);
    }

    public static void validateChanges(
            CompoundTag before,
            CompoundTag after,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            HolderLookup.Provider registries,
            Set<List<String>> nativePaths) {
        validateChanges(before, after, subject, registries, nativePaths, List.of(), false, 0);
    }

    private static boolean declaredItem(
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            List<String> path,
            Set<List<String>> nativePaths) {
        for (var declared : nativePaths) {
            if (declared.size() != path.size()) continue;
            boolean matches = true;
            for (int i = 0; i < path.size() && matches; i++)
                matches =
                        declared.get(i).equals("[]")
                                ? path.get(i).matches("[0-9]+")
                                : declared.get(i).equals(path.get(i));
            if (matches) return true;
        }
        return BlockSettingTemplates.declaredItemField(subject, path);
    }

    private static void validateChanges(
            Tag before,
            Tag after,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            HolderLookup.Provider registries,
            Set<List<String>> nativePaths,
            List<String> path,
            boolean item,
            int depth) {
        if (Objects.equals(before, after)) return;
        if (depth > 64) throw new IllegalArgumentException("Item data is too deeply nested");
        if (item && after != null && !(after instanceof CompoundTag) && !(after instanceof ListTag))
            throw new IllegalArgumentException(
                    "Item data must be a compound; use {} to clear the item.");
        if (after instanceof CompoundTag object) {
            if (item) {
                if (object.isEmpty()) return;
                validateCount(object);
                var id = net.minecraft.resources.ResourceLocation.tryParse(object.getString("id"));
                // Missing registries remain opaque author data. Native previews already refuse
                // them.
                if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
                for (String key : object.getCompound("components").getAllKeys()) {
                    var component =
                            net.minecraft.resources.ResourceLocation.tryParse(
                                    key.startsWith("!") ? key.substring(1) : key);
                    if (component == null
                            || !BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(component))
                        return;
                }
                if (!id.equals(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("air")))
                    parse(object, registries);
                var old = before instanceof CompoundTag prior ? prior : new CompoundTag();
                var components = object.getCompound("components");
                var previous = old.getCompound("components");
                for (String key :
                        List.of("minecraft:bundle_contents", "minecraft:charged_projectiles"))
                    validateChanges(
                            previous.get(key),
                            components.get(key),
                            subject,
                            registries,
                            nativePaths,
                            List.of(),
                            true,
                            depth + 1);
                var contents = components.getList("minecraft:container", Tag.TAG_COMPOUND);
                var oldContents = previous.getList("minecraft:container", Tag.TAG_COMPOUND);
                for (int i = 0; i < contents.size(); i++)
                    validateChanges(
                            i < oldContents.size() ? oldContents.getCompound(i).get("item") : null,
                            contents.getCompound(i).get("item"),
                            subject,
                            registries,
                            nativePaths,
                            List.of(),
                            true,
                            depth + 1);
                return;
            }
            var old = before instanceof CompoundTag c ? c : new CompoundTag();
            for (String key : object.getAllKeys()) {
                var at = new ArrayList<>(path);
                at.add(key);
                boolean stack = declaredItem(subject, at, nativePaths);
                validateChanges(
                        old.get(key),
                        object.get(key),
                        subject,
                        registries,
                        nativePaths,
                        at,
                        stack,
                        depth + 1);
            }
        } else if (after instanceof ListTag list) {
            var old = before instanceof ListTag l ? l : new ListTag();
            for (int i = 0; i < list.size(); i++) {
                var at = new ArrayList<>(path);
                at.add(Integer.toString(i));
                validateChanges(
                        i < old.size() ? old.get(i) : null,
                        list.get(i),
                        subject,
                        registries,
                        nativePaths,
                        at,
                        item || declaredItem(subject, at, nativePaths),
                        depth + 1);
            }
        }
    }

    private record Pending(Tag value, int depth) {}

    private static void bounded(Tag root) {
        var pending = new ArrayDeque<Pending>();
        pending.push(new Pending(root, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            var at = pending.pop();
            if (at.depth() > 64 || ++nodes > 100000)
                throw new IllegalArgumentException("Item data is too deeply nested or too large");
            if (at.value() instanceof CompoundTag object) {
                for (var key : object.getAllKeys())
                    pending.push(new Pending(object.get(key), at.depth() + 1));
            } else if (at.value() instanceof ListTag list) {
                if (list.size() > 100000 - nodes)
                    throw new IllegalArgumentException("Item list is too large");
                for (var value : list) pending.push(new Pending(value, at.depth() + 1));
            }
        }
    }

    /** Detached completion templates; samples are never applied to accepted item data. */
    public static DataSuggestions suggestions(ItemStack item, HolderLookup.Provider registries) {
        return suggestions((CompoundTag) item.saveOptional(registries), registries);
    }

    /** Read-only hints also remain available for temporarily unavailable authored items. */
    public static DataSuggestions suggestions(
            CompoundTag stored, HolderLookup.Provider registries) {
        return suggestions(stored, registries, null);
    }

    public static DataSuggestions suggestions(
            CompoundTag stored, HolderLookup.Provider registries, DataSuggestions previous) {
        var base = new ItemStack(net.minecraft.world.item.Items.STONE);
        return new DataSuggestions((CompoundTag) base.saveOptional(registries), stored, Map.of())
                .itemDocument()
                .withItems(registries, previous);
    }

    record Hints(
            CompoundTag components,
            Map<String, String> names,
            Map<String, List<String>> values,
            Map<String, String> enchantments) {}

    /**
     * Build the detached registry snapshot; the live binding may reuse it across accepted edits.
     */
    static Hints hints(HolderLookup.Provider registries) {
        var components = new CompoundTag();
        int inspected = 0;
        for (var item : BuiltInRegistries.ITEM) {
            if (++inspected > 16384) break;
            var stack = item.getDefaultInstance();
            for (var component : stack.getComponents()) {
                var type = component.type();
                String id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString();
                if (type.codec() != null && !components.contains(id)) {
                    var sample = example(type, stack, registries);
                    if (sample != null) components.put(id, sample);
                }
            }
        }
        var samples = new ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
        samples.set(DataComponents.CUSTOM_NAME, Component.literal("Example"));
        samples.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        samples.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        samples.set(
                DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY);
        samples.set(
                DataComponents.DYED_COLOR,
                new net.minecraft.world.item.component.DyedItemColor(0x77aadd, true));
        samples.set(
                DataComponents.CUSTOM_MODEL_DATA,
                new net.minecraft.world.item.component.CustomModelData(1));
        samples.set(
                DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(
                        List.of(new ItemStack(net.minecraft.world.item.Items.STONE))));
        var enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
        var first =
                enchantments
                        .listElements()
                        .filter(e -> e.value().isSupportedItem(samples))
                        .limit(2)
                        .toList();
        if (!first.isEmpty()) {
            var sample = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            for (var enchantment : first) sample.set(enchantment, 1);
            samples.set(DataComponents.ENCHANTMENTS, sample.toImmutable());
            samples.set(DataComponents.STORED_ENCHANTMENTS, sample.toImmutable());
        }
        var material =
                registries.lookupOrThrow(Registries.TRIM_MATERIAL).listElements().findFirst();
        var pattern = registries.lookupOrThrow(Registries.TRIM_PATTERN).listElements().findFirst();
        if (material.isPresent() && pattern.isPresent())
            samples.set(
                    DataComponents.TRIM,
                    new net.minecraft.world.item.armortrim.ArmorTrim(
                            material.get(), pattern.get()));
        for (var component : samples.getComponents()) {
            var type = component.type();
            if (type.codec() == null) continue;
            var example = example(type, samples, registries);
            if (example != null)
                components.put(
                        BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString(), example);
        }
        var names = new TreeMap<String, String>();
        for (var type : BuiltInRegistries.DATA_COMPONENT_TYPE) {
            if (type.codec() == null) continue;
            var id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString();
            names.put(id, components.contains(id) ? components.get(id).toString() : "");
            names.put("!" + id, "{}");
        }
        var levels = new TreeMap<String, String>();
        enchantments
                .listElements()
                .limit(4096)
                .forEach(e -> levels.put(e.key().location().toString(), "1"));
        var values = new LinkedHashMap<String, List<String>>();
        values.put(
                "id",
                BuiltInRegistries.ITEM.keySet().stream()
                        .sorted()
                        .limit(16384)
                        .map(id -> StringTag.valueOf(id.toString()).toString())
                        .toList());
        values.put(
                "components.minecraft:trim.material",
                registryIds(registries, Registries.TRIM_MATERIAL));
        values.put(
                "components.minecraft:trim.pattern",
                registryIds(registries, Registries.TRIM_PATTERN));
        values.put(
                "components.minecraft:potion_contents.potion",
                registryIds(registries, Registries.POTION));
        values.put(
                "components.minecraft:instrument", registryIds(registries, Registries.INSTRUMENT));
        values.put(
                "components.minecraft:jukebox_playable.song",
                registryIds(registries, Registries.JUKEBOX_SONG));
        values.put(
                "components.minecraft:attribute_modifiers.modifiers[].type",
                registryIds(registries, Registries.ATTRIBUTE));
        values.put("components.minecraft:rarity", List.of("common", "uncommon", "rare", "epic"));
        values.put(
                "components.minecraft:attribute_modifiers.modifiers[].slot",
                Arrays.stream(net.minecraft.world.entity.EquipmentSlotGroup.values())
                        .map(net.minecraft.world.entity.EquipmentSlotGroup::getSerializedName)
                        .toList());
        values.put(
                "components.minecraft:potion_contents.custom_effects[].id",
                registryIds(registries, Registries.MOB_EFFECT));
        return new Hints(components, Map.copyOf(names), Map.copyOf(values), Map.copyOf(levels));
    }

    private static <T> List<String> registryIds(
            HolderLookup.Provider registries,
            net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> key) {
        return registries
                .lookupOrThrow(key)
                .listElementIds()
                .map(k -> k.location().toString())
                .sorted()
                .limit(4096)
                .map(id -> StringTag.valueOf(id).toString())
                .toList();
    }

    private static <T> Tag example(
            DataComponentType<T> type, ItemStack item, HolderLookup.Provider registries) {
        var value = item.get(type);
        if (value == null) return null;
        try {
            var encoded =
                    type.codec()
                            .encodeStart(
                                    registries.createSerializationContext(NbtOps.INSTANCE), value)
                            .result()
                            .orElse(null);
            if (encoded != null) bounded(encoded);
            return encoded;
        } catch (RuntimeException unsupportedExample) {
            // A failed optional help example must not remove the component name or block raw SNBT.
            return null;
        }
    }
}
