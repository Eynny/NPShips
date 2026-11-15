package com.eynny.npships.integration.computercraft;

import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.OptionalCalls;
import com.google.gson.*;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Copies only the computer's own writable disk. IDs are allocated by CC's own allocator. */
public final class ComputerPatch {
    public static final int MAX_BYTES = 8 * 1024 * 1024, MAX_FILES = 512;

    private ComputerPatch() {}

    public static Path disk(ServerLevel level, int id) {
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("computercraft/computer/" + id);
    }

    public static JsonObject capture(ServerLevel level, int id) throws Exception {
        var files = new JsonObject();
        if (id < 0) return files;
        Path root = disk(level, id);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return files;
        if (Files.isSymbolicLink(root))
            throw new IllegalArgumentException("Computer disk symlinks are not exportable.");
        int total = 0, count = 0;
        try (var walk = capturePaths(root)) {
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                var path = iterator.next();
                if (Files.isSymbolicLink(path))
                    throw new IllegalArgumentException(
                            "Computer disk contains a symlink: " + path.getFileName());
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (root.relativize(path).toString().equals("npships_ids.lua")) continue;
                long bytes = Files.size(path);
                if (bytes > MAX_BYTES || total + bytes > MAX_BYTES || ++count > MAX_FILES)
                    throw new IllegalArgumentException(
                            "Computer export exceeds 8 MiB / 512 files.");
                byte[] contents = captureContents(path, MAX_BYTES - total);
                total += contents.length;
                files.addProperty(
                        root.relativize(path).toString().replace('\\', '/'),
                        Base64.getEncoder().encodeToString(contents));
            }
        }
        return files;
    }

    /** Reject unvisited subtrees instead of exporting a silently incomplete disk. */
    static java.util.stream.Stream<Path> capturePaths(Path root) throws java.io.IOException {
        return Files.walk(root, 32)
                .peek(
                        path -> {
                            if (root.relativize(path).getNameCount() >= 32
                                    && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                                throw new IllegalArgumentException(
                                        "Computer disk directory nesting exceeds 31 levels: "
                                                + root.relativize(path));
                        });
    }

    /** File metadata can race a CC writer; bound the actual read before encoding/retaining it. */
    static byte[] captureContents(Path path, int remaining) throws java.io.IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return captureContents(input, remaining);
        }
    }

    static byte[] captureContents(java.io.InputStream input, int remaining)
            throws java.io.IOException {
        if (remaining < 0 || remaining > MAX_BYTES)
            throw new IllegalArgumentException("Invalid computer capture allowance");
        var contents = input.readNBytes(remaining + 1);
        if (contents.length > remaining)
            throw new IllegalArgumentException("Computer file exceeds remaining export allowance");
        return contents;
    }

    private static String generatedMap(Path path) throws java.io.IOException {
        // Match Files.readString's strict UTF-8 contract; corrupt programs must not be rewritten.
        return StandardCharsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(captureContents(path, 262144)))
                .toString();
    }

    public static void validate(JsonObject files) {
        if (files == null)
            throw new IllegalArgumentException("Computer patch requires files object.");
        if (files.size() > MAX_FILES)
            throw new IllegalArgumentException("Too many computer files.");
        ComputerFiles.validatePaths(files.keySet(), true);
        int total = 0;
        for (var e : files.entrySet()) {
            if (e.getKey().equals("npships_ids.lua"))
                throw new IllegalArgumentException(
                        "npships_ids.lua is reserved for the generated address map.");
            relative(e.getKey());
            String text = e.getValue().getAsString();
            if (text.length() > MAX_BYTES * 2)
                throw new IllegalArgumentException("Computer file too large.");
            total += Base64.getDecoder().decode(text).length;
            if (total > MAX_BYTES)
                throw new IllegalArgumentException("Computer files exceed 8 MiB.");
        }
    }

    public static Path relative(String value) {
        if (value.isBlank()
                || value.contains("\\")
                || value.contains(":")
                || value.startsWith("/")
                || Arrays.stream(value.split("/", -1))
                        .anyMatch(
                                p ->
                                        p.isBlank()
                                                || p.equals(".")
                                                || p.equals("..")
                                                || p.endsWith(".")
                                                || p.endsWith(" ")
                                                || p.matches(
                                                        "(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")))
            throw new IllegalArgumentException("Unsafe computer file path: " + value);
        return Path.of(value);
    }

    public static int allocate(ServerLevel level, String category) throws Exception {
        return (int)
                OptionalCalls.call(
                        Class.forName("dan200.computercraft.api.ComputerCraftAPI"),
                        "createUniqueNumberedSaveDir",
                        level.getServer(),
                        category);
    }

    public static void validatePatch(JsonObject patch) {
        validate(patch.getAsJsonObject("files"));
        if (!patch.has("old_id") || !validId(patch.get("old_id"), -1))
            throw new IllegalArgumentException("Invalid old computer ID.");
        if (patch.has("aliases")) {
            var aliases = patch.getAsJsonObject("aliases");
            var computers = aliases.getAsJsonArray("computers");
            var peripherals = aliases.getAsJsonObject("peripherals");
            if (computers == null
                    || peripherals == null
                    || computers.size() > 2048
                    || peripherals.size() > 4096)
                throw new IllegalArgumentException("Invalid address aliases.");
            for (var id : computers)
                if (!validId(id, 0)) throw new IllegalArgumentException("Invalid computer alias.");
            for (var e : peripherals.entrySet())
                if (!e.getKey().matches("[a-zA-Z0-9_.:-]+")
                        || !e.getValue().getAsString().matches("[a-zA-Z0-9_.:-]+"))
                    throw new IllegalArgumentException("Invalid peripheral alias.");
        }
    }

    private static boolean validId(JsonElement value, int minimum) {
        try {
            return com.eynny.npships.authoring.validation.JsonNumbers.integer(value, "computer ID")
                    >= minimum;
        } catch (com.google.gson.JsonParseException invalid) {
            return false;
        }
    }

    /** Completeness is checked on export/spawn; an editing draft may have removed references. */
    public static void validateReferences(JsonObject patch) {
        var refs = patch.has("references") ? patch.getAsJsonObject("references") : new JsonObject();
        for (var file : patch.getAsJsonObject("files").entrySet())
            if (file.getKey().endsWith(".lua")) {
                String text =
                        new String(
                                Base64.getDecoder().decode(file.getValue().getAsString()),
                                StandardCharsets.UTF_8);
                var named =
                        java.util.regex.Pattern.compile(
                                        "(?:npships_(id|peripheral)\\(\"|require\\(\"npships_ids\"\\)\\.(computers|peripherals)\\[\")([a-zA-Z_][a-zA-Z0-9_]{0,63})\"")
                                .matcher(text);
                while (named.find()) {
                    String name = named.group(3);
                    boolean peripheral =
                            "peripheral".equals(named.group(1))
                                    || "peripherals".equals(named.group(2));
                    if (!refs.has(name) && !(peripheral && name.matches(".*_\\d+")))
                        throw new IllegalArgumentException(
                                "Missing named reference '" + name + "' in " + file.getKey());
                }
            }
    }

    public static JsonObject capturedReferences(
            ServerLevel level,
            int id,
            net.minecraft.core.BlockPos owner,
            com.eynny.npships.authoring.document.SchematicDocument doc,
            JsonArray patches)
            throws Exception {
        var refs = new JsonObject();
        Path module = disk(level, id).resolve("npships_ids.lua");
        if (id < 0 || !Files.isRegularFile(module, LinkOption.NOFOLLOW_LINKS)) return refs;
        if (Files.isSymbolicLink(module) || Files.size(module) > 262144)
            throw new IllegalArgumentException("Invalid generated address map.");
        String text = generatedMap(module);
        var named = namedKeys(text);
        var ids =
                java.util.regex.Pattern.compile("\\[\"([a-zA-Z_][a-zA-Z0-9_]{0,63})\"\\]=(\\d+),")
                        .matcher(text);
        while (ids.find()) {
            if (!named.contains(ids.group(1))) continue;
            int targetId = Integer.parseInt(ids.group(2));
            net.minecraft.core.BlockPos target = null;
            for (var value : patches) {
                var patch = value.getAsJsonObject();
                if (!com.eynny.npships.authoring.patches.ExternalPatches.external(patch)
                        && patch.has("kind")
                        && patch.get("kind").getAsString().equals("computer")
                        && patch.has("old_id")
                        && patch.get("old_id").getAsInt() == targetId)
                    target = SchematicPatches.position(patch.getAsJsonArray("pos"));
            }
            if (target == null)
                throw new IllegalArgumentException(
                        "Named computer reference leaves export: " + ids.group(1));
            refs.add(ids.group(1), PatchDevices.endpoint(doc, owner, target));
        }
        var names =
                java.util.regex.Pattern.compile(
                                "\\[\"([a-zA-Z_][a-zA-Z0-9_]{0,63})\"\\]=\"([^\"]+)\",")
                        .matcher(text);
        while (names.find()) {
            String name = names.group(1);
            if (!named.contains(name)) continue;
            net.minecraft.core.BlockPos target = null;
            for (var p : doc.blocks().keySet())
                if (hasPeripheral(doc.blockEntityData(p), names.group(2))) {
                    if (target != null)
                        throw new IllegalArgumentException("Ambiguous named peripheral: " + name);
                    target = p;
                }
            if (target == null) {
                var local = ComputerSides.target(doc, owner, names.group(2));
                if (local != null
                        && doc.contains(local)
                        && !PatchDevices.kind(doc, local).isEmpty()) target = local;
            }
            if (target == null)
                throw new IllegalArgumentException(
                        "Named peripheral reference leaves export: " + name);
            refs.add(name, PatchDevices.endpoint(doc, owner, target));
        }
        return refs;
    }

    private static Set<String> namedKeys(String text) {
        var names = new HashSet<String>();
        var m =
                java.util.regex.Pattern.compile(
                                "-- npships-reference ([a-zA-Z_][a-zA-Z0-9_]{0,63})")
                        .matcher(text);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static boolean hasPeripheral(CompoundTag data, String name) {
        for (String key : data.getAllKeys()) {
            if (key.startsWith("PeripheralId")
                    && (data.getString("PeripheralType" + key.substring("PeripheralId".length()))
                                    + "_"
                                    + data.getInt(key))
                            .equals(name)) return true;
            if (data.get(key) instanceof CompoundTag nested && hasPeripheral(nested, name))
                return true;
        }
        return false;
    }

    public static JsonObject aliases(ServerLevel level, int id) throws Exception {
        var result = new JsonObject();
        var computers = new JsonArray();
        var peripherals = new JsonObject();
        Path module = disk(level, id).resolve("npships_ids.lua");
        if (id >= 0 && Files.isRegularFile(module, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(module) > 262144)
                throw new IllegalArgumentException("Generated ID map is unexpectedly large.");
            String text = generatedMap(module);
            var named = namedKeys(text);
            var numbers = java.util.regex.Pattern.compile("\\[(\\d+)\\]=(\\d+),").matcher(text);
            while (numbers.find())
                if (Integer.parseInt(numbers.group(2)) == id)
                    computers.add(Integer.parseInt(numbers.group(1)));
            var names =
                    java.util.regex.Pattern.compile(
                                    "\\[\"([a-zA-Z0-9_.:-]+)\"\\]=\"([a-zA-Z0-9_.:-]+)\",")
                            .matcher(text);
            while (names.find())
                if (!named.contains(names.group(1)))
                    peripherals.addProperty(names.group(1), names.group(2));
        }
        result.add("computers", computers);
        result.add("peripherals", peripherals);
        return result;
    }

    public static void preflightPrograms(
            JsonObject files, Map<Integer, Integer> computers, Map<String, String> peripherals) {
        for (var e : files.entrySet())
            if (e.getKey().endsWith(".lua")) {
                var text =
                        new String(
                                Base64.getDecoder().decode(e.getValue().getAsString()),
                                StandardCharsets.UTF_8);
                var ids =
                        java.util.regex.Pattern.compile(
                                        "(?:npships_id\\(\\s*|require\\(\"npships_ids\"\\)\\.computers\\[)(\\d+)")
                                .matcher(text);
                while (ids.find())
                    if (!computers.containsKey(Integer.parseInt(ids.group(1))))
                        throw new IllegalArgumentException(
                                "Lua references an uncaptured computer: " + ids.group(1));
                var names =
                        java.util.regex.Pattern.compile(
                                        "(?:npships_peripheral\\(|require\\(\"npships_ids\"\\)\\.peripherals\\[)\"([^\"]+)\"")
                                .matcher(text);
                while (names.find())
                    if (!peripherals.containsKey(names.group(1)))
                        throw new IllegalArgumentException(
                                "Lua references an uncaptured peripheral: " + names.group(1));
            }
    }

    public static void write(
            ServerLevel level,
            int id,
            JsonObject files,
            Map<Integer, Integer> computers,
            Map<String, String> peripherals)
            throws Exception {
        write(level, id, files, computers, peripherals, Map.of(), Map.of());
    }

    public static JsonObject namedPrograms(
            JsonObject files, Map<String, Integer> computers, Map<String, String> peripherals) {
        var result = files.deepCopy();
        for (var entry : files.entrySet())
            if (entry.getKey().endsWith(".lua")) {
                byte[] bytes = Base64.getDecoder().decode(entry.getValue().getAsString());
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (!Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))) continue;
                for (var name : computers.keySet())
                    text =
                            text.replace(
                                    "npships_id(\"" + name + "\")",
                                    "require(\"npships_ids\").computers[\"" + name + "\"]");
                for (var name : peripherals.keySet())
                    text =
                            text.replace(
                                    "npships_peripheral(\"" + name + "\")",
                                    "require(\"npships_ids\").peripherals[\"" + name + "\"]");
                var existing =
                        java.util.regex.Pattern.compile(
                                        "require\\(\"npships_ids\"\\)\\.(computers|peripherals)\\[\"([a-zA-Z_][a-zA-Z0-9_]{0,63})\"\\]")
                                .matcher(text);
                while (existing.find())
                    if (!(existing.group(1).equals("computers") ? computers : peripherals)
                            .containsKey(existing.group(2)))
                        throw new IllegalArgumentException(
                                "Missing named reference: " + existing.group(2));
                if (text.contains("npships_id(\"") || text.contains("npships_peripheral(\""))
                    throw new IllegalArgumentException(
                            "Unresolved named device reference in " + entry.getKey());
                result.addProperty(
                        entry.getKey(),
                        Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
            }
        return result;
    }

    public static void write(
            ServerLevel level,
            int id,
            JsonObject files,
            Map<Integer, Integer> computers,
            Map<String, String> peripherals,
            Map<String, Integer> namedComputers,
            Map<String, String> namedPeripherals)
            throws Exception {
        try (var transaction = new ComputerFiles()) {
            write(
                    level,
                    id,
                    files,
                    computers,
                    peripherals,
                    namedComputers,
                    namedPeripherals,
                    transaction);
            transaction.commit();
        }
    }

    public static void write(
            ServerLevel level,
            int id,
            JsonObject files,
            Map<Integer, Integer> computers,
            Map<String, String> peripherals,
            Map<String, Integer> namedComputers,
            Map<String, String> namedPeripherals,
            ComputerFiles transaction)
            throws Exception {
        validate(files);
        var allNames = new HashMap<>(peripherals);
        allNames.putAll(namedPeripherals);
        files = namedPrograms(files, namedComputers, allNames);
        var contents = new LinkedHashMap<Path, byte[]>();
        // Decode/translate the complete proposed tree before the first directory or file write.
        for (var e : files.entrySet()) {
            byte[] bytes = Base64.getDecoder().decode(e.getValue().getAsString());
            if (e.getKey().endsWith(".lua")) {
                String original = new String(bytes, StandardCharsets.UTF_8);
                if (Arrays.equals(bytes, original.getBytes(StandardCharsets.UTF_8))) {
                    // Explicit author opt-in expressions only. No replacement of arbitrary numeric
                    // literals.
                    var pattern = java.util.regex.Pattern.compile("npships_id\\(\\s*(\\d+)\\s*\\)");
                    var matcher = pattern.matcher(original);
                    var out = new StringBuilder();
                    while (matcher.find()) {
                        int old = Integer.parseInt(matcher.group(1));
                        if (!computers.containsKey(old))
                            throw new IllegalArgumentException(
                                    "Lua references an uncaptured computer: " + old);
                        matcher.appendReplacement(
                                out, "require(\"npships_ids\").computers[" + old + "]");
                    }
                    matcher.appendTail(out);
                    String result = out.toString();
                    for (var address : peripherals.entrySet())
                        result =
                                result.replace(
                                        "npships_peripheral(\"" + address.getKey() + "\")",
                                        "require(\"npships_ids\").peripherals[\""
                                                + address.getKey()
                                                + "\"]");
                    if (result.contains("npships_peripheral(\""))
                        throw new IllegalArgumentException(
                                "Lua references an uncaptured peripheral.");
                    bytes = result.getBytes(StandardCharsets.UTF_8);
                }
            }
            contents.put(relative(e.getKey()), bytes);
        }
        StringBuilder map = new StringBuilder();
        var names = new TreeSet<String>(namedComputers.keySet());
        names.addAll(namedPeripherals.keySet());
        for (String name : names) map.append("-- npships-reference ").append(name).append("\n");
        map.append("return { computers = {\n");
        computers.forEach(
                (old, next) -> map.append("[").append(old).append("]=").append(next).append(",\n"));
        namedComputers.forEach(
                (name, next) ->
                        map.append("[\"").append(name).append("\"]=").append(next).append(",\n"));
        map.append("}, peripherals = {\n");
        peripherals.forEach(
                (old, next) ->
                        map.append("[\"")
                                .append(old)
                                .append("\"]=\"")
                                .append(next)
                                .append("\",\n"));
        namedPeripherals.forEach(
                (name, next) ->
                        map.append("[\"")
                                .append(name)
                                .append("\"]=\"")
                                .append(next)
                                .append("\",\n"));
        map.append("} }\n");
        contents.put(Path.of("npships_ids.lua"), map.toString().getBytes(StandardCharsets.UTF_8));
        transaction.write(disk(level, id), contents);
    }

    public static void peripheralIds(
            CompoundTag data, ServerLevel level, Map<String, String> mapping) throws Exception {
        for (String key : List.copyOf(data.getAllKeys())) {
            if (key.startsWith("PeripheralId") && data.getInt(key) >= 0) {
                String suffix = key.substring("PeripheralId".length()),
                        type = data.getString("PeripheralType" + suffix);
                if (!type.matches("[a-zA-Z0-9_.:-]+"))
                    throw new IllegalArgumentException("Unknown peripheral type: " + type);
                String old = type + "_" + data.getInt(key);
                int next = allocate(level, "peripheral." + type);
                data.putInt(key, next);
                if (mapping.putIfAbsent(old, type + "_" + next) != null)
                    throw new IllegalArgumentException(
                            "Ambiguous duplicate peripheral address: " + old);
            } else if (data.get(key) instanceof CompoundTag nested)
                peripheralIds(nested, level, mapping);
        }
    }
}
