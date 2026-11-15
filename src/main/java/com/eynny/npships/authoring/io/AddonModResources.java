package com.eynny.npships.authoring.io;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Projects the current data-only mod layout before native server resource merging. */
public final class AddonModResources {
    private static final String MARKER = "npships-pack.json";

    /** Mod-bus callback on both physical sides; client asset repositories remain native. */
    public static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        var suppliers = new LinkedHashMap<IModFile, Pack.ResourcesSupplier>();
        boolean changed = false;
        for (var info : ModList.get().getModFiles()) {
            if (info.getMods().isEmpty()) continue;
            var nativeSupplier =
                    ResourcePackLoader.getPackFor(info.getMods().getFirst().getModId());
            if (nativeSupplier.isEmpty()) continue;
            Pack.ResourcesSupplier supplier = nativeSupplier.get();
            if (Files.exists(info.getFile().findResource(MARKER), LinkOption.NOFOLLOW_LINKS)) {
                String owner = owner(info);
                inspect(info, owner);
                supplier = new ProjectedSupplier(info, owner);
                changed = true;
            }
            suppliers.put(info.getFile(), supplier);
        }
        if (changed) {
            // The event follows the native finder. Identical pack IDs replace its mod_data and
            // children together, preserving native order/selection and eliminating raw namespaces.
            // The immutable map belongs to this repository, never a world or a global registry.
            event.addRepositorySource(
                    ResourcePackLoader.buildPackFinder(
                            Collections.unmodifiableMap(suppliers), PackType.SERVER_DATA));
        }
    }

    private static String owner(IModFileInfo info) {
        if (info.getMods().size() != 1)
            throw rejected(info, "Marked addon must contain exactly one metadata mod owner.");
        if (info.requiredLanguageLoaders().size() != 1
                || !info.requiredLanguageLoaders().getFirst().languageName().equals("lowcodefml"))
            throw rejected(info, "Marked addon must use the data-only lowcodefml loader.");
        if (info.showAsDataPack())
            throw rejected(info, "Marked addon must remain in the always-active native mod data.");
        return info.getMods().getFirst().getModId();
    }

    private static Index inspect(IModFileInfo info, String owner) {
        try {
            var container =
                    Files.readAttributes(
                            info.getFile().getFilePath(),
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
            if (!container.isRegularFile()
                    || container.isSymbolicLink()
                    || container.size() > AddonArchive.MAX_JAR)
                throw new IOException("Marked addon must be a regular JAR of at most 64 MiB.");
            Path root = info.getFile().getSecureJar().getRootPath();
            var files = new TreeMap<String, Entry>();
            var canonical = new HashSet<String>();
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        private int count;
                        private long total;

                        private String admit(Path path, BasicFileAttributes attributes)
                                throws IOException {
                            String name = root.relativize(path).toString().replace('\\', '/');
                            AddonZip.path(name);
                            if (++count > AddonArchive.MAX_ENTRIES || !canonical.add(name))
                                throw new IOException("Too many or colliding addon entries.");
                            if (attributes.isSymbolicLink()
                                    || (!attributes.isDirectory() && !attributes.isRegularFile()))
                                throw new IOException("Unsupported addon entry: " + name);
                            if (attributes.isRegularFile()) {
                                total += attributes.size();
                                if (attributes.size() > AddonArchive.MAX_ENTRY
                                        || total > AddonArchive.MAX_TOTAL)
                                    throw new IOException(
                                            "Addon exceeds resource size limits: " + name);
                            }
                            return name;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path path, BasicFileAttributes attributes) throws IOException {
                            if (!path.equals(root)) admit(path, attributes);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path path, BasicFileAttributes attributes)
                                throws IOException {
                            String name = admit(path, attributes);
                            files.put(name, new Entry(path, attributes.size()));
                            return FileVisitResult.CONTINUE;
                        }
                    });
            var marker = files.get(MARKER);
            if (marker == null || marker.size() > 16 * 1024 * 1024)
                throw new IOException("Missing or oversized addon marker.");
            JsonObject manifest;
            try (var reader = new InputStreamReader(marker.open(), StandardCharsets.UTF_8)) {
                manifest = JsonParser.parseReader(reader).getAsJsonObject();
            }
            AddonModLayout.validate(files.keySet(), manifest, owner);
            var resources = new TreeMap<ResourceLocation, Entry>();
            for (var entry : files.entrySet()) {
                String name = entry.getKey();
                if (name.startsWith("data/"))
                    resources.put(
                            ResourceLocation.fromNamespaceAndPath(owner, name.substring(5)),
                            entry.getValue());
            }
            return new Index(Map.copyOf(files), Map.copyOf(resources));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "NPShips addon mod "
                            + owner
                            + " ("
                            + info.getFile().getFileName()
                            + "): "
                            + e.getMessage(),
                    e);
        }
    }

    private static IllegalStateException rejected(IModFileInfo info, String message) {
        return new IllegalStateException(
                "NPShips addon mod " + info.getFile().getFileName() + ": " + message);
    }

    private record Index(Map<String, Entry> files, Map<ResourceLocation, Entry> resources) {}

    private record Entry(Path path, long size) {
        InputStream open() throws IOException {
            // This stream is ours; its Path and filesystem remain owned by the native loader.
            return new SizedStream(Files.newInputStream(path), size);
        }

        IoSupplier<InputStream> supplier() {
            return this::open;
        }
    }

    private record ProjectedSupplier(IModFileInfo info, String owner)
            implements Pack.ResourcesSupplier {
        @Override
        public PackResources openPrimary(PackLocationInfo location) {
            return new ProjectedResources(location, owner, inspect(info, owner));
        }

        @Override
        public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
            if (!metadata.overlays().isEmpty())
                throw rejected(info, "Pack overlays are not part of the addon-mod layout.");
            return openPrimary(location);
        }
    }

    private static final class ProjectedResources extends AbstractPackResources {
        private final String owner;
        private final Index index;

        ProjectedResources(PackLocationInfo location, String owner, Index index) {
            super(location);
            this.owner = owner;
            this.index = index;
        }

        @Override
        public IoSupplier<InputStream> getRootResource(String... elements) {
            String name = String.join("/", elements);
            try {
                AddonZip.path(name);
            } catch (IOException e) {
                return null;
            }
            var entry = index.files().get(name);
            return entry == null ? null : entry.supplier();
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer)
                throws IOException {
            if (!index.files().containsKey(PACK_META)
                    && deserializer.getMetadataSectionName().equals("pack")) {
                var metadata = new JsonObject();
                metadata.addProperty("description", "NPShips addon " + owner);
                metadata.addProperty(
                        "pack_format",
                        SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
                return deserializer.fromJson(metadata);
            }
            return super.getMetadataSection(deserializer);
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            var entry = type == PackType.SERVER_DATA ? index.resources().get(location) : null;
            return entry == null ? null : entry.supplier();
        }

        @Override
        public void listResources(
                PackType type, String namespace, String path, ResourceOutput output) {
            if (type != PackType.SERVER_DATA || !namespace.equals(owner)) return;
            String prefix = path.isEmpty() || path.endsWith("/") ? path : path + "/";
            index.resources()
                    .forEach(
                            (id, entry) -> {
                                if (id.getPath().startsWith(prefix))
                                    output.accept(id, entry.supplier());
                            });
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.SERVER_DATA ? Set.of(owner) : Set.of();
        }

        @Override
        public void close() {
            // Like native PathPackResources, this owner keeps no open streams or owned filesystem.
        }
    }

    /** Fail on growth/truncation rather than silently handing the resource decoder another size. */
    private static final class SizedStream extends FilterInputStream {
        private long remaining;

        SizedStream(InputStream stream, long size) {
            super(stream);
            remaining = size;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (remaining == 0) {
                if (value != -1) throw new IOException("Addon resource grew after admission.");
                return -1;
            }
            if (value == -1) throw new IOException("Addon resource truncated after admission.");
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (remaining == 0) return read();
            int count = in.read(bytes, offset, (int) Math.min(length, remaining));
            if (count == -1) throw new IOException("Addon resource truncated after admission.");
            remaining -= count;
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = in.skip(Math.min(Math.max(0, count), remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int limit) {}

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("Addon resource stream does not support reset.");
        }
    }

    private AddonModResources() {}
}
