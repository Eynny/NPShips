package com.eynny.npships.ship;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.resources.ResourceMemoryBudget;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Per-entry bounds precede native decoding; aggregate refusal aborts the replacement generation.
 */
final class CatalogueInput {
    // Scratch size, not an admission limit. Avoid full decoder/read buffers for tiny entries.
    private static final int SMALL_INPUT_BYTES = 1024;

    private CatalogueInput() {}

    static byte[] nbt(InputStream stream) throws IOException {
        return nbt(stream, null);
    }

    static byte[] nbt(InputStream stream, CatalogueResources.Generation resources)
            throws IOException {
        int nativeLimit = Math.toIntExact(SchematicDocument.NBT_MEMORY_LIMIT);
        int limit =
                resources == null
                        ? nativeLimit
                        : (int) Math.min(nativeLimit, resources.available());
        byte[] prefix = stream.readNBytes(Math.min(SMALL_INPUT_BYTES, limit + 1));
        if (resources != null) resources.check(prefix.length);
        if (prefix.length < SMALL_INPUT_BYTES) return prefix;
        var prefixed = new PushbackInputStream(stream, prefix.length);
        prefixed.unread(prefix);
        stream = prefixed;
        byte[] bytes = stream.readNBytes(limit + 1);
        if (resources != null) resources.check(bytes.length);
        if (bytes.length > nativeLimit)
            throw new IOException("Compressed NBT exceeds " + limit + " bytes");
        return bytes;
    }

    static String json(InputStream stream) throws IOException {
        return json(stream, null);
    }

    static String json(InputStream stream, CatalogueResources.Generation resources)
            throws IOException {
        byte[] prefix = stream.readNBytes(SMALL_INPUT_BYTES);
        if (prefix.length < SMALL_INPUT_BYTES) {
            String result = new String(prefix, StandardCharsets.UTF_8);
            if (resources != null) resources.check(CatalogueResources.text(result));
            return result;
        }
        var prefixed = new PushbackInputStream(stream, prefix.length);
        prefixed.unread(prefix);
        stream = prefixed;
        // Same text envelope as the existing full-config editor, measured in UTF-16 chars.
        int nativeLimit = 16 * 1024 * 1024;
        int limit =
                resources == null
                        ? nativeLimit
                        : (int) Math.min(nativeLimit, resources.available() / Character.BYTES);
        var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        var text = new StringBuilder();
        var buffer = new char[8192];
        for (int count;
                (count = reader.read(buffer, 0, Math.min(buffer.length, limit - text.length() + 1)))
                        >= 0; ) {
            if (resources != null)
                resources.check(Math.multiplyExact((long) text.length() + count, Character.BYTES));
            if (count > nativeLimit - text.length())
                throw new IOException("Config JSON exceeds " + nativeLimit + " characters");
            text.append(buffer, 0, count);
        }
        return text.toString();
    }

    /** Owned buffers reserve before allocation, including old/new resize and result overlap. */
    static byte[] nbt(
            InputStream stream,
            CatalogueResources.Generation resources,
            ResourceMemoryBudget.Scope scratch)
            throws IOException {
        if (scratch == null) return nbt(stream, resources);
        // Only this exact JDK type guarantees available() is its complete remaining payload.
        // Do not trust available() as a source length for arbitrary mod/resource streams.
        if (stream.getClass() == java.io.ByteArrayInputStream.class
                && stream.available() < SMALL_INPUT_BYTES) {
            int count = stream.available();
            if (resources != null) resources.check(count);
            scratch.reserve(ResourceMemoryBudget.arrayBytes(count, Byte.BYTES));
            return stream.readNBytes(count);
        }
        int nativeLimit = Math.toIntExact(SchematicDocument.NBT_MEMORY_LIMIT);
        int limit =
                resources == null
                        ? nativeLimit
                        : (int) Math.min(nativeLimit, resources.available());
        int capacity = Math.min(SMALL_INPUT_BYTES, limit + 1);
        scratch.reserve(ResourceMemoryBudget.arrayBytes(capacity, Byte.BYTES));
        byte[] buffer = new byte[capacity];
        int count = 0;
        while (true) {
            if (count == buffer.length) {
                int next = stream.read();
                if (next < 0) break;
                checkNbtSize(count + 1, nativeLimit, resources);
                int growth =
                        Math.toIntExact(
                                Math.min(
                                        (long) limit + 1,
                                        Math.max((long) count + 1, (long) buffer.length * 2)));
                scratch.reserve(ResourceMemoryBudget.arrayBytes(growth, Byte.BYTES));
                int previous = buffer.length;
                buffer = java.util.Arrays.copyOf(buffer, growth);
                scratch.release(ResourceMemoryBudget.arrayBytes(previous, Byte.BYTES));
                buffer[count++] = (byte) next;
            } else {
                int read = stream.read(buffer, count, buffer.length - count);
                if (read < 0) break;
                if (read == 0) {
                    int next = stream.read();
                    if (next < 0) break;
                    buffer[count++] = (byte) next;
                } else count += read;
                checkNbtSize(count, nativeLimit, resources);
            }
        }
        if (count != buffer.length) {
            scratch.reserve(ResourceMemoryBudget.arrayBytes(count, Byte.BYTES));
            int previous = buffer.length;
            buffer = java.util.Arrays.copyOf(buffer, count);
            scratch.release(ResourceMemoryBudget.arrayBytes(previous, Byte.BYTES));
        }
        return buffer;
    }

