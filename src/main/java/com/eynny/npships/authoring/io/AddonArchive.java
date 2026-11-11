package com.eynny.npships.authoring.io;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.SchematicSafety;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.*;

import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Current data-only addon mod JARs; never executes addon code or extracts archive paths. */
public final class AddonArchive {
    public static final String FORMAT = "npships-addon-mod";
    public static final int MAX_JAR = 64 * 1024 * 1024;
    static final int MAX_ENTRIES = 4096;
    static final int MAX_ENTRY = 32 * 1024 * 1024, MAX_TOTAL = 256 * 1024 * 1024;
    private static final int MAX_JSON = 16 * 1024 * 1024;
    private static final Gson JSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public record Metadata(
            String id,
            String name,
            String version,
            String author,
            String description,
            AddonLicense license) {
        /** Existing code callers never implicitly grant a license to authored content. */
        public Metadata(String id, String name, String version, String author, String description) {
            this(id, name, version, author, description, null);
        }

        public Metadata {
            if (id == null
                    || !id.matches("[a-z][a-z0-9_]{1,63}")
                    || Set.of("minecraft", "neoforge", "npships").contains(id))
                throw new IllegalArgumentException(
                        "Use a unique pack mod ID: 2-64 lowercase letters, digits or underscores,"
                                + " starting with a letter.");
            if (version == null || !version.matches("[0-9A-Za-z][0-9A-Za-z._+\\-]{0,63}"))
                throw new IllegalArgumentException(
                        "Pack version: 1-64 letters, digits, dots, underscores, plus or minus"
                                + " signs.");
            for (String s : List.of(name, version, author, description))
                if (s.isBlank() || s.length() > 256 || s.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException(
                            "Fill all addon metadata fields (up to 256 characters).");
        }
    }

    public record Contents(
            Map<String, byte[]> entries,
            List<String> ships,
            String source,
            Metadata metadata,
            List<AddonDependency> dependencies) {
        public String owner() {
            return metadata.id();
        }
    }

    public static byte[] readIcon(Path path) throws IOException {
        if (Files.size(path) > 4 * 1024 * 1024) throw new IOException("PNG must be at most 4 MiB.");
        byte[] bytes = Files.readAllBytes(path);
        validateIcon(bytes);
        return bytes;
    }

    private static void validateIcon(byte[] bytes) throws IOException {
        if (bytes == null) return;
        // Check dimensions before decoding to avoid huge allocations from compressed images.
        if (bytes.length < 24
                || bytes.length > 4 * 1024 * 1024
                || java.nio.ByteBuffer.wrap(bytes).getLong() != 0x89504e470d0a1a0aL)
            throw new IOException("Expected PNG, at most 4 MiB.");
        var header = java.nio.ByteBuffer.wrap(bytes);
        int w = header.getInt(16), h = header.getInt(20);
        if (w < 1 || h < 1 || w > 2048 || h > 2048)
            throw new IOException("PNG dimensions: 1..2048 pixels.");
        if (javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)) == null)
            throw new IOException("Invalid PNG.");
    }

    public static byte[] export(
            Metadata meta,
            Map<String, SchematicDocument> selected,
            byte[] icon,
            List<AddonDependency> requirements)
            throws IOException {
        return export(
                meta, selected, icon, requirements, AddonDependencies.inspect(selected.values()));
    }

    private static byte[] export(
            Metadata meta,
            Map<String, SchematicDocument> selected,
            byte[] icon,
            List<AddonDependency> requirements,
            AddonDependencies.Inspection inspection)
            throws IOException {
        validateIcon(icon);
        var dependencies = AddonModMetadata.validate(meta.id(), requirements);
        for (var known : inspection.defaults())
            if (known.required()
                    && dependencies.stream()
                            .noneMatch(dep -> dep.required() && dep.modId().equals(known.modId())))
                throw new IOException("Required detected mod dependency missing: " + known.modId());
        if (meta.license() == null)
            throw new IOException("Choose the authored content license before exporting a mod.");
        var entries = exportEntries(meta, selected);
        if (icon != null) entries.put("pack.png", icon.clone());
        entries.put(
                AddonModMetadata.PATH, AddonModMetadata.write(meta, dependencies, icon != null));
        entries.put(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nFMLModType: MOD\n\n".getBytes(StandardCharsets.UTF_8));
        AddonZip.validateEntries(entries);
        validateContents(entries, "<generated addon mod>");
        return zip(entries);
    }

    public static byte[] export(Metadata meta, Map<String, SchematicDocument> selected)
            throws IOException {
        var inspection = AddonDependencies.inspect(selected.values());
        return export(meta, selected, null, inspection.defaults(), inspection);
    }

    private static TreeMap<String, byte[]> exportEntries(
            Metadata meta, Map<String, SchematicDocument> selected) throws IOException {
        if (selected.isEmpty()) throw new IOException("Select at least one schematic.");
        var entries = new TreeMap<String, byte[]>();
        var manifest = new JsonObject();
        manifest.addProperty("format", FORMAT);
        if (meta.license() != null) meta.license().appendTo(entries);
        var manifestShips = new JsonArray();
        for (var selectedShip : selected.entrySet()) {
            String name =
                    selectedShip
                            .getKey()
                            .replace('\\', '/')
                            .replaceFirst("(?i)\\.nbt$", "")
                            .toLowerCase(Locale.ROOT);
            if (ResourceLocation.tryBuild(meta.id(), name) == null
                    || Arrays.stream(name.split("/", -1))
                            .anyMatch(v -> v.isEmpty() || v.equals(".") || v.equals("..")))
                throw new IOException("Invalid resource name: " + name);
            String base = "data/npships/" + name;
            if (entries.containsKey(base + ".nbt"))
                throw new IOException("Names collide after lowercase conversion: " + name);
            var doc = selectedShip.getValue();
            try {
                validate(doc);
            } catch (RuntimeException e) {
                throw new IOException(name + ": " + e.getMessage(), e);
            }
            var config = doc.configCopy();
            var tag = doc.nbtCopy();
            var tables = EmbeddedLoot.read(config);
            var mapping = new HashMap<String, String>();
            var usedLoot = new HashSet<String>();
            collectLoot(config, usedLoot);
            collectLoot(tag, usedLoot);
            for (String id : usedLoot)
                if (id.startsWith("npships:local/") && !tables.has(id))
                    throw new IOException("Missing local loot table in " + name + ": " + id);
            for (String local : tables.keySet())
                mapping.put(
                        local,
                        meta.id()
                                + ":ships/"
                                + name
                                + "/"
                                + ResourceLocation.parse(local).getPath());
            for (var table : tables.entrySet()) {
                var value = table.getValue().deepCopy();
                rewriteTable(value, mapping);
                String id = mapping.get(table.getKey());
                var key = ResourceLocation.parse(id);
                String tablePath = "data/loot_table/" + key.getPath() + ".json";
                if (entries.putIfAbsent(tablePath, json(value)) != null)
                    throw new IOException(
                            "Loot resource names collide: "
                                    + tablePath
                                    + ". Rename a schematic or local table.");
            }
            config.remove(EmbeddedLoot.KEY);
            rewriteAssignments(config, mapping);
            rewriteNbt(tag, mapping);
            entries.put(base + ".json", json(config));
            entries.put(base + ".nbt", nbt(tag));
            manifestShips.add(name);
        }
        manifest.add("schematics", manifestShips);
        entries.put("npships-pack.json", json(manifest));
        return entries;
    }

    public static Contents read(byte[] archive) throws IOException {
        return read(archive, "<memory archive>");
    }

    public static Contents read(Path path) throws IOException {
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new IOException(
                    "Select an NPShips addon mod JAR. Datapack ZIP import is not supported.");
        return read(readBytes(path), path.toString());
    }

    private static Contents read(byte[] archive, String source) throws IOException {
        var entries = AddonZip.read(archive, source);
        try {
            return validateContents(entries, source);
        } catch (IOException | RuntimeException error) {
            throw AddonZip.failure(
                    source,
                    "npships-pack.json + META-INF/neoforge.mods.toml",
                    "addon mod admission",
                    error);
        }
    }

    private static Contents validateContents(Map<String, byte[]> entries, String source)
            throws IOException {
        var parsed = AddonModMetadata.read(entries);
        byte[] marker = required(entries, "npships-pack.json");
        if (marker.length > 128 * 1024) throw new IOException("Addon descriptor exceeds128KiB.");
        var relative =
                AddonModLayout.validate(entries.keySet(), object(marker), parsed.metadata().id());
        var ships = relative.stream().map(name -> "data/npships/" + name + ".nbt").toList();
        for (String ship : ships) {
            var doc = document(entries, ship, source);
            try {
                validate(doc);
            } catch (RuntimeException e) {
                throw AddonZip.failure(
                        source,
                        ship + " + " + ship.substring(0, ship.length() - 4) + ".json",
                        "schematic configuration/targets",
                        e);
            }
        }
        return new Contents(
                Collections.unmodifiableMap(entries),
                List.copyOf(ships),
                source,
                parsed.metadata(),
                parsed.dependencies());
    }

    private static ResourceLocation resource(
            String path, String directory, String extension, String owner, String source)
            throws IOException {
        String prefix = "data/" + directory + "/";
        if (!path.startsWith(prefix) || !path.endsWith(extension)) return null;
        String name = path.substring(prefix.length(), path.length() - extension.length());
        var id = ResourceLocation.tryBuild(owner, name);
        if (id == null
                || name.isEmpty()
                || Arrays.stream(name.split("/", -1))
                        .anyMatch(v -> v.isEmpty() || v.equals(".") || v.equals("..")))
            throw AddonZip.failure(
                    source,
                    path,
                    "resource identity",
                    new IOException("Invalid native resource path"));
        return id;
    }

    public static void validateLoot(
            Contents pack, net.minecraft.core.HolderLookup.Provider registries) throws IOException {
        for (var entry : pack.entries().entrySet())
            if (resource(entry.getKey(), "loot_table", ".json", pack.owner(), pack.source())
                    != null) {
                try {
                    net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC
                            .parse(
                                    registries.createSerializationContext(
                                            com.mojang.serialization.JsonOps.INSTANCE),
                                    object(entry.getValue()))
                            .getOrThrow(IllegalArgumentException::new);
                } catch (IOException | RuntimeException e) {
                    throw AddonZip.failure(pack.source(), entry.getKey(), "loot JSON/codec", e);
                }
            }
    }

    public static SchematicDocument importShip(Contents pack, String ship) throws IOException {
        if (!pack.ships().contains(ship))
            throw new IOException("Select a schematic from this archive.");
        var doc = document(pack.entries(), ship, pack.source());
        var config = doc.configCopy();
        var tag = doc.nbtCopy();
        var references = new HashMap<String, String>();
        var tables = EmbeddedLoot.read(config);
        var used = new HashSet<String>();
        collectLoot(config, used);
        collectLoot(tag, used);
        String ownedPrefix =
                pack.owner()
                        + ":ships/"
                        + ship.substring("data/npships/".length(), ship.length() - 4)
                        + "/";
        var localNames = new HashSet<String>(tables.keySet());
        var available = new TreeMap<String, String>();
        for (String path : pack.entries().keySet())
            if (resource(path, "loot_table", ".json", pack.owner(), pack.source()) != null)
                available.put(
                        pack.owner()
                                + ":"
                                + path.substring("data/loot_table/".length(), path.length() - 5),
                        path);
        var pending = new ArrayDeque<String>();
        var queued = new HashSet<String>();
        var materialized = new TreeSet<String>();
        for (String id : available.keySet())
            if (used.contains(id) || id.startsWith(ownedPrefix)) {
                pending.add(id);
                queued.add(id);
                materialized.add(id);
            }
        if (queued.size() > 128)
            throw AddonZip.failure(
                    pack.source(),
                    ship,
                    "working-import loot closure",
                    new IOException("Maximum 128 reachable local loot tables."));
        var definitions = new TreeMap<String, JsonObject>();
        // Follow only formal immediate/deferred references reachable from this selected document.
        // Foreign namespaces and unrelated tables remain external to the working import.
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (definitions.containsKey(id)) continue;
            String path = available.get(id);
            try {
                var definition = object(pack.entries().get(path));
                definitions.put(id, definition);
                AddonDependencies.lootReferences(
                        definition,
                        reference -> {
                            String target = reference.id();
                            if (available.containsKey(target)) {
                                if (reference.deferred()) materialized.add(target);
                                if (queued.add(target)) {
                                    if (queued.size() > 128)
                                        throw new IllegalArgumentException(
                                                "Maximum 128 reachable local loot tables.");
                                    pending.add(target);
                                }
                            }
                        });
            } catch (IOException | RuntimeException e) {
                throw AddonZip.failure(pack.source(), path, "working-import loot JSON", e);
            }
        }
        for (String id : materialized) {
            String exportedLocalPrefix = ownedPrefix + "local/";
            String fallback =
                    "npships:local/import_"
                            + java.util.HexFormat.of()
                                    .formatHex(sha256(id.getBytes(StandardCharsets.UTF_8)));
            String local =
                    id.startsWith(exportedLocalPrefix)
                            ? "npships:local/" + id.substring(exportedLocalPrefix.length())
                            : fallback;
            int suffix = 0;
            while (!localNames.add(local)) local = fallback + "_" + (++suffix);
            references.put(id, local);
        }
        rewriteAssignments(config, references);
        var expandedCosts = new HashMap<String, Long>();
        // Every local table shares this one working companion JSON, including existing config.
        long expandedTotal =
                config.toString().getBytes(StandardCharsets.UTF_8).length
                        + EmbeddedLoot.KEY.length()
                        + 8;
        for (String id : materialized) {
            try {
                expandedTotal +=
                        expandedLootCost(
                                        id,
                                        definitions,
                                        references,
                                        expandedCosts,
                                        new HashSet<>(),
                                        0)
                                + references.get(id).getBytes(StandardCharsets.UTF_8).length
                                + 4;
                if (expandedTotal > MAX_JSON)
                    throw new IllegalArgumentException(
                            "Expanded working companion exceeds 16 MiB JSON.");
            } catch (RuntimeException e) {
                throw AddonZip.failure(
                        pack.source(), available.get(id), "working-import loot expansion", e);
            }
        }
        for (var entry : definitions.entrySet()) {
            // Immediate-only targets remain definitions for inlining and cost planning; they
            // do not become extra editable tables. Deferred targets must retain their own IDs.
            if (!materialized.contains(entry.getKey())) continue;
            try {
                var definition = entry.getValue().deepCopy();
                inlineOwnedTables(
                        definition, definitions, new HashSet<>(Set.of(entry.getKey())), 0);
                // Deferred container references stay IDs; map them only after the full reachable
                // set is known. Immediate entry references retain their existing inline semantics.
                rewriteTable(definition, references);
                tables.add(references.get(entry.getKey()), definition);
            } catch (RuntimeException e) {
                throw AddonZip.failure(
                        pack.source(),
                        available.get(entry.getKey()),
                        "working-import loot JSON",
                        e);
            }
        }
        rewriteNbt(tag, references);
        if (!tables.isEmpty()) config.add(EmbeddedLoot.KEY, tables);
        var result = new SchematicDocument(tag, config);
        validate(result);
        return result;
    }

    /** Identifies the conflicting export without relying on matching an English error string. */
    public static final class ExportCollision extends IOException {
        private final String id, version;
        private final Path folder;

        ExportCollision(Metadata metadata, Path folder) {
            super(
                    "Pack "
                            + metadata.id()
                            + " version "
                            + metadata.version()
                            + " already exists in "
                            + folder
                            + ". Change the pack ID or pack version.");
            this.id = metadata.id();
            this.version = metadata.version();
            this.folder = folder;
        }

        public String id() {
            return id;
        }

        public String version() {
            return version;
        }

        public Path folder() {
            return folder;
        }
    }

    public static Path saveJar(Path folder, String name, byte[] data) throws IOException {
        if (!name.matches("[a-z][a-z0-9_]{1,63}-[0-9A-Za-z][0-9A-Za-z._+\\-]{0,63}\\.jar"))
            throw new IOException("Use the addon ID-version.jar filename.");
        var metadata = read(data).metadata();
        Files.createDirectories(folder);
        Path root = folder.toRealPath();
        Path dest = root.resolve(name);
        try (var files = Files.list(root)) {
            if (files.anyMatch(p -> p.getFileName().toString().equalsIgnoreCase(name)))
                throw new ExportCollision(metadata, root);
        }
        Path temp = Files.createTempFile(root, ".addon-", ".tmp");
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, dest);
            } catch (FileAlreadyExistsException collision) {
                // The same no-overwrite contract also covers an export created after the scan.
                throw new ExportCollision(metadata, root);
            }
            return dest;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static byte[] readBytes(Path path) throws IOException {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_JAR)
                throw new IOException("Select a regular addon JAR at most 64 MiB compressed");
            try (var in = Files.newInputStream(path)) {
                byte[] bytes = in.readNBytes(MAX_JAR + 1);
                if (bytes.length > MAX_JAR) throw new IOException("Compressed size exceeds 64 MiB");
                return bytes;
            }
        } catch (IOException e) {
            throw AddonZip.failure(path.toString(), "<container>", "archive input", e);
        }
    }

    private static SchematicDocument document(
            Map<String, byte[]> entries, String ship, String source) throws IOException {
        CompoundTag tag;
        try {
            tag =
                    NbtIo.readCompressed(
                            new ByteArrayInputStream(required(entries, ship)),
                            NbtAccounter.create(SchematicDocument.NBT_MEMORY_LIMIT));
        } catch (IOException | RuntimeException e) {
            throw AddonZip.failure(source, ship, "schematic NBT", e);
        }
        String json = ship.substring(0, ship.length() - 4) + ".json";
        JsonObject config;
        try {
            config = object(required(entries, json));
        } catch (IOException | RuntimeException e) {
            throw AddonZip.failure(source, json, "companion JSON", e);
        }
        try {
            return new SchematicDocument(tag, config);
        } catch (RuntimeException e) {
            throw AddonZip.failure(source, ship, "schematic NBT structure", e);
        }
    }

    public static void validate(SchematicDocument doc) {
        if (!doc.hasConfig()) throw new IllegalArgumentException("A ship JSON config is required.");
        com.eynny.npships.config.NPShipsStructureConfigLoader.load(doc.configCopy());
        StructureSegments.validate(doc.nbtCopy(), StructureSegments.read(doc.configCopy()));
        StructureCrew.validateBounds(doc, doc.configCopy());
        var tag = doc.nbtCopy();
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            code.validateDocument(doc);
        SchematicSafety.validate(tag);
        com.eynny.npships.authoring.patches.SchematicPatches.embed(tag, doc.configCopy());
        com.eynny.npships.authoring.patches.SchematicPatches.validate(
                tag, true, PatchAuthoring.Kind.EXPORT);
        StructureSegments.visitValidationVariants(
                doc,
                part -> {
                    var partDocument = new SchematicDocument(part, null);
                    for (var code :
                            com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                        code.validateDocument(partDocument);
                    SchematicSafety.validate(part);
                    com.eynny.npships.authoring.patches.SchematicPatches.validate(
                            part, true, PatchAuthoring.Kind.EXPORT);
                });
        for (var assignment : LootAssignments.read(doc.configCopy()))
            if (!doc.blocks().containsKey(assignment.pos()))
                throw new IllegalArgumentException("Loot outside schematic.");
        EmbeddedLoot.read(doc.configCopy());
    }

    private static void rewriteAssignments(JsonElement node, Map<String, String> mapping) {
        if (node.isJsonArray()) {
            for (var child : node.getAsJsonArray()) rewriteAssignments(child, mapping);
        } else if (node.isJsonObject()) {
            var o = node.getAsJsonObject();
            if (o.has(LootAssignments.KEY))
                for (var v : o.getAsJsonArray(LootAssignments.KEY)) {
                    var e = v.getAsJsonObject();
                    String id = e.get("table").getAsString();
                    if (mapping.containsKey(id)) e.addProperty("table", mapping.get(id));
                }
            // Only authored segment configuration, never rewrite Lua, item strings or arbitrary
            // patches.
            for (String key : List.of("segments", "pool"))
                if (o.has(key)) rewriteAssignments(o.get(key), mapping);
        }
    }

    private static void rewriteNbt(Tag node, Map<String, String> mapping) {
        AddonDependencies.nativeRoots(
                node,
                root -> {
                    if (root.contains("LootTable", Tag.TAG_STRING)
                            && mapping.containsKey(root.getString("LootTable")))
                        root.putString("LootTable", mapping.get(root.getString("LootTable")));
                });
    }

    private static void rewriteTable(JsonElement node, Map<String, String> mapping) {
        AddonDependencies.lootReferences(
                node,
                reference -> {
                    String replacement = mapping.get(reference.id());
                    if (replacement != null) reference.replace(new JsonPrimitive(replacement));
                });
    }

    private static void collectLoot(JsonElement node, Set<String> out) {
        if (node.isJsonArray()) for (var child : node.getAsJsonArray()) collectLoot(child, out);
        else if (node.isJsonObject()) {
            var o = node.getAsJsonObject();
            if (o.has(LootAssignments.KEY))
                for (var v : o.getAsJsonArray(LootAssignments.KEY))
                    out.add(v.getAsJsonObject().get("table").getAsString());
            for (String key : List.of("segments", "pool"))
                if (o.has(key)) collectLoot(o.get(key), out);
        }
    }

    private static void collectLoot(Tag node, Set<String> out) {
        AddonDependencies.nativeRoots(
                node,
                root -> {
                    if (root.contains("LootTable", Tag.TAG_STRING))
                        out.add(root.getString("LootTable"));
                });
    }

    /** Plan the immediate-reference DAG before any expansion copies; deferred targets stay IDs. */
    private static long expandedLootCost(
            String id,
            Map<String, JsonObject> definitions,
            Map<String, String> references,
            Map<String, Long> costs,
            Set<String> visiting,
            int depth) {
        if (depth > 32) throw new IllegalArgumentException("Loot nesting exceeds 32 levels.");
        var cached = costs.get(id);
        if (cached != null) return cached;
        if (!visiting.add(id))
            throw new IllegalArgumentException("Cyclic loot references cannot be imported: " + id);
        long[] size = {definitions.get(id).toString().getBytes(StandardCharsets.UTF_8).length};
        if (size[0] > MAX_JSON)
            throw new IllegalArgumentException("Expanded loot table exceeds 16 MiB JSON.");
        AddonDependencies.lootReferences(
                definitions.get(id),
                reference -> {
                    String replacement = references.get(reference.id());
                    if (reference.deferred() && replacement != null)
                        size[0] +=
                                Math.max(
                                        0,
                                        replacement.getBytes(StandardCharsets.UTF_8).length
                                                - reference
                                                        .id()
                                                        .getBytes(StandardCharsets.UTF_8)
                                                        .length);
                    if (!reference.deferred() && definitions.containsKey(reference.id())) {
                        size[0] +=
                                expandedLootCost(
                                        reference.id(),
                                        definitions,
                                        references,
                                        costs,
                                        visiting,
                                        depth + 1);
                        if (size[0] > MAX_JSON)
                            throw new IllegalArgumentException(
                                    "Expanded loot table exceeds 16 MiB JSON.");
                    }
                    if (size[0] > MAX_JSON)
                        throw new IllegalArgumentException(
                                "Expanded loot table exceeds 16 MiB JSON.");
                });
        visiting.remove(id);
        costs.put(id, size[0]);
        return size[0];
    }

    private static void inlineOwnedTables(
            JsonElement node,
            Map<String, JsonObject> definitions,
            Set<String> visiting,
            int depth) {
        if (depth > 32) throw new IllegalArgumentException("Loot nesting exceeds 32 levels.");
        AddonDependencies.lootReferences(
                node,
                reference -> {
                    if (reference.deferred()) return;
                    String id = reference.id();
                    var source = definitions.get(id);
                    if (source == null) return;
                    if (!visiting.add(id))
                        throw new IllegalArgumentException(
                                "Cyclic loot references cannot be imported: " + id);
                    var nested = source.deepCopy();
                    inlineOwnedTables(nested, definitions, visiting, depth + 1);
                    visiting.remove(id);
                    reference.replace(nested);
                });
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] required(Map<String, byte[]> entries, String path) throws IOException {
        var data = entries.get(path);
        if (data == null) throw new IOException("Missing " + path);
        return data;
    }

    private static JsonObject object(byte[] data) throws IOException {
        if (data.length > MAX_JSON) throw new IOException("JSON exceeds 16 MiB.");
        try {
            return JsonStructure.parse(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid addon JSON", e);
        }
    }

    private static byte[] json(JsonElement value) {
        return (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] nbt(CompoundTag tag) throws IOException {
        var out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        AddonZip.validateEntries(entries);
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var e : entries.entrySet()) {
                var entry = new ZipEntry(e.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
                if (out.size() > MAX_JAR) throw new IOException("Addon exceeds 64 MiB compressed.");
            }
        }
        if (out.size() > MAX_JAR) throw new IOException("Addon exceeds 64 MiB compressed.");
        return out.toByteArray();
    }

    private AddonArchive() {}
}
