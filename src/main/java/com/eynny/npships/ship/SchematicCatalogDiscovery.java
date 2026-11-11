package com.eynny.npships.ship;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers deterministic resource/file entries, including duplicate and override rules. The
 * catalog owns the destination map, publication and subsequent metadata loading.
 */
final class SchematicCatalogDiscovery {
    static final String SCHEMATIC_ROOT = "npships";
    static final String OWN_NAMESPACE = "npships";

    private SchematicCatalogDiscovery() {}

    static void refreshResourceManagerResources(
            final net.minecraft.server.packs.resources.ResourceManager resources,
            Map<String, SchematicCatalogEntry> entries,
            CatalogueDiagnostics diagnostics,
            CatalogueResources.Generation budget) {

        final Map<String, Resource> jsonResources = new HashMap<>();

        /*
         * JSON resources.
         */
        final Map<ResourceLocation, Resource> allJson =
                resources.listResources(
                        SCHEMATIC_ROOT, path -> path.getPath().toLowerCase().endsWith(".json"));

        final List<Map.Entry<ResourceLocation, Resource>> sortedJson =
                new ArrayList<>(allJson.entrySet());

        sortedJson.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        for (final Map.Entry<ResourceLocation, Resource> entry : sortedJson) {

            final ResourceLocation resourceId = entry.getKey();

            final String fileName =
                    getFileNameWithoutExtension(
                            resourceId.getPath().substring(SCHEMATIC_ROOT.length() + 1),
                            diagnostics);

            if (fileName == null) {
                continue;
            }

            final String key = resourceId.getNamespace() + ":" + fileName;

            jsonResources.putIfAbsent(key, entry.getValue());
        }

        /*
         * NBT resources.
         */
        final Map<ResourceLocation, Resource> allNbt =
                resources.listResources(
                        SCHEMATIC_ROOT, path -> path.getPath().toLowerCase().endsWith(".nbt"));

        final List<Map.Entry<ResourceLocation, Resource>> sortedNbt =
                new ArrayList<>(allNbt.entrySet());

        sortedNbt.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        var seenResources = new java.util.HashSet<String>();
        for (final Map.Entry<ResourceLocation, Resource> entry : sortedNbt) {

            final ResourceLocation resourceId = entry.getKey();

            final String fileName =
                    getFileNameWithoutExtension(
                            resourceId.getPath().substring(SCHEMATIC_ROOT.length() + 1),
                            diagnostics);

            if (fileName == null) {
                continue;
            }

            final String commandName = resourceId.getNamespace() + ":" + fileName;

            /*
             * Deterministic first one wins.
             */
            if (!seenResources.add(commandName) || entries.containsKey(commandName)) {

                continue;
            }

            final Resource config = jsonResources.get(commandName);

            try {
                entries.put(
                        commandName,
                        SchematicCatalogEntry.resource(
                                commandName, resourceId, entry.getValue(), config, budget));
            } catch (CatalogueDiagnostics.EntryFailure failure) {
                diagnostics.reject(
                        commandName,
                        resourceId
                                + " pack="
                                + entry.getValue().sourcePackId()
                                + (config == null ? "" : " config-pack=" + config.sourcePackId()),
                        failure);
            }
        }
    }

    static void scanExternalDirectory(
            final Path root,
            Map<String, SchematicCatalogEntry> entries,
            CatalogueDiagnostics diagnostics,
            CatalogueResources.Generation budget) {

        try {

            Files.createDirectories(root);

        } catch (final IOException e) {

            throw new RuntimeException(
                    "Failed creating NPShips external schematic " + "directory: " + root, e);
        }

        final List<Path> nbtFiles = new ArrayList<>();

        final List<Path> jsonFiles = new ArrayList<>();

        try (var stream = Files.walk(root)) {

            stream.filter(Files::isRegularFile)
                    .forEach(
                            path -> {
                                final String lower = path.getFileName().toString().toLowerCase();

                                if (lower.endsWith(".nbt")) {

                                    nbtFiles.add(path);

                                } else if (lower.endsWith(".json")) {

                                    jsonFiles.add(path);
                                }
                            });

        } catch (final IOException e) {

            throw new RuntimeException(
                    "Failed scanning NPShips external schematic " + "directory: " + root, e);
        }

        nbtFiles.sort(Comparator.comparing(Path::toString));

        jsonFiles.sort(Comparator.comparing(Path::toString));

        final Map<String, Path> configs = new HashMap<>();

        for (final Path json : jsonFiles) {

            final String fileName =
                    getFileNameWithoutExtension(root.relativize(json).toString(), diagnostics);

            if (fileName == null) {
                continue;
            }

            final Path duplicate = configs.putIfAbsent(fileName, json);
            if (duplicate != null) {
                diagnostics.detail(
                        "Duplicate config ID "
                                + fileName
                                + ": keeping "
                                + duplicate
                                + ", ignoring "
                                + json);
            }
        }

        var seenExternal = new java.util.HashSet<String>();
        for (final Path nbt : nbtFiles) {

            final String fileName =
                    getFileNameWithoutExtension(root.relativize(nbt).toString(), diagnostics);

            if (fileName == null) {
                continue;
            }

            /*
             * External schematics use a bare command name.
             */
            if (!seenExternal.add(fileName) || entries.containsKey(fileName)) {
                diagnostics.detail("Duplicate schematic ID " + fileName + ": ignoring " + nbt);
                continue;
            }

            // Reserve the overriding identity even when it is invalid: never silently substitute
            // the bundled file for a broken external author's entry.
            if (entries.remove(OWN_NAMESPACE + ":" + fileName) != null)
                diagnostics.detail(
                        "External schematic "
                                + fileName
                                + " overrides bundled resource of the same ID");
            try {
                entries.put(
                        fileName,
                        SchematicCatalogEntry.external(
                                fileName, nbt, configs.get(fileName), budget));
            } catch (CatalogueDiagnostics.EntryFailure failure) {
                diagnostics.reject(
                        OWN_NAMESPACE + ":" + fileName,
                        nbt + " config=" + configs.get(fileName),
                        failure);
            }
        }
    }

    private static String getFileNameWithoutExtension(
            final String path, CatalogueDiagnostics diagnostics) {

        if (path == null || path.isEmpty()) {

            return null;
        }

        final String fileName = path.replace('\\', '/');

        final int dot = fileName.lastIndexOf('.');

        if (dot <= 0 || dot >= fileName.length() - 1) {

            return null;
        }

        final String name = fileName.substring(0, dot);
        try {
            return SchematicNames.parse(name).getPath();
        } catch (IllegalArgumentException exception) {
            diagnostics.detail(
                    "Invalid schematic/config resource name "
                            + path
                            + ": "
                            + exception.getMessage());
            return null;
        }
    }
}
