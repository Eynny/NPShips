package com.eynny.npships.authoring.io;

import static com.eynny.npships.authoring.io.AddonArchive.MAX_ENTRY;
import static com.eynny.npships.authoring.io.AddonArchive.MAX_TOTAL;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Container checks complement the bounded local-entry reader; nothing is extracted. */
final class AddonZip {
    /** Owned export entries need the same pre-decode limits without reopening a generated ZIP. */
    static void validateEntries(Map<String, byte[]> entries) throws IOException {
        if (entries.size() > AddonArchive.MAX_ENTRIES)
            throw new IOException("Entry count exceeds 4096");
        long total = 0;
        var canonical = new HashSet<String>();
        for (var entry : entries.entrySet()) {
            path(entry.getKey());
            String name = entry.getKey();
            String key = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (!canonical.add(key)) throw new IOException("Canonically colliding entry: " + name);
            total += entry.getValue().length;
            if (entry.getValue().length > MAX_ENTRY || total > MAX_TOTAL)
                throw new IOException("Addon exceeds size limits: " + name);
        }
    }

    static Map<String, byte[]> read(byte[] bytes, String source) throws IOException {
        if (bytes.length > AddonArchive.MAX_JAR)
            throw failure(
                    source,
                    "<container>",
                    "container",
                    new IOException("Compressed size exceeds 64 MiB"));
        // ZipFile requires a seekable file. This private, bounded snapshot also prevents a caller's
        // changing Path from supplying different central-directory and local-entry generations.
        try (var snapshot =
                new Snapshot(Files.createTempFile("npships-addon-validation-", ".zip"))) {
            Files.write(snapshot.path(), bytes);
            return readSnapshot(snapshot.path(), bytes, source);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ArchiveFailure f) throw f;
            throw failure(source, "<container>", "container", e);
        }
    }

    private record Snapshot(Path path) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    private static Map<String, byte[]> readSnapshot(Path temp, byte[] bytes, String source)
            throws IOException {
        var central = new LinkedHashMap<String, ZipEntry>();
        var canonical = new HashSet<String>();
        try (var file = new ZipFile(temp.toFile(), StandardCharsets.UTF_8)) {
            checkEndCount(bytes, file.size(), source);
            var enumeration = file.entries();
            long declaredTotal = 0;
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                String name = entry.getName();
                try {
                    path(name);
                    if (central.size() >= AddonArchive.MAX_ENTRIES)
                        throw new IOException("Entry count exceeds 4096");
                    String key = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                    if (!canonical.add(key) || central.putIfAbsent(name, entry) != null)
                        throw new IOException("Duplicate or canonically colliding entry");
                    declaredTotal += entry.getSize();
                    if (entry.getSize() < 0
                            || entry.getSize() > MAX_ENTRY
                            || declaredTotal > MAX_TOTAL)
                        throw new IOException(
                                "Uncompressed size exceeds 32 MiB per entry / 256 MiB total");
                } catch (IOException e) {
                    throw failure(source, name, "central directory", e);
                }
            }
            var result = new TreeMap<String, byte[]>();
            var seen = new HashSet<String>();
            long total = 0;
            try (var stream =
                    new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                while (true) {
                    ZipEntry local;
                    try {
                        local = stream.getNextEntry();
                    } catch (IOException | RuntimeException e) {
                        throw failure(source, "<local header>", "container", e);
                    }
                    if (local == null) break;
                    String name = local.getName();
                    try {
                        var expected = central.get(name);
                        if (expected == null || !seen.add(name))
                            throw new IOException(
                                    "Local entry missing from central directory or duplicated");
                        byte[] data = stream.readNBytes(MAX_ENTRY + 1);
                        total += data.length;
                        if (data.length > MAX_ENTRY || total > MAX_TOTAL)
                            throw new IOException(
                                    "Uncompressed size exceeds 32 MiB per entry / 256 MiB total");
                        stream.closeEntry(); // validates local CRC and data descriptor
                        var crc = new CRC32();
                        crc.update(data);
                        if (expected.getSize() != data.length
                                || expected.getCrc() != crc.getValue()
                                || expected.getMethod() != local.getMethod()
                                || expected.getCompressedSize() != local.getCompressedSize())
                            throw new IOException("Central/local size, method or CRC mismatch");
                        // Verify ZipFile's actual offset resolution too: enumeration alone does
                        // not prove a central record points to the matching local payload.
                        try (var nativeEntry = file.getInputStream(expected)) {
                            byte[] chunk = new byte[8192];
                            int offset = 0, read;
                            while ((read = nativeEntry.read(chunk)) != -1) {
                                if (offset + read > data.length)
                                    throw new IOException(
                                            "Central entry resolves to different data");
                                for (int i = 0; i < read; i++)
                                    if (chunk[i] != data[offset + i])
                                        throw new IOException(
                                                "Central entry resolves to different data");
                                offset += read;
                            }
                            if (offset != data.length)
                                throw new IOException("Truncated central entry payload");
                        }
                        if (!local.isDirectory()) result.put(name, data);
                    } catch (IOException | RuntimeException e) {
                        throw failure(source, name, "entry integrity", e);
                    }
                }
            }
            if (seen.size() != central.size())
                throw failure(
                        source,
                        "<container>",
                        "central directory",
                        new IOException("Central directory contains missing local entries"));
            return result;
        }
    }

    private static void checkEndCount(byte[] bytes, int actual, String source) throws IOException {
        // JDK ZipFile.initCEN deliberately repairs an incorrect ENDTOT count. The strict authoring
        // importer must report it. ZipFile remains the parser, including ZIP64 record ownership;
        // 0xffff counts are its ZIP64 sentinel, not an alternative entry limit.
        var view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int end = bytes.length - 22; end >= Math.max(0, bytes.length - 65557); end--) {
            if (view.getInt(end) != 0x06054b50
                    || end + 22 + Short.toUnsignedInt(view.getShort(end + 20)) != bytes.length)
                continue;
            int diskCount = Short.toUnsignedInt(view.getShort(end + 8));
            int count = Short.toUnsignedInt(view.getShort(end + 10));
            if (view.getShort(end + 4) != 0
                    || view.getShort(end + 6) != 0
                    || diskCount != count
                    || (count != 0xffff && count != actual))
                throw failure(
                        source,
                        "<container>",
                        "central directory",
                        new IOException(
                                "Inconsistent end-of-central-directory count or unsupported split archive"));
            return;
        }
        throw failure(
                source,
                "<container>",
                "container",
                new IOException("Missing or truncated end-of-central-directory record"));
    }

    static void path(String name) throws IOException {
        if (name.isEmpty()
                || name.length() > 512
                || name.startsWith("/")
                || name.contains("//")
                || name.contains("\\")
                || name.contains(":")
                || name.chars().anyMatch(Character::isISOControl))
            throw new IOException(
                    "Unsafe archive path (no absolute paths, separators or control characters)");
        for (String part : name.split("/"))
            if (part.equals(".") || part.equals(".."))
                throw new IOException("Unsafe dot path component");
    }

    static IOException failure(String source, String entry, String stage, Throwable cause) {
        if (cause instanceof ArchiveFailure qualified) return qualified;
        String detail = cause.getMessage();
        if (detail == null || detail.isBlank()) detail = cause.getClass().getSimpleName();
        String entryText = bounded(entry, 512);
        String stageText = bounded(stage, 200);
        String detailText = bounded(detail, 700);
        String suffix = " | " + entryText + " | " + stageText + ": " + detailText;
        // Short details leave room for the full source path. At the hard bound retain
        // both its root and filename, rather than discarding the identifying basename.
        int sourceBudget = 1600 - "ZIP ".length() - suffix.length();
        return new ArchiveFailure("ZIP " + sourceName(source, sourceBudget) + suffix, cause);
    }

    private static String sourceName(String value, int length) {
        String text = value.replaceAll("[\\p{Cntrl}]", " ");
        if (text.length() <= length) return text;
        int tail = Math.min(160, length / 2);
        return text.substring(0, length - tail - 1) + "…" + text.substring(text.length() - tail);
    }

    private static String bounded(String value, int length) {
        String text = value.replaceAll("[\\p{Cntrl}]", " ");
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }

    private static final class ArchiveFailure extends IOException {
        ArchiveFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private AddonZip() {}
}
