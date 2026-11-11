package com.eynny.npships.ship;

import com.eynny.npships.api.NPShipsCatalogueApi;
import com.eynny.npships.config.NPShipsStructureConfig;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NPShipsSchematicManager {

    private static final Map<MinecraftServer, NPShipsSchematicCatalog> CATALOGS =
            new ConcurrentHashMap<>();

    private NPShipsSchematicManager() {}

    /**
     * Completely rebuilds the schematic catalog.
     *
     * <p>This includes:
     *
     * <p>- built-in/mod-provided NBT resources; - built-in/mod-provided JSON configurations; -
     * external NPShips NBT files; - external NPShips JSON configurations.
     *
     * <p>Only a complete validated replacement is published. A structural failure retains the
     * last-good catalogue (or an explicit unavailable empty state on first use).
     */
    public static void refresh(final MinecraftServer server) {
        tryRefresh(server);
    }

    /** Reports publication failure to commands while preserving the existing void refresh API. */
    public static synchronized boolean tryRefresh(final MinecraftServer server) {
        boolean published =
                refresh(server, server.getResourceManager(), getExternalDirectory(server));
        if (!published) return false;
        for (var player : server.getPlayerList().getPlayers())
            server.getCommands().sendCommands(player);
        net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
                new com.eynny.npships.network.CatalogRefreshNetworking.Changed());
        return true;
    }

    static boolean refresh(
            MinecraftServer server,
            net.minecraft.server.packs.resources.ResourceManager resources,
            Path externalDirectory) {
        final NPShipsSchematicCatalog replacement;
        try {
            replacement = NPShipsSchematicCatalog.build(server, resources, externalDirectory);
        } catch (RuntimeException failure) {
            // Enumeration/registry/unexpected implementation faults are NOT entry invalidity.
            // No partially constructed map escapes. Do not retry this on every manager tick.
            if (!CATALOGS.containsKey(server))
                CATALOGS.putIfAbsent(server, NPShipsSchematicCatalog.failed(server, failure));
            com.mojang.logging.LogUtils.getLogger()
                    .error(
                            "[NPShips catalogue] Structural build failure; {}. {}",
                            CATALOGS.get(server).status().failed()
                                    ? "catalogue unavailable; refresh after repair"
                                    : "retaining last-good catalogue",
                            CatalogueDiagnostics.concise(failure.toString()));
            return false;
        }
        CATALOGS.put(server, replacement);
        com.eynny.npships.network.RuntimeSettingsNetworking.invalidateCatalogue(server);
        return true;
    }

    public record CatalogueStatus(boolean failed, int excludedEntries, List<String> diagnostics) {
        public CatalogueStatus {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public static CatalogueStatus status(MinecraftServer server) {
        return getCatalog(server).status();
    }

    /** Internal bridge for the immutable public facade; no second catalogue or cache. */
    public static NPShipsCatalogueApi.Snapshot publicSnapshot(MinecraftServer server) {
        if (!server.isSameThread())
            throw new IllegalStateException("Catalogue queries require the server thread.");
        return getCatalog(server).publicSnapshot();
    }

    /**
     * Returns command names understood by:
     *
     * <p>/npships spawn /npships naturalspawn
     */
    public static List<String> getSuggestionNames(final MinecraftServer server) {

        return getCatalog(server).getSuggestionNames();
    }

    /** Returns metadata for every discovered schematic. */
    public static Collection<SchematicInfo> getAll(final MinecraftServer server) {

        return getCatalog(server).getAll();
    }

    /**
     * Finds a schematic by command name.
     *
     * <p>Examples:
     *
     * <p>foo npships:foo custommod:tank
     */
    @Nullable
    public static SchematicInfo find(final MinecraftServer server, final String commandName) {

        return getCatalog(server).find(normalizeCommandName(commandName));
    }

    /**
     * Finds the parsed structure configuration.
     *
     * <p>Returns null when:
     *
     * <p>- the schematic does not exist; - the schematic has no companion JSON.
     */
    @Nullable
    public static NPShipsStructureConfig findConfig(
            final MinecraftServer server, final String commandName) {

        return getCatalog(server).findConfig(normalizeCommandName(commandName));
    }

    /**
     * Maps legacy minecraft-prefixed IDs to npships; current command parsing already supplies
     * npships for bare names.
     */
    public static ResourceLocation resolveRequestedId(final ResourceLocation requestedId) {

        if ("minecraft".equals(requestedId.getNamespace())) {

            return ResourceLocation.fromNamespaceAndPath("npships", requestedId.getPath());
        }

        return requestedId;
    }

    /**
     * Loads a StructureTemplate.
     *
     * <p>Source resolution is performed by the catalog.
     */
    public static StructureTemplate loadTemplate(
            ServerLevel level,
            ResourceLocation id,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            net.minecraft.core.BlockPos origin) {
        return loadTemplate(level, id, plan, origin, null);
    }

    public static StructureTemplate loadTemplate(
            ServerLevel level,
            ResourceLocation id,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            BlockPos origin,
            @Nullable SpawnTimingProfiler timing) {
        return getCatalog(level.getServer()).loadTemplate(level, id, plan, origin, timing);
    }

    public static StructureTemplate loadTemplate(
            final ServerLevel level, final ResourceLocation id) {

        return getCatalog(level.getServer()).loadTemplate(level, id);
    }

    public static StructureTemplate loadTemplate(
            ServerLevel level,
            ResourceLocation id,
            com.eynny.npships.authoring.StructureSegments.Plan plan) {
        return getCatalog(level.getServer()).loadTemplate(level, id, plan);
    }

    /**
     * Returns the external NPShips schematic directory:
     *
     * <p>{@code <server>/npships/schematics}
     */
    public static Path getExternalDirectory(final MinecraftServer server) {

        return NPShipsSchematicCatalog.getExternalDirectory(server);
    }

    /** Clears the catalog when a server stops. */
    public static void clear(final MinecraftServer server) {

        CATALOGS.remove(server);
        com.eynny.npships.network.RuntimeSettingsNetworking.invalidateCatalogue(server);
        CatalogueResources.clear(server);
    }

    /** Internal derived-view ownership bridge; does not create another catalogue. */
    @Nullable
    public static CatalogueResources.Generation catalogueResources(MinecraftServer server) {
        return getCatalog(server).resources();
    }

    private static NPShipsSchematicCatalog getCatalog(final MinecraftServer server) {

        var catalog = CATALOGS.get(server);
        if (catalog == null) {
            synchronized (NPShipsSchematicManager.class) {
                if (!CATALOGS.containsKey(server))
                    refresh(server, server.getResourceManager(), getExternalDirectory(server));
                catalog = CATALOGS.get(server);
            }
        }
        return catalog;
    }

    private static String normalizeCommandName(final String value) {

        return value.trim().replace('\\', '/').replaceAll("^/+", "").replaceAll("/+", "/");
    }

    public static Collection<SchematicInfo> getAutomatic(MinecraftServer server) {
        return getCatalog(server).automatic();
    }

    /** Owns immutable compressed template bytes for attempts spanning a refresh. */
    public static final class TemplateSnapshot {
        private final byte[] bytes;
        @Nullable private final CatalogueResources.Generation resources;
        private final NPShipsStructureConfig config;
        private final BlockPos size;
        @Nullable private final SchematicRarityMetadata rarityMetadata;

        TemplateSnapshot(byte[] bytes, NPShipsStructureConfig config, BlockPos size) {
            this(bytes, config, size, null);
        }

        TemplateSnapshot(
                byte[] bytes,
                NPShipsStructureConfig config,
                BlockPos size,
                @Nullable SchematicRarityMetadata rarityMetadata) {
            this(bytes, config, size, rarityMetadata, null);
        }

        TemplateSnapshot(
                byte[] bytes,
                NPShipsStructureConfig config,
                BlockPos size,
                @Nullable SchematicRarityMetadata rarityMetadata,
                @Nullable CatalogueResources.Generation resources) {
            this.resources = resources;
            this.bytes = bytes.clone();
            this.config = config;
            this.size = size.immutable();
            this.rarityMetadata = rarityMetadata;
        }

        java.io.InputStream openStream() {
            return new java.io.ByteArrayInputStream(bytes);
        }

        /** Captured on catalogue refresh; reading it never opens/decompresses template data. */
        @Nullable
        public SchematicRarityMetadata rarityMetadata() {
            return rarityMetadata;
        }

        /** Pinned native NBT dimensions; segment/loot/crew composition preserves this envelope. */
        public BlockPos size() {
            return size;
        }

        public StructureTemplate load(
                ServerLevel level,
                com.eynny.npships.authoring.StructureSegments.Plan plan,
                BlockPos origin) {
            return load(level, plan, origin, -1);
        }

        /**
         * Select the pinned native palette before composition/preflight/placement can reroll it.
         */
        public StructureTemplate load(
                ServerLevel level,
                com.eynny.npships.authoring.StructureSegments.Plan plan,
                BlockPos origin,
                int palette) {
            return load(level, plan, origin, palette, null);
        }

        public StructureTemplate load(
                ServerLevel level,
                com.eynny.npships.authoring.StructureSegments.Plan plan,
                BlockPos origin,
                int palette,
                @Nullable SpawnTimingProfiler timing) {
            var spawnTiming = timing;
            long spawnToken =
                    spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.DECODE);
            try {
                try {
                    var tag =
                            net.minecraft.nbt.NbtIo.readCompressed(
                                    new java.io.ByteArrayInputStream(bytes),
                                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                    if (palette >= 0 && tag.contains("palettes", net.minecraft.nbt.Tag.TAG_LIST)) {
                        var palettes = tag.getList("palettes", net.minecraft.nbt.Tag.TAG_LIST);
                        if (palette >= palettes.size())
                            throw new IllegalArgumentException("Invalid pinned native palette");
                        tag.put("palette", palettes.getList(palette).copy());
                        tag.remove("palettes");
                    } else if (palette > 0)
                        throw new IllegalArgumentException("Invalid pinned native palette");
                    return NPShipsSchematicCatalog.compose(
                            level, tag, config, plan, origin, timing);
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException("Cannot read pinned schematic", e);
                }

            } finally {

                if (spawnTiming != null) spawnTiming.end(spawnToken);
            }
        }
    }

    public record SchematicInfo(
            String commandName,
            SchematicSource source,
            boolean hasJson,
            @Nullable Path nbtFile,
            @Nullable Path jsonFile,
            @Nullable NPShipsStructureConfig config,
            @Nullable BlockPos naturalSpawnSize,
            @Nullable TemplateSnapshot snapshot) {

        public SchematicInfo(
                String name,
                SchematicSource source,
                boolean json,
                Path nbt,
                Path jsonPath,
                NPShipsStructureConfig config,
                BlockPos size) {
            this(name, source, json, nbt, jsonPath, config, size, null);
        }

        public boolean external() {
            return this.source == SchematicSource.EXTERNAL;
        }
    }
}
