package com.eynny.npships.authoring.io;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicExtensions;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Local document IO only; no access to the connected server's filesystem. */
public final class AuthoringFiles {
    private AuthoringFiles() {}

    public static String duplicate(Path directory, String source, String requested)
            throws IOException {
        return AuthoringCreation.create(directory, requested, read(directory, source));
    }

    /** Move the local pair without overwriting an existing name; roll back a partial move. */
    public static synchronized String rename(Path directory, String source, String requested)
            throws IOException {
        Path root = directory.toRealPath(), old = root.resolve(source).normalize();
        if (!old.startsWith(root)
                || !Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)
                || !old.getParent().toRealPath().startsWith(root))
            throw new IOException("Invalid schematic path.");
        read(directory, source);
        String name = AuthoringCreation.filename(requested);
        Path next = old.resolveSibling(name);
        if (old.equals(next)) return source;
        String oldJson = old.getFileName().toString().replaceFirst("(?i)\\.nbt$", ".json"),
                newJson = name.substring(0, name.length() - 4) + ".json";
        Path companion = null;
        try (var entries = Files.list(old.getParent())) {
            for (var p : entries.toList()) {
                String file = p.getFileName().toString();
                if (file.equalsIgnoreCase(name) || file.equalsIgnoreCase(newJson))
                    throw new IOException("This name already exists.");
                if (file.equalsIgnoreCase(oldJson)) companion = p;
            }
        }
        if (companion != null && !Files.isRegularFile(companion, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid companion JSON.");
        Files.move(old, next);
        try {
            if (companion != null) Files.move(companion, old.resolveSibling(newJson));
        } catch (IOException e) {
            try {
                Files.move(next, old);
            } catch (IOException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        }
        return root.relativize(next).toString();
    }

    /** Delete only the selected local pair; never follow a file link or delete directories. */
    public static void delete(Path directory, String name) throws IOException {
        Path root = directory.toRealPath(), nbt = root.resolve(name).normalize();
        if (!nbt.startsWith(root)
                || !nbt.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt")
                || !Files.isRegularFile(nbt, LinkOption.NOFOLLOW_LINKS)
                || !nbt.getParent().toRealPath().startsWith(root))
            throw new IOException("Select a regular NBT file inside npships/schematics.");
        String companion = nbt.getFileName().toString().replaceFirst("(?i)\\.nbt$", ".json");
        List<Path> matches;
        try (var siblings = Files.list(nbt.getParent())) {
            matches =
                    siblings.filter(p -> p.getFileName().toString().equalsIgnoreCase(companion))
                            .toList();
        }
        if (matches.size() > 1) throw new IOException("Ambiguous companion JSON.");
        Path json = matches.isEmpty() ? null : matches.getFirst();
        if (json != null
                && (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(json) > 16L * 1024 * 1024))
            throw new IOException("Invalid companion JSON.");
        byte[] backup = json == null ? null : Files.readAllBytes(json);
        if (json != null) Files.delete(json);
        try {
            Files.delete(nbt);
        } catch (IOException e) {
            if (json != null)
                Files.write(json, backup, java.nio.file.StandardOpenOption.CREATE_NEW);
            throw e;
        }
    }

    public static List<String> list(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.walk(directory, 16)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .toLowerCase(Locale.ROOT)
                                            .endsWith(".nbt"))
                    .map(directory::relativize)
                    .map(Path::toString)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    public static SchematicDocument read(final Path directory, final String name)
            throws IOException {
        return read(directory, name, false);
    }

    public static SchematicDocument readImported(final Path directory, final String name)
            throws IOException {
        return read(directory, name, true);
    }

    private static SchematicDocument read(Path directory, String name, boolean importing)
            throws IOException {
        Path root = directory.toRealPath();
        Path nbt = root.resolve(name).normalize().toRealPath();
        if (!nbt.startsWith(root)
                || !nbt.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt")) {
            throw new IOException("Select an NBT file inside npships/schematics.");
        }
        if (Files.size(nbt) > 32L * 1024 * 1024)
            throw new IOException("NBT exceeds 32 MiB compressed.");
        var tag =
                NbtIo.readCompressed(nbt, NbtAccounter.create(SchematicDocument.NBT_MEMORY_LIMIT));
        String filename = nbt.getFileName().toString();
        String configName = filename.substring(0, filename.length() - 4) + ".json";
        List<Path> configs;
        try (var siblings = Files.list(nbt.getParent())) {
            configs =
                    siblings.filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .equalsIgnoreCase(configName))
                            .toList();
        }
        if (configs.size() > 1) throw new IOException("Ambiguous companion JSON for " + filename);
        Path json = configs.isEmpty() ? null : configs.getFirst();
        JsonObject config = null;
        if (json != null) {
            if (!json.toRealPath().startsWith(root))
                throw new IOException("Config points outside npships/schematics.");
            if (Files.size(json) > 16L * 1024 * 1024)
                throw new IOException("Config exceeds 16 MiB.");
            try (var reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
                var parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject())
                    throw new IOException("The ship config must be a JSON object.");
                config = parsed.getAsJsonObject();
            }
        }
        if (!importing) return new SchematicDocument(tag, config);
        // Import callbacks inspect the original wrapper only after native conversion has produced
        // its detached assembly pose. Internal conversion parts do not invoke providers again.
        var converted = ContraptionImport.convert(SubLevelImport.read(tag, config));
        return SchematicExtensions.inspectImport(tag, converted);
    }

