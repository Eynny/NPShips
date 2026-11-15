package com.eynny.npships.naturalspawn.search;

import net.minecraft.core.BlockPos;

/**
 * Three-dimensional world-space area available to the natural-spawn spatial scanner.
 *
 * <p>Coordinates are inclusive on both ends.
 *
 * <p>The volume itself does not scan anything and does not access the world.
 */
public record SpawnSearchVolume(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public SpawnSearchVolume {

        if (minX > maxX) {
            throw new IllegalArgumentException("minX cannot be greater than maxX.");
        }

        if (minY > maxY) {
            throw new IllegalArgumentException("minY cannot be greater than maxY.");
        }

        if (minZ > maxZ) {
            throw new IllegalArgumentException("minZ cannot be greater than maxZ.");
        }
        try {
            Math.multiplyExact(
                    Math.multiplyExact(span(minX, maxX), (long) span(minY, maxY)),
                    span(minZ, maxZ));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Search volume dimensions exceed the supported numeric range.", overflow);
        }
    }

    /**
     * Creates a search volume around an approximate origin.
     *
     * <p>The approximate point is treated as the centre of the allowed origin search area.
     *
     * <p>The structure size is included so that the whole structure can exist inside the scanned
     * world volume.
     */
    public static SpawnSearchVolume around(
            final BlockPos approximateOrigin,
            final BlockPos structureSize,
            final int horizontalRadius,
            final int verticalRadius) {

        if (approximateOrigin == null) {
            throw new IllegalArgumentException("approximateOrigin cannot be null.");
        }

        if (structureSize == null) {
            throw new IllegalArgumentException("structureSize cannot be null.");
        }

        if (structureSize.getX() <= 0 || structureSize.getY() <= 0 || structureSize.getZ() <= 0) {

            throw new IllegalArgumentException("Structure size must be positive: " + structureSize);
        }

        if (horizontalRadius < 0) {
            throw new IllegalArgumentException("horizontalRadius cannot be negative.");
        }

        if (verticalRadius < 0) {
            throw new IllegalArgumentException("verticalRadius cannot be negative.");
        }

        /*
         * Candidate origins are allowed to move horizontally and
         * vertically away from approximateOrigin.
         *
         * Because origin is the minimum corner of the structure,
         * the world-space volume extends by structureSize - 1 beyond
         * the maximum candidate origin.
         */
        final int minX = coordinate((long) approximateOrigin.getX() - horizontalRadius);

        final int minY = coordinate((long) approximateOrigin.getY() - verticalRadius);

        final int minZ = coordinate((long) approximateOrigin.getZ() - horizontalRadius);

        final int maxX =
                coordinate(
                        (long) approximateOrigin.getX()
                                + horizontalRadius
                                + structureSize.getX()
                                - 1);

        final int maxY =
                coordinate(
                        (long) approximateOrigin.getY()
                                + verticalRadius
                                + structureSize.getY()
                                - 1);

        final int maxZ =
                coordinate(
                        (long) approximateOrigin.getZ()
                                + horizontalRadius
                                + structureSize.getZ()
                                - 1);

        return new SpawnSearchVolume(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int sizeX() {
        return span(minX, maxX);
    }

    public int sizeY() {
        return span(minY, maxY);
    }

    public int sizeZ() {
        return span(minZ, maxZ);
    }

    private static int span(int min, int max) {
        return Math.toIntExact((long) max - min + 1);
    }

    private static int coordinate(long value) {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Search coordinate exceeds the supported integer range: " + value, overflow);
        }
    }

    /** Number of blocks contained in the volume. */
    public long blockCount() {
        return (long) sizeX() * (long) sizeY() * (long) sizeZ();
    }

    public boolean contains(final BlockPos pos) {

        return pos.getX() >= this.minX
                && pos.getX() <= this.maxX
                && pos.getY() >= this.minY
                && pos.getY() <= this.maxY
                && pos.getZ() >= this.minZ
                && pos.getZ() <= this.maxZ;
    }

    /** Returns the world position of the first block in the volume. */
    public BlockPos minPos() {
        return new BlockPos(this.minX, this.minY, this.minZ);
    }

    /**
     * Returns whether a complete structure of the supplied size can have its origin at the given
     * position while remaining inside this search volume.
     */
    public boolean canContainStructure(final BlockPos origin, final BlockPos structureSize) {

        if (origin == null || structureSize == null) {

            return false;
        }

        return origin.getX() >= this.minX
                && origin.getY() >= this.minY
                && origin.getZ() >= this.minZ
                && structureSize.getX() > 0
                && structureSize.getY() > 0
                && structureSize.getZ() > 0
                && (long) origin.getX() + structureSize.getX() - 1 <= this.maxX
                && (long) origin.getY() + structureSize.getY() - 1 <= this.maxY
                && (long) origin.getZ() + structureSize.getZ() - 1 <= this.maxZ;
    }
}
