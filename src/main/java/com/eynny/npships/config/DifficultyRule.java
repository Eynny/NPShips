package com.eynny.npships.config;

import net.minecraft.world.Difficulty;

import org.jetbrains.annotations.Nullable;

public record DifficultyRule(Difficulty min, @Nullable Difficulty max) {

    public DifficultyRule {
        if (min == null) {
            throw new IllegalArgumentException("Minimum difficulty cannot be null.");
        }

        if (max != null && max.getId() < min.getId()) {
            throw new IllegalArgumentException("Maximum difficulty cannot be lower than minimum.");
        }
    }

    public static DifficultyRule defaults() {
        return new DifficultyRule(Difficulty.PEACEFUL, null);
    }

    public boolean allows(final Difficulty difficulty) {
        if (difficulty == null) {
            return false;
        }

        final int id = difficulty.getId();

        if (id < min.getId()) {
            return false;
        }

        return max == null || id <= max.getId();
    }
}
