package com.eynny.npships.authoring.document;

import net.minecraft.nbt.*;

import java.util.List;
import java.util.Set;

/** Entry templates for native lists whose serializers omit their empty element structure. */
public final class BlockSettingTemplates {
    private BlockSettingTemplates() {}

    private static final class NativeSchemas {
        static final java.util.List<com.eynny.npships.authoring.patches.PatchFieldTemplates> CODE =
                java.util.List.copyOf(
                        com.eynny.npships.authoring.patches.PatchCode.sourceFormats(
                                        com.eynny.npships.authoring.patches.PatchFieldTemplates
                                                .class)
                                .values());
    }

    public static final Set<String> ITEMS =
            Set.of("RecordItem", "Book", "item", "key_item", "Item");

    private static java.util.List<com.eynny.npships.authoring.patches.PatchFieldTemplates> codes(
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject) {
        return NativeSchemas.CODE.stream().filter(code -> code.applies(subject)).toList();
    }

    static boolean itemField(
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            List<String> path) {
        return item(subject, path, codes(subject));
    }

    static boolean declaredItemField(
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            List<String> path) {
        return codes(subject).stream().anyMatch(c -> c.itemField(subject, path));
    }

    private static Tag declaredEntry(
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject, String path) {
        for (var code : codes(subject)) {
            var value = code.entry(subject, path);
            if (value != null) return value;
        }
        return null;
    }

    private static boolean item(
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            java.util.List<String> path,
            java.util.List<com.eynny.npships.authoring.patches.PatchFieldTemplates> code) {
        return !path.isEmpty()
                && (ITEMS.contains(path.getLast())
                        || code.stream().anyMatch(c -> c.itemField(subject, path)));
    }

    private static java.util.List<String> child(java.util.List<String> path, String key) {
        var result = new java.util.ArrayList<>(path);
        result.add(key);
        return result;
    }

    public static void expand(CompoundTag tag) {
        expand(tag, com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.unknown());
    }

    public static void expand(
            CompoundTag tag,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject) {
        expand(tag, subject, List.of(), codes(subject));
    }

    private static void expand(
            CompoundTag tag,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            List<String> path,
            List<com.eynny.npships.authoring.patches.PatchFieldTemplates> code) {
        for (var c : code) c.expand(tag);
        for (String key : List.copyOf(tag.getAllKeys())) {
            var at = child(path, key);
            Tag value = tag.get(key);
            if (value instanceof CompoundTag compound) {
                if (item(subject, at, code) && compound.isEmpty()) {
                    compound.putString("id", "minecraft:air");
                    compound.putInt("count", 1);
                } else expand(compound, subject, at, code);
            } else if (value instanceof ListTag list)
                for (int i = 0; i < list.size(); i++)
                    if (list.get(i) instanceof CompoundTag compound)
                        expand(compound, subject, child(at, Integer.toString(i)), code);
        }
    }

    /**
     * Help-only examples of existing serialization contracts; never used to populate inventories.
     */
    public static void reference(CompoundTag tag) {
        reference(tag, com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.unknown());
    }

    public static void reference(
            CompoundTag tag,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject) {
        reference(tag, "", 0, subject, List.of(), codes(subject));
    }

    private static CompoundTag itemExample() {
        var item = new CompoundTag();
        item.putString("id", "minecraft:stone");
        item.putInt("count", 1);
        return item;
    }

    private static void reference(
            CompoundTag tag,
            String path,
            int depth,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            List<String> keys,
            List<com.eynny.npships.authoring.patches.PatchFieldTemplates> code) {
        if (depth > 32) return;
        // ItemStack is a shared serialized contract, independent of its owning block/entity.
        if (tag.contains("id", Tag.TAG_STRING)
                && tag.contains("count", Tag.TAG_ANY_NUMERIC)
                && !tag.contains("components")) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(tag.getString("id"));
            if (id != null
                    && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                var components = new CompoundTag();
                components.putString("minecraft:custom_name", "\"Example\"");
                tag.put("components", components);
            }
        }

