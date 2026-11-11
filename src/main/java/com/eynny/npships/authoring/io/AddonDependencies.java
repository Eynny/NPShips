package com.eynny.npships.authoring.io;

import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.SchematicSafety.InspectionScope;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicExtensions;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.PatchInstances;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.JsonElement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Read-only export inspection. Class ownership and provider declarations, never namespace guesses.
 */
public final class AddonDependencies {
    public record Inspection(List<AddonDependency> defaults, Set<String> unresolvedReferences) {
        public Inspection {
            defaults = List.copyOf(defaults);
            unresolvedReferences = Collections.unmodifiableSet(new TreeSet<>(unresolvedReferences));
        }
    }

    public static Inspection inspect(Collection<SchematicDocument> documents) {
        var required = new TreeSet<String>(List.of("minecraft", "neoforge", "npships"));
        var unresolved = new TreeSet<String>();
        var owners = new HashMap<String, Set<String>>();
        for (var file : ModList.get().getModFiles()) {
            if (file.getMods().size() != 1) continue;
            String id = file.getMods().getFirst().getModId();
            var scan = file.getFile().getScanResult();
            if (scan == null) continue;
            for (var type : scan.getClasses())
                owners.computeIfAbsent(type.clazz().getClassName(), key -> new TreeSet<>()).add(id);
        }
        var references = new TreeSet<ResourceLocation>();
        for (var doc : documents) {
            collect(doc.nbtCopy(), InspectionScope.DOCUMENT, references, required, unresolved);
            config(doc.configCopy(), references, required, unresolved);
        }
        for (var id : references) {
            var candidates = new TreeSet<String>();
            for (var registry :
                    List.of(
                            BuiltInRegistries.BLOCK,
                            BuiltInRegistries.ITEM,
                            BuiltInRegistries.ENTITY_TYPE,
                            BuiltInRegistries.BLOCK_ENTITY_TYPE))
                registry.getOptional(id)
                        .ifPresent(
                                value -> {
                                    var declared =
                                            owners.getOrDefault(
                                                    value.getClass().getName(), Set.of());
                                    // A block and its vanilla BlockItem have independent,
                                    // legitimate code owners. Only a duplicated class owner
                                    // is ambiguous, not the union of different classes.
                                    if (declared.size() == 1) candidates.addAll(declared);
                                });
            // A declaring class proves a code dependency, not who registered this resource.
            required.addAll(candidates);
            boolean registeredVanilla =
                    id.getNamespace().equals("minecraft")
                            && (BuiltInRegistries.BLOCK.containsKey(id)
                                    || BuiltInRegistries.ITEM.containsKey(id)
                                    || BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                                    || BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id));
            // Matching a known declaring mod and namespace resolves this ordinary reference.
            // A shared vanilla/base class alone still cannot prove a foreign registrar.
            if (!registeredVanilla && !candidates.contains(id.getNamespace()))
                unresolved.add(id.toString());
        }
        var defaults = new ArrayList<AddonDependency>();
        for (String id : required) {
            var mod = ModList.get().getModContainerById(id);
            if (mod.isEmpty()) {
                unresolved.add("mod:" + id);
                continue;
            }
            defaults.add(new AddonDependency(id, true, installedRange(id)));
        }
        return new Inspection(defaults, unresolved);
    }

    /**
     * Host prerequisites are displayed separately; no content owner is inferred from a namespace.
     */
    public static Set<String> runtimeMods() {
        var result = new TreeSet<String>(List.of("minecraft", "neoforge"));
        var pending = new ArrayDeque<String>();
        pending.add("npships");
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!result.add(id)) continue;
            ModList.get()
                    .getModContainerById(id)
                    .ifPresent(
                            mod -> {
                                for (var dep : mod.getModInfo().getDependencies())
                                    if (dep.getType().name().equals("REQUIRED"))
                                        pending.add(dep.getModId());
                            });
        }
        return Collections.unmodifiableSet(result);
    }

    public static String installedRange(String id) {
        var version =
                ModList.get()
                        .getModContainerById(id)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Mod is not installed: " + id))
                        .getModInfo()
                        .getVersion()
                        .toString();
        // The game platform is explicitly tied to MC1.21.1; newer mod builds remain allowed.
        return "[" + version + (id.equals("minecraft") ? "]" : ",)");
    }

    private static void provider(String text, Set<String> required, Set<String> unresolved) {
        var id = ResourceLocation.tryParse(text);
        var entry = id == null ? null : PatchTypes.providers().get(id);
        if (entry == null) {
            unresolved.add("provider:" + text);
            return;
        }
        required.add(entry.registration().ownerMod());
        required.addAll(entry.requiredMods());
    }

    private static void config(
            JsonElement node,
            Set<ResourceLocation> refs,
            Set<String> required,
            Set<String> unresolved) {
        if (node == null) return;
        if (node.isJsonArray()) {
            for (var child : node.getAsJsonArray()) config(child, refs, required, unresolved);
            return;
        }
        if (!node.isJsonObject()) return;
        var object = node.getAsJsonObject();
        patchConfig(object, "patches", false, required, unresolved);
        patchConfig(object, EntityPatches.KEY, true, required, unresolved);
        for (var rule : StructureCrew.read(object)) {
            nativeRoot(rule.state(), true, refs, 0);
            for (var choice : rule.pool()) {
                if (choice.type().startsWith("#"))
                    unresolved.add("entity_tag:" + choice.type().substring(1));
                else refs.add(ResourceLocation.parse(choice.type()));
                nativeRoot(choice.state(), true, refs, 0);
            }
        }
        if (object.has(EmbeddedLoot.KEY))
            for (var table : object.getAsJsonObject(EmbeddedLoot.KEY).entrySet())
                loot(table.getValue(), LootRole.TABLE, refs, unresolved);
        equipment(object, refs);
        for (String key : List.of("segments", "pool"))
            if (object.has(key)) config(object.get(key), refs, required, unresolved);
    }

    private enum LootRole {
        TABLE,
        POOL,
        ENTRY,
        FUNCTION,
        CONDITION
    }

    // Finite MC1.21.1 codec grammar, verified against the installed native registration sources.
    // A custom codec owns all its fields, including private keys named children/functions/terms.
    private static final Set<String> NATIVE_LOOT_ENTRIES =
            Set.of(
                    "empty",
                    "item",
                    "loot_table",
                    "dynamic",
                    "tag",
                    "alternatives",
                    "sequence",
                    "group");
    private static final Set<String> NATIVE_LOOT_FUNCTIONS =
            Set.of(
                    "set_count",
                    "set_item",
                    "enchant_with_levels",
                    "enchant_randomly",
                    "set_enchantments",
                    "set_custom_data",
                    "set_components",
                    "furnace_smelt",
                    "enchanted_count_increase",
                    "set_damage",
                    "set_attributes",
                    "set_name",
                    "exploration_map",
                    "set_stew_effect",
                    "copy_name",
                    "set_contents",
                    "modify_contents",
                    "filtered",
                    "limit_count",
                    "apply_bonus",
                    "set_loot_table",
                    "explosion_decay",
                    "set_lore",
                    "fill_player_head",
                    "copy_custom_data",
                    "copy_state",
                    "set_banner_pattern",
                    "set_potion",
                    "set_instrument",
                    "reference",
                    "sequence",
                    "copy_components",
                    "set_fireworks",
                    "set_firework_explosion",
                    "set_book_cover",
                    "set_written_book_pages",
                    "set_writable_book_pages",
                    "toggle_tooltips",
                    "set_ominous_bottle_amplifier",
                    "set_custom_model_data");
    private static final Set<String> NATIVE_LOOT_CONDITIONS =
            Set.of(
                    "inverted",
                    "any_of",
                    "all_of",
                    "random_chance",
                    "random_chance_with_enchanted_bonus",
                    "entity_properties",
                    "killed_by_player",
                    "entity_scores",
                    "block_state_property",
                    "match_tool",
                    "table_bonus",
                    "survives_explosion",
                    "damage_source_properties",
                    "location_check",
                    "weather_check",
                    "reference",
                    "time_check",
                    "value_check",
                    "enchantment_active_check");

    private static String lootCodec(
            com.google.gson.JsonObject object,
            String key,
            Set<String> supported,
            String label,
            Set<String> unresolved) {
        var id = ResourceLocation.tryParse(primitiveString(object, key));
        if (id == null) return "";
        if (!id.getNamespace().equals("minecraft") || !supported.contains(id.getPath())) {
            unresolved.add(label + ":" + id);
            return "";
        }
        return id.getPath();
    }

    private static void lootField(
            com.google.gson.JsonObject object,
            String key,
            LootRole role,
            Set<ResourceLocation> refs,
            Set<String> unresolved,
            java.util.function.Consumer<LootReference> references,
            int depth) {
        if (object.has(key)) loot(object.get(key), role, refs, unresolved, references, depth + 1);
    }

    private static void lootReference(
            com.google.gson.JsonObject object, String key, String label, Set<String> unresolved) {
        String text = primitiveString(object, key);
        if (!text.isEmpty()) unresolved.add(label + ":" + text);
    }

    private static void lootResource(
            com.google.gson.JsonObject object, String key, Set<ResourceLocation> refs) {
        String text = primitiveString(object, key);
        if (!text.isEmpty()) refs.add(ResourceLocation.parse(text));
    }

    /** Native grammar shared by inspection, export relocation and working import. */
    record LootReference(com.google.gson.JsonObject object, String key, boolean deferred) {
        String id() {
            return object.get(key).getAsString();
        }

        void replace(JsonElement value) {
            object.add(key, value);
        }
    }

    static void lootReferences(
            JsonElement table, java.util.function.Consumer<LootReference> references) {
        loot(table, LootRole.TABLE, new TreeSet<>(), new TreeSet<>(), references, 0);
    }

    private static void loot(
            JsonElement node, LootRole role, Set<ResourceLocation> refs, Set<String> unresolved) {
        loot(
                node,
                role,
                refs,
                unresolved,
                reference -> unresolved.add("loot:" + reference.id()),
                0);
    }

    private static void loot(
            JsonElement node,
            LootRole role,
            Set<ResourceLocation> refs,
            Set<String> unresolved,
            java.util.function.Consumer<LootReference> references,
            int depth) {
        if (depth > 64)
            throw new IllegalArgumentException("Native loot grammar nesting exceeds 64 levels.");
        if (node.isJsonArray()) {
            for (var child : node.getAsJsonArray())
                loot(child, role, refs, unresolved, references, depth + 1);
            return;
        }
        if (!node.isJsonObject()) return;
        var object = node.getAsJsonObject();
        switch (role) {
            case TABLE -> {
                lootField(object, "pools", LootRole.POOL, refs, unresolved, references, depth);
                lootField(
                        object,
                        "functions",
                        LootRole.FUNCTION,
                        refs,
                        unresolved,
                        references,
                        depth);
            }
            case POOL -> {
                lootField(object, "entries", LootRole.ENTRY, refs, unresolved, references, depth);
                lootField(
                        object,
                        "conditions",
                        LootRole.CONDITION,
                        refs,
                        unresolved,
                        references,
                        depth);
                lootField(
                        object,
                        "functions",
                        LootRole.FUNCTION,
                        refs,
                        unresolved,
                        references,
                        depth);
            }
            case ENTRY -> {
                String type = lootCodec(object, "type", NATIVE_LOOT_ENTRIES, "entry", unresolved);
                if (type.isEmpty()) return;
                lootField(
                        object,
                        "conditions",
                        LootRole.CONDITION,
                        refs,
                        unresolved,
                        references,
                        depth);
                if (Set.of("alternatives", "sequence", "group").contains(type))
                    lootField(
                            object,
                            "children",
                            LootRole.ENTRY,
                            refs,
                            unresolved,
                            references,
                            depth);
                else
                    lootField(
                            object,
                            "functions",
                            LootRole.FUNCTION,
                            refs,
                            unresolved,
                            references,
                            depth);
                if (type.equals("item")) lootResource(object, "name", refs);
                if (type.equals("tag")) lootReference(object, "name", "item_tag", unresolved);
                if (type.equals("loot_table")) {
                    String key = "value";
                    String table = primitiveString(object, key);
                    if (!table.isEmpty()) references.accept(new LootReference(object, key, false));
                    else
                        lootField(object, key, LootRole.TABLE, refs, unresolved, references, depth);
                }
            }
            case FUNCTION -> {
                String type =
                        lootCodec(
                                object, "function", NATIVE_LOOT_FUNCTIONS, "function", unresolved);
                if (type.isEmpty()) return;
                if (!type.equals("sequence"))
                    lootField(
                            object,
                            "conditions",
                            LootRole.CONDITION,
                            refs,
                            unresolved,
                            references,
                            depth);
                switch (type) {
                    case "sequence" ->
                            lootField(
                                    object,
                                    "functions",
                                    LootRole.FUNCTION,
                                    refs,
                                    unresolved,
                                    references,
                                    depth);
                    case "filtered", "modify_contents" ->
                            lootField(
                                    object,
                                    "modifier",
                                    LootRole.FUNCTION,
                                    refs,
                                    unresolved,
                                    references,
                                    depth);
                    case "set_contents" ->
                            lootField(
                                    object,
                                    "entries",
                                    LootRole.ENTRY,
                                    refs,
                                    unresolved,
                                    references,
                                    depth);
                    case "set_loot_table" -> {
                        if (!primitiveString(object, "name").isEmpty())
                            references.accept(new LootReference(object, "name", true));
                        lootResource(object, "type", refs);
                    }
                    case "set_item" -> lootResource(object, "item", refs);
                    case "copy_state" -> lootResource(object, "block", refs);
                    case "reference" -> lootReference(object, "name", "modifier", unresolved);
                }
            }
            case CONDITION -> {
                String type =
                        lootCodec(
                                object,
                                "condition",
                                NATIVE_LOOT_CONDITIONS,
                                "condition",
                                unresolved);
                if (type.isEmpty()) return;
                switch (type) {
                    case "inverted" ->
                            lootField(
                                    object,
                                    "term",
                                    LootRole.CONDITION,
                                    refs,
                                    unresolved,
                                    references,
                                    depth);
                    case "any_of", "all_of" ->
                            lootField(
                                    object,
                                    "terms",
                                    LootRole.CONDITION,
                                    refs,
                                    unresolved,
                                    references,
                                    depth);
                    case "reference" -> lootReference(object, "name", "predicate", unresolved);
                }
            }
        }
    }

    private static String primitiveString(com.google.gson.JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    private static void equipment(com.google.gson.JsonObject object, Set<ResourceLocation> refs) {
        if (object.has("crew"))
            for (var rule : object.getAsJsonArray("crew"))
                for (var choice : rule.getAsJsonObject().getAsJsonArray("pool")) {
                    var value = choice.getAsJsonObject();
                    if (!value.has("equipment")) continue;
                    for (var slot : value.getAsJsonObject("equipment").entrySet())
                        for (var item : slot.getValue().getAsJsonArray())
                            if (item.getAsJsonObject().has("nbt"))
                                try {
                                    stack(
                                            com.eynny.npships.authoring.document.SnbtText
                                                    .nativeCompound(
                                                            item.getAsJsonObject()
                                                                    .get("nbt")
                                                                    .getAsString()),
                                            refs,
                                            0);
                                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                                    throw new IllegalArgumentException(
                                            "Invalid authored equipment", e);
                                }
                }
    }

    private static void patchConfig(
            com.google.gson.JsonObject config,
            String key,
            boolean entity,
            Set<String> required,
            Set<String> unresolved) {
        if (config.has(key))
            patchHeaders(config.getAsJsonObject(key), entity, required, unresolved);
    }

    private static void patchHeaders(
            com.google.gson.JsonObject envelope,
            boolean entity,
            Set<String> required,
            Set<String> unresolved) {
        for (var entry : envelope.getAsJsonArray("entries"))
            provider(
                    PatchInstances.identity(entry.getAsJsonObject(), entity).type().toString(),
                    required,
                    unresolved);
    }

    private static void reference(CompoundTag value, String key, Set<ResourceLocation> refs) {
        if (!value.contains(key, Tag.TAG_STRING)) return;
        var id = ResourceLocation.tryParse(value.getString(key));
        if (id != null) refs.add(id);
    }

    /** Only native root inventory fields grant stack positions; private child NBT stays opaque. */
    private static void nativeRoot(
            CompoundTag value, boolean entity, Set<ResourceLocation> refs, int depth) {
        if (depth > 64)
            throw new IllegalArgumentException("Dependency inventory nesting exceeds 64 levels.");
        reference(value, "id", refs);
        for (String key : List.of("Items", "HandItems", "ArmorItems", "Inventory"))
            for (var item : value.getList(key, Tag.TAG_COMPOUND))
                stack((CompoundTag) item, refs, depth + 1);
        if (!entity) return;
        if (value.getString("id").equals("minecraft:item"))
            stack(value.getCompound("Item"), refs, depth + 1);
        for (var passenger : value.getList("Passengers", Tag.TAG_COMPOUND))
            nativeRoot((CompoundTag) passenger, true, refs, depth + 1);
    }

    private static void stack(CompoundTag value, Set<ResourceLocation> refs, int depth) {
        if (depth > 64)
            throw new IllegalArgumentException("Dependency inventory nesting exceeds 64 levels.");
        reference(value, "id", refs);
        var components = value.getCompound("components");
        for (var entry : components.getList("minecraft:container", Tag.TAG_COMPOUND))
            stack(((CompoundTag) entry).getCompound("item"), refs, depth + 1);
        for (String key : List.of("minecraft:bundle_contents", "minecraft:charged_projectiles"))
            for (var item : components.getList(key, Tag.TAG_COMPOUND))
                stack((CompoundTag) item, refs, depth + 1);
    }

    private static void block(
            CompoundTag value,
            Set<ResourceLocation> refs,
            Set<String> required,
            Set<String> unresolved) {
        if (value.contains(SchematicPatches.EMBEDDED, Tag.TAG_BYTE_ARRAY))
            patchHeaders(
                    JsonStructure.parse(
                                    new String(
                                            value.getByteArray(SchematicPatches.EMBEDDED),
                                            StandardCharsets.UTF_8))
                            .getAsJsonObject(),
                    false,
                    required,
                    unresolved);
    }

    private static void nativeDocument(
            CompoundTag value,
            Set<ResourceLocation> refs,
            Set<String> required,
            Set<String> unresolved) {
        for (var state : value.getList("palette", Tag.TAG_COMPOUND))
            reference((CompoundTag) state, "Name", refs);
        for (var palette : value.getList("palettes", Tag.TAG_LIST))
            for (var state : (ListTag) palette) reference((CompoundTag) state, "Name", refs);
        documentRoots(value, (root, entity) -> nativeRoot(root, entity, refs, 0));
        for (var entry : value.getList("blocks", Tag.TAG_COMPOUND))
            block((CompoundTag) entry, refs, required, unresolved);
        if (value.contains(SchematicExtensions.KEY, Tag.TAG_COMPOUND))
            for (var data :
                    value.getCompound(SchematicExtensions.KEY).getList("entries", Tag.TAG_COMPOUND))
                provider(((CompoundTag) data).getString("type"), required, unresolved);
        if (value.contains(EntityPatches.EMBEDDED, Tag.TAG_BYTE_ARRAY))
            patchConfig(
                    JsonStructure.parse(
                                    new String(
                                            value.getByteArray(EntityPatches.EMBEDDED),
                                            StandardCharsets.UTF_8))
                            .getAsJsonObject(),
                    EntityPatches.KEY,
                    true,
                    required,
                    unresolved);
    }

    private static void savedConfig(
            CompoundTag value,
            String key,
            Set<ResourceLocation> refs,
            Set<String> required,
            Set<String> unresolved) {
        if (value.contains(key, Tag.TAG_STRING) && !value.getString(key).isBlank())
            config(JsonStructure.parse(value.getString(key)), refs, required, unresolved);
    }

    private static void documentRoots(
            CompoundTag value, java.util.function.BiConsumer<CompoundTag, Boolean> action) {
        for (var entry : value.getList("blocks", Tag.TAG_COMPOUND))
            action.accept(((CompoundTag) entry).getCompound("nbt"), false);
        for (var entry : value.getList("entities", Tag.TAG_COMPOUND))
            action.accept(((CompoundTag) entry).getCompound("nbt"), true);
    }

    /** Only actual native roots in formal document frames own LootTable fields. */
    static void nativeRoots(Tag node, java.util.function.Consumer<CompoundTag> action) {
        frames(
                node,
                InspectionScope.DOCUMENT,
                (value, scope) -> {
                    java.util.function.BiConsumer<CompoundTag, Boolean> visit =
                            (root, entity) -> nativeRootTree(root, entity, action, 0);
                    if (scope == InspectionScope.DOCUMENT
                            || scope == InspectionScope.PACKED
                                    && !value.contains("metadata", Tag.TAG_COMPOUND))
                        documentRoots(value, visit);
                    if (scope == InspectionScope.PACKED
                            && value.contains("metadata", Tag.TAG_COMPOUND))
                        for (var extra : value.getList("extras", Tag.TAG_COMPOUND))
                            visit.accept(
                                    ((CompoundTag) extra).getCompound("data").getCompound("nbt"),
                                    false);
                });
    }

    private static void nativeRootTree(
            CompoundTag value,
            boolean entity,
            java.util.function.Consumer<CompoundTag> action,
            int depth) {
        if (depth > 64)
            throw new IllegalArgumentException("Native entity nesting exceeds 64 levels.");
        action.accept(value);
        if (entity)
            for (var passenger : value.getList("Passengers", Tag.TAG_COMPOUND))
                nativeRootTree((CompoundTag) passenger, true, action, depth + 1);
    }

    /** Opaque/native children cannot create new host document frames. */
    private static void frames(
            Tag node,
            InspectionScope scope,
            java.util.function.BiConsumer<CompoundTag, InspectionScope> action) {
        frames(node, scope, action, 0);
    }

    private static void frames(
            Tag node,
            InspectionScope scope,
            java.util.function.BiConsumer<CompoundTag, InspectionScope> action,
            int depth) {
        if (scope == InspectionScope.OPAQUE || scope == InspectionScope.NATIVE) return;
        if (depth > 64)
            throw new IllegalArgumentException("Native document-frame nesting exceeds 64 levels.");
        if (node instanceof CompoundTag value) {
            action.accept(value, scope);
            for (String key : value.getAllKeys())
                frames(value.get(key), scope.child(value, key), action, depth + 1);
        } else if (node instanceof ListTag list)
            for (var value : list) frames(value, scope.element(), action, depth + 1);
    }

    private static void collect(
            Tag node,
            InspectionScope scope,
            Set<ResourceLocation> refs,
            Set<String> required,
            Set<String> unresolved) {
        frames(
                node,
                scope,
                (value, position) -> {
                    if (position == InspectionScope.DOCUMENT
                            || position == InspectionScope.PACKED
                                    && !value.contains("metadata", Tag.TAG_COMPOUND))
                        nativeDocument(value, refs, required, unresolved);
                    if (position == InspectionScope.PACKED
                            && value.contains("metadata", Tag.TAG_COMPOUND))
                        for (var extra : value.getList("extras", Tag.TAG_COMPOUND)) {
                            var data = ((CompoundTag) extra).getCompound("data");
                            nativeRoot(data.getCompound("nbt"), false, refs, 0);
                            block(data, refs, required, unresolved);
                        }
                    if (position == InspectionScope.AREAS)
                        savedConfig(value, "base_config", refs, required, unresolved);
                    if (position == InspectionScope.LAYER)
                        for (String key : List.of("config", "source_config"))
                            savedConfig(value, key, refs, required, unresolved);
                });
    }

    private AddonDependencies() {}
}
