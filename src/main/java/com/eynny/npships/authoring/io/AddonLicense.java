package com.eynny.npships.authoring.io;

import com.eynny.npships.authoring.validation.ValidationProblem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** An explicit export choice, independent of the license of NPShips itself. */
public final class AddonLicense {
    public static final int MAX_TEXT = 128 * 1024;
    private final String id;
    private final Map<String, byte[]> files;

    private AddonLicense(String id, Map<String, byte[]> files) {
        this.id = id;
        var copy = new TreeMap<String, byte[]>();
        files.forEach((name, bytes) -> copy.put(name, bytes.clone()));
        this.files = Collections.unmodifiableMap(copy);
    }

    public static AddonLicense mit(String holder, String year) {
        if (holder == null
                || holder.isBlank()
                || holder.length() > 256
                || holder.chars().anyMatch(Character::isISOControl)
                || holder.contains("<")
                || holder.contains(">"))
            throw ValidationProblem.invalid(
                    "addon_license_holder",
                    "Enter the copyright holder's public name (1..256 characters, one line).");
        if (year == null || !year.matches("[1-9][0-9]{3}"))
            throw ValidationProblem.invalid(
                    "addon_license_year", "Enter a four-digit copyright year.");
        try (var stream = AddonLicense.class.getResourceAsStream("/npships/licenses/MIT.txt")) {
            if (stream == null)
                throw new IllegalStateException("Missing SPDX MIT license template");
            String text =
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("<year>", year)
                            .replace("<copyright holders>", holder.strip());
            return new AddonLicense(
                    "MIT", Map.of("LICENSE", text.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read SPDX MIT license template", e);
        }
    }

    public static AddonLicense custom(Path path) throws IOException {
        // Bounded read even when the file changes after the author selected it.
        try (var stream = Files.newInputStream(path)) {
            return custom(stream.readNBytes(MAX_TEXT + 1));
        }
    }

    public static AddonLicense custom(byte[] text) {
        validateText(text);
        return new AddonLicense("LicenseRef-Custom", Map.of("LICENSE", text));
    }

    /**
     * Add an author-selected third-party notice without replacing existing terms or attribution.
     */
    public AddonLicense withNotice(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            return withNotice(path.getFileName().toString(), stream.readNBytes(MAX_TEXT + 1));
        }
    }

    public AddonLicense withNotice(String filename, byte[] text) {
        validateText(text);
        String leaf = filename.replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (leaf.isBlank() || leaf.equals(".") || leaf.equals("..") || leaf.length() > 128)
            throw new IllegalArgumentException("Choose a short license/notice filename.");
        String name = "licenses/" + leaf;
        var copy = new TreeMap<String, byte[]>(files);
        if (copy.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name)))
            throw new IllegalArgumentException(
                    "A license/notice with this filename is already attached.");
        copy.put(name, text.clone());
        if (id.equals("MIT")) {
            // Only a fresh, explicit MIT choice receives our scope note. Opaque imported terms
            // retain their original meaning and bytes. Never replace an author-selected notice.
            var names = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            names.addAll(copy.keySet());
            String scope = "licenses/addon-license-scope.txt";
            for (int suffix = 2; names.contains(scope); suffix++)
                scope = "licenses/addon-license-scope-" + suffix + ".txt";
            try (var stream =
                    AddonLicense.class.getResourceAsStream("/npships/licenses/ADDON-SCOPE.txt")) {
                if (stream == null)
                    throw new IllegalStateException("Missing addon license scope notice");
                copy.put(scope, stream.readAllBytes());
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read addon license scope notice", error);
            }
        }
        if (copy.size() > 64
                || copy.values().stream().mapToLong(value -> value.length).sum() > 1024 * 1024)
            throw new IllegalArgumentException("License/notice bundle exceeds 64 files or 1 MiB.");
        // The primary text does not grant blanket MIT terms to separately licensed materials.
        return new AddonLicense("LicenseRef-Custom", copy);
    }

    public java.util.List<String> noticeFiles() {
        return files.keySet().stream().filter(name -> !name.equals("LICENSE")).toList();
    }

    public String allTexts() {
        var text = new StringBuilder(preview());
        files.forEach(
                (name, bytes) -> {
                    if (!name.equals("LICENSE"))
                        text.append("\n\n--- ")
                                .append(name)
                                .append(" ---\n")
                                .append(new String(bytes, StandardCharsets.UTF_8));
                });
        return text.toString();
    }

    /** Preserve recognized notices byte-for-byte; this is not a whole-archive re-export. */
    public static AddonLicense existing(AddonArchive.Contents source) {
        return source.metadata().license();
    }

    static AddonLicense fromEntries(Map<String, byte[]> entries) {
        var notices = new TreeMap<String, byte[]>();
        int total = 0;
        for (var entry : entries.entrySet()) {
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            if (!name.matches(
                            "(?:LICENSE|LICENCE|COPYING|NOTICE|COPYRIGHT|AUTHORS|ATTRIBUTION)(?:\\.(?:TXT|MD))?")
                    && !name.startsWith("LICENSES/")) continue;
            try {
                AddonZip.path(entry.getKey());
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Unsafe license/notice path: " + entry.getKey(), e);
            }
            total += entry.getValue().length;
            if (notices.size() >= 64 || total > 1024 * 1024)
                throw new IllegalArgumentException(
                        "Existing license/notice bundle exceeds 64 files or 1 MiB; review the original archive.");
            notices.put(entry.getKey(), entry.getValue());
        }
        if (notices.isEmpty())
            throw new IllegalArgumentException("The addon must include its LICENSE text.");
        byte[] primary = notices.get("LICENSE");
        if (primary == null) {
            primary =
                    notices.entrySet().stream()
                            .filter(
                                    e ->
                                            e.getKey()
                                                    .toUpperCase(Locale.ROOT)
                                                    .matches(
                                                            "(?:LICENSE|LICENCE|COPYING)(?:\\.(?:TXT|MD))?"))
                            .map(Map.Entry::getValue)
                            .findFirst()
                            .orElse(null);
        }
        if (primary == null)
            throw new IllegalArgumentException(
                    "Archive has notices but no identifiable license. Review and retain its terms before exporting.");
        validateText(primary);
        notices.putIfAbsent("LICENSE", primary);
        // Opaque existing terms are never relabelled MIT by inference or by a manifest claim.
        return new AddonLicense("LicenseRef-Custom", notices);
    }

    private static void validateText(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_TEXT)
            throw new IllegalArgumentException(
                    "Choose a nonempty UTF-8 LICENSE file, at most 128 KiB.");
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            if (text.replace("\uFEFF", "").isBlank() || text.indexOf('\0') >= 0)
                throw new IllegalArgumentException("LICENSE must contain readable nonempty text.");
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "LICENSE must be valid UTF-8; convert an author-owned copy explicitly.", e);
        }
    }

    public String id() {
        return id;
    }

    public String preview() {
        return new String(files.get("LICENSE"), StandardCharsets.UTF_8);
    }

    void appendTo(Map<String, byte[]> entries) throws IOException {
        for (var entry : files.entrySet()) {
            if (entries.putIfAbsent(entry.getKey(), entry.getValue().clone()) != null)
                throw new IOException(
                        "License/notice would overwrite export entry: " + entry.getKey());
        }
    }
}
