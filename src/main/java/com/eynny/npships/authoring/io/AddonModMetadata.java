package com.eynny.npships.authoring.io;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One current data-only mod descriptor. No language loader or addon code is executed here. */
final class AddonModMetadata {
    static final String PATH = "META-INF/neoforge.mods.toml";

    record Parsed(AddonArchive.Metadata metadata, List<AddonDependency> dependencies) {}

    static byte[] write(
            AddonArchive.Metadata meta, List<AddonDependency> dependencies, boolean hasIcon) {
        StringBuilder text =
                new StringBuilder(
                        "modLoader=\"lowcodefml\"\nloaderVersion=\"[4,)\"\nshowAsDataPack=false\n");
        text.append("license=")
                .append(quote(meta.license().id()))
                .append("\n[[mods]]\nmodId=")
                .append(quote(meta.id()))
                .append("\nversion=")
                .append(quote(meta.version()))
                .append("\ndisplayName=")
                .append(quote(meta.name()))
                .append("\nauthors=")
                .append(quote(meta.author()))
                .append("\ndescription=")
                .append(quote(meta.description()))
                .append('\n');
        if (hasIcon) text.append("logoFile=\"pack.png\"\n");
        for (var d : dependencies)
            text.append("\n[[dependencies.")
                    .append(meta.id())
                    .append("]]\nmodId=")
                    .append(quote(d.modId()))
                    .append("\ntype=")
                    .append(quote(d.required() ? "required" : "optional"))
                    .append("\nversionRange=")
                    .append(quote(d.versionRange()))
                    .append("\nordering=\"NONE\"\nside=\"BOTH\"\n");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Parsed read(Map<String, byte[]> entries) throws IOException {
        byte[] bytes = entries.get(PATH);
        if (bytes == null || bytes.length > 128 * 1024)
            throw new IOException("Missing or oversized mod metadata: " + PATH);
        try {
            var root =
                    new TomlParser()
                            .parse(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
            if (!"lowcodefml".equals(root.get("modLoader"))
                    || root.contains("mixins")
                    || Boolean.TRUE.equals(root.get("showAsDataPack")))
                throw new IllegalArgumentException(
                        "Expected a data-only addon mod without executable hooks or a selectable data pack.");
            var mods = root.get("mods");
            if (!(mods instanceof List<?> list)
                    || list.size() != 1
                    || !(list.getFirst() instanceof UnmodifiableConfig mod))
                throw new IllegalArgumentException(
                        "An addon JAR must have exactly one actual mod owner.");
            String owner = string(mod, "modId");
            string(root, "license");
            var meta =
                    new AddonArchive.Metadata(
                            owner,
                            string(mod, "displayName"),
                            string(mod, "version"),
                            string(mod, "authors"),
                            string(mod, "description"),
                            AddonLicense.fromEntries(entries));
            var result = new ArrayList<AddonDependency>();
            Object declarations = root.get(List.of("dependencies", owner));
            if (!(declarations instanceof List<?> dependencies))
                throw new IllegalArgumentException("Missing loader-checked dependencies.");
            for (var value : dependencies) {
                if (!(value instanceof UnmodifiableConfig dep))
                    throw new IllegalArgumentException("Invalid dependency declaration.");
                String type = string(dep, "type");
                if (!Set.of("required", "optional").contains(type))
                    throw new IllegalArgumentException(
                            "Dependency type must be required or optional.");
                String side = dep.getOptional("side").map(Object::toString).orElse("BOTH");
                if (!side.equals("BOTH"))
                    throw new IllegalArgumentException(
                            "Data addon dependencies must apply to both sides.");
                result.add(
                        new AddonDependency(
                                string(dep, "modId"),
                                type.equals("required"),
                                string(dep, "versionRange")));
            }
            return new Parsed(meta, validate(owner, result));
        } catch (RuntimeException e) {
            throw new IOException("Invalid addon mod metadata: " + e.getMessage(), e);
        }
    }

    static List<AddonDependency> validate(String owner, List<AddonDependency> declarations) {
        if (declarations.size() > 128)
            throw new IllegalArgumentException("At most 128 dependencies are supported.");
        var values = new TreeMap<String, AddonDependency>();
        for (var d : declarations) {
            if (d.modId().equals(owner) || values.putIfAbsent(d.modId(), d) != null)
                throw new IllegalArgumentException("Duplicate or self dependency: " + d.modId());
        }
        for (String id : List.of("minecraft", "neoforge", "npships"))
            if (!values.containsKey(id) || !values.get(id).required())
                throw new IllegalArgumentException("Required platform dependency missing: " + id);
        return List.copyOf(values.values());
    }

    private static String string(UnmodifiableConfig config, String key) {
        Object value = config.get(key);
        if (!(value instanceof String text)
                || text.isBlank()
                || text.length() > 256
                || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid mod metadata field: " + key);
        return text;
    }

    private static String quote(String value) {
        return new Gson().toJson(value);
    }

    private AddonModMetadata() {}
}
