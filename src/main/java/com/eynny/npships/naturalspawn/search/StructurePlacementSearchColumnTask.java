package com.eynny.npships.naturalspawn.search;

/**
 * One searchable X/Z column and the vertical structure-origin range belonging to the search region
 * that created it.
 */
record StructurePlacementSearchColumnTask(int x, int z, int minY, int maxY) {}
