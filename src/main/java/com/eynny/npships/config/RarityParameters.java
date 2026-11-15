package com.eynny.npships.config;

/**
 * One immutable SERVER-configuration snapshot per opportunity; no world or RNG access. This
 * original FQN is an intentional API signature dependency.
 *
 * @param pivotBlocks block count where the automatic curve changes exponent
 * @param scaleBlocks scale of the automatic rarity curve
 * @param sizeExponent exponent below the pivot
 * @param tailExponent exponent above the pivot
 * @param halfLifeTicks pressure observation half-life in game ticks
 * @param pressureStrength strength applied to bounded pressure
 * @param maximumPressure cap on pressure used by relative rarity
 * @param extraRadius extra local pressure radius in blocks
 */
public record RarityParameters(
        double pivotBlocks,
        double scaleBlocks,
        double sizeExponent,
        double tailExponent,
        long halfLifeTicks,
        double pressureStrength,
        double maximumPressure,
        int extraRadius) {
    /** Default validated SERVER rarity parameters. */
    public static final RarityParameters DEFAULTS =
            new RarityParameters(20000, 6000, 2.6, .1, 24000, 2.75, 2, 160);

    /**
     * Validates finite supported ranges without accessing configuration or world state.
     *
     * @throws IllegalArgumentException if a component lies outside its supported range
     * @param pivotBlocks block count where the automatic curve changes exponent
     * @param scaleBlocks scale of the automatic rarity curve
     * @param sizeExponent exponent below the pivot
     * @param tailExponent exponent above the pivot
     * @param halfLifeTicks pressure observation half-life in game ticks
     * @param pressureStrength strength applied to bounded pressure
     * @param maximumPressure cap on pressure used by relative rarity
     * @param extraRadius extra local pressure radius in blocks
     */
    public RarityParameters {
        if (!Double.isFinite(pivotBlocks)
                || pivotBlocks < 1
                || pivotBlocks > 1e15
                || !Double.isFinite(scaleBlocks)
                || scaleBlocks < 1
                || scaleBlocks > 1e15
                || !Double.isFinite(sizeExponent)
                || sizeExponent < .01
                || sizeExponent > 8
                || !Double.isFinite(tailExponent)
                || tailExponent < .01
                || tailExponent > 8
                || halfLifeTicks < 1
                || halfLifeTicks > 1000000000L
                || !Double.isFinite(pressureStrength)
                || pressureStrength < 0
                || pressureStrength > 16
                || !Double.isFinite(maximumPressure)
                || maximumPressure < 0
                || maximumPressure > 16
                || extraRadius < 0
                || extraRadius > 1000000)
            throw new IllegalArgumentException("Invalid rarity parameters");
    }

    /**
     * Evaluates the automatic size preference in log space.
     *
     * @param blocks nonnegative authored block count
     * @return logarithm of automatic preference; zero blocks returns zero
     * @throws IllegalArgumentException if blocks is negative
     */
    public double automaticLogProbability(long blocks) {
        if (blocks < 0) throw new IllegalArgumentException("Negative block count");
        if (blocks == 0) return 0;
        double odds =
                sizeExponent * (Math.log(Math.min(blocks, pivotBlocks)) - Math.log(scaleBlocks))
                        + tailExponent * Math.max(0, Math.log(blocks) - Math.log(pivotBlocks));
        return -Math.max(0, odds) - Math.log1p(Math.exp(-Math.abs(odds)));
    }

    /**
     * Evaluates the automatic preference with a positive representable lower bound.
     *
     * @param blocks nonnegative authored block count
     * @return preference, at least Double.MIN_VALUE
     * @throws IllegalArgumentException if blocks is negative
     */
    public double automaticProbability(long blocks) {
        return Math.max(Double.MIN_VALUE, Math.exp(automaticLogProbability(blocks)));
    }

    /**
     * Applies the configured bounded pressure exponent to an author preference.
     *
     * @param author finite relative preference greater than zero and at most one
     * @param pressure finite nonnegative observed pressure
     * @return logarithm of the effective relative preference
     * @throws IllegalArgumentException if an input is outside its supported range
     */
    public double effectiveLogRarity(double author, double pressure) {
        if (!Double.isFinite(author)
                || author <= 0
                || author > 1
                || !Double.isFinite(pressure)
                || pressure < 0)
            throw new IllegalArgumentException("Invalid relative rarity input");
        return Math.log(author) * (1 + pressureStrength * Math.min(maximumPressure, pressure));
    }

    /**
     * Rounds the effective preference to the existing integer percentage group.
     *
     * @param author finite relative preference greater than zero and at most one
     * @param pressure finite nonnegative observed pressure
     * @return rounded percentage from zero through one hundred
     * @throws IllegalArgumentException if an input is outside its supported range
     */
    public int percentGroup(double author, double pressure) {
        // Use q itself for the specified rounding, retaining the log separately for tiny weights.
        // When H=0, pow(author,1) also avoids a log/exp round trip at percentage boundaries.
        effectiveLogRarity(author, pressure);
        double q = Math.pow(author, 1 + pressureStrength * Math.min(maximumPressure, pressure));
        return (int) Math.max(0, Math.min(100, Math.round(100 * q)));
    }
}
