package com.eynny.npships.api;

import com.eynny.npships.api.internal.NPShipsRuntimeAccess;
import com.eynny.npships.config.RarityParameters;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Read-only catalogue snapshots. Query on the server thread; retain returned values across reloads.
 */
public final class NPShipsCatalogueApi {
    private NPShipsCatalogueApi() {}

    /** Origin of the selected catalogue entry, independent of current spawn eligibility. */
    public enum Source {
        /** Validated resource-manager input eligible for the automatic catalogue. */
        AUTOMATIC_RESOURCE,
        /** Working schematic selected through manual lookup precedence. */
        MANUAL_WORKING
    }

    /**
     * Native local bounds are [0,x), [0,y), [0,z), before physical assembly.
     *
     * @param x extent along the local X axis
     * @param y extent along the local Y axis
     * @param z extent along the local Z axis
     */
    public record Dimensions(int x, int y, int z) {}

    /**
     * Detached minimum and maximum native block counts for the authored composition.
     *
     * @param minimum smallest count represented by the available metadata
     * @param maximum largest count represented by the available metadata
     */
    public record CountRange(long minimum, long maximum) {}

    /**
     * Native count metadata. An empty range means unavailable, never zero blocks.
     *
     * @param range count bounds when the native metadata supports them
     * @param nativePalettes native palette count, or zero when metadata is unavailable
     * @param unavailableReason diagnostic explanation, empty when the range is available
     */
    public record Counts(
            Optional<CountRange> range, int nativePalettes, String unavailableReason) {}

    /**
     * Relative preference, not an absolute event probability. Weight competes within percent
     * groups.
     *
     * @param automatic whether automatic count-derived rarity is configured
     * @param manualValue configured relative manual rarity, absent for automatic rarity
     * @param selectionWeight configured weight within the eligible rarity group
     */
    public record Rarity(boolean automatic, OptionalDouble manualValue, double selectionWeight) {}

    /**
     * Detached source metadata, safe to retain after a catalogue replacement.
     *
     * @param id canonical schematic identifier
     * @param source origin selected for this entry
     * @param dimensions local envelope dimensions when available
     * @param counts available native count metadata or its diagnostic
     * @param rarity configured relative rarity when a valid configuration is available
     * @param naturalPlacementAvailable whether source, configuration and dimensions exist for
     *     natural placement; dynamic world suitability is not implied
     */
    public record Entry(
            ResourceLocation id,
            Source source,
            Optional<Dimensions> dimensions,
            Counts counts,
            Optional<Rarity> rarity,
            boolean naturalPlacementAvailable) {}

    /**
     * effectiveEntries follows manual lookup precedence; automaticEntries contains resources only,
     * including resource versions shadowed by working files. Presence is not dynamic eligibility. A
     * generation changes only on replacement publication, not on a failed refresh or config edit.
     *
     * @param generation identity of this published catalogue replacement
     * @param unavailable whether the published catalogue reports unavailable source state
     * @param excludedEntries entries excluded while building this catalogue
     * @param effectiveEntries detached entries under manual lookup precedence
     * @param automaticEntries detached resource entries, including shadowed resources
     */
    public record Snapshot(
            long generation,
            boolean unavailable,
            int excludedEntries,
            Map<ResourceLocation, Entry> effectiveEntries,
            Map<ResourceLocation, Entry> automaticEntries) {
        /**
         * Copies both lookup maps so the snapshot may outlive a reload.
         *
         * @param generation identity of this published catalogue replacement
         * @param unavailable whether this catalogue reports unavailable source state
         * @param excludedEntries number of excluded entries
         * @param effectiveEntries entries under manual lookup precedence
         * @param automaticEntries resource-only entries
         * @throws NullPointerException if a map, key or value is null
         */
        public Snapshot {
            effectiveEntries = Map.copyOf(effectiveEntries);
            automaticEntries = Map.copyOf(automaticEntries);
        }

        /**
         * Looks up an entry using this snapshot's effective precedence without world access.
         *
         * @param id canonical schematic identifier
         * @return the detached effective entry, or empty when absent
         * @throws NullPointerException if the backing immutable map rejects a null identifier
         */
        public Optional<Entry> find(ResourceLocation id) {
            return Optional.ofNullable(effectiveEntries.get(id));
        }
    }

    /**
     * Returns metadata cached with the existing catalogue. First use may initialize it; later
     * queries perform no IO. The detached result remains readable after refresh and server stop.
     *
     * @param server actual target server, queried on its server thread after levels load
     * @return the current published immutable metadata snapshot
     * @throws IllegalStateException if server is null, the call is off-thread, or the server is not
     *     running with loaded levels
     */
    public static Snapshot snapshot(MinecraftServer server) {
        return NPShipsRuntimeAccess.get().snapshot(server);
    }

    /**
     * Returns current immutable SERVER parameters, independent of a catalogue generation.
     *
     * @param server actual target server, queried on its server thread
     * @return detached current rarity parameters; later config edits do not change this value
     * @throws IllegalStateException if server is null or the call is off its server thread
     */
    public static RarityParameters rarityParameters(MinecraftServer server) {
        return NPShipsRuntimeAccess.get().rarityParameters(server);
    }

    /**
     * Current administrator exclusions only; does not promise restrictions/search/player
     * suitability. It also requires the identifier to exist in the automatic resource catalogue.
     *
     * @param server actual running target server, queried on its server thread after levels load
     * @param id canonical schematic identifier
     * @return whether that automatic entry is enabled by current administrator exclusions
     * @throws IllegalStateException if server is null, the call is off-thread, or loaded running
     *     levels are unavailable
     * @throws NullPointerException if a null identifier reaches an immutable catalogue lookup
     */
    public static boolean automaticAllowedByAdmin(MinecraftServer server, ResourceLocation id) {
        return NPShipsRuntimeAccess.get().automaticAllowedByAdmin(server, id);
    }
}
