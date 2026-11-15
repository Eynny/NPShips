package com.eynny.npships.config;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public record RealTimeRule(List<Interval> intervals) {

    public RealTimeRule {
        intervals = List.copyOf(intervals == null ? List.of() : intervals);
    }

    public static RealTimeRule allowAll() {
        return new RealTimeRule(List.of());
    }

    public record Interval(LocalTime from, LocalTime to) {}

    public static LocalTime parseTime(final String value) {
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid real time. Expected HH:mm, got: " + value, e);
        }
    }
}
