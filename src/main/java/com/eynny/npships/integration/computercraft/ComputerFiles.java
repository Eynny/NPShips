package com.eynny.npships.integration.computercraft;

import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;

/** In-process ownership of fresh CC files before the first possible native placement. */
public final class ComputerFiles implements AutoCloseable {
    private record Identity(Object key, FileTime created) {
        static Identity read(Path path, boolean directory) throws IOException {
            var attributes =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()
                    || attributes.isOther()
                    || (directory ? !attributes.isDirectory() : !attributes.isRegularFile()))
                throw new IOException("Unsafe computer destination type: " + path);
            // NOFOLLOW attributes reject links/reparse nodes; Windows native ID lookup also
            // opens the final node with OPEN_REPARSE_POINT and verifies its native type.
            Object key = attributes.fileKey();
            if (key == null) key = ComputerFileIdentity.read(path, directory);
            if (key == null)
                throw new IOException("Computer destination has no stable file identity: " + path);
            return new Identity(key, attributes.creationTime());
        }
    }

    private static final class File {
        final Path path;
        final Identity identity;
        byte[] digest;
        long length;
        FileTime modified;

        File(Path path, Identity identity) {
            this.path = path;
            this.identity = identity;
            digest = digest().digest();
        }
    }

    private final Map<Path, Identity> guards = new LinkedHashMap<>();
    private final Set<Path> roots = new HashSet<>();
    private final List<Path> directories = new ArrayList<>();
    private final List<File> files = new ArrayList<>();
    private final List<String> cleanupFailures = new ArrayList<>();
    private int cleanupFailureCount;
    private boolean committed, closed;

    @FunctionalInterface
    interface WriteOperation {
        int write(FileChannel channel, ByteBuffer source) throws IOException;
    }

    private final WriteOperation writer;

    public ComputerFiles() {
        this(FileChannel::write);
    }

    ComputerFiles(WriteOperation writer) {
        this.writer = Objects.requireNonNull(writer);
    }

    private static final class PathNode {
        TreeMap<String, PathNode> children;
        boolean file;
    }

    /** A file cannot also be a parent, including case aliases on supported Windows storage. */
    static void validatePaths(Collection<String> paths, boolean computerMap) {
        var root = new PathNode();
        if (computerMap) addPath(root, "npships_ids.lua");
        for (String path : paths) addPath(root, path);
    }

    private static void addPath(PathNode root, String path) {
        ComputerPatch.relative(path);
        var node = root;
        for (int start = 0; ; ) {
            if (node.file)
                throw new IllegalArgumentException("Computer file is also a parent: " + path);
            int slash = path.indexOf('/', start);
            int end = slash < 0 ? path.length() : slash;
            String component = path.substring(start, end);
            if (node.children == null) node.children = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            var child = node.children.get(component);
            if (child == null) {
                child = new PathNode();
                node.children.put(component, child);
            }
            node = child;
            if (slash < 0) break;
            start = slash + 1;
        }
        if (node.file || node.children != null)
            throw new IllegalArgumentException("Conflicting computer file path: " + path);
        node.file = true;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void writable() {
        if (closed || committed)
            throw new IllegalStateException("Computer file transaction is finished");
    }

    private void verifyAncestors(Path path) throws IOException {
        if (path == null) return;
        var ancestors = new ArrayDeque<Path>();
        for (Path value = path; value != null; value = value.getParent()) ancestors.addFirst(value);
        for (var value : ancestors) {
            var expected = guards.get(value);
            var attributes =
                    Files.readAttributes(
                            value, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (expected == null
                    || attributes.isSymbolicLink()
                    || attributes.isOther()
                    || !attributes.isDirectory()
                    || !attributes.creationTime().equals(expected.created()))
                throw new IOException("Computer destination ancestor changed: " + value);
        }
        // The exact containing directory's native identity prevents foreign-tree redirection.
        // Upper components are no-follow/type checked, without reopening every ancestor handle.
        if (!Identity.read(path, true).equals(guards.get(path)))
            throw new IOException("Computer destination parent identity changed: " + path);
        for (Path value = path; value != null; value = value.getParent())
            if (roots.contains(value)) {
                if (!value.equals(path) && !Identity.read(value, true).equals(guards.get(value)))
                    throw new IOException("Computer destination root identity changed: " + value);
                break;
            }
    }

    private void rememberDirectory(Path path) throws IOException {
        try {
            guards.put(path, Identity.read(path, true));
            directories.add(path);
        } catch (IOException failure) {
            problem(path, "created directory identity could not be established", failure);
            throw failure;
        }
    }

    /** Shared real category parents are guarded, never claimed unless this call creates them. */
    private void parents(Path path) throws IOException {
        var ancestors = new ArrayDeque<Path>();
        for (Path value = path; value != null; value = value.getParent()) ancestors.addFirst(value);
        for (var value : ancestors) {
            if (guards.containsKey(value)) {
                if (!Identity.read(value, true).equals(guards.get(value)))
                    throw new IOException("Computer destination ancestor changed: " + value);
            } else if (Files.exists(value, LinkOption.NOFOLLOW_LINKS)) {
                guards.put(value, Identity.read(value, true));
            } else {
                verifyAncestors(value.getParent());
                Files.createDirectory(value);
                rememberDirectory(value);
                verifyAncestors(value);
            }
        }
    }

    void createRoot(Path destination) throws IOException {
        writable();
        Path root = destination.toAbsolutePath().normalize();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(
                    "New computer/media ID root already exists: " + root);
        parents(root.getParent());
        verifyAncestors(root.getParent());
        Files.createDirectory(root);
        rememberDirectory(root);
        roots.add(root);
        verifyAncestors(root);
    }

    void write(Path destination, Map<Path, byte[]> contents) throws IOException {
        createRoot(destination);
        for (var entry : contents.entrySet()) file(destination, entry.getKey(), entry.getValue());
    }

    /**
     * Package-owned primitive also allows native tests to inject a real later CREATE_NEW failure.
     */
    void file(Path destination, Path relative, byte[] bytes) throws IOException {
        writable();
        Path root = destination.toAbsolutePath().normalize();
        if (!roots.contains(root)) throw new IOException("Unowned computer destination: " + root);
        if (relative.getRoot() != null
                || relative.isAbsolute()
                || !relative.normalize().equals(relative)
                || relative.startsWith(".."))
            throw new IOException("Unsafe relative computer destination: " + relative);
        Path target = root.resolve(relative);
        verifyAncestors(root);
        Path parent = root;
        for (var part : relative) {
            parent = parent.resolve(part);
            if (parent.equals(target)) break;
            if (guards.containsKey(parent)) verifyAncestors(parent);
            else {
                verifyAncestors(parent.getParent());
                Files.createDirectory(parent);
                rememberDirectory(parent);
                verifyAncestors(parent);
            }
        }
        verifyAncestors(target.getParent());
        File file = null;
        try (var channel =
                FileChannel.open(
                        target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
            try {
                file = new File(target, Identity.read(target, false));
                files.add(file);
            } catch (IOException failure) {
                problem(target, "created file identity could not be established", failure);
                throw failure;
            }
            var hash = digest();
            try {
                verifyAncestors(target.getParent());
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    int offset = buffer.position(), written = writer.write(channel, buffer);
                    if (written <= 0) throw new IOException("Computer file write made no progress");
                    hash.update(bytes, offset, written);
                    file.length += written;
                }
            } finally {
                // Only acknowledged writes are compensable. An unreported partial native write
                // or external modification fails the length/content comparison and is retained.
                file.digest = hash.digest();
            }
            // The post-close check below verifies this exact file identity and ancestors again.
        } catch (IOException | RuntimeException failure) {
            if (file != null) {
                try {
                    modified(file);
                } catch (IOException | RuntimeException unknown) {
                    problem(target, "created file metadata could not be established", unknown);
                }
            }
            throw failure;
        }
        // Windows may finalize the last-write timestamp only when the writing handle closes.
        modified(file);
    }

    private void modified(File file) throws IOException {
        verifyAncestors(file.path.getParent());
        if (!Identity.read(file.path, false).equals(file.identity))
            throw new IOException("Created computer file identity changed after write");
        file.modified = Files.getLastModifiedTime(file.path, LinkOption.NOFOLLOW_LINKS);
    }

    /** Native placement may expose these IDs even if a later block/provider fails. */
    public void commit() {
        writable();
        committed = true;
        release();
    }

    private void problem(Path path, String reason, Exception cause) {
        cleanupFailureCount++;
        if (cleanupFailures.size() < 4)
            cleanupFailures.add(
                    reason + ": " + path + " (" + cause.getClass().getSimpleName() + ")");
    }

    List<String> cleanupFailures() {
        return List.copyOf(cleanupFailures);
    }

    /** Hash only acknowledged bytes; concurrent growth must not extend cleanup work. */
    static byte[] recordedDigest(FileChannel channel, long length) throws IOException {
        var hash = digest();
        var buffer = ByteBuffer.allocate(8192);
        long remaining = length;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer);
            if (read < 0)
                throw new IOException("Computer file cleanup ended before recorded length");
            if (read == 0) throw new IOException("Computer file cleanup read made no progress");
            buffer.flip();
            hash.update(buffer);
            remaining -= read;
        }
        if (channel.size() != length)
            throw new IOException("Computer file length changed during cleanup");
        return hash.digest();
    }

    private void remove(File file) throws IOException {
        verifyAncestors(file.path.getParent());
        if (!Identity.read(file.path, false).equals(file.identity))
            throw new IOException("Created computer file identity changed");
        if (file.modified == null
                || !Files.getLastModifiedTime(file.path, LinkOption.NOFOLLOW_LINKS)
                        .equals(file.modified))
            throw new IOException("Created computer file modification time changed");
        if (Files.size(file.path) != file.length)
            throw new IOException("Created computer file length changed");
        byte[] contents;
        try (var channel =
                FileChannel.open(file.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            contents = recordedDigest(channel, file.length);
        }
        if (!Arrays.equals(contents, file.digest))
            throw new IOException("Created computer file contents changed");
        verifyAncestors(file.path.getParent());
        if (!Identity.read(file.path, false).equals(file.identity))
            throw new IOException("Created computer file identity changed before removal");
        // Portable Java has no atomic identity-conditional unlink. Hostile concurrent filesystem
        // mutation and process crashes require a stronger native/durable contract, not a claim
        // here.
        Files.delete(file.path);
    }

    private void release() {
        files.clear();
        directories.clear();
        roots.clear();
        guards.clear();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (!committed) {
            for (int i = files.size() - 1; i >= 0; i--) {
                var file = files.get(i);
                try {
                    remove(file);
                } catch (IOException | RuntimeException failure) {
                    problem(file.path, "uncommitted computer file was retained", failure);
                }
            }
            for (int i = directories.size() - 1; i >= 0; i--) {
                Path directory = directories.get(i);
                try {
                    verifyAncestors(directory.getParent());
                    if (!Identity.read(directory, true).equals(guards.get(directory)))
                        throw new IOException("Created computer directory identity changed");
                    Files.delete(
                            directory); // Empty-only, never recursive; unknown contents survive.
                } catch (IOException | RuntimeException failure) {
                    problem(directory, "uncommitted computer directory was retained", failure);
                }
            }
        }
        release();
        if (cleanupFailureCount > 0)
            LogUtils.getLogger()
                    .warn(
                            "[NPShips] Fresh ComputerCraft file cleanup retained {} unsafe/unverifiable paths; first details: {}",
                            cleanupFailureCount,
                            cleanupFailures);
    }
}
