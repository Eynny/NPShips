package com.eynny.npships.naturalspawn.search;

import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.naturalspawn.NaturalSpawnChunkLoader;
import com.eynny.npships.naturalspawn.NaturalSpawnSearchResult;

import net.minecraft.core.BlockPos;

/** Cooperates within ONE tick's budgets; a requested column does not cost an extra tick. */
public final class NaturalSpawnSearchDriver {
    public record Result(
            NaturalSpawnSearchResult search, SpawnAreaScanner.Status scanner, int scanOperations) {}

    public static Result step(
            StructurePlacementSearch search,
            SpawnAreaScanner scanner,
            NaturalSpawnChunkLoader loader,
            BlockPos preferred,
            int searchBudget,
            int scanBudget,
            int chunkBudget) {
        return step(
                search, scanner, loader, preferred, searchBudget, scanBudget, chunkBudget, null);
    }

    public static Result step(
            StructurePlacementSearch search,
            SpawnAreaScanner scanner,
            NaturalSpawnChunkLoader loader,
            BlockPos preferred,
            int searchBudget,
            int scanBudget,
            int chunkBudget,
            @org.jetbrains.annotations.Nullable SpawnTimingProfiler timing) {
        loader.useRequestedChunks();
        scanner.requestChunks(loader);
        chunkBudget -= loader.step(chunkBudget).chunksProcessed();
        int searched = 0, scanned = 0;
        var scanStatus = SpawnAreaScanner.Status.COMPLETE;
        NaturalSpawnSearchResult result;
        do {
            // Empty request queue != fully scanned active volume.
            search.setActiveVolumeComplete(scanner.snapshot().isComplete());
            long token = timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.SEARCH);
            try {
                result = search.step(searchBudget - searched, preferred);
            } finally {
                if (timing != null) timing.end(token);
            }
            searched += result.operationsUsed();
            if (result.status() != NaturalSpawnSearchResult.Status.WAITING_FOR_DATA
                    || scanned >= scanBudget) break;
            scanner.requestChunks(loader);
            if (chunkBudget > 0) chunkBudget -= loader.step(chunkBudget).chunksProcessed();
            var scan = scanner.step(scanBudget - scanned);
            scanned += scan.checksPerformed();
            scanStatus = scan.status();
            if (scanStatus == SpawnAreaScanner.Status.WAITING_FOR_UNLOADED_CHUNK) break;
            // No state or range could be provided: retain WAITING instead of spinning.
            if (scan.checksPerformed() == 0) break;
        } while (searched < searchBudget);
        return new Result(
                new NaturalSpawnSearchResult(result.status(), result.placement(), searched),
                scanStatus,
                scanned);
    }
}
