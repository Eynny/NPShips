package com.eynny.npships.config;

import java.time.DateTimeException;
import java.time.MonthDay;
import java.util.List;

public record RealDateRule(List<Interval> intervals) {

    public RealDateRule {
        intervals = List.copyOf(intervals == null ? List.of() : intervals);
    }

    public static RealDateRule allowAll() {
        return new RealDateRule(List.of());
    }

    public record Interval(MonthDay from, MonthDay to) {}

    public static MonthDay parseMonthDay(final String value) {
        try {
            return MonthDay.parse("--" + value);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "Invalid real date. Expected MM-DD, got: " + value, e);
        }
    }
}