    private static void checkNbtSize(
            int count, int nativeLimit, CatalogueResources.Generation resources)
            throws IOException {
        if (resources != null) resources.check(count);
        if (count > nativeLimit)
            throw new IOException("Compressed NBT exceeds " + nativeLimit + " bytes");
    }

    static String json(
            InputStream stream,
            CatalogueResources.Generation resources,
            ResourceMemoryBudget.Scope scratch)
            throws IOException {
        if (scratch == null) return json(stream, resources);
        if (stream.getClass() == java.io.ByteArrayInputStream.class
                && stream.available() < SMALL_INPUT_BYTES) {
            int count = stream.available();
            long inputCapacity = ResourceMemoryBudget.arrayBytes(count, Byte.BYTES);
            scratch.reserve(inputCapacity);
            byte[] bytes = stream.readNBytes(count);
            scratch.reserve(ResourceMemoryBudget.arrayBytes(count, Character.BYTES));
            String result = new String(bytes, StandardCharsets.UTF_8);
            if (resources != null) resources.check(CatalogueResources.text(result));
            bytes = null;
            scratch.release(inputCapacity);
            return result;
        }
        long prefixCapacity = ResourceMemoryBudget.arrayBytes(SMALL_INPUT_BYTES, Byte.BYTES);
        scratch.reserve(prefixCapacity);
        byte[] prefix = new byte[SMALL_INPUT_BYTES];
        int count = 0;
        while (count < prefix.length) {
            int read = stream.read(prefix, count, prefix.length - count);
            if (read < 0) break;
            if (read == 0) {
                int next = stream.read();
                if (next < 0) break;
                prefix[count++] = (byte) next;
            } else count += read;
        }
        if (count < prefix.length) {
            // UTF-8 has at most one output UTF-16 unit per input byte. This content-capacity
            // bound does not claim ownership of Charset/String native implementation scratch.
            long resultCapacity = ResourceMemoryBudget.arrayBytes(count, Character.BYTES);
            scratch.reserve(resultCapacity);
            String result = new String(prefix, 0, count, StandardCharsets.UTF_8);
            if (resources != null) resources.check(CatalogueResources.text(result));
            prefix = null;
            scratch.release(prefixCapacity);
            return result;
        }
        // PushbackInputStream's declared buffer is known and admitted before construction.
        scratch.reserve(prefixCapacity);
        var prefixed = new PushbackInputStream(stream, SMALL_INPUT_BYTES);
        prefixed.unread(prefix);
        prefix = null;
        scratch.release(prefixCapacity);
        var reader = new InputStreamReader(prefixed, StandardCharsets.UTF_8);
        int nativeLimit = 16 * 1024 * 1024;
        int limit =
                resources == null
                        ? nativeLimit
                        : (int) Math.min(nativeLimit, resources.available() / Character.BYTES);
        int capacity = Math.min(8192, limit + 1);
        scratch.reserve(ResourceMemoryBudget.arrayBytes(capacity, Character.BYTES));
        char[] text = new char[capacity];
        count = 0;
        while (true) {
            if (count == text.length) {
                int next = reader.read();
                if (next < 0) break;
                checkJsonSize(count + 1, nativeLimit, resources);
                int growth =
                        Math.toIntExact(
                                Math.min(
                                        (long) limit + 1,
                                        Math.max((long) count + 1, (long) text.length * 2)));
                scratch.reserve(ResourceMemoryBudget.arrayBytes(growth, Character.BYTES));
                int previous = text.length;
                text = java.util.Arrays.copyOf(text, growth);
                scratch.release(ResourceMemoryBudget.arrayBytes(previous, Character.BYTES));
                text[count++] = (char) next;
            } else {
                int read = reader.read(text, count, text.length - count);
                if (read < 0) break;
                if (read == 0) continue;
                count += read;
                checkJsonSize(count, nativeLimit, resources);
            }
        }
        scratch.reserve(ResourceMemoryBudget.arrayBytes(count, Character.BYTES));
        String result = new String(text, 0, count);
        int previous = text.length;
        text = null;
        scratch.release(ResourceMemoryBudget.arrayBytes(previous, Character.BYTES));
        reader = null;
        prefixed = null;
        scratch.release(prefixCapacity);
        return result;
    }

    private static void checkJsonSize(
            int count, int nativeLimit, CatalogueResources.Generation resources)
            throws IOException {
        if (resources != null) resources.check(Math.multiplyExact((long) count, Character.BYTES));
        if (count > nativeLimit)
            throw new IOException("Config JSON exceeds " + nativeLimit + " characters");
    }
}
