package com.eynny.npships.block;

/** Tick-based timing shared by authoritative impact and interpolated client geometry. */
public final class ClaimAnimation {
    public static final int DESCENT = 12, IMPACT = 14, FINISH = 30, BLINK_FINISH = 12;

    private ClaimAnimation() {}

    public record Pose(double column, double cap, double top) {}

    public static Pose pose(double tick) {
        if (tick < 0 || tick >= FINISH) return new Pose(0, 0, 0);
        if (tick <= DESCENT) {
            double y = -3 * smooth(tick / DESCENT);
            return new Pose(y, y, y);
        }
        if (tick <= IMPACT)
            return new Pose(-3, -3, -3 - 2 * smooth((tick - DESCENT) / (IMPACT - DESCENT)));
        double remaining = 1 - smooth((tick - IMPACT) / (FINISH - IMPACT));
        return new Pose(-3 * remaining, -3 * remaining, -5 * remaining);
    }

    public static boolean blinkOff(double tick) {
        return tick >= 0 && tick < 3 || tick >= 6 && tick < 9;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }
}
