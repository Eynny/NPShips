package com.eynny.npships.authoring.document;

import net.minecraft.nbt.*;

import java.util.*;

/**
 * Completion from serialized data and explicitly supplied value domains, never guessed NBT fields.
 */
public final class DataSuggestions {
    public record Entry(String insert, String detail, Integer color) {
        public Entry(String insert, String detail) {
            this(insert, detail, null);
        }
    }

    public record Result(int start, int end, List<Entry> entries) {}

    public record Help(
            String path,
            String key,
            Tag value,
            List<String> allowed,
            String owner,
            NativeField nativeField,
            int start,
            int end) {}

    public record NativeField(String label, int min, int max, Map<Integer, String> options) {}

    /** Detached JSON reference information. Empty samples never guess a foreign codec's shape. */
    public interface JsonHints {
        Map<String, String> fields(String path, CompoundTag current);

        List<String> values(String path, String key, CompoundTag current);

        String example();

        String descriptionKey(String path);

        default Map<String, String> fields(
                String path, CompoundTag current, Map<String, CompoundTag> parents) {
            return fields(path, current);
        }

        default List<String> values(
                String path, String key, CompoundTag current, Map<String, CompoundTag> parents) {
            return values(path, key, current);
        }

        default Map<String, String> fields(
                String path,
                CompoundTag current,
                Map<String, CompoundTag> parents,
                Set<String> arrayParents) {
            return fields(path, current, parents);
        }

        default List<String> values(
                String path,
                String key,
                CompoundTag current,
                Map<String, CompoundTag> parents,
                Set<String> arrayParents) {
            return values(path, key, current, parents);
        }
    }

    private JsonHints jsonHints;

    public DataSuggestions jsonFormat(JsonHints hints) {
        jsonHints = Objects.requireNonNull(hints);
        return this;
    }

    public boolean jsonMode() {
        return jsonHints != null;
    }

    public String exampleText(String text) {
        return jsonHints == null
                ? new SnbtPrinterTagVisitor().visit(example(text))
                : jsonHints.example();
    }

    public String descriptionKey(String path) {
        return jsonHints == null ? "" : jsonHints.descriptionKey(path);
    }

    private String literal(Tag value) {
        if (value == null) return "";
        return jsonHints == null
                ? value.toString()
                : NbtOps.INSTANCE
                        .convertTo(com.mojang.serialization.JsonOps.INSTANCE, value)
                        .toString();
    }

    private Map<String, String> fields(Frame frame, String text) {
        return jsonHints == null
                ? fieldHints.getOrDefault(frame.path(), Map.of())
                : jsonHints.fields(
                        frame.path(),
                        objectFields(text, frame.start()),
                        parentFields(frame, text),
                        arrayParents(frame));
    }

    private List<String> values(Frame frame, String text) {
        return jsonHints == null
                ? domains.getOrDefault(frame.childPath(), List.of())
                : jsonHints.values(
                        frame.path(),
                        frame.key(),
                        objectFields(text, frame.start()),
                        parentFields(frame, text),
                        arrayParents(frame));
    }

    // Keep actual array ancestry separate from display paths: a literal key can contain "[]".
    private static Set<String> arrayParents(Frame frame) {
        var result = new HashSet<String>();
        int depth = 0;
        for (Frame at = frame; at != null && depth++ < 64; at = at.parent())
            if (at.list()) result.add(at.path());
        return Set.copyOf(result);
    }

    private static Map<String, CompoundTag> parentFields(Frame frame, String text) {
        var result = new LinkedHashMap<String, CompoundTag>();
        for (Frame parent = frame.parent(); parent != null; parent = parent.parent()) {
            if (!parent.list() && parent.parent() != null)
                result.putIfAbsent(parent.path(), objectFields(text, parent.start()));
            if (result.size() >= 32) break;
        }
        return Map.copyOf(result);
    }

