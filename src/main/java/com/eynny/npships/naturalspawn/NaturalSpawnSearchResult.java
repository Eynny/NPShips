package com.eynny.npships.naturalspawn;

public record NaturalSpawnSearchResult(
        Status status, NaturalSpawnPlacement placement, int operationsUsed) {

    public enum Status {
        RUNNING,
        WAITING_FOR_DATA,
        FOUND,
        EXHAUSTED
    }

    public static NaturalSpawnSearchResult running(int operationsUsed) {
        return new NaturalSpawnSearchResult(Status.RUNNING, null, operationsUsed);
    }

    public static NaturalSpawnSearchResult waitingForData(int operationsUsed) {
        return new NaturalSpawnSearchResult(Status.WAITING_FOR_DATA, null, operationsUsed);
    }

    public static NaturalSpawnSearchResult found(
            NaturalSpawnPlacement placement, int operationsUsed) {
        return new NaturalSpawnSearchResult(Status.FOUND, placement, operationsUsed);
    }

    public static NaturalSpawnSearchResult exhausted(int operationsUsed) {
        return new NaturalSpawnSearchResult(Status.EXHAUSTED, null, operationsUsed);
    }
}
