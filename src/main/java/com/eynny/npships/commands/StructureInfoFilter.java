package com.eynny.npships.commands;

import java.util.*;

/** Bounded, composable read-only query; no world loads or chunk generation. */
public record StructureInfoFilter(
        List<String> terms,
        String dimension,
        String status,
        String sort,
        String type,
        double radius,
        int page,
        long from,
        long to) {
    public static StructureInfoFilter parse(String query) {
        var terms = new ArrayList<String>();
        String dimension = "", status = "", sort = "newest", type = "tracked";
        double radius = Double.POSITIVE_INFINITY;
        int page = 1;
        long from = 0, to = Long.MAX_VALUE;
        for (String part : query.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
            if (part.isEmpty()) continue;
            int separator = part.indexOf('=');
            if (separator < 0) {
                terms.add(part);
                continue;
            }
            String key = part.substring(0, separator), value = part.substring(separator + 1);
            switch (key) {
                case "radius" -> {
                    radius = Double.parseDouble(value);
                    if (!Double.isFinite(radius) || radius < 0 || radius > 1000000)
                        throw new IllegalArgumentException("radius: 0..1000000");
                }
                case "page" -> {
                    page = Integer.parseInt(value);
                    if (page < 1) throw new IllegalArgumentException("page >= 1");
                }
                case "from" -> {
                    from = time(value, false);
                }
                case "to" -> {
                    to = time(value, true);
                }
                case "type" -> {
                    if (!Set.of("all", "tracked", "untracked").contains(value))
                        throw new IllegalArgumentException("type: all / tracked / untracked");
                    type = value;
                }
                case "dimension" -> dimension = value;
                case "status" -> {
                    if (!Set.of("loaded", "unloaded", "expired").contains(value))
                        throw new IllegalArgumentException("status: loaded / unloaded / expired");
                    status = value;
                }
                case "sort" -> {
                    if (!Set.of("nearest", "newest", "oldest").contains(value))
                        throw new IllegalArgumentException("sort: nearest / newest / oldest");
                    sort = value;
                }
                default -> throw new IllegalArgumentException("Unknown filter: " + key);
            }
        }
        if (from > to) throw new IllegalArgumentException("from <= to (UTC)");
        return new StructureInfoFilter(
                List.copyOf(terms), dimension, status, sort, type, radius, page, from, to);
    }

    /** Dates use UTC; a date-only upper bound includes that whole day. Epoch ms stay valid. */
    private static long time(String value, boolean upper) {
        try {
            long result;
            if (value.matches("[0-9]+")) result = Long.parseLong(value);
            else if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                var date = java.time.LocalDate.parse(value);
                result =
                        upper
                                ? Math.subtractExact(
                                        date.plusDays(1)
                                                .atStartOfDay(java.time.ZoneOffset.UTC)
                                                .toInstant()
                                                .toEpochMilli(),
                                        1)
                                : date.atStartOfDay(java.time.ZoneOffset.UTC)
                                        .toInstant()
                                        .toEpochMilli();
            } else result = java.time.Instant.parse(value.toUpperCase(Locale.ROOT)).toEpochMilli();
            if (result < 0) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("from/to: YYYY-MM-DD, ISO UTC or epoch ms >= 0");
        }
    }

    /**
     * Suggestions replace only the active token; completed filters and search terms stay intact.
     */
    public static List<String> suggestions(String query, Collection<String> dimensions) {
        int start = query.lastIndexOf(' ') + 1;
        var used = new HashSet<String>();
        for (String part : query.substring(0, start).toLowerCase(Locale.ROOT).split("\\s+")) {
            int equals = part.indexOf('=');
            if (equals > 0) used.add(part.substring(0, equals));
        }
        var candidates = new ArrayList<String>();
        for (String value :
                List.of(
                        "type=all",
                        "type=tracked",
                        "type=untracked",
                        "radius=",
                        "status=loaded",
                        "status=unloaded",
                        "status=expired",
                        "sort=nearest",
                        "sort=newest",
                        "sort=oldest",
                        "page=",
                        "from=",
                        "to="))
            if (!used.contains(value.substring(0, value.indexOf('=')))) candidates.add(value);
        if (!used.contains("dimension"))
            dimensions.stream()
                    .distinct()
                    .sorted()
                    .map(id -> "dimension=" + id)
                    .forEach(candidates::add);
        return List.copyOf(candidates);
    }

    public boolean matches(
            String uuid, String name, String dim, String state, double distanceSquared) {
        String text = (uuid + " " + name + " " + dim).toLowerCase(Locale.ROOT);
        return terms.stream().allMatch(text::contains)
                && (dimension.isEmpty() || dimension.equals(dim))
                && (status.isEmpty() || status.equalsIgnoreCase(state))
                && (Double.isInfinite(radius) || distanceSquared <= radius * radius);
    }
}