    // Read preceding complete siblings even in an unfinished draft. This is hint evidence only;
    // accepted JSON still goes through JsonStructure and the native loot codec.
    private static CompoundTag objectFields(String text, int start) {
        var result = new CompoundTag();
        var reader = new com.mojang.brigadier.StringReader(text);
        reader.setCursor(Math.clamp(start + 1, 0, text.length()));
        try {
            for (int count = 0; count < 256 && reader.canRead(); count++) {
                reader.skipWhitespace();
                if (!reader.canRead() || reader.peek() == '}') break;
                String key = reader.readString();
                reader.skipWhitespace();
                reader.expect(':');
                reader.skipWhitespace();
                result.put(key, SnbtText.nativeValue(reader));
                reader.skipWhitespace();
                if (!reader.canRead() || reader.peek() != ',') break;
                reader.skip();
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException incompleteDraft) {
            // No inferred fields beyond the first incomplete value.
        }
        return result;
    }

    private record Context(Frame frame, int start, int end, String typed) {}

    private Map<String, NativeField> nativeFields = Map.of();
    private Map<String, List<String>> declaredDomains = Map.of();
    private boolean entityDocument;
    private boolean itemDocument;
    private final Set<String> itemPaths = new LinkedHashSet<>();

    private java.util.function.BiFunction<CompoundTag, CompoundTag, CompoundTag> exampleProjection =
            (example, stored) -> example;

    /** Presentation only; the caller supplies a detached projection and retains full source. */
    public DataSuggestions withExampleProjection(
            java.util.function.BiFunction<CompoundTag, CompoundTag, CompoundTag> projection) {
        exampleProjection = java.util.Objects.requireNonNull(projection);
        return this;
    }

    /** A selected native ItemStack keeps its required id in reference examples. */
    public DataSuggestions itemDocument() {
        itemDocument = true;
        return this;
    }

    private Map<String, Map<String, String>> fieldHints = Map.of();

    /**
     * Registry-owned names with native encoded examples, or no invented value for unknown shapes.
     */
    public DataSuggestions withFieldHints(Map<String, Map<String, String>> hints) {
        var detached = new LinkedHashMap<String, Map<String, String>>();
        hints.forEach((path, fields) -> detached.put(path, Map.copyOf(fields)));
        fieldHints = Map.copyOf(detached);
        return this;
    }

    /**
     * Explicit registry/native scalar domains; generic completion hints cannot bypass state guards.
     */
    public DataSuggestions withDeclaredValues(Map<String, List<String>> values) {
        var merged = new LinkedHashMap<String, List<String>>(declaredDomains);
        values.forEach((key, choices) -> merged.put(key, List.copyOf(choices)));
        declaredDomains = Map.copyOf(merged);
        return this;
    }

    /** Entity editor accepts a complete root; its serializable native type is required. */
    public DataSuggestions entityDocument() {
        entityDocument = true;
        return this;
    }

    public DataSuggestions withNativeFields(Map<String, NativeField> fields) {
        var merged = new LinkedHashMap<>(nativeFields);
        merged.putAll(fields);
        nativeFields = Map.copyOf(merged);
        return this;
    }

    public SnbtPropertyValues.Result nativeValues(String text, int offset) {
        if (jsonHints != null) {
            var selected = inspect(text, offset);
            if (selected == null || selected.allowed().isEmpty()) return null;
            return SnbtPropertyValues.declaredValues(
                    text, offset, Map.of(selected.path(), selected.allowed()));
        }
        var nativeResult = SnbtPropertyValues.nativeValues(text, offset, nativeFields);
        return nativeResult != null
                ? nativeResult
                : SnbtPropertyValues.declaredValues(text, offset, declaredDomains);
    }

    private record Frame(
            Tag tag,
            String path,
            boolean list,
            String key,
            boolean value,
            int start,
            Frame parent) {
        Frame field(String name) {
            return new Frame(tag, path, list, name, true, start, parent);
        }

        Frame next() {
            return new Frame(tag, path, list, "", list, start, parent);
        }

        Tag child() {
            if (tag instanceof CompoundTag c) return c.get(key);
            if (tag instanceof ListTag l && !l.isEmpty()) return l.getFirst();
            return null;
        }

        String childPath() {
            return path + (list ? "[]" : (path.isEmpty() ? "" : ".") + key);
        }
    }

    private final CompoundTag schema;
    private final CompoundTag typeSchema;
    private final CompoundTag stored;
    private Map<String, List<String>> domains;
    private final com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject;
    private final String nativeRoot;
    private final Set<List<String>> connectionItemPaths = new HashSet<>();

    private final Map<String, String> connectionLabels = new HashMap<>();

    public String connectionLabel(String path) {
        return connectionLabels.getOrDefault(path, "");
    }

    public DataSuggestions withNativeSchema(NativeSerializationFields.Schema fields) {
        connectionItemPaths.addAll(fields.itemPaths());
        for (var reference : List.of(schema, typeSchema)) {
            var root = nativeRoot.isEmpty() ? reference : reference.getCompound(nativeRoot);
            fields.expand(root);
            if (!nativeRoot.isEmpty()) BlockSettings.IDENTITY.forEach(root::remove);
        }
        return this;
    }

    /**
     * Resolve embedded native records by their registered type, never by their owner's field name.
     */
    public DataSuggestions withNativeValues(net.minecraft.world.level.Level level) {
        var domains = new LinkedHashMap<>(declaredDomains);
        enrichNative(
                nativeRoot.isEmpty() ? schema : schema.getCompound(nativeRoot),
                nativeRoot,
                level,
                domains,
                0,
                new HashSet<>());
        enrichNative(
                nativeRoot.isEmpty() ? typeSchema : typeSchema.getCompound(nativeRoot),
                nativeRoot,
                level,
                new LinkedHashMap<>(),
                0,
                new HashSet<>());
        withDeclaredValues(domains);
        var all = new LinkedHashMap<>(this.domains);
        all.putAll(declaredDomains);
        this.domains = Map.copyOf(all);
        return this;
    }

    private void enrichNative(
            Tag value,
            String path,
            net.minecraft.world.level.Level level,
            Map<String, List<String>> choices,
            int depth,
            Set<String> ancestors) {
        if (depth > 16) return;
        if (value instanceof CompoundTag object) {
            if (object.contains("Name", Tag.TAG_STRING)) {
                var id =
                        net.minecraft.resources.ResourceLocation.tryParse(object.getString("Name"));
                if (id != null
                        && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)) {
                    var state =
                            NbtUtils.readBlockState(
                                    level.holderLookup(
                                            net.minecraft.core.registries.Registries.BLOCK),
                                    object);
                    var props = object.getCompound("Properties");
                    for (var property : state.getProperties()) {
                        if (!props.contains(property.getName()))
                            props.putString(property.getName(), propertyValue(state, property));
                        var allowed = new ArrayList<String>();
                        for (var option : property.getPossibleValues())
                            allowed.add(
                                    StringTag.valueOf(propertyName(property, option)).toString());
                        choices.put(
                                join(path, "Properties." + property.getName()),
                                List.copyOf(allowed));
                    }
                    if (!props.isEmpty()) object.put("Properties", props);
                }
            }
            String entityId = object.getString("id");
            var entityKey = net.minecraft.resources.ResourceLocation.tryParse(entityId);
            if (!entityDocument
                    && !itemDocument
                    && entityKey != null
                    && !object.contains("count")
                    && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(
                            entityKey)
                    && !ancestors.contains(entityId)
                    && ancestors.size() < 8) {
                var identity = new CompoundTag();
                identity.putString("id", entityId);
                var nativeDefaults = EntitySettings.defaults(identity, level);
                nativeDefaults.remove("UUID");
                nativeDefaults.remove("Pos");
                object.merge((CompoundTag) structuralMerge(nativeDefaults, object));
                ancestors = new HashSet<>(ancestors);
                ancestors.add(entityId);
            }
            for (String key : List.copyOf(object.getAllKeys())) {
                if (key.equals(SchematicExtensions.KEY)
                        || key.equals(com.eynny.npships.authoring.patches.EntityPatches.EMBEDDED))
                    continue;
                enrichNative(
                        object.get(key), join(path, key), level, choices, depth + 1, ancestors);
            }
        } else if (value instanceof ListTag list) {
            for (int i = 0; i < Math.min(64, list.size()); i++)
                enrichNative(list.get(i), path + "[]", level, choices, depth + 1, ancestors);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.state.properties.Property property) {
        return property.getName(state.getValue(property));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyName(
            net.minecraft.world.level.block.state.properties.Property property, Comparable value) {
        return property.getName(value);
    }

    /** Declared native channel paths enrich reference/help only; accepted SNBT is never changed. */
    public DataSuggestions withConnections(
            List<com.eynny.npships.api.patches.PatchConnectionFields.Group> groups) {
        for (var group : groups) {
            for (var field : group.fields())
                connectionLabels.put(
                        join(
                                nativeRoot,
                                String.join(".", group.path())
                                        + (group.repeated() ? "[]" : "")
                                        + "."
                                        + field.key()),
                        field.title().getString());
            for (var field : group.fields())
                if (field.kind()
                        == com.eynny.npships.api.patches.PatchConnectionFields.ValueKind.KEY) {
                    String path =
                            String.join(".", group.path())
                                    + (group.repeated() ? "[]" : "")
                                    + "."
                                    + field.key();
                    path = join(nativeRoot, path);
                    var keys = NativeSerializationFields.keyboardKeys();
                    if (!keys.isEmpty()) {
                        withNativeFields(
                                Map.of(
                                        path,
                                        new NativeField(field.title().getString(), 0, 0, keys)));
                        withDeclaredValues(
                                Map.of(
                                        path,
                                        keys.keySet().stream()
                                                .sorted()
                                                .map(Object::toString)
                                                .toList()));
                        var merged = new LinkedHashMap<>(domains);
                        merged.putAll(declaredDomains);
                        domains = Map.copyOf(merged);
                    }
                }
            for (var field : group.fields())
                if (field.kind()
                        == com.eynny.npships.api.patches.PatchConnectionFields.ValueKind.ITEM) {
                    var path = new ArrayList<>(group.path());
                    if (group.repeated()) path.add("*");
                    path.add(field.key());
                    connectionItemPaths.add(List.copyOf(path));
                }
            for (var reference : List.of(schema, typeSchema)) {
                var root = nativeRoot.isEmpty() ? reference : reference.getCompound(nativeRoot);
                CompoundTag compound = root;
                for (int i = 0; i < group.path().size(); i++) {
                    String key = group.path().get(i);
                    if (group.repeated() && i == group.path().size() - 1) {
                        if (!compound.contains(key)) compound.put(key, new ListTag());
                        var list = compound.getList(key, Tag.TAG_COMPOUND);
                        if (list.isEmpty()) list.add(new CompoundTag());
                        compound = list.getCompound(0);
                    } else {
                        if (!compound.contains(key)) compound.put(key, new CompoundTag());
                        compound = compound.getCompound(key);
                    }
                }
                for (var field : group.fields())
                    if (!compound.contains(field.key()))
                        compound.put(
                                field.key(),
                                switch (field.kind()) {
                                    case ITEM -> new CompoundTag();
                                    case KEY -> IntTag.valueOf(65);
                                    case RELATIVE_POSITION ->
                                            NbtUtils.writeBlockPos(
                                                    net.minecraft.core.BlockPos.ZERO);
                                });
                for (var field : group.fields())
                    if (field.kind()
                                    == com.eynny.npships.api.patches.PatchConnectionFields.ValueKind
                                            .ITEM
                            && compound.get(field.key()) instanceof CompoundTag item) {
                        if (!item.contains("id")) item.putString("id", "minecraft:stone");
                        if (!item.contains("count")) item.putInt("count", 1);
                    }
            }
        }
        return this;
    }

    private boolean connectionItem(List<String> path) {
        for (var declared : connectionItemPaths) {
            if (declared.size() != path.size()) continue;
            boolean match = true;
            for (int i = 0; i < path.size() && match; i++)
                match =
                        (declared.get(i).equals("*") || declared.get(i).equals("[]"))
                                ? path.get(i).matches("[0-9]+")
                                : declared.get(i).equals(path.get(i));
            if (match) return true;
        }
        return false;
    }

    public DataSuggestions(
            CompoundTag defaults, CompoundTag stored, Map<String, List<String>> domains) {
        this(
                defaults,
                stored,
                domains,
                com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.unknown(),
                "");
    }

    public DataSuggestions(
            CompoundTag defaults,
            CompoundTag stored,
            Map<String, List<String>> domains,
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject,
            String nativeRoot) {
        this.subject = subject;
        this.nativeRoot = nativeRoot;
        this.stored = stored.copy();
        typeSchema = defaults.copy();
        seedObservedLists(typeSchema, this.stored, 0);
        BlockSettingTemplates.reference(
                nativeRoot.isEmpty() ? typeSchema : typeSchema.getCompound(nativeRoot), subject);
        schema = (CompoundTag) structuralMerge(typeSchema, stored);
        BlockSettingTemplates.reference(
                nativeRoot.isEmpty() ? schema : schema.getCompound(nativeRoot), subject);
        this.domains = Map.copyOf(domains);
    }

    /**
     * Native item positions only; opaque provider payloads and custom components keep their schema.
     */
    private net.minecraft.core.HolderLookup.Provider itemRegistries;

    private ItemSettings.Hints itemHintSnapshot;

    public DataSuggestions withItems(net.minecraft.core.HolderLookup.Provider registries) {
        return withItems(registries, null);
    }

    /** The live text binding may lend only its registry snapshot to a fresh document schema. */
    public DataSuggestions withItems(
            net.minecraft.core.HolderLookup.Provider registries, DataSuggestions previous) {
        var retained =
                previous != null && previous.itemRegistries == registries
                        ? previous.itemHintSnapshot
                        : null;
        itemRegistries = registries;
        itemHintSnapshot = retained != null ? retained : ItemSettings.hints(registries);
        var hints = itemHintSnapshot;
        var fields = new LinkedHashMap<String, Map<String, String>>(fieldHints);
        var values = new LinkedHashMap<String, List<String>>();
        var root = nativeRoot.isEmpty() ? schema : schema.getCompound(nativeRoot);
        itemHints(root, nativeRoot, List.of(), itemDocument, hints, fields, values, 0);
        fieldHints = Map.copyOf(fields);
        withDeclaredValues(values);
        var merged = new LinkedHashMap<>(domains);
        merged.putAll(declaredDomains);
        domains = Map.copyOf(merged);
        return this;
    }

    private void itemHints(
            Tag value,
            String path,
            List<String> keys,
            boolean item,
            ItemSettings.Hints hints,
            Map<String, Map<String, String>> fields,
            Map<String, List<String>> values,
            int depth) {
        if (depth > 32) return;
        if (value instanceof CompoundTag object) {
            if (!item
                    && object.contains("count", Tag.TAG_ANY_NUMERIC)
                    && object.contains("id", Tag.TAG_STRING)) {
                var itemId =
                        net.minecraft.resources.ResourceLocation.tryParse(object.getString("id"));
                item =
                        itemId != null
                                && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(
                                        itemId);
            }
            if (item) {
                itemPaths.add(path);
                var authoredComponents = object.getCompound("components");
                var components =
                        (CompoundTag) structuralMerge(hints.components(), object.get("components"));
                object.put("components", components);
                var rootFields = new LinkedHashMap<String, String>();
                rootFields.put("id", "\"minecraft:stone\"");
                rootFields.put("count", "1");
                rootFields.put("components", "{}");
                fields.put(path, Map.copyOf(rootFields));
                fields.put(join(path, "components"), hints.names());
                fields.put(
                        join(path, "components.minecraft:enchantments.levels"),
                        hints.enchantments());
                fields.put(
                        join(path, "components.minecraft:stored_enchantments.levels"),
                        hints.enchantments());
                hints.values().forEach((key, choices) -> values.put(join(path, key), choices));
                // Only the three native nested-stack components have a known item role here.
                for (var name :
                        List.of(
                                "minecraft:container",
                                "minecraft:bundle_contents",
                                "minecraft:charged_projectiles")) {
                    var contents = authoredComponents.get(name);
                    if (contents != null)
                        itemHints(
                                contents,
                                join(path, "components." + name),
                                List.of(),
                                !name.equals("minecraft:container"),
                                hints,
                                fields,
                                values,
                                depth + 1);
                }
                return;
            }
            for (var key : List.copyOf(object.getAllKeys())) {
                if (key.equals(SchematicExtensions.KEY)
                        || key.equals(com.eynny.npships.authoring.patches.EntityPatches.EMBEDDED))
                    continue;
                var at = new ArrayList<>(keys);
                at.add(key);
                itemHints(
                        object.get(key),
                        join(path, key),
                        at,
                        connectionItem(at) || BlockSettingTemplates.declaredItemField(subject, at),
                        hints,
                        fields,
                        values,
                        depth + 1);
            }
        } else if (value instanceof ListTag list) {
            // A shared path represents the union of native element shapes; never rewrites data.
            for (int i = 0; i < Math.min(64, list.size()); i++) {
                var at = new ArrayList<>(keys);
                at.add(Integer.toString(i));
                itemHints(list.get(i), path + "[]", at, item, hints, fields, values, depth + 1);
            }
        }
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    /**
     * A retained concrete element is evidence of shape even after the current draft clears a list.
     */
    private static void seedObservedLists(CompoundTag reference, CompoundTag observed, int depth) {
        if (depth > 32) return;
        for (String key : observed.getAllKeys()) {
            if (key.equals(SchematicExtensions.KEY)
                    || key.equals(com.eynny.npships.authoring.patches.EntityPatches.EMBEDDED))
                continue;
            Tag value = observed.get(key);
            if (value instanceof ListTag list
                    && !list.isEmpty()
                    && reference.get(key) instanceof ListTag known
                    && known.isEmpty()) {
                var sample = new ListTag();
                sample.add(list.getFirst().copy());
                reference.put(key, sample);
            } else if (value instanceof CompoundTag child
                    && reference.get(key) instanceof CompoundTag target) {
                seedObservedLists(target, child, depth + 1);
            }
        }
    }

    /** Reference only: keep coherent native defaults; never apply samples to the document. */
    public CompoundTag example(String text) {
        CompoundTag current = stored;
        try {
            current = com.eynny.npships.authoring.document.SnbtText.nativeCompound(text);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
        }
        var result = typeSchema.copy();
        overlayExample(result, current);
        // Identity is supplied by the selected type. Item ids below it remain essential.
        if (!entityDocument && !itemDocument) {
            result.remove("id");
            result.getCompound("data").remove("id");
        } else if (entityDocument) {
            result.remove("UUID");
            result.remove(com.eynny.npships.authoring.patches.EntityIdentity.KEY);
        }
        coherentExample(result);
        minimalItems(result, current, "");
        return exampleProjection.apply(result, stored);
    }

    private void minimalItems(Tag value, Tag authored, String path) {
        if (value instanceof CompoundTag object) {
            var original = authored instanceof CompoundTag c ? c : new CompoundTag();
            if (itemPaths.contains(path) && !original.contains("components"))
                object.put("components", new CompoundTag());
            for (String key : object.getAllKeys())
                minimalItems(object.get(key), original.get(key), join(path, key));
        } else if (value instanceof ListTag list) {
            var original = authored instanceof ListTag l ? l : new ListTag();
            for (int i = 0; i < list.size(); i++)
                minimalItems(
                        list.get(i), i < original.size() ? original.get(i) : null, path + "[]");
        }
    }

    private static void overlayExample(CompoundTag target, CompoundTag source) {
        for (String key : source.getAllKeys()) {
            Tag value = source.get(key);
            if (value instanceof ListTag list
                    && list.isEmpty()
                    && target.get(key) instanceof ListTag known
                    && !known.isEmpty()) continue;
            if (value instanceof CompoundTag c && target.get(key) instanceof CompoundTag nested)
                overlayExample(nested, c);
            else target.put(key, value.copy());
        }
    }

    private static void coherentExample(CompoundTag tag) {
        if (tag.contains("LootTable")) {
            if (tag.getString("LootTable").isEmpty()) {
                tag.remove("LootTable");
                tag.remove("LootTableSeed");
            } else tag.remove("Items");
        }
        if (tag.contains("CustomName", Tag.TAG_STRING) && tag.getString("CustomName").isEmpty())
            tag.putString("CustomName", "\"Example\"");
        for (String key : tag.getAllKeys()) {
            Tag value = tag.get(key);
            if (value instanceof CompoundTag child) coherentExample(child);
            else if (value instanceof ListTag list)
                for (Tag entry : list)
                    if (entry instanceof CompoundTag child) coherentExample(child);
        }
    }

    /** Known native item roles only; opaque provider fields never acquire item semantics. */
    public String itemPart(String path, String text) {
        String root = itemRoot(path);
        if (root == null) return "";
        String part =
                path.equals(root) ? "" : path.substring(root.isEmpty() ? 0 : root.length() + 1);
        return switch (part) {
            case "id" -> "item_id";
            case "count" -> "item_count";
            case "Slot" -> "item_slot";
            case "", "components" -> "item_components_optional";
            case "components.minecraft:custom_name" -> "item_name";
            case "components.minecraft:enchantments", "components.minecraft:stored_enchantments" ->
                    "item_enchantments";
            case "components.minecraft:trim" -> "item_trim";
            case "components.minecraft:lore" -> "item_lore";
            case "components.minecraft:attribute_modifiers" -> "item_attributes";
            case "components.minecraft:dyed_color" -> "item_dye";
            case "components.minecraft:custom_data" -> "item_custom_data";
            default -> "";
        };
    }

    private String itemRoot(String path) {
        String result = null;
        for (String root : itemPaths)
            if ((path.equals(root) || root.isEmpty() || path.startsWith(root + "."))
                    && (result == null || root.length() > result.length())) result = root;
        return result;
    }

    /** Detached native samples for discoverability, never automatic mutations. */
    public Map<String, String> itemExamples(String path) {
        String root = itemRoot(path);
        return root == null
                ? Map.of()
                : fieldHints.getOrDefault(join(root, "components"), Map.of());
    }

    public record Structure(List<Help> fields, boolean truncated) {}

    /** Native/stored fields are evidence of structure, not an exhaustive mod schema. */
    public Structure structure(Help selected, String text) {
        Tag root = schema.copy();
        try {
            root =
                    structuralMerge(
                            root,
                            com.eynny.npships.authoring.document.SnbtText.nativeCompound(text));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
            // Keep the known structure while the user is halfway through an edit.
        }
        String path = selected == null ? "" : selected.path();
        if (selected != null) {
            for (String part : path.split("\\.")) {
                boolean list = part.endsWith("[]");
                String key = list ? part.substring(0, part.length() - 2) : part;
                root = root instanceof CompoundTag c ? c.get(key) : null;
                if (list && root instanceof ListTag entries) root = listSample(entries);
            }
            root = structuralMerge(root, selected.value());
        }
        var fields = new ArrayList<Help>();
        boolean[] truncated = {false};
        walkStructure(
                root, path, selected == null ? "" : selected.key(), fields, 0, truncated, 512, 16);
        return new Structure(List.copyOf(fields), truncated[0]);
    }

    /** Compact hover describes current data; the separate reference retains the full schema. */
    public Structure hoverStructure(Help selected, String text) {
        if (selected == null) return structure(null, text);
        var fields = new ArrayList<Help>();
        var truncated = new boolean[] {false};
        walkStructure(
                selected.value(), selected.path(), selected.key(), fields, 0, truncated, 9, 2);
        return new Structure(List.copyOf(fields), truncated[0]);
    }

    private Tag structuralMerge(Tag known, Tag current) {
        if (current == null) return known;
        if (known instanceof CompoundTag defaults && current instanceof CompoundTag actual) {
            var merged = defaults.copy();
            for (String key : actual.getAllKeys())
                merged.put(key, structuralMerge(defaults.get(key), actual.get(key)).copy());
            return merged;
        }
        if (known instanceof ListTag defaults
                && current instanceof ListTag actual
                && !defaults.isEmpty()
                && !actual.isEmpty()
                && defaults.getElementType() == actual.getElementType()) {
            var merged = new ListTag();
            // This is evidence for the element shape, never data written back to the object.
            merged.add(structuralMerge(listSample(defaults), listSample(actual)));
            return merged;
        }
        if (known instanceof ListTag defaults
                && current instanceof ListTag actual
                && actual.isEmpty()) return defaults;
        return current;
    }

    private Tag listSample(ListTag list) {
        if (list.isEmpty()) return null;
        Tag first = list.getFirst().copy();
        if (first instanceof CompoundTag compound) {
            for (int i = 1; i < Math.min(64, list.size()); i++)
                if (list.get(i) instanceof CompoundTag other) compound.merge(other);
        }
        return first;
    }

    private void walkStructure(
            Tag tag,
            String path,
            String key,
            List<Help> out,
            int depth,
            boolean[] truncated,
            int limit,
            int maximumDepth) {
        if (tag == null) return;
        if (out.size() >= limit || depth > maximumDepth) {
            truncated[0] = true;
            return;
        }
        String owner = schema.getCompound("state").getString("Name");
        if (owner.isEmpty()) owner = schema.getString("id");
        if (owner.isEmpty()) owner = subject.id();
        out.add(
                new Help(
                        path,
                        key,
                        tag.copy(),
                        domains.getOrDefault(path, List.of()),
                        owner,
                        nativeFields.get(path),
                        0,
                        0));
        if (tag instanceof CompoundTag compound) {
            for (String child : new TreeSet<>(compound.getAllKeys()))
                walkStructure(
                        compound.get(child),
                        path + (path.isEmpty() ? "" : ".") + child,
                        child,
                        out,
                        depth + 1,
                        truncated,
                        limit,
                        maximumDepth);
        } else if (tag instanceof ListTag list) {
            if (list.size() > 64) truncated[0] = true;
            walkStructure(
                    listSample(list),
                    path + "[]",
                    "[]",
                    out,
                    depth + 1,
                    truncated,
                    limit,
                    maximumDepth);
        }
    }

    private Context context(String text, int cursor) {
        cursor = Math.clamp(cursor, 0, text.length());
        var stack = new ArrayDeque<Frame>();
        stack.push(new Frame(schema, "", false, "", true, 0, null));
        int token = cursor;
        String pending = "";
        for (int i = 0; i < cursor; ) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '{' || ch == '[') {
                var parent = stack.peek();
                Tag child = stack.size() == 1 ? schema : parent.child();
                String path = stack.size() == 1 ? "" : parent.childPath();
                stack.push(new Frame(child, path, ch == '[', "", ch == '[', i, parent));
                i++;
                token = i;
                continue;
            }
            if (ch == '}' || ch == ']') {
                if (stack.size() > 1) stack.pop();
                i++;
                token = i;
                continue;
            }
            if (ch == ':') {
                var f = stack.pop();
                stack.push(f.field(pending));
                i++;
                token = i;
                continue;
            }
            if (ch == ',') {
                stack.push(stack.pop().next());
                i++;
                token = i;
                continue;
            }
            token = i;
            boolean quoted = ch == '\"' || ch == '\'';
            if (quoted) {
                char quote = ch;
                i++;
                while (i < cursor) {
                    char c = text.charAt(i++);
                    if (c == '\\' && i < cursor) i++;
                    else if (c == quote) break;
                }
            } else while (i < cursor && ",:{}[] \t\n\r".indexOf(text.charAt(i)) < 0) i++;
            if (i == token) i++;
            String raw = text.substring(token, Math.min(i, cursor));
            pending = unquote(raw);
            if (i < cursor) token = i;
        }
        while (token < cursor && Character.isWhitespace(text.charAt(token))) token++;
        var frame = stack.peek();
        String prefix = text.substring(token, cursor);
        String typed = unquote(prefix);
        if (prefix.isEmpty() && frame.value()) {
            // A caret after ':' may precede whitespace and an existing scalar. Complete that
            // whole native token instead of inserting another value before it. Never consume
            // an existing compound/list or move the user's caret merely to inspect a domain.
            var reader = new com.mojang.brigadier.StringReader(text);
            reader.setCursor(cursor);
            reader.skipWhitespace();
            int start = reader.getCursor();
            if (reader.canRead() && "{[,:}]".indexOf(reader.peek()) < 0) {
                try {
                    reader.readString();
                    if (reader.getCursor() > start)
                        return new Context(frame, start, reader.getCursor(), "");
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException incompleteScalar) {
                    // Preserve completion while the author is still typing an unfinished string.
                }
            }
        }
        int end = cursor;
        if (!prefix.isEmpty() && (prefix.charAt(0) == '\"' || prefix.charAt(0) == '\'')) {
            char quote = prefix.charAt(0);
            if (prefix.length() < 2 || prefix.charAt(prefix.length() - 1) != quote) {
                int closing = -1;
                while (end < text.length()) {
                    char ch = text.charAt(end++);
                    if (ch == '\\' && end < text.length()) end++;
                    else if (ch == quote) {
                        closing = end;
                        break;
                    } else if (ch == '\n') {
                        end--;
                        break;
                    }
                }
                if (closing < 0) end = cursor;
            }
        } else while (end < text.length() && ",:{}[] \t\n\r".indexOf(text.charAt(end)) < 0) end++;
        return new Context(frame, token, end, typed);
    }

    public Result complete(String text, int cursor) {
        if (!SnbtText.allowedDepth(text)) return new Result(cursor, cursor, List.of());
        var context = context(text, cursor);
        var frame = context.frame();
        int token = context.start(), end = context.end();
        String typed = context.typed();
        var entries = new ArrayList<Entry>();
        if (!frame.value
                && (jsonHints != null
                        || frame.tag instanceof CompoundTag
                        || fieldHints.containsKey(frame.path()))) {
            var compound = frame.tag instanceof CompoundTag c ? c : new CompoundTag();
            int after = end;
            while (after < text.length() && Character.isWhitespace(text.charAt(after))) after++;
            boolean colon = after < text.length() && text.charAt(after) == ':';
            var extras = fields(frame, text);
            var keys =
                    new TreeSet<>(
                            jsonHints == null
                                    ? compound.getAllKeys()
                                    : objectFields(text, frame.start()).getAllKeys());
            keys.addAll(extras.keySet());
            var present = SnbtText.siblingKeys(text, frame.start(), token);
            for (String key : keys)
                if ((!key.startsWith("!") || typed.startsWith("!"))
                        && !present.contains(key)
                        && matches(key, typed)) {
                    Tag value = compound.get(key);
                    String sample = extras.containsKey(key) ? extras.get(key) : literal(value);
                    // Completion supplies structure, not arbitrary example data or extra entries.
                    if (sample.stripLeading().startsWith("{")
                            || jsonHints == null && value instanceof CompoundTag) sample = "{}";
                    else if (jsonHints == null
                            && (value instanceof ByteArrayTag
                                    || sample.stripLeading().startsWith("[B;"))) sample = "[B;]";
                    else if (jsonHints == null
                            && (value instanceof IntArrayTag
                                    || sample.stripLeading().startsWith("[I;"))) sample = "[I;]";
                    else if (jsonHints == null
                            && (value instanceof LongArrayTag
                                    || sample.stripLeading().startsWith("[L;"))) sample = "[L;]";
                    else if (sample.stripLeading().startsWith("[")
                            || jsonHints == null && value instanceof ListTag) sample = "[]";
                    entries.add(
                            new Entry(
                                    (jsonHints == null
                                                    ? StringTag.valueOf(key).toString()
                                                    : new com.google.gson.JsonPrimitive(key)
                                                            .toString())
                                            + (colon ? "" : ": " + sample),
                                    frame.path
                                            + (frame.path.isEmpty() ? "" : ".")
                                            + key
                                            + " | "
                                            + (value == null
                                                    ? "native field"
                                                    : value.getType().getPrettyName())
                                            + " | "
                                            + (value == null ? "" : shortValue(value))));
                }
        } else {
            Tag value = frame.child();
            var values = new LinkedHashSet<String>(values(frame, text));
            if (value != null
                    && values.isEmpty()
                    && jsonHints == null
                    && !domains.containsKey(frame.childPath())) {
                values.add(
                        value instanceof CompoundTag
                                ? "{}"
                                : value instanceof ListTag ? "[]" : value.toString());
                if (value instanceof ByteTag
                        && (((ByteTag) value).getAsByte() == 0
                                || ((ByteTag) value).getAsByte() == 1)) {
                    values.add("0b");
                    values.add("1b");
                }
            }
            var nativeField = nativeFields.get(frame.childPath());
            var descriptions = new HashMap<String, String>();
            var colors = new HashMap<String, Integer>();
            if (nativeField != null)
                nativeField.options().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(
                                entry -> {
                                    String literal =
                                            value instanceof NumericTag
                                                    ? BlockSettings.scalar(
                                                                    value,
                                                                    Integer.toString(
                                                                            entry.getKey()))
                                                            .toString()
                                                    : Integer.toString(entry.getKey());
                                    values.add(literal);
                                    descriptions.put(literal, entry.getValue());
                                    if (nativeField.label().equals("DyeColor"))
                                        colors.put(
                                                literal,
                                                net.minecraft.world.item.DyeColor.byId(
                                                                entry.getKey())
                                                        .getTextColor());
                                });
            for (String v : values)
                if (matches(unquote(v), typed))
                    entries.add(
                            new Entry(
                                    v,
                                    frame.childPath()
                                            + " | "
                                            + (value == null
                                                    ? "value"
                                                    : value.getType().getPrettyName())
                                            + " | "
                                            + v
                                            + (nativeField == null
                                                    ? ""
                                                    : " | "
                                                            + nativeField.label()
                                                            + " | "
                                                            + nativeField.min()
                                                            + " .. "
                                                            + nativeField.max()
                                                            + " | "
                                                            + descriptions.getOrDefault(v, "")),
                                    colors.get(v)));
        }
        return new Result(token, end, List.copyOf(entries));
    }

    static boolean matches(String candidate, String query) {
        candidate = candidate.toLowerCase(Locale.ROOT);
        query = query.toLowerCase(Locale.ROOT);
        if (candidate.startsWith(query)) return true;
        if (candidate.startsWith("!") != query.startsWith("!")) return false;
        if (candidate.startsWith("!")) {
            candidate = candidate.substring(1);
            query = query.substring(1);
        }
        return !query.contains(":")
                && candidate.substring(candidate.indexOf(':') + 1).contains(query);
    }

    /** Locate a complete lexical token without treating quoted punctuation as syntax. */
    public Help inspect(String text, int offset) {
        if (offset < 0 || offset >= text.length() || !SnbtText.allowedDepth(text)) return null;
        for (int i = 0; i < text.length(); ) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || ",:{}[]".indexOf(ch) >= 0) {
                i++;
                continue;
            }
            int start = i++;
            if (ch == '\"' || ch == '\'') {
                while (i < text.length()) {
                    char c = text.charAt(i++);
                    if (c == '\\' && i < text.length()) i++;
                    else if (c == ch) break;
                }
            } else while (i < text.length() && ",:{}[] \t\n\r".indexOf(text.charAt(i)) < 0) i++;
            if (offset < start) return null;
            if (offset >= i) continue;
            var c = context(text, i);
            var frame = c.frame();
            if (!frame.value()) frame = frame.field(unquote(text.substring(start, i)));
            Tag value = frame.child();
            if (!c.frame().value()) {
                int begin = i;
                while (begin < text.length() && Character.isWhitespace(text.charAt(begin))) begin++;
                if (begin < text.length() && text.charAt(begin) == ':') {
                    Tag typedValue = fieldValue(text, begin + 1);
                    if (typedValue != null) value = typedValue;
                }
            }
            if (c.frame().value()) {
                try {
                    value =
                            com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                            "{v:" + text.substring(start, i) + "}")
                                    .get("v");
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
                }
            }
            String owner = schema.getCompound("state").getString("Name");
            if (owner.isEmpty()) owner = schema.getString("id");
            if (owner.isEmpty()) owner = subject.id();
            return new Help(
                    frame.childPath(),
                    frame.key(),
                    value == null ? null : value.copy(),
                    values(frame, text),
                    owner,
                    nativeFields.get(frame.childPath()),
                    start,
                    i);
        }
        return null;
    }

    private static Tag fieldValue(String text, int start) {
        int depth = 0, end = start;
        char quote = 0;
        for (; end < text.length(); end++) {
            char c = text.charAt(end);
            if (quote != 0) {
                if (c == '\\') end++;
                else if (c == quote) quote = 0;
            } else if (c == '\"' || c == '\'') quote = c;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                if (depth == 0) break;
                depth--;
            } else if (c == ',' && depth == 0) break;
        }
        if (quote != 0 || depth != 0) return null;
        try {
            return com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                            "{v:" + text.substring(start, end).trim() + "}")
                    .get("v");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalidValue) {
            return null;
        }
    }

    public static String numericLiteral(Tag type, int value) {
        try {
            return BlockSettings.scalar(type, Integer.toString(value)).toString();
        } catch (IllegalArgumentException changedType) {
            return Integer.toString(value);
        }
    }

    private static String shortValue(Tag tag) {
        String s = tag.toString();
        return s.length() > 180 ? s.substring(0, 180) + "..." : s;
    }

    private static String unquote(String s) {
        if (s.startsWith("\"") || s.startsWith("'")) {
            char q = s.charAt(0);
            s = s.substring(1);
            if (!s.isEmpty() && s.charAt(s.length() - 1) == q) s = s.substring(0, s.length() - 1);
            s = s.replace("\\" + q, "" + q).replace("\\\\", "\\");
        }
        return s;
    }
}
