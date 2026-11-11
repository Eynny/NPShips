package com.eynny.npships.authoring.io;

import com.eynny.npships.authoring.document.SchematicDocument;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Preflight every selected name/document, then commit with rollback of completed pairs. */
public final class AddonImports {
    public static final class Collision extends IOException {
        public Collision(String text) {
            super(text);
        }
    }

    private record Item(
            String resource, String name, SchematicDocument before, SchematicDocument after) {}

    public static synchronized int apply(
            AddonArchive.Contents pack, Map<String, String> requested, Path folder, boolean replace)
            throws IOException {
        Path root;
        try {
            Files.createDirectories(folder);
            root = folder.toRealPath();
        } catch (IOException e) {
            throw AddonZip.failure(
                    pack.source(),
                    "<working directory>",
                    "working-import destination " + folder,
                    e);
        }
        var plan = new ArrayList<Item>();
        var used = new HashSet<String>();
        var collisions = new ArrayList<String>();
        var destinations = new TreeMap<String, List<Path>>(String.CASE_INSENSITIVE_ORDER);
        try (var files = Files.list(root)) {
            files.forEach(
                    p ->
                            destinations
                                    .computeIfAbsent(
                                            p.getFileName().toString(), k -> new ArrayList<>())
                                    .add(p));
        } catch (IOException e) {
            throw AddonZip.failure(
                    pack.source(),
                    "<working directory>",
                    "working-import destination index " + folder,
                    e);
        }
        for (var request : requested.entrySet()) {
            try {
                String name = AuthoringCreation.filename(request.getValue());
                if (!used.add(name.toLowerCase(Locale.ROOT)))
                    throw new IOException(
                            "Two selected ships have the same destination name. Rename one in the list.");
                var document = AddonArchive.importShip(pack, request.getKey());
                SchematicDocument before = null;
                String json = name.substring(0, name.length() - 4) + ".json";
                var existing = new ArrayList<>(destinations.getOrDefault(name, List.of()));
                existing.addAll(destinations.getOrDefault(json, List.of()));
                if (!existing.isEmpty()) {
                    collisions.add(name);
                    if (!replace) continue;
                    String targetName = name;
                    var nbt =
                            existing.stream()
                                    .filter(
                                            p ->
                                                    p.getFileName()
                                                            .toString()
                                                            .equalsIgnoreCase(targetName))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IOException(
                                                            "Orphan JSON blocks this name: "
                                                                    + targetName));
                    if (existing.size() != 2
                            || !Files.isRegularFile(nbt, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException(
                                "Cannot replace an incomplete or ambiguous pair: " + name);
                    name = nbt.getFileName().toString();
                    before = AuthoringFiles.read(root, name);
                    if (!before.hasConfig())
                        throw new IOException("Cannot replace a schematic without JSON: " + name);
                }
                plan.add(new Item(request.getKey(), name, before, document));
            } catch (IOException | RuntimeException e) {
                throw AddonZip.failure(
                        pack.source(),
                        request.getKey(),
                        "working-import destination " + request.getValue(),
                        e);
            }
        }
        if (!collisions.isEmpty() && !replace)
            throw new Collision(
                    "Replace existing NBT + JSON pairs: "
                            + String.join(", ", collisions)
                            + "? Cancel to edit destination names in the list.");
        var completed = new ArrayList<Item>();
        String active = "<selection>";
        try {
            for (var item : plan) {
                active = item.resource;
                if (item.before == null) AuthoringCreation.create(root, item.name, item.after);
                else AuthoringFiles.saveDocument(root, item.name, item.before, item.after);
                completed.add(item);
            }
        } catch (Exception error) {
            Collections.reverse(completed);
            for (var item : completed)
                try {
                    if (item.before != null)
                        AuthoringFiles.saveDocument(root, item.name, item.after, item.before);
                    else {
                        var current = AuthoringFiles.read(root, item.name);
                        if (!current.nbtCopy().equals(item.after.nbtCopy())
                                || !Objects.equals(current.configCopy(), item.after.configCopy()))
                            throw new IOException(
                                    "A concurrently edited file was retained: " + item.name);
                        AuthoringFiles.delete(root, item.name);
                    }
                } catch (Exception rollback) {
                    error.addSuppressed(rollback);
                }
            throw AddonZip.failure(
                    pack.source(),
                    active,
                    "working-import commit",
                    new IOException(
                            "Import failed: "
                                    + error.getMessage()
                                    + (error.getSuppressed().length == 0
                                            ? "; previous changes rolled back."
                                            : "; rollback incomplete, inspect the destination files."),
                            error));
        }
        return completed.size();
    }

    private AddonImports() {}
}
