package com.eynny.npships.authoring.document;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.CustomData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** Native loot JSON hints. Examples never validate, normalize, or replace the authored document. */
public final class LootDataSuggestions {
    private LootDataSuggestions() {}

    private static final String UNIFORM = "{\"type\":\"minecraft:uniform\",\"min\":1,\"max\":3}";
    private static final String REFERENCE =
            """
            {
              "type": "minecraft:chest",
              "pools": [
                {
                  "rolls": {"type": "minecraft:uniform", "min": 1, "max": 3},
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:bread",
                      "weight": 3,
                      "functions": [
                        {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 4}}
                      ]
                    },
                    {"type": "minecraft:item", "name": "minecraft:apple", "weight": 1}
                  ],
                  "conditions": [{"condition": "minecraft:random_chance", "chance": 0.75}]
                },
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:stone",
                      "functions": [
                        {
                          "function": "minecraft:set_components",
                          "components": {
                            "minecraft:custom_name": {"text": "Example keepsake"},
                            "minecraft:custom_data": {"example": true}
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    /** Capture registry names and codec field names once for this detached help binding. */
    public static DataSuggestions create(JsonObject stored, HolderLookup.Provider registries) {
        Objects.requireNonNull(stored);
        Objects.requireNonNull(registries);
        var defaults = new CompoundTag();
        defaults.putString("type", "minecraft:chest");
        defaults.put("pools", new net.minecraft.nbt.ListTag());
        CompoundTag observed;
        try {
            var value = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, stored);
            observed = value instanceof CompoundTag object ? object : new CompoundTag();
        } catch (RuntimeException unavailableHintProjection) {
            // JSON may contain heterogeneous arrays unavailable to NBT. Only help loses that view.
            observed = new CompoundTag();
        }
        return new DataSuggestions(defaults, observed, Map.of()).jsonFormat(new Hints(registries));
    }

    private enum Role {
        ROOT,
        POOL,
        ENTRY,
        FUNCTION,
        CONDITION,
        NUMBER,
        RANGE,
        UNKNOWN
    }

    private static final class Hints implements DataSuggestions.JsonHints {
        private final Map<String, Map<String, String>> entries =
                codecs(BuiltInRegistries.LOOT_POOL_ENTRY_TYPE, value -> value.codec());
        private final Map<String, Map<String, String>> functions =
                codecs(BuiltInRegistries.LOOT_FUNCTION_TYPE, value -> value.codec());
        private final Map<String, Map<String, String>> conditions =
                codecs(BuiltInRegistries.LOOT_CONDITION_TYPE, value -> value.codec());
        private final Map<String, Map<String, String>> numbers =
                codecs(BuiltInRegistries.LOOT_NUMBER_PROVIDER_TYPE, value -> value.codec());
        private final List<String> itemIds = ids(BuiltInRegistries.ITEM);
        private final List<String> itemTags =
                BuiltInRegistries.ITEM
                        .getTagNames()
                        .map(tag -> quote(tag.location().toString()))
                        .sorted()
                        .limit(4096)
                        .toList();
        private final List<String> enchantments;
        private final List<String> potions;
        private final List<String> predicates;
        private final List<String> modifiers;
        private final List<String> tables;
        private final String enchantmentExample;
        private final String reference;
        private final String componentPatchExample;
        private final Map<String, Map<String, String>> componentFields = new LinkedHashMap<>();
        private final Map<String, List<String>> componentValues = new LinkedHashMap<>();
        private final Map<String, String> enchantmentFields;

        private Hints(HolderLookup.Provider registries) {
            enchantments = ids(registries, Registries.ENCHANTMENT);
            potions = ids(registries, Registries.POTION);
            predicates = ids(registries, Registries.PREDICATE);
            modifiers = ids(registries, Registries.ITEM_MODIFIER);
            tables = ids(registries, Registries.LOOT_TABLE);
            enchantmentExample =
                    enchantments.isEmpty() ? "{}" : "{" + enchantments.getFirst() + ":1}";
            var document = JsonParser.parseString(REFERENCE).getAsJsonObject();
            var components =
                    document.getAsJsonArray("pools")
                            .get(1)
                            .getAsJsonObject()
                            .getAsJsonArray("entries")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonArray("functions")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonObject("components");
            var customData = new CompoundTag();
            customData.putBoolean("example", true);
            components.add(
                    "minecraft:custom_name",
                    DataComponents.CUSTOM_NAME
                            .codec()
                            .encodeStart(
                                    registries.createSerializationContext(JsonOps.INSTANCE),
                                    Component.literal("Example keepsake"))
                            .getOrThrow());
            components.add(
                    "minecraft:custom_data",
                    DataComponents.CUSTOM_DATA
                            .codec()
                            .encodeStart(
                                    registries.createSerializationContext(JsonOps.INSTANCE),
                                    CustomData.of(customData))
                            .getOrThrow());
            componentPatchExample = components.toString();
            reference = new GsonBuilder().setPrettyPrinting().create().toJson(document);
            var itemHints = ItemSettings.hints(registries);
            enchantmentFields = itemHints.enchantments();
            var names = new TreeMap<String, String>();
            itemHints
                    .names()
                    .forEach(
                            (id, ignored) -> {
                                if (id.startsWith("!")) {
                                    names.put(id, "{}");
                                    return;
                                }
                                var type =
                                        BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                                                ResourceLocation.parse(id));
                                var encoded =
                                        componentExample(
                                                type, itemHints.components().get(id), registries);
                                names.put(id, encoded == null ? "" : encoded.toString());
                                if (encoded != null)
                                    collectComponent("components." + id, encoded, 0);
                            });
            componentFields.put("components", Map.copyOf(names));
            for (String id : List.of("minecraft:enchantments", "minecraft:stored_enchantments"))
                componentFields.put("components." + id + ".levels", enchantmentFields);
            itemHints
                    .values()
                    .forEach(
                            (path, choices) -> {
                                if (path.startsWith("components."))
                                    componentValues.put(
                                            path,
                                            choices.stream()
                                                    .map(
                                                            value ->
                                                                    value.startsWith("\"")
                                                                            ? JsonParser
                                                                                    .parseString(
                                                                                            value)
                                                                                    .toString()
                                                                            : quote(value))
                                                    .toList());
                            });
        }

        private Role role(
                String path,
                CompoundTag current,
                Map<String, CompoundTag> parents,
                Set<String> arrays) {
            return role(path, current, parents, arrays, 0);
        }

        private Role role(
                String path,
                CompoundTag current,
                Map<String, CompoundTag> parents,
                Set<String> arrays,
                int depth) {
            if (depth > 32 || parents.size() > 32) return Role.UNKNOWN;
            if (path.isEmpty()) return depth > 0 || parents.isEmpty() ? Role.ROOT : Role.UNKNOWN;
            if (path.endsWith("[]") && !arrays.contains(path.substring(0, path.length() - 2)))
                return Role.UNKNOWN;
            if (path.equals("pools[]") && parents.containsKey("")) return Role.POOL;
            if (path.equals("functions[]") && parents.containsKey("")) return Role.FUNCTION;
            String parentPath = null;
            // Registry IDs can contain dots. Only the actual object chain establishes a parent.
            for (String candidate : parents.keySet())
                if ((candidate.isEmpty() || path.startsWith(candidate + "."))
                        && !candidate.equals(path)
                        && (parentPath == null || candidate.length() > parentPath.length()))
                    parentPath = candidate;
            if (parentPath == null) return Role.UNKNOWN;
            String field = path.substring(parentPath.isEmpty() ? 0 : parentPath.length() + 1);
            var parent = parents.get(parentPath);
            Role owner = role(parentPath, parent, parents, arrays, depth + 1);
            String selected = type(parent, selector(owner));
            boolean nativeOwner =
                    selected.startsWith("minecraft:") && registry(owner).containsKey(selected);
            var ownerFields = registry(owner).getOrDefault(selected, Map.of());
            if (owner == Role.POOL && field.equals("entries[]")) return Role.ENTRY;
            if (owner == Role.ENTRY
                    && field.equals("children[]")
                    && Set.of("minecraft:alternatives", "minecraft:sequence", "minecraft:group")
                            .contains(selected)) return Role.ENTRY;
            if (field.equals("functions[]")
                    && (owner == Role.POOL
                            || nativeOwner
                                    && ownerFields.containsKey("functions")
                                    && (owner == Role.ENTRY || owner == Role.FUNCTION)))
                return Role.FUNCTION;
            if (field.equals("conditions[]")
                    && (owner == Role.POOL
                            || nativeOwner
                                    && ownerFields.containsKey("conditions")
                                    && (owner == Role.ENTRY || owner == Role.FUNCTION)))
                return Role.CONDITION;
            if (owner == Role.CONDITION
                    && (field.equals("term") && selected.equals("minecraft:inverted")
                            || field.equals("terms[]")
                                    && Set.of("minecraft:all_of", "minecraft:any_of")
                                            .contains(selected))) return Role.CONDITION;
            if (owner == Role.CONDITION
                    && (selected.equals("minecraft:value_check") && field.equals("range")
                            || selected.equals("minecraft:time_check") && field.equals("value")))
                return Role.RANGE;
            if (numberField(owner, selected, field)) return Role.NUMBER;
            if (isEnchantmentMap(parentPath, parents, arrays, depth + 1)) return Role.NUMBER;
            return Role.UNKNOWN;
        }

        private boolean isEnchantmentMap(
                String path, Map<String, CompoundTag> parents, Set<String> arrays) {
            return isEnchantmentMap(path, parents, arrays, 0);
        }

        private boolean isEnchantmentMap(
                String path, Map<String, CompoundTag> parents, Set<String> arrays, int depth) {
            if (depth > 32 || !path.endsWith(".enchantments")) return false;
            String owner = path.substring(0, path.length() - ".enchantments".length());
            var record = parents.getOrDefault(owner, new CompoundTag());
            return type(record, "function").equals("minecraft:set_enchantments")
                    && role(owner, record, parents, arrays, depth + 1) == Role.FUNCTION;
        }

        private String componentPath(
                String path, Map<String, CompoundTag> parents, Set<String> arrays) {
            for (var ancestor : parents.entrySet()) {
                if (!type(ancestor.getValue(), "function").equals("minecraft:set_components"))
                    continue;
                String base = ancestor.getKey() + ".components";
                if ((path.equals(base) || path.startsWith(base + "."))
                        && role(ancestor.getKey(), ancestor.getValue(), parents, arrays)
                                == Role.FUNCTION)
                    return componentPath(path, ancestor.getKey(), base, parents, arrays);
            }
            return "";
        }

        private String componentPath(
                String path,
                String owner,
                String base,
                Map<String, CompoundTag> parents,
                Set<String> arrays) {
            if (path.equals(base)) return "components";
            if (!parents.containsKey(base)) return "";
            String relative = path.substring(owner.length() + 1);
            for (int at = relative.indexOf("[]"); at >= 0; at = relative.indexOf("[]", at + 2))
                if (!arrays.contains(owner + "." + relative.substring(0, at))) return "";
            String root = null;
            for (String id : componentFields.getOrDefault("components", Map.of()).keySet()) {
                String candidate = "components." + id;
                if ((relative.equals(candidate)
                                || relative.startsWith(candidate + ".")
                                || relative.startsWith(candidate + "[]"))
                        && (root == null || candidate.length() > root.length())) root = candidate;
            }
            if (root == null) return "";
            // A literal dotted author key must not impersonate an absent component object.
            for (String object : componentFields.keySet())
                if ((object.equals(root)
                                || object.startsWith(root + ".")
                                || object.startsWith(root + "[]"))
                        && relative.startsWith(object + ".")
                        && !parents.containsKey(owner + "." + object)) return "";
            return relative;
        }

        private void collectComponent(String path, JsonElement value, int depth) {
            if (depth > 16 || componentFields.size() >= 2048) return;
            componentValues.put(path, List.of(value.toString()));
            if (value.isJsonObject()) {
                var fields = new LinkedHashMap<String, String>();
                value.getAsJsonObject().entrySet().stream()
                        .limit(256)
                        .forEach(
                                entry -> {
                                    fields.put(entry.getKey(), entry.getValue().toString());
                                    collectComponent(
                                            path + "." + entry.getKey(),
                                            entry.getValue(),
                                            depth + 1);
                                });
                componentFields.put(path, Map.copyOf(fields));
            } else if (value.isJsonArray() && !value.getAsJsonArray().isEmpty())
                collectComponent(path + "[]", value.getAsJsonArray().get(0), depth + 1);
        }

        @Override
        public Map<String, String> fields(String path, CompoundTag current) {
            return fields(path, current, Map.of());
        }

        @Override
        public Map<String, String> fields(
                String path, CompoundTag current, Map<String, CompoundTag> parents) {
            return fields(path, current, parents, Set.of());
        }

        @Override
        public Map<String, String> fields(
                String path,
                CompoundTag current,
                Map<String, CompoundTag> parents,
                Set<String> arrays) {
            String component = componentPath(path, parents, arrays);
            if (!component.isEmpty()) return componentFields.getOrDefault(component, Map.of());
            if (isEnchantmentMap(path, parents, arrays)) return enchantmentFields;
            Role role = role(path, current, parents, arrays);
            if (role == Role.ROOT)
                return Map.of(
                        "type",
                        quote("minecraft:chest"),
                        "pools",
                        "[]",
                        "functions",
                        "[]",
                        "random_sequence",
                        "");
            if (role == Role.POOL)
                return Map.of(
                        "rolls",
                        "1",
                        "bonus_rolls",
                        "0",
                        "entries",
                        "[]",
                        "conditions",
                        "[]",
                        "functions",
                        "[]",
                        "name",
                        quote("example"));
            if (role == Role.RANGE) return Map.of("min", "0", "max", "3");
            String selector = selector(role);
            if (selector.isEmpty()) return Map.of();
            String selected = type(current, selector);
            Map<String, Map<String, String>> known = registry(role);
            var result = new LinkedHashMap<String, String>();
            result.put(selector, "");
            // An object in a proven number-provider field may use the native untyped uniform form.
            String effective =
                    selected.isEmpty() && role == Role.NUMBER ? "minecraft:uniform" : selected;
            result.putAll(known.getOrDefault(effective, Map.of()));
            var samples = samples(role, effective);
            result.replaceAll((key, value) -> samples.getOrDefault(key, value));
            return Map.copyOf(result);
        }

        @Override
        public List<String> values(String path, String key, CompoundTag current) {
            return values(path, key, current, Map.of());
        }

        @Override
        public List<String> values(
                String path, String key, CompoundTag current, Map<String, CompoundTag> parents) {
            return values(path, key, current, parents, Set.of());
        }

        @Override
        public List<String> values(
                String path,
                String key,
                CompoundTag current,
                Map<String, CompoundTag> parents,
                Set<String> arrays) {
            if (key.isEmpty() && !arrays.contains(path)) return List.of();
            String component = componentPath(path, parents, arrays);
            if (!component.isEmpty()) {
                String at = component + (key.isEmpty() ? "[]" : "." + key);
                var choices = componentValues.getOrDefault(at, List.of());
                if (!choices.isEmpty()) return choices;
                var sample = componentFields.getOrDefault(component, Map.of()).get(key);
                return sample == null || sample.isEmpty() ? List.of() : List.of(sample);
            }
            if (isEnchantmentMap(path, parents, arrays) && enchantmentFields.containsKey(key))
                return List.of("1", UNIFORM);
            if (key.isEmpty()) {
                if (path.endsWith(".options")) {
                    String owner = path.substring(0, path.length() - ".options".length());
                    var record = parents.getOrDefault(owner, new CompoundTag());
                    if (role(owner, record, parents, arrays) == Role.FUNCTION
                            && Set.of("minecraft:enchant_randomly", "minecraft:enchant_with_levels")
                                    .contains(type(record, "function"))) return enchantments;
                }
                return role(path + "[]", current, parents, arrays) == Role.UNKNOWN
                        ? List.of()
                        : List.of("{}");
            }
            Role role = role(path, current, parents, arrays);
            if (key.equals(selector(role))) {
                if (role == Role.ROOT) return List.of(quote("minecraft:chest"));
                return registry(role).keySet().stream()
                        .sorted()
                        .map(LootDataSuggestions::quote)
                        .toList();
            }
            String selected = type(current, selector(role));
            if (role == Role.ENTRY && selected.equals("minecraft:item") && key.equals("name"))
                return itemIds;
            if (role == Role.ENTRY && selected.equals("minecraft:tag") && key.equals("name"))
                return itemTags;
            if (role == Role.ENTRY
                    && selected.equals("minecraft:loot_table")
                    && key.equals("value")) return tables;
            if (role == Role.FUNCTION) {
                if (selected.equals("minecraft:set_item") && key.equals("item")) return itemIds;
                if (selected.equals("minecraft:set_potion") && key.equals("id")) return potions;
                if (key.equals("enchantment")
                        && Set.of("minecraft:apply_bonus", "minecraft:enchanted_count_increase")
                                .contains(selected)) return enchantments;
                if (key.equals("options")
                        && Set.of("minecraft:enchant_randomly", "minecraft:enchant_with_levels")
                                .contains(selected)) return List.of("[]");
                if (selected.equals("minecraft:reference") && key.equals("name")) return modifiers;
                if (selected.equals("minecraft:set_loot_table") && key.equals("name"))
                    return tables;
            }
            if (role == Role.CONDITION) {
                if (selected.equals("minecraft:reference") && key.equals("name")) return predicates;
                if (key.equals("enchantment")
                        && Set.of(
                                        "minecraft:table_bonus",
                                        "minecraft:random_chance_with_enchanted_bonus")
                                .contains(selected)) return enchantments;
            }
            var sample = fields(path, current, parents, arrays).get(key);
            if (sample == null || sample.isEmpty()) return List.of();
            if (numberField(role, selected, key))
                return List.of(sample, UNIFORM).stream().distinct().toList();
            if (sample.equals("false") || sample.equals("true")) return List.of("false", "true");
            return List.of(sample);
        }

        private static boolean numberField(Role role, String selected, String key) {
            if (role == Role.POOL) return key.equals("rolls") || key.equals("bonus_rolls");
            if (role == Role.RANGE) return key.equals("min") || key.equals("max");
            if (role == Role.NUMBER && (selected.isEmpty() || selected.equals("minecraft:uniform")))
                return key.equals("min") || key.equals("max");
            if (role == Role.NUMBER && selected.equals("minecraft:binomial"))
                return key.equals("n") || key.equals("p");
            return role == Role.FUNCTION
                            && (selected.equals("minecraft:set_count") && key.equals("count")
                                    || selected.equals("minecraft:set_damage")
                                            && key.equals("damage")
                                    || selected.equals("minecraft:enchant_with_levels")
                                            && key.equals("levels"))
                    || role == Role.CONDITION
                            && (selected.equals("minecraft:random_chance") && key.equals("chance")
                                    || selected.equals("minecraft:value_check")
                                            && key.equals("value"));
        }

        private Map<String, String> samples(Role role, String selected) {
            // Codec keys alone cannot establish value shapes, including similarly named foreign
            // fields.
            if (!selected.startsWith("minecraft:")) return Map.of();
            var result = new LinkedHashMap<String, String>();
            if (role == Role.ENTRY) {
                result.put("conditions", "[]");
                result.put("functions", "[]");
                result.put("weight", "1");
                result.put("quality", "0");
                switch (selected) {
                    case "minecraft:item" -> result.put("name", quote("minecraft:stone"));
                    case "minecraft:tag" -> result.put("expand", "true");
                    case "minecraft:alternatives", "minecraft:sequence", "minecraft:group" ->
                            result.put("children", "[]");
                    default -> {}
                }
            } else if (role == Role.FUNCTION) {
                result.put("conditions", "[]");
                switch (selected) {
                    case "minecraft:set_count" -> {
                        result.put("count", "1");
                        result.put("add", "false");
                    }
                    case "minecraft:set_damage" -> {
                        result.put("damage", "1");
                        result.put("add", "false");
                    }
                    case "minecraft:set_components" ->
                            result.put("components", componentPatchExample);
                    case "minecraft:set_enchantments" -> {
                        result.put("enchantments", enchantmentExample);
                        result.put("add", "false");
                    }
                    case "minecraft:set_name" -> result.put("name", "{\"text\":\"Example\"}");
                    case "minecraft:set_item" -> result.put("item", quote("minecraft:stone"));
                    case "minecraft:enchant_with_levels" -> {
                        result.put("levels", "1");
                        result.put("options", "[]");
                    }
                    case "minecraft:enchant_randomly" -> {
                        result.put("options", "[]");
                        result.put("only_compatible", "true");
                    }
                    case "minecraft:sequence" -> result.put("functions", "[]");
                    case "minecraft:set_custom_model_data" -> result.put("value", "1");
                    case "minecraft:set_custom_data" -> result.put("tag", "{}");
                    default -> {}
                }
            } else if (role == Role.CONDITION) {
                switch (selected) {
                    case "minecraft:random_chance" -> result.put("chance", "0.5");
                    case "minecraft:inverted" ->
                            result.put(
                                    "term",
                                    "{\"condition\":\"minecraft:random_chance\",\"chance\":0.5}");
                    case "minecraft:all_of", "minecraft:any_of" -> result.put("terms", "[]");
                    case "minecraft:weather_check" -> {
                        result.put("raining", "false");
                        result.put("thundering", "false");
                    }
                    case "minecraft:time_check" -> {
                        result.put("period", "24000");
                        result.put("value", "{\"min\":0,\"max\":12000}");
                    }
                    case "minecraft:value_check" -> {
                        result.put("value", "1");
                        result.put("range", "{\"min\":0,\"max\":3}");
                    }
                    default -> {}
                }
            } else if (role == Role.NUMBER) {
                switch (selected.isEmpty() ? "minecraft:uniform" : selected) {
                    case "minecraft:constant" -> result.put("value", "1");
                    case "minecraft:uniform" -> {
                        result.put("min", "1");
                        result.put("max", "3");
                    }
                    case "minecraft:binomial" -> {
                        result.put("n", "3");
                        result.put("p", "0.5");
                    }
                    default -> {}
                }
            }
            return result;
        }

        private Map<String, Map<String, String>> registry(Role role) {
            return switch (role) {
                case ENTRY -> entries;
                case FUNCTION -> functions;
                case CONDITION -> conditions;
                case NUMBER -> numbers;
                default -> Map.of();
            };
        }

        @Override
        public String example() {
            return reference;
        }

        @Override
        public String descriptionKey(String path) {
            if (path.isEmpty()) return "npships.loot.help.root";
            String field = path.substring(path.lastIndexOf('.') + 1).replace("[]", "");
            return "npships.loot.help."
                    + (Set.of(
                                            "type",
                                            "pools",
                                            "rolls",
                                            "bonus_rolls",
                                            "entries",
                                            "children",
                                            "name",
                                            "weight",
                                            "quality",
                                            "functions",
                                            "function",
                                            "conditions",
                                            "condition",
                                            "count",
                                            "add",
                                            "chance",
                                            "min",
                                            "max",
                                            "components",
                                            "enchantments",
                                            "enchantment",
                                            "options",
                                            "levels",
                                            "damage",
                                            "term",
                                            "terms",
                                            "random_sequence",
                                            "expand",
                                            "value",
                                            "range",
                                            "period",
                                            "raining",
                                            "thundering",
                                            "tag")
                                    .contains(field)
                            ? field
                            : "codec_field");
        }
    }

    private static <T> JsonElement componentExample(
            DataComponentType<T> type, Tag encoded, HolderLookup.Provider registries) {
        if (type == null || type.codec() == null || encoded == null) return null;
        try {
            return type.codec()
                    .parse(registries.createSerializationContext(NbtOps.INSTANCE), encoded)
                    .flatMap(
                            value ->
                                    type.codec()
                                            .encodeStart(
                                                    registries.createSerializationContext(
                                                            JsonOps.INSTANCE),
                                                    value))
                    .result()
                    .orElse(null);
        } catch (RuntimeException unavailableExample) {
            return null;
        }
    }

    private static String selector(Role role) {
        return switch (role) {
            case ROOT, ENTRY, NUMBER -> "type";
            case FUNCTION -> "function";
            case CONDITION -> "condition";
            default -> "";
        };
    }

    private static String type(CompoundTag current, String selector) {
        if (selector.isEmpty() || current.getString(selector).isEmpty()) return "";
        var id = ResourceLocation.tryParse(current.getString(selector));
        return id == null ? "" : id.toString();
    }

    private static <T> Map<String, Map<String, String>> codecs(
            Registry<T> registry, Function<T, MapCodec<?>> codec) {
        var result = new TreeMap<String, Map<String, String>>();
        for (var id : registry.keySet().stream().sorted().limit(4096).toList()) {
            var fields = new TreeMap<String, String>();
            try {
                codec.apply(registry.get(id))
                        .keys(JsonOps.INSTANCE)
                        .limit(256)
                        .filter(key -> key.isJsonPrimitive() && key.getAsJsonPrimitive().isString())
                        .forEach(key -> fields.put(key.getAsString(), ""));
            } catch (RuntimeException unavailableShape) {
                // A provider with opaque codec keys retains its ID and authored JSON.
            }
            result.put(id.toString(), Map.copyOf(fields));
        }
        return Map.copyOf(result);
    }

    private static List<String> ids(Registry<?> registry) {
        return registry.keySet().stream()
                .sorted()
                .limit(16384)
                .map(id -> quote(id.toString()))
                .toList();
    }

    private static <T> List<String> ids(
            HolderLookup.Provider registries, ResourceKey<? extends Registry<T>> key) {
        return registries
                .lookup(key)
                .map(
                        lookup ->
                                lookup.listElementIds()
                                        .map(id -> quote(id.location().toString()))
                                        .sorted()
                                        .limit(4096)
                                        .toList())
                .orElse(List.of());
    }

    private static String quote(String value) {
        return new JsonPrimitive(value).toString();
    }
}
