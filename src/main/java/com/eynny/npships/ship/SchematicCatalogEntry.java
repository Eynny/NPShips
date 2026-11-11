package com.eynny.npships.ship;

import com.eynny.npships.authoring.SchematicSafety;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicExtensions;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.PatchAvailability;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.config.NPShipsStructureConfig;
import com.eynny.npships.config.NPShipsStructureConfigLoader;
import com.eynny.npships.resources.PreparedTemplateResources;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** One source entry, its validated config and cached spawn metadata. */
final class SchematicCatalogEntry {
    final String commandName;
    final SchematicSource source;
    @Nullable final ResourceLocation resourceId;
    @Nullable final Resource resource;
    @Nullable final Path externalNbt;
    @Nullable final Path externalJson;
    @Nullable final Resource jsonResource;
    @Nullable final NPShipsStructureConfig config;
    @Nullable private final CatalogueResources.Generation resources;
    private long retainedCapacity;

    /**
     * Cached NBT schematic dimensions.
     *
     * <p>This is loaded once when the catalog is built/refreshed.
     *
     * <p>Null means metadata could not be loaded.
     */
    @Nullable BlockPos naturalSpawnSize;

    SchematicCatalogEntry(
            final String commandName,
            final SchematicSource source,
            @Nullable final ResourceLocation resourceId,
            @Nullable final Resource resource,
            @Nullable final Path externalNbt,
            @Nullable final Path externalJson,
            @Nullable final Resource jsonResource) {
        this(
                commandName,
                source,
                resourceId,
                resource,
                externalNbt,
                externalJson,
                jsonResource,
                null);
    }

    private SchematicCatalogEntry(
            String commandName,
            SchematicSource source,
            @Nullable ResourceLocation resourceId,
            @Nullable Resource resource,
            @Nullable Path externalNbt,
            @Nullable Path externalJson,
            @Nullable Resource jsonResource,
            @Nullable CatalogueResources.Generation resources) {
        this.resources = resources;
        retain(CatalogueResources.text(commandName));
        this.commandName = commandName;
        this.source = source;
        this.resourceId = resourceId;
        this.resource = resource;
        this.externalNbt = externalNbt;
        this.externalJson = externalJson;
        this.jsonResource = jsonResource;
        this.config = loadConfig(externalJson, jsonResource, commandName, this);
    }

    static SchematicCatalogEntry resource(
            final String commandName,
            final ResourceLocation resourceId,
            final Resource resource,
            @Nullable final Resource jsonResource) {
        return new SchematicCatalogEntry(
                commandName,
                SchematicSource.RESOURCE,
                resourceId,
                resource,
                null,
                null,
                jsonResource);
    }

    static SchematicCatalogEntry external(
            final String commandName, final Path nbtFile, @Nullable final Path jsonFile) {
        return new SchematicCatalogEntry(
                commandName, SchematicSource.EXTERNAL, null, null, nbtFile, jsonFile, null);
    }

    static SchematicCatalogEntry resource(
            String name,
            ResourceLocation id,
            Resource resource,
            @Nullable Resource json,
            CatalogueResources.Generation resources) {
        return admitted(name, SchematicSource.RESOURCE, id, resource, null, null, json, resources);
    }

    static SchematicCatalogEntry external(
            String name, Path nbt, @Nullable Path json, CatalogueResources.Generation resources) {
        return admitted(name, SchematicSource.EXTERNAL, null, null, nbt, json, null, resources);
    }

    private static SchematicCatalogEntry admitted(
            String name,
            SchematicSource source,
            @Nullable ResourceLocation id,
            @Nullable Resource resource,
            @Nullable Path nbt,
            @Nullable Path json,
            @Nullable Resource jsonResource,
            CatalogueResources.Generation resources) {
        long before = resources.bytes();
        try {
            return new SchematicCatalogEntry(
                    name, source, id, resource, nbt, json, jsonResource, resources);
        } catch (CatalogueDiagnostics.EntryFailure invalid) {
            resources.release(resources.bytes() - before);
            throw invalid;
        }
    }

    private void retain(long bytes) {
        if (resources == null) return;
        resources.reserve(bytes);
        retainedCapacity = Math.addExact(retainedCapacity, bytes);
    }

    void rejectRetainedCapacity() {
        if (resources != null) resources.release(retainedCapacity);
        retainedCapacity = 0;
    }

    boolean external() {
        return this.source == SchematicSource.EXTERNAL;
    }

    String commandName() {
        return this.commandName;
    }

    NPShipsSchematicManager.TemplateSnapshot snapshot;

