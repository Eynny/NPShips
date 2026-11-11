package com.eynny.npships.authoring.io;

import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.*;

/** Shared current addon layout admission for author import and loader-owned resource projection. */
final class AddonModLayout {
    static List<String> validate(Collection<String> paths, JsonObject marker, String owner)
            throws IOException {
        var format = marker.get("format");
        if (format == null
                || !format.isJsonPrimitive()
                || !format.getAsJsonPrimitive().isString()
                || !AddonArchive.FORMAT.equals(format.getAsString()))
            throw new IOException("Unsupported addon format: expected current data-only mod JAR.");
        var declarations = marker.get("schematics");
        if (declarations == null
                || !declarations.isJsonArray()
                || declarations.getAsJsonArray().isEmpty())
            throw new IOException("Missing relative schematic list.");
        var files = new HashSet<>(paths);
        var nodes = new HashSet<String>();
        for (String path : paths) {
            nodes.add(path);
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                String parent = path.substring(0, slash);
                if (files.contains(parent))
                    throw new IOException("File/directory collision in addon: " + parent);
                nodes.add(parent);
            }
            if (nodes.size() > AddonArchive.MAX_ENTRIES)
                throw new IOException("Addon contains more than 4,096 files and folders.");
        }
        var names = new TreeSet<String>();
        for (var value : declarations.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IOException("Schematic names must be strings.");
            String name = value.getAsString();
            AddonZip.path(name);
            if (name.endsWith("/")
                    || ResourceLocation.tryBuild(owner, name) == null
                    || !names.add(name))
                throw new IOException("Invalid or duplicate schematic: " + name);
            for (String extension : List.of(".nbt", ".json"))
                if (!files.contains("data/npships/" + name + extension))
                    throw new IOException("Missing data/npships/" + name + extension);
        }
        for (String name : paths) {
            AddonZip.path(name);
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".class")
                    || lower.endsWith(".jar")
                    || lower.endsWith(".zip")
                    || lower.endsWith("mixins.json"))
                throw new IOException(
                        "Executable content is not part of a data-only addon: " + name);
            if (!name.startsWith("data/")) {
                if (!rootFile(name))
                    throw new IOException("Unsupported data-only addon entry: " + name);
                continue;
            }
            String relative = name.substring(5);
            if (ResourceLocation.tryBuild(owner, relative) == null)
                throw new IOException("Invalid resource path: " + name);
            if (relative.startsWith("npships/")) {
                int extension = relative.endsWith(".nbt") ? 4 : relative.endsWith(".json") ? 5 : 0;
                if (extension == 0
                        || !names.contains(relative.substring(8, relative.length() - extension)))
                    throw new IOException("Unlisted schematic resource: " + name);
            } else if (!relative.startsWith("loot_table/") || !relative.endsWith(".json"))
                throw new IOException("Unsupported addon data layout: " + name);
        }
        return List.copyOf(names);
    }

    private static boolean rootFile(String name) {
        return Set.of(
                                "npships-pack.json",
                                "pack.mcmeta",
                                "pack.png",
                                "META-INF/neoforge.mods.toml",
                                "META-INF/MANIFEST.MF")
                        .contains(name)
                || name.matches(
                        "(?i)(?:LICENSE|LICENCE|COPYING|NOTICE|COPYRIGHT|AUTHORS|ATTRIBUTION)(?:\\.(?:TXT|MD))?")
                || name.regionMatches(true, 0, "licenses/", 0, 9);
    }

    private AddonModLayout() {}
}
