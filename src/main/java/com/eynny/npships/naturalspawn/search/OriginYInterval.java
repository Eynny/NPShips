package com.eynny.npships.naturalspawn.search;

public record OriginYInterval(int minY, int maxY) {

    public OriginYInterval {
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must be <= maxY");
        }
    }

    public boolean contains(int y) {
        return y >= minY && y <= maxY;
    }

    public int clamp(int y) {
        if (y < minY) {
            return minY;
        }

        if (y > maxY) {
            return maxY;
        }

        return y;
    }
}
