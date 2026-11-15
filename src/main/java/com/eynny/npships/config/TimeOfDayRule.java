package com.eynny.npships.config;

import java.util.List;

public record TimeOfDayRule(List<Interval> intervals) {

    public TimeOfDayRule {
        intervals = List.copyOf(intervals == null ? List.of() : intervals);

        for (Interval interval : intervals) {
            if (interval.from() < 0 || interval.from() > 23999) {
                throw new IllegalArgumentException(
                        "Invalid Minecraft day-time: " + interval.from());
            }

            if (interval.to() < 0 || interval.to() > 23999) {
                throw new IllegalArgumentException("Invalid Minecraft day-time: " + interval.to());
            }
        }
    }

    public static TimeOfDayRule allowAll() {
        return new TimeOfDayRule(List.of());
    }

    public record Interval(int from, int to) {}
}
