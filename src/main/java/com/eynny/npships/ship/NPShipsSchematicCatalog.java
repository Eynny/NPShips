package com.eynny.npships.ship;

import com.eynny.npships.api.NPShipsCatalogueApi;
import com.eynny.npships.config.NPShipsStructureConfig;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.resources.PreparedTemplateResources;
import com.eynny.npships.resources.ResourceWorkMeter;
import com.eynny.npships.resources.ServerResourcePolicy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NPShipsSchematicCatalog {

    /**
     * Resource path used by NPShips.
     *
     * <p>Examples:
     *
     * <p>data/npships/npships/foo.nbt data/someaddon/npships/foo.nbt
     */
    private static final String SCHEMATIC_ROOT = SchematicCatalogDiscovery.SCHEMATIC_ROOT;

    /** External NPShips schematic directory. */
    private static final String EXTERNAL_DIRECTORY = "npships/schematics";

    private static final String OWN_NAMESPACE = SchematicCatalogDiscovery.OWN_NAMESPACE;

    private final Map<String, SchematicCatalogEntry> entries = new LinkedHashMap<>();

    private final Map<String, SchematicCatalogEntry> automaticEntries = new LinkedHashMap<>();
    private List<NPShipsSchematicManager.SchematicInfo> allInfo = List.of();
    private List<NPShipsSchematicManager.SchematicInfo> automaticInfo = List.of();
    private List<String> suggestions = List.of();

    Collection<NPShipsSchematicManager.SchematicInfo> automatic() {
        return automaticInfo;
    }

    private NPShipsSchematicManager.CatalogueStatus status;

    private NPShipsCatalogueApi.Snapshot publicSnapshot;
    private CatalogueResources.Generation resources;

    @Nullable
    CatalogueResources.Generation resources() {
        return resources;
    }

    NPShipsCatalogueApi.Snapshot publicSnapshot() {
        return publicSnapshot;
    }

    NPShipsSchematicManager.CatalogueStatus status() {
        return status;
    }

    static NPShipsSchematicCatalog failed(RuntimeException failure) {
        var catalog = new NPShipsSchematicCatalog();
        catalog.status =
                new NPShipsSchematicManager.CatalogueStatus(
                        true, 0, List.of(CatalogueDiagnostics.concise(failure.toString())));

        catalog.publicSnapshot = CataloguePublicView.create(catalog.status, List.of(), List.of());
        return catalog;
    }

    static NPShipsSchematicCatalog failed(MinecraftServer server, RuntimeException failure) {
        var catalog = failed(failure);
        catalog.resources = CatalogueResources.begin(server);
        return catalog;
    }

    private NPShipsSchematicCatalog() {}

    static NPShipsSchematicCatalog build(final MinecraftServer server) {
        return build(server, server.getResourceManager());
    }

    /** Keeps resource discovery independent from the server's mutable reload state. */
    static NPShipsSchematicCatalog build(
            final MinecraftServer server,
            final net.minecraft.server.packs.resources.ResourceManager resources) {

        return build(server, resources, getExternalDirectory(server));
    }

    static NPShipsSchematicCatalog build(
            MinecraftServer server,
            net.minecraft.server.packs.resources.ResourceManager resources,
            Path externalDirectory) {
        final NPShipsSchematicCatalog catalog = new NPShipsSchematicCatalog();
        catalog.resources = CatalogueResources.begin(server);
        try {
            return build(server, resources, externalDirectory, catalog);
        } catch (RuntimeException | Error failure) {
            catalog.resources.close();
            throw failure;
        }
    }

    private static NPShipsSchematicCatalog build(
            MinecraftServer server,
            net.minecraft.server.packs.resources.ResourceManager resources,
            Path externalDirectory,
            NPShipsSchematicCatalog catalog) {
        var diagnostics = new CatalogueDiagnostics();

        /*
         * Built-in / mod-provided resources.
         */
        SchematicCatalogDiscovery.refreshResourceManagerResources(
                resources, catalog.entries, diagnostics, catalog.resources);

        /*
         * External NPShips content.
         */
        catalog.automaticEntries.putAll(catalog.entries);
        SchematicCatalogDiscovery.scanExternalDirectory(
                externalDirectory, catalog.entries, diagnostics, catalog.resources);

        /*
         * Structure dimensions are metadata for the already built
         * catalog. Load them once now, rather than every time
         * natural spawning needs to know the schematic size.
         */
        catalog.loadNaturalSpawnMetadata(server, diagnostics);
        // No externally visible map changes until every surviving entry has a complete snapshot.
        if (java.util.stream.Stream.concat(
                        catalog.entries.values().stream(),
                        catalog.automaticEntries.values().stream())
                .anyMatch(e -> e.snapshot == null))
            throw new IllegalStateException("Incomplete catalogue snapshot");
        // Identifiable retained reference slots in both maps, cached lists and public DTO maps.
        // Object headers, HashMap implementation slack and decoded native graphs are not heap
        // claims.
        long entrySlots = Math.addExact(catalog.entries.size(), catalog.automaticEntries.size());
        catalog.resources.reserve(CatalogueResources.references(Math.multiplyExact(entrySlots, 5)));
        catalog.resources.reserve(CatalogueResources.references(catalog.entries.size()));
        catalog.status = diagnostics.finish(catalog.entries.size());
        catalog.resources.reserve(
                CatalogueResources.references(catalog.status.diagnostics().size()));
        for (String detail : catalog.status.diagnostics())
            catalog.resources.reserve(CatalogueResources.text(detail));
        catalog.allInfo =
                catalog.entries.values().stream().map(SchematicCatalogEntry::info).toList();
        catalog.automaticInfo =
                catalog.automaticEntries.values().stream()
                        .map(SchematicCatalogEntry::info)
                        .toList();
        var names = new ArrayList<>(catalog.entries.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        catalog.suggestions = List.copyOf(names);

        catalog.publicSnapshot =
                CataloguePublicView.create(
                        catalog.status,
                        catalog.entries.values(),
                        catalog.automaticEntries.values());

        return catalog;
    }

    List<String> getSuggestionNames() {

        // Keep the legacy caller-owned mutable list, but sort only once per generation.
        return new ArrayList<>(suggestions);
    }

    Collection<NPShipsSchematicManager.SchematicInfo> getAll() {

        return allInfo;
    }

    @Nullable
    NPShipsSchematicManager.SchematicInfo find(final String commandName) {

        final SchematicCatalogEntry entry = resolveEntry(SchematicNames.parse(commandName));
        return entry == null ? null : entry.info();
    }

    @Nullable
    NPShipsStructureConfig findConfig(final String commandName) {
        final SchematicCatalogEntry entry = resolveEntry(SchematicNames.parse(commandName));
        return entry == null ? null : entry.config();
    }

    StructureTemplate loadTemplate(final ServerLevel level, final ResourceLocation id) {
        return loadTemplate(level, id, null);
    }

    StructureTemplate loadTemplate(
            final ServerLevel level,
            final ResourceLocation id,
            final com.eynny.npships.authoring.StructureSegments.Plan plan) {
        return loadTemplate(level, id, plan, net.minecraft.core.BlockPos.ZERO);
    }

    StructureTemplate loadTemplate(
            ServerLevel level,
            ResourceLocation id,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            net.minecraft.core.BlockPos origin) {
        return loadTemplate(level, id, plan, origin, null);
    }

    StructureTemplate loadTemplate(
            ServerLevel level,
            ResourceLocation id,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            BlockPos origin,
            @Nullable SpawnTimingProfiler timing) {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.DECODE);
        try {
            final SchematicCatalogEntry entry = resolveEntry(id);

            if (entry == null) {

                throw new IllegalArgumentException("Unknown NPShips structure: " + id);
            }

            try (InputStream stream = entry.openStream()) {

                final CompoundTag tag =
                        NbtIo.readCompressed(
                                stream,
                                NbtAccounter.create(
                                        com.eynny.npships.authoring.document.SchematicDocument
                                                .NBT_MEMORY_LIMIT));

                return compose(level, tag, entry.config(), plan, origin, timing);

            } catch (final IOException e) {

                throw new RuntimeException(
                        "Failed loading NPShips structure: " + entry.commandName(), e);
            }

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    static StructureTemplate compose(
            ServerLevel level,
            CompoundTag tag,
            NPShipsStructureConfig config,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            BlockPos origin) {
        return compose(level, tag, config, plan, origin, null);
    }

    static StructureTemplate compose(
            ServerLevel level,
            CompoundTag tag,
            NPShipsStructureConfig config,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            BlockPos origin,
            @Nullable SpawnTimingProfiler timing) {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.COMPOSE);
        var resources =
                new PreparedTemplateResources(
                        ServerResourcePolicy.memory(level.getServer()), new ResourceWorkMeter());
        boolean transferred = false;
        try {
            // Decoding precedes this boundary. Admit known retained payload before further copies.
            resources.retain(tag);
            final HolderGetter<Block> blocks =
                    level.registryAccess().lookupOrThrow(Registries.BLOCK);

            CompoundTag placedTag = tag;
            if (config != null) {
                com.eynny.npships.authoring.patches.SchematicPatches.embed(tag, config.authoring());
                var loot = config.lootContainers();
                if (!config.segments().isEmpty()) {
                    // The composer makes one complete mutable result root. Per-variant scratch,
                    // maps and rewritten children remain separately documented exposures.
                    resources.reserveInputCopy(tag);
                    var composition =
                            com.eynny.npships.authoring.StructureSegments.compose(
                                    tag,
                                    config.segments(),
                                    loot,
                                    plan == null
                                            ? com.eynny.npships.authoring.StructureSegments.choose(
                                                    config.segments(), level.random)
                                            : plan);
                    placedTag = composition.nbt();
                    loot = composition.loot();
                }
                SchematicLoot.apply(
                        placedTag,
                        loot,
                        level,
                        com.eynny.npships.authoring.EmbeddedLoot.read(config.authoring()),
                        origin);
                com.eynny.npships.authoring.StructureCrew.applyPrepared(
                        placedTag, config.authoring(), level, level.random, timing, resources);
            }

            final StructureTemplate template =
                    new com.eynny.npships.authoring.patches.PatchedTemplate(placedTag, resources);
            ((com.eynny.npships.authoring.patches.PatchedTemplate) template).timing(timing);
            template.load(blocks, placedTag);
            resources.completeInput(placedTag);
            // No later native/world access to discarded composition input; child data that can
            // escape through the loaded template remains covered by its final input envelope.
            tag = null;
            placedTag = null;
            transferred = true;
            return template;

        } finally {

            if (!transferred) resources.abort();
            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    /**
     * Loads metadata required by the natural-spawn system.
     *
     * <p>Each accepted entry pins validated template bytes, dimensions and native rarity counts;
     * required patch availability is checked without running restoration.
     *
     * <p>This happens once when the catalog is built/refreshed.
     */
    private void loadNaturalSpawnMetadata(
            final MinecraftServer server, CatalogueDiagnostics diagnostics) {

        final ServerLevel level = server.overworld();

        var rejected =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<SchematicCatalogEntry, Boolean>());
        var providers = new com.eynny.npships.authoring.patches.PatchAvailability();
        for (final SchematicCatalogEntry entry :
                java.util.stream.Stream.concat(
                                this.entries.values().stream(),
                                this.automaticEntries.values().stream())
                        .distinct()
                        .toList()) {

            try {

                entry.loadNaturalSpawnSize(level, providers);

            } catch (CatalogueDiagnostics.EntryFailure failure) {
                diagnostics.reject(
                        SchematicNames.parse(entry.commandName()).toString(),
                        entry.origin(),
                        failure);
                rejected.add(entry);
            } catch (IOException error) {
                diagnostics.reject(
                        SchematicNames.parse(entry.commandName()).toString(),
                        entry.origin(),
                        new CatalogueDiagnostics.EntryFailure("cannot read NBT source", error));
                rejected.add(entry);
            }
        }
        for (var entry : rejected) entry.rejectRetainedCapacity();
        this.entries.values().removeIf(rejected::contains);
        this.automaticEntries.values().removeIf(rejected::contains);
    }

    static Path getExternalDirectory(final MinecraftServer server) {

        return server.getServerDirectory().resolve(EXTERNAL_DIRECTORY);
    }

    @Nullable
    private SchematicCatalogEntry resolveEntry(final ResourceLocation id) {

        if (OWN_NAMESPACE.equals(id.getNamespace())) {

            /*
             * External bare-name schematic has priority.
             */
            final SchematicCatalogEntry external = this.entries.get(id.getPath());

            if (external != null && external.external()) {

                return external;
            }
        }

        SchematicCatalogEntry entry = this.entries.get(normalizeResourceLocation(id));
        // Compatibility with the previous accidental resource ID prefix.
        if (entry == null && id.getPath().startsWith(SCHEMATIC_ROOT + "/")) {
            entry =
                    this.entries.get(
                            id.getNamespace()
                                    + ":"
                                    + id.getPath().substring(SCHEMATIC_ROOT.length() + 1));
        }
        return entry;
    }

    @Nullable
    private static String normalizeResourceLocation(final ResourceLocation id) {

        return normalizeCommandName(id.getNamespace() + ":" + id.getPath());
    }

    private static String normalizeCommandName(final String value) {

        return value.trim().replace('\\', '/').replaceAll("^/+", "").replaceAll("/+", "/");
    }
}
