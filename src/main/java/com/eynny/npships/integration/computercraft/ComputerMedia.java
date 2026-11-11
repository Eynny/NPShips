package com.eynny.npships.integration.computercraft;

import com.google.gson.*;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.*;
import java.util.*;

/** Portable floppy contents, including disks nested in item containers. */
public final class ComputerMedia {
    private ComputerMedia() {}

    private static final String ID = "computercraft:disk_id";

    public static Set<Integer> ids(Tag data) {
        var out = new TreeSet<Integer>();
        visit(
                data,
                c -> {
                    if (c.contains(ID, Tag.TAG_INT) && c.getInt(ID) >= 0) out.add(c.getInt(ID));
                });
        return out;
    }

    private static void visit(Tag data, java.util.function.Consumer<CompoundTag> consumer) {
        if (data instanceof CompoundTag c) {
            if (c.getString("id").equals("computercraft:disk"))
                consumer.accept(c.getCompound("components"));
            for (String key : List.copyOf(c.getAllKeys())) visit(c.get(key), consumer);
        } else if (data instanceof ListTag list) for (var item : list) visit(item, consumer);
    }

    public static Path root(ServerLevel level, int id) {
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("computercraft/disk/" + id);
    }

    public static JsonObject capture(ServerLevel level, CompoundTag data) throws Exception {
        var result = new JsonObject();
        // The existing import/runtime budget belongs to this whole media patch, not each disk.
        int bytes = 0, count = 0;
        for (int id : ids(data)) {
            var files = new JsonObject();
            Path root = root(level, id);
            if (Files.isSymbolicLink(root))
                throw new IllegalArgumentException("Disk directory is a symlink");
            if (Files.exists(root))
                try (var paths = ComputerPatch.capturePaths(root)) {
                    var iterator = paths.iterator();
                    while (iterator.hasNext()) {
                        var p = iterator.next();
                        if (Files.isSymbolicLink(p))
                            throw new IllegalArgumentException("Disk contains a symlink");
                        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                        long size = Files.size(p);
                        if (size > ComputerPatch.MAX_BYTES
                                || bytes + size > ComputerPatch.MAX_BYTES
                                || ++count > ComputerPatch.MAX_FILES)
                            throw new IllegalArgumentException("Disk exceeds file export limits");
                        String name = root.relativize(p).toString().replace('\\', '/');
                        ComputerPatch.relative(name);
                        byte[] contents =
                                ComputerPatch.captureContents(p, ComputerPatch.MAX_BYTES - bytes);
                        if (contents.length > ComputerPatch.MAX_BYTES - bytes)
                            throw new IllegalArgumentException("Disk exceeds file export limits");
                        bytes += contents.length;
                        files.addProperty(name, Base64.getEncoder().encodeToString(contents));
                    }
                }
            result.add(Integer.toString(id), files);
        }
        return result;
    }

    public static void validate(JsonObject patch) {
        if (!patch.has("media")) return;
        long total = 0;
        int count = 0;
        for (var disk : patch.getAsJsonObject("media").entrySet()) {
            if (!disk.getKey().matches("0|[1-9][0-9]*")
                    || Long.parseLong(disk.getKey()) > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid disk ID");
            ComputerFiles.validatePaths(disk.getValue().getAsJsonObject().keySet(), false);
            for (var file : disk.getValue().getAsJsonObject().entrySet()) {
                ComputerPatch.relative(file.getKey());
                if (file.getValue().getAsString().length() > ComputerPatch.MAX_BYTES * 2)
                    throw new IllegalArgumentException("Disk file too large");
                total += Base64.getDecoder().decode(file.getValue().getAsString()).length;
                if (total > ComputerPatch.MAX_BYTES || ++count > ComputerPatch.MAX_FILES)
                    throw new IllegalArgumentException("Disk export exceeds 8 MiB / 512 files");
            }
        }
    }

    public static void validateReferences(Set<Integer> referenced, JsonObject patch) {
        var media = patch.has("media") ? patch.getAsJsonObject("media") : new JsonObject();
        for (int old : referenced)
            if (!media.has(Integer.toString(old)))
                throw new IllegalArgumentException(
                        "Disk " + old + " has no captured files; re-export it with NPShips");
    }

    public static void validateReferences(CompoundTag data, JsonObject patch) {
        validateReferences(ids(data), patch);
    }

    public static void restore(
            ServerLevel level,
            CompoundTag data,
            JsonObject patch,
            Map<Integer, Integer> allocated,
            Map<Integer, JsonObject> originals)
            throws Exception {
        try (var transaction = new ComputerFiles()) {
            restore(level, data, patch, allocated, originals, transaction);
            transaction.commit();
        }
    }

    public static void restore(
            ServerLevel level,
            CompoundTag data,
            JsonObject patch,
            Map<Integer, Integer> allocated,
            Map<Integer, JsonObject> originals,
            ComputerFiles transaction)
            throws Exception {
        validate(patch);
        var media = patch.has("media") ? patch.getAsJsonObject("media") : new JsonObject();
        var referenced = ids(data);
        validateReferences(referenced, patch);
        for (int old : referenced)
            if (allocated.containsKey(old)
                    && !Objects.equals(
                            originals.get(old), media.getAsJsonObject(Integer.toString(old))))
                throw new IllegalArgumentException("Conflicting contents for disk " + old);
        var pending = new LinkedHashMap<Integer, Integer>();
        var pendingOriginals = new LinkedHashMap<Integer, JsonObject>();
        for (int old : referenced) {
            if (allocated.containsKey(old)) continue;
            var files = media.getAsJsonObject(Integer.toString(old));
            var contents = new LinkedHashMap<Path, byte[]>();
            for (var file : files.entrySet())
                contents.put(
                        ComputerPatch.relative(file.getKey()),
                        Base64.getDecoder().decode(file.getValue().getAsString()));
            int id = ComputerPatch.allocate(level, "disk");
            transaction.write(root(level, id), contents);
            pending.put(old, id);
            pendingOriginals.put(old, files);
        }
        allocated.putAll(pending);
        originals.putAll(pendingOriginals);
        visit(
                data,
                c -> {
                    if (c.contains(ID, Tag.TAG_INT) && allocated.containsKey(c.getInt(ID)))
                        c.putInt(ID, allocated.get(c.getInt(ID)));
                });
    }
}