    InputStream openStream() throws IOException {
        if (snapshot != null) return snapshot.openStream();
        if (this.externalNbt != null) {
            return Files.newInputStream(this.externalNbt);
        }
        if (this.resource != null) {
            return this.resource.open();
        }
        throw new IOException("Schematic has no source: " + this.commandName);
    }

    /**
     * Pins dimensions and native rarity counts from the existing catalogue NBT read.
     *
     * <p>This happens during catalog build, not during every natural-spawn attempt.
     */
    void loadNaturalSpawnSize(final ServerLevel level) throws IOException {
        loadNaturalSpawnSize(level, new PatchAvailability());
    }

    void loadNaturalSpawnSize(final ServerLevel level, PatchAvailability providers)
            throws IOException {
        try (InputStream stream = this.openStream();
                var scratch =
                        resources == null ? null : resources.working("catalogue source scratch")) {
            // Own the exact source bytes from this single resource read; do not recompress NBT.
            byte[] sourceBytes = CatalogueInput.nbt(stream, resources, scratch);
            // Admit before native decode and before the frozen snapshot's defensive byte clone.
            retain(sourceBytes.length);
            final CompoundTag tag;
            try {
                tag =
                        NbtIo.readCompressed(
                                new java.io.ByteArrayInputStream(sourceBytes),
                                NbtAccounter.create(
                                        com.eynny.npships.authoring.document.SchematicDocument
                                                .NBT_MEMORY_LIMIT));
            } catch (net.minecraft.nbt.NbtAccounterException
                    | net.minecraft.nbt.NbtFormatException error) {
                // Native depth/quota/format failures belong to this authored entry, not a
                // systemic resource-manager failure that aborts the complete catalogue.
                throw new CatalogueDiagnostics.EntryFailure("invalid native NBT envelope", error);
            }
            // Native decoding is opaque. Reserve the resulting known payload before validation
            // and native list construction; never equate NbtAccounter units with Java heap.
            if (scratch != null) {
                var decoded = PreparedTemplateResources.measure(tag);
                scratch.reserve(Math.addExact(decoded.mutableBytes(), decoded.textBytes()));
            }
            // Resolve required registries outside data validation: failure here is systemic.
            final HolderGetter<Block> blocks =
                    level.registryAccess().lookupOrThrow(Registries.BLOCK);
            final StructureTemplate template = new StructureTemplate();
            SchematicRarityMetadata metadata;
            try {
                var dimensions = tag.getList("size", net.minecraft.nbt.Tag.TAG_INT);
                for (int axis = 0; axis < dimensions.size(); axis++)
                    if (dimensions.getInt(axis)
                            > com.eynny.npships.authoring.document.SchematicDocument.MAX_SIDE)
                        throw new IllegalArgumentException(
                                "Template exceeds native preparation dimension limit: "
                                        + com.eynny.npships.authoring.document.SchematicDocument
                                                .MAX_SIDE);
                if (tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).size()
                        > com.eynny.npships.authoring.document.SchematicDocument.MAX_BLOCKS)
                    throw new IllegalArgumentException(
                            "Template exceeds native preparation block-entry limit");
                var segments =
                        config == null
                                ? java.util.List.<StructureSegments.Segment>of()
                                : config.segments();
                metadata = SchematicRarityMetadata.inspect(tag, segments, blocks);
                if (!metadata.available()) throw new IllegalArgumentException(metadata.error());
                StructureSegments.validate(tag, segments);
                validatePatches(level, tag, providers);
                // Inspect every stored occurrence in its base context, never a random plan.
                StructureSegments.visitValidationVariants(
                        new com.eynny.npships.authoring.document.SchematicDocument(
                                tag, config == null ? null : config.authoring()),
                        segments,
                        part -> validatePatches(level, part, providers, false));
                template.load(blocks, tag);
            } catch (com.eynny.npships.resources.ResourceLimitException refused) {
                throw refused;
            } catch (IllegalArgumentException
                    | IllegalStateException
                    | IndexOutOfBoundsException error) {
                throw new CatalogueDiagnostics.EntryFailure("invalid NBT/patch data", error);
            }
            retain(metadata.catalogueCapacity());
            final var size = template.getSize();
            this.naturalSpawnSize = new BlockPos(size.getX(), size.getY(), size.getZ());
            snapshot =
                    new NPShipsSchematicManager.TemplateSnapshot(
                            sourceBytes, config, naturalSpawnSize, metadata, resources);
        }
    }

    String origin() {
        return externalNbt != null
                ? externalNbt + " config=" + externalJson
                : resourceId
                        + " pack="
                        + resource.sourcePackId()
                        + (jsonResource == null
                                ? ""
                                : " config-pack=" + jsonResource.sourcePackId());
    }

    private void validatePatches(ServerLevel level, CompoundTag tag, PatchAvailability providers) {
        validatePatches(level, tag, providers, true);
    }

    private void validatePatches(
            ServerLevel level, CompoundTag tag, PatchAvailability providers, boolean root) {
        if (tag.contains("palettes", net.minecraft.nbt.Tag.TAG_LIST)) {
            var palettes = tag.getList("palettes", net.minecraft.nbt.Tag.TAG_LIST);
            for (var palette : palettes) {
                var single = tag.copy();
                single.remove("palettes");
                single.put("palette", palette.copy());
                validatePatches(level, single, providers, root);
            }
            return;
        }
        SchematicSafety.validate(tag);
        // Native entity inventories also need this guard when there is no block patch or BE.
        if (com.eynny.npships.authoring.patches.SchematicResources.unavailableTemplateItems(tag))
            throw new IllegalArgumentException(
                    "Unavailable registered item or item component in template payload");
        boolean storedPatch =
                tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).stream()
                        .map(net.minecraft.nbt.CompoundTag.class::cast)
                        .anyMatch(
                                b ->
                                        b.contains(SchematicPatches.EMBEDDED)
                                                || !b.getCompound("nbt").isEmpty());
        storedPatch |=
                tag.contains(SchematicExtensions.KEY)
                        || tag.contains(com.eynny.npships.authoring.document.BlockAreaLayers.KEY)
                        || tag.contains(EntityPatches.EMBEDDED)
                        || (root && config != null && config.authoring().has(EntityPatches.KEY));
        if (!storedPatch
                && (!root || config == null || SchematicPatches.read(config.authoring()).isEmpty()))
            return;
        var checked =
                root ? tag.copy() : com.eynny.npships.authoring.AssemblyPoints.segmentTemplate(tag);
        if (root && config != null) SchematicPatches.embed(checked, config.authoring());
        SchematicPatches.validate(checked);
        var document = new com.eynny.npships.authoring.document.SchematicDocument(checked, null);
        EntityPatches.validate(document, true);
        SchematicExtensions.validate(document, true);
        PatchAvailability.validateDocument(document, level);
        SchematicPatches.requireCapture(checked);
        String unavailable = providers.unavailable(SchematicPatches.fromTemplate(checked));
        if (!unavailable.isEmpty()) throw new IllegalArgumentException(unavailable);
    }

    @Nullable
    NPShipsStructureConfig config() {
        return this.config;
    }

    @Nullable
    BlockPos naturalSpawnSize() {
        return this.naturalSpawnSize;
    }

    NPShipsSchematicManager.SchematicInfo info() {
        return new NPShipsSchematicManager.SchematicInfo(
                this.commandName,
                this.source,
                this.externalJson != null || this.jsonResource != null,
                this.externalNbt,
                this.externalJson,
                this.config,
                this.naturalSpawnSize,
                snapshot);
    }

    private static NPShipsStructureConfig loadConfig(
            @Nullable final Path externalJson,
            @Nullable final Resource jsonResource,
            final String commandName,
            SchematicCatalogEntry owner) {
        try (var scratch =
                owner.resources == null
                        ? null
                        : owner.resources.working("catalogue JSON text scratch")) {
            final String text;
            try {
                if (externalJson != null) {
                    try (InputStream stream = Files.newInputStream(externalJson)) {
                        text = CatalogueInput.json(stream, owner.resources, scratch);
                    }
                } else if (jsonResource != null) {
                    try (InputStream stream = jsonResource.open()) {
                        text = CatalogueInput.json(stream, owner.resources, scratch);
                    }
                } else return null; // NBT-only entries remain manual-only.
            } catch (IOException error) {
                throw new CatalogueDiagnostics.EntryFailure(
                        "cannot read config for " + commandName, error);
            }
            // Retained source-content weight is explicit; this is not the parsed record graph's
            // heap.
            owner.retain(CatalogueResources.text(text));
            // A ResourceManager/pack RuntimeException above is structural. Only parser/data-shape
            // failures below belong to this particular document.
            try {
                return NPShipsStructureConfigLoader.load(
                        JsonParser.parseString(text).getAsJsonObject());
            } catch (com.eynny.npships.resources.ResourceLimitException refused) {
                throw refused;
            } catch (JsonParseException
                    | IllegalArgumentException
                    | IllegalStateException
                    | UnsupportedOperationException
                    | NullPointerException
                    | ClassCastException
                    | ArithmeticException error) {
                throw new CatalogueDiagnostics.EntryFailure(
                        "invalid config for " + commandName, error);
            }
        }
    }
}