    /** Optimistic concurrency check and atomic JSON replacement. NBT is never rewritten. */
    public static void saveConfig(
            final Path directory,
            final String name,
            final SchematicDocument expected,
            final JsonObject updated)
            throws IOException {
        saveConfig(directory, name, expected, updated, true);
    }

    private static void saveConfig(
            Path directory,
            String name,
            SchematicDocument expected,
            JsonObject updated,
            boolean validateAuthorData)
            throws IOException {
        if (!expected.hasConfig())
            throw new IOException(
                    "A companion ship JSON is required for editing loot assignments.");
        com.eynny.npships.config.NPShipsStructureConfigLoader.load(updated);
        var authoringTemplate = expected.nbtCopy();
        StructureSegments.validate(authoringTemplate, StructureSegments.read(updated));
        StructureCrew.validateBounds(expected, updated);
        for (var assignment : LootAssignments.read(updated)) {
            if (!expected.blocks().containsKey(assignment.pos()))
                throw new IOException("Loot assignment outside schematic blocks.");
        }
        if (validateAuthorData
                && (SchematicExtensions.present(expected)
                        || authoringTemplate.contains(
                                com.eynny.npships.authoring.document.BlockAreaLayers.KEY)))
            SchematicExtensions.validate(
                    expected.withConfig(updated), false, PatchAuthoring.Kind.SAVE);
        var current = read(directory, name);
        if (!current.nbtCopy().equals(expected.nbtCopy())
                || !java.util.Objects.equals(current.configCopy(), expected.configCopy()))
            throw new IOException(
                    "The NBT or JSON changed on disk. Reopen the schematic before saving.");
        Path root = directory.toRealPath();
        Path nbt = root.resolve(name).normalize().toRealPath();
        String filename = nbt.getFileName().toString();
        String companion = filename.substring(0, filename.length() - 4) + ".json";
        Path json;
        try (var siblings = Files.list(nbt.getParent())) {
            var matches =
                    siblings.filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .equalsIgnoreCase(companion))
                            .toList();
            if (matches.size() != 1)
                throw new IOException("Companion JSON disappeared or became ambiguous.");
            json = matches.getFirst().toRealPath();
        }
        if (!json.startsWith(root))
            throw new IOException("Config points outside npships/schematics.");
        String contents =
                new com.google.gson.GsonBuilder()
                                .setPrettyPrinting()
                                .disableHtmlEscaping()
                                .create()
                                .toJson(updated)
                        + "\n";
        if (contents.getBytes(StandardCharsets.UTF_8).length > 16L * 1024 * 1024)
            throw new IOException("Config exceeds 16 MiB.");
        Path temporary = Files.createTempFile(json.getParent(), ".npships-config-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    json,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Optimistic paired save with atomic file replacements and rollback on a JSON write failure.
     */
    public static void saveDocument(
            Path directory, String name, SchematicDocument expected, SchematicDocument updated)
            throws IOException {
        if (!expected.hasConfig() || !updated.hasConfig())
            throw new IOException("Companion JSON is required.");
        var oldTag = expected.nbtCopy();
        var newTag = updated.nbtCopy();
        var checked = newTag.copy();
        com.eynny.npships.authoring.patches.SchematicPatches.embed(checked, updated.configCopy());
        com.eynny.npships.authoring.patches.SchematicPatches.validate(
                checked, true, PatchAuthoring.Kind.SAVE);
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            code.validateDocument(updated);
        StructureCrew.validateBounds(updated, updated.configCopy());
        for (var assignment : LootAssignments.read(updated.configCopy()))
            if (!updated.blocks().containsKey(assignment.pos()))
                throw new IOException("Loot assignment outside schematic blocks.");
        StructureSegments.visitValidationVariants(
                updated,
                part -> {
                    com.eynny.npships.authoring.patches.SchematicPatches.validate(
                            part, true, PatchAuthoring.Kind.SAVE);
                    var partDocument = new SchematicDocument(part, null);
                    for (var code :
                            com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                        code.validateDocument(partDocument);
                });
        StructureSegments.validate(newTag, StructureSegments.read(updated.configCopy()));
        com.eynny.npships.config.NPShipsStructureConfigLoader.load(updated.configCopy());
        // Derived metadata is not written into JSON/NBT; the author's serialized fields are
        // unchanged.
        updated.refreshRarityMetadata();
        if (oldTag.equals(newTag)) {
            saveConfig(directory, name, expected, updated.configCopy(), false);
            return;
        }
        Path root = directory.toRealPath(), nbt = root.resolve(name).normalize().toRealPath();
        if (!nbt.startsWith(root)) throw new IOException("NBT points outside schematic directory.");
        Path json;
        String companion = nbt.getFileName().toString().replaceFirst("(?i)\\.nbt$", ".json");
        try (var siblings = Files.list(nbt.getParent())) {
            var matches =
                    siblings.filter(p -> p.getFileName().toString().equalsIgnoreCase(companion))
                            .toList();
            if (matches.size() != 1)
                throw new IOException("Companion JSON disappeared or became ambiguous.");
            json = matches.getFirst().toRealPath();
        }
        if (!json.startsWith(root))
            throw new IOException("Config points outside schematic directory.");
        byte[] originalNbt = Files.readAllBytes(nbt), originalJson = Files.readAllBytes(json);
        var observed = read(directory, name);
        if (!observed.nbtCopy().equals(oldTag)
                || !java.util.Objects.equals(observed.configCopy(), expected.configCopy()))
            throw new IOException("NBT or JSON changed on disk. Reopen before saving.");
        byte[] nextJson =
                (new com.google.gson.GsonBuilder()
                                        .setPrettyPrinting()
                                        .disableHtmlEscaping()
                                        .create()
                                        .toJson(updated.configCopy())
                                + "\n")
                        .getBytes(StandardCharsets.UTF_8);
        if (nextJson.length > 16L * 1024 * 1024) throw new IOException("Config exceeds 16 MiB.");
        Path temporary = Files.createTempFile(nbt.getParent(), ".npships-variants-", ".tmp");
        Path nextConfig = null;
        try {
            NbtIo.writeCompressed(newTag, temporary);
            if (Files.size(temporary) > 32L * 1024 * 1024)
                throw new IOException("Combined NBT exceeds 32 MiB compressed.");
            NbtIo.readCompressed(
                    temporary, NbtAccounter.create(SchematicDocument.NBT_MEMORY_LIMIT));
            byte[] replacementNbt = Files.readAllBytes(temporary);
            nextConfig = Files.createTempFile(json.getParent(), ".npships-config-", ".tmp");
            Files.write(nextConfig, nextJson);
            // Both proposed files are prepared before either replaces its accepted owner. A resized
            // NBT and the old JSON need not form a valid document, even temporarily.
            if (!java.util.Arrays.equals(Files.readAllBytes(nbt), originalNbt)
                    || !java.util.Arrays.equals(Files.readAllBytes(json), originalJson))
                throw new IOException("NBT or JSON changed on disk. Reopen before saving.");
            Files.move(
                    temporary,
                    nbt,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(
                        nextConfig,
                        json,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                // Compare raw files: parsing the deliberately intermediate pair can itself fail and
                // must never prevent rollback. Do not overwrite another writer's intervening edit.
                if (java.util.Arrays.equals(Files.readAllBytes(nbt), replacementNbt)
                        && java.util.Arrays.equals(Files.readAllBytes(json), originalJson)) {
                    Files.write(temporary, originalNbt);
                    Files.move(
                            temporary,
                            nbt,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    throw new IOException(
                            "Save failed; original NBT restored. " + failure.getMessage(), failure);
                }
                throw new IOException(
                        "JSON save failed and files changed concurrently; reopen and inspect the pair.",
                        failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
            if (nextConfig != null) Files.deleteIfExists(nextConfig);
        }
    }
}