        for (String key : List.copyOf(tag.getAllKeys())) {
            Tag value = tag.get(key);
            String child = path + "/" + key;
            if (value instanceof CompoundTag compound) {
                if (declaredItemField(subject, child(keys, key))
                        && (compound.isEmpty() || emptyPlaceholder(compound, false)))
                    tag.put(key, itemExample());
                else reference(compound, child, depth + 1, subject, child(keys, key), code);
            } else if (value instanceof ListTag list) {
                if (list.isEmpty()) {
                    Tag template = declaredEntry(subject, child);
                    if (template != null) list.add(template);
                }
                for (int index = 0; index < list.size(); index++) {
                    if (list.get(index) instanceof CompoundTag compound) {
                        reference(
                                compound,
                                child + "/" + index,
                                depth + 1,
                                subject,
                                child(child(keys, key), Integer.toString(index)),
                                code);
                    }
                }
            }
        }
    }

    public static Tag entry(String path) {
        return entry(
                com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.unknown(), path);
    }

    public static Tag entry(
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject, String path) {
        for (var code : codes(subject)) {
            var template = code.entry(subject, path);
            if (template != null) return template;
        }
        String key = path.substring(path.lastIndexOf('/') + 1);
        var tag = new CompoundTag();
        switch (key) {
            case "patterns" -> {
                tag.putString("pattern", "minecraft:base");
                tag.putString("color", "white");
            }
            case "SpawnPotentials", "spawn_potentials" -> {
                tag.putInt("weight", 1);
                var data = new CompoundTag();
                var entity = new CompoundTag();
                entity.putString("id", "minecraft:pig");
                data.put("entity", entity);
                tag.put("data", data);
            }
            case "bees" -> {
                var entity = new CompoundTag();
                entity.putString("id", "minecraft:bee");
                tag.put("entity_data", entity);
                tag.putInt("ticks_in_hive", 0);
                tag.putInt("min_ticks_in_hive", 600);
            }
            case "Items" -> {
                tag.putString("id", "minecraft:stone");
                tag.putInt("count", 1);
                tag.putByte("Slot", (byte) 0);
            }
            case "loot_tables_to_eject" -> {
                tag.putInt("weight", 1);
                tag.putString("data", "minecraft:spawners/trial_chamber/key");
            }
            default -> {
                return null;
            }
        }
        return tag;
    }

    public static void normalizeEmptyItems(CompoundTag tag) {
        normalizeChangedItems(
                tag,
                new CompoundTag(),
                tag.copy(),
                com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.unknown());
    }

    public static void normalizeChangedItems(
            CompoundTag tag,
            CompoundTag before,
            CompoundTag after,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject) {
        normalizeChangedItems(tag, before, after, subject, new CompoundTag());
    }

    /**
     * Original native roots distinguish persisted data from placeholders introduced by defaults.
     */
    public static void normalizeChangedItems(
            CompoundTag tag,
            CompoundTag before,
            CompoundTag after,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            CompoundTag original) {
        normalize(tag, before, after, original, subject, List.of(), codes(subject));
    }

    private static void normalize(
            CompoundTag tag,
            CompoundTag before,
            CompoundTag after,
            CompoundTag original,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            List<String> path,
            List<com.eynny.npships.authoring.patches.PatchFieldTemplates> code) {
        for (String key : List.copyOf(tag.getAllKeys())) {
            if (path.isEmpty() && java.util.Objects.equals(before.get(key), after.get(key)))
                continue;
            Tag value = tag.get(key);
            if (java.util.Objects.equals(before.get(key), after.get(key))
                    && java.util.Objects.equals(original.get(key), value)) continue;
            var at = child(path, key);
            if (value instanceof CompoundTag compound) {
                if (item(subject, at, code) && emptyPlaceholder(compound, false))
                    tag.put(key, new CompoundTag());
                else
                    normalize(
                            compound,
                            before.getCompound(key),
                            after.getCompound(key),
                            original.getCompound(key),
                            subject,
                            at,
                            code);
            } else if (value instanceof ListTag list) {
                var old = before.getList(key, Tag.TAG_COMPOUND);
                var next = after.getList(key, Tag.TAG_COMPOUND);
                var nativeValues = original.getList(key, Tag.TAG_COMPOUND);
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (!(list.get(i) instanceof CompoundTag compound)) continue;
                    var oldValue = i < old.size() ? old.getCompound(i) : new CompoundTag();
                    var newValue = i < next.size() ? next.getCompound(i) : new CompoundTag();
                    var nativeValue =
                            i < nativeValues.size()
                                    ? nativeValues.getCompound(i)
                                    : new CompoundTag();
                    if (oldValue.equals(newValue) && nativeValue.equals(compound)) continue;
                    if (key.equals("Items") && emptyPlaceholder(compound, true)) list.remove(i);
                    else
                        normalize(
                                compound,
                                oldValue,
                                newValue,
                                nativeValue,
                                subject,
                                child(at, Integer.toString(i)),
                                code);
                }
            }
        }
    }

    // Collapse only the editor's canonical empty placeholder, never arbitrary authored extras.
    private static boolean emptyPlaceholder(CompoundTag value, boolean slot) {
        return value.getString("id").equals("minecraft:air")
                && value.getAllKeys().stream()
                        .allMatch(
                                key ->
                                        key.equals("id")
                                                || key.equals("count")
                                                || slot && key.equals("Slot"));
    }

    public static List<String> choices(String path, String key) {
        return choices(
                com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.unknown(),
                path,
                key);
    }

    public static List<String> choices(
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            String path,
            String key) {
        for (var schema : codes(subject)) {
            var declared = schema.choices(path, key);
            if (!declared.isEmpty()) return List.copyOf(declared);
        }
        return List.of();
    }
}
