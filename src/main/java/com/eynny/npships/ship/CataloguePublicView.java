package com.eynny.npships.ship;

import com.eynny.npships.api.NPShipsCatalogueApi;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicLong;

/** Detached DTO construction belongs to the catalogue's publication, not to caller-side caches. */
final class CataloguePublicView {
    private static final AtomicLong GENERATIONS = new AtomicLong();

    private CataloguePublicView() {}

    static NPShipsCatalogueApi.Snapshot create(
            NPShipsSchematicManager.CatalogueStatus status,
            Collection<SchematicCatalogEntry> effective,
            Collection<SchematicCatalogEntry> automatic) {
        return new NPShipsCatalogueApi.Snapshot(
                GENERATIONS.incrementAndGet(),
                status.failed(),
                status.excludedEntries(),
                entries(effective),
                entries(automatic));
    }

    private static Map<ResourceLocation, NPShipsCatalogueApi.Entry> entries(
            Collection<SchematicCatalogEntry> entries) {
        var result = new LinkedHashMap<ResourceLocation, NPShipsCatalogueApi.Entry>();
        for (var entry : entries) {
            var id = SchematicNames.parse(entry.commandName());
            var metadata = entry.snapshot == null ? null : entry.snapshot.rarityMetadata();
            var range =
                    metadata != null && metadata.available()
                            ? Optional.of(
                                    new NPShipsCatalogueApi.CountRange(
                                            metadata.range().minimum(), metadata.range().maximum()))
                            : Optional.<NPShipsCatalogueApi.CountRange>empty();
            var size = entry.naturalSpawnSize;
            var config = entry.config();
            var rarity =
                    config == null
                            ? Optional.<NPShipsCatalogueApi.Rarity>empty()
                            : Optional.of(
                                    new NPShipsCatalogueApi.Rarity(
                                            config.rarity().automatic(),
                                            config.rarity().automatic()
                                                    ? OptionalDouble.empty()
                                                    : OptionalDouble.of(
                                                            config.rarity().manualProbability()),
                                            config.selectionWeight()));
            result.put(
                    id,
                    new NPShipsCatalogueApi.Entry(
                            id,
                            entry.external()
                                    ? NPShipsCatalogueApi.Source.MANUAL_WORKING
                                    : NPShipsCatalogueApi.Source.AUTOMATIC_RESOURCE,
                            size == null
                                    ? Optional.empty()
                                    : Optional.of(
                                            new NPShipsCatalogueApi.Dimensions(
                                                    size.getX(), size.getY(), size.getZ())),
                            new NPShipsCatalogueApi.Counts(
                                    range,
                                    metadata == null ? 0 : metadata.paletteCount(),
                                    range.isPresent()
                                            ? ""
                                            : metadata == null
                                                    ? "Native count metadata unavailable"
                                                    : metadata.error()),
                            rarity,
                            config != null && size != null && entry.snapshot != null));
        }
        return result;
    }
}
