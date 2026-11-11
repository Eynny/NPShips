package com.eynny.npships.config;

/** Inclusive bounds for the schematic origin, not its top or reference point. */
public record HeightRange(int min, int max) {
    public HeightRange {
        if (min > max) {
            throw new IllegalArgumentException("Height range min must not exceed max.");
        }
    }

    public boolean contains(final int y) {
        return y >= min && y <= max;
    }
}
