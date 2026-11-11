package com.eynny.npships.authoring.io;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicExtensions;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

/** Creation never overwrites an existing author's pair. Imported source files remain untouched. */
public final class AuthoringCreation {
    private AuthoringCreation() {}

    /** Reset only spawn rules. Patches, loot, crew, segments and unknown extensions survive. */
    public static SchematicDocument resetSpawnProfile(
            SchematicDocument source, ExportProfile profile, int seaLevel) {
        var defaults = withProfile(source.withConfig(null), profile, seaLevel).configCopy();
        var config = source.configCopy();
        if (config == null) config = new JsonObject();
        for (String key : java.util.List.of("rarity", "environment", "restrictions", "placement"))
            config.add(key, defaults.get(key));
        return source.withConfig(config);
    }

    public static SchematicDocument empty(BlockPos size, ExportProfile profile) {
        var nbt = new CompoundTag();
        var dimensions = new ListTag();
        dimensions.add(IntTag.valueOf(size.getX()));
        dimensions.add(IntTag.valueOf(size.getY()));
        dimensions.add(IntTag.valueOf(size.getZ()));
        nbt.put("size", dimensions);
        var palette = new ListTag();
        var air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        nbt.put("palette", palette);
        nbt.put("blocks", new ListTag());
        nbt.put("entities", new ListTag());
        nbt.putInt(
                "DataVersion",
                net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        return withProfile(new SchematicDocument(nbt, null), profile, 63);
    }

    public static SchematicDocument withProfile(
            SchematicDocument source, ExportProfile profile, int seaLevel) {
        if (source.hasConfig()) {
            var existing = source.configCopy();
            if (!existing.has(ContraptionImport.REPORT)
                    || java.util.List.of("rarity", "environment", "restrictions", "placement")
                            .stream()
                            .anyMatch(existing::has)) return source;
            var defaults = withProfile(source.withConfig(null), profile, seaLevel).configCopy();
            existing.entrySet().forEach(e -> defaults.add(e.getKey(), e.getValue()));
            return source.withConfig(defaults);
        }
        var config = new JsonObject();
        var rarity = new JsonObject();
        rarity.addProperty("mode", "automatic");
        config.add("rarity", rarity);
        var environment = new JsonObject();
        var restrictions = new JsonObject();
        if (profile == ExportProfile.GROUND) {
            var under = rule("surface", 4);
            var excludes = new JsonArray();
            excludes.add("minecraft:leaves");
            under.add("exclude_tags", excludes);
            environment.add("under", under);
            environment.add("above", rule("air", 8));
        } else if (profile == ExportProfile.AIR) {
            environment.add("under", rule("air", 8));
            environment.add("above", rule("air", 8));
            var ranges = new JsonArray();
            var range = new JsonObject();
            range.addProperty("min", 128);
            ranges.add(range);
            restrictions.add("height_ranges", ranges);
        } else if (profile == ExportProfile.SEA) {
            var under = rule("fluid", 4);
            under.addProperty("fluid", "minecraft:water");
            environment.add("under", under);
        }
        if (profile == ExportProfile.GROUND || profile == ExportProfile.SEA) {
            var biomes = new JsonObject();
            var tags = new JsonArray();
            for (String t :
                    new String[] {"minecraft:is_ocean", "minecraft:is_river", "c:is_aquatic"})
                tags.add(t);
            var denied = new JsonArray();
            denied.add("c:is_swamp");
            if (profile == ExportProfile.SEA) biomes.add("allow_tags", tags);
            else tags.forEach(denied::add);
            biomes.add("forbid_tags", denied);
            restrictions.add("biomes", biomes);
        }
        config.add("environment", environment);
        if (profile != ExportProfile.EMPTY) {
            var dimensions = new JsonObject();
            var allow = new JsonArray();
            allow.add("minecraft:overworld");
            dimensions.add("allow", allow);
            restrictions.add("dimensions", dimensions);
        }
        config.add("restrictions", restrictions);
        config.add("placement", new JsonObject());
        return source.withConfig(config);
    }

    private static JsonObject rule(String type, int depth) {
        var o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("depth", depth);
        return o;
    }

    public static String filename(String name) {
        String n = name.trim();
        if (n.toLowerCase(Locale.ROOT).endsWith(".nbt")) n = n.substring(0, n.length() - 4);
        if (!n.matches("[A-Za-z0-9_-]{1,64}")
                || n.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])"))
            throw new IllegalArgumentException(
                    "Use 1..64 letters, digits, _ or - for the filename.");
        return n + ".nbt";
    }

    public static synchronized String create(
            Path directory, String requested, SchematicDocument document) throws IOException {
        String name = filename(requested);
        Files.createDirectories(directory);
        Path root = directory.toRealPath();
        String base = name.substring(0, name.length() - 4);
        try (var entries = Files.list(root)) {
            if (entries.anyMatch(
                    p ->
                            p.getFileName().toString().equalsIgnoreCase(name)
                                    || p.getFileName().toString().equalsIgnoreCase(base + ".json")))
                throw new IOException("This name already exists. Choose another name.");
        }
        var authorConfig = document.configCopy();
        if (authorConfig != null)
            com.eynny.npships.config.NPShipsStructureConfigLoader.load(authorConfig);
        var authoringTemplate = document.nbtCopy();
        StructureSegments.validate(authoringTemplate, StructureSegments.read(authorConfig));
        if (SchematicExtensions.present(document)
                || authoringTemplate.contains(
                        com.eynny.npships.authoring.document.BlockAreaLayers.KEY))
            SchematicExtensions.validate(document, false, PatchAuthoring.Kind.SAVE);
        byte[] json =
                authorConfig == null
                        ? null
                        : new GsonBuilder()
                                .setPrettyPrinting()
                                .disableHtmlEscaping()
                                .create()
                                .toJson(authorConfig)
                                .getBytes(StandardCharsets.UTF_8);
        if (json != null && json.length > 16 * 1024 * 1024)
            throw new IOException("Config exceeds 16 MiB.");
        Path temp = Files.createTempFile(root, ".npships-new-", ".tmp"), jsonTemp = null;
        Path nbt = root.resolve(name), config = root.resolve(base + ".json");
        try {
            NbtIo.writeCompressed(document.nbtCopy(), temp);
            if (Files.size(temp) > 32 * 1024 * 1024) throw new IOException("NBT exceeds 32 MiB.");
            NbtIo.readCompressed(temp, NbtAccounter.create(SchematicDocument.NBT_MEMORY_LIMIT));
            // No REPLACE_EXISTING, including concurrent external writers.
            if (json == null) {
                // Legacy NBT-only documents remain NBT-only; do not invent spawn settings.
                Files.move(temp, nbt);
            } else {
                jsonTemp = Files.createTempFile(root, ".npships-new-json-", ".tmp");
                Files.write(jsonTemp, json);
                byte[] expected = Files.readAllBytes(temp);
                Files.move(temp, nbt);
                try {
                    Files.move(jsonTemp, config);
                } catch (IOException e) {
                    if (Files.isRegularFile(nbt, LinkOption.NOFOLLOW_LINKS)
                            && Files.size(nbt) == expected.length
                            && java.util.Arrays.equals(Files.readAllBytes(nbt), expected))
                        Files.delete(nbt);
                    throw e;
                }
            }
        } finally {
            Files.deleteIfExists(temp);
            if (jsonTemp != null) Files.deleteIfExists(jsonTemp);
        }
        return name;
    }
}
