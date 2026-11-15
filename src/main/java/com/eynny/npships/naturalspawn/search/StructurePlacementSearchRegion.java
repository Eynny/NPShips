package com.eynny.npships.naturalspawn.search;

/** Region of valid structure origins. */
record StructurePlacementSearchRegion(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {

    boolean isEmpty() {

        return minX > maxX || minY > maxY || minZ > maxZ;
    }

    boolean containsXZ(final int x, final int z) {

        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
