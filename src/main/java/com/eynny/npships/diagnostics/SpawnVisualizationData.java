package com.eynny.npships.diagnostics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** Bounded transient diagnostics, never SavedData or an input to gameplay selection. */
public final class SpawnVisualizationData {
    public static final int MAX_FIELDS = 64, MAX_MARKERS = 64, MAX_ROSTER = 256;
    public static final int MARKER_TICKS = 2400, UPDATE_TICKS = 10;

    private SpawnVisualizationData() {}

    public record Field(UUID owner, Vec3 position, double pressure, double radius) {
        public Field {
            if (owner == null
                    || position == null
                    || !coordinate(position.x)
                    || !coordinate(position.y)
                    || !coordinate(position.z)
                    || !Double.isFinite(pressure)
                    || pressure < 0
                    || pressure > 16
                    || !Double.isFinite(radius)
                    || radius < 0
                    || radius > 2000000)
                throw new IllegalArgumentException("Invalid diagnostic field");
        }
    }

    public record Marker(long id, BlockPos position, long assigned, double frozenPressure) {
        public Marker {
            if (id < 1
                    || position == null
                    || assigned < 0
                    || assigned > Long.MAX_VALUE - MARKER_TICKS
                    || !Double.isFinite(frozenPressure)
                    || frozenPressure < 0
                    || frozenPressure > 16)
                throw new IllegalArgumentException("Invalid INITIAL_POINT marker");
            position = position.immutable();
        }

        public long expiresAt() {
            return assigned + MARKER_TICKS;
        }
    }

    /** Read-only manager state. The deadline is an opportunity, never a placement promise. */
    public record Schedule(
            boolean enabled,
            int eligiblePlayers,
            long nextSelectionTick,
            String mode,
            String phase,
            long elapsedTicks,
            String lastOutcome) {
        public Schedule {
            if (eligiblePlayers < 0
                    || eligiblePlayers > 1000000
                    || nextSelectionTick < -1
                    || !token(mode, 16)
                    || !token(phase, 32)
                    || elapsedTicks < 0
                    || !token(lastOutcome, 64))
                throw new IllegalArgumentException("Invalid diagnostic schedule");
        }

        public static Schedule empty() {
            return new Schedule(false, 0, -1, "", "", 0, "");
        }
    }

    public record Frame(
            long tick,
            boolean overflow,
            List<Field> fields,
            List<Marker> markers,
            Schedule schedule,
            double maximumPressure) {
        public Frame(
                long tick,
                boolean overflow,
                List<Field> fields,
                List<Marker> markers,
                Schedule schedule) {
            this(tick, overflow, fields, markers, schedule, 2.0);
        }

        public Frame(long tick, boolean overflow, List<Field> fields, List<Marker> markers) {
            this(tick, overflow, fields, markers, Schedule.empty());
        }

        public Frame {
            if (tick < 0
                    || !Double.isFinite(maximumPressure)
                    || maximumPressure < 0
                    || maximumPressure > 16
                    || schedule == null
                    || fields.size() > MAX_FIELDS
                    || markers.size() > MAX_MARKERS
                    || overflow && !fields.isEmpty())
                throw new IllegalArgumentException("Diagnostic snapshot exceeds bounds");
            fields = List.copyOf(fields);
            markers = List.copyOf(markers);
        }

        public static Frame empty() {
            return new Frame(0, false, List.of(), List.of());
        }
    }

    private static boolean coordinate(double n) {
        return Double.isFinite(n) && Math.abs(n) <= 1e9;
    }

    private static boolean token(String value, int maximum) {
        if (value == null || value.length() > maximum) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '_' && (c < 'A' || c > 'Z')) return false;
        }
        return true;
    }

    /** CPU oracle for the renderer: hard spheres, inclusive boundary, pointwise MAX. */
    public static double maximum(List<Field> fields, double x, double y, double z) {
        double maximum = 0;
        for (var f : fields) {
            if (f.position.distanceToSqr(x, y, z) <= f.radius * f.radius)
                maximum = Math.max(maximum, f.pressure);
        }
        return maximum;
    }
}
