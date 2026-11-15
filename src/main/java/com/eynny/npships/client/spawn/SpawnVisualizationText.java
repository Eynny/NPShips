package com.eynny.npships.client.spawn;

import com.eynny.npships.diagnostics.SpawnVisualizationData.Frame;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Schedule;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/** Bounded, localized presentation of the received diagnostics, with no gameplay queries. */
final class SpawnVisualizationText {
    static final int VALUE_COLOR = 0xF4F4F4;
    static final int FIELD_COLOR = 0xFF2A9D;
    static final int SCHEDULE_COLOR = 0xFF8C00;
    static final int MARKER_COLOR = 0xC42AFF;
    static final int WARNING_COLOR = 0xFF622A;

    private SpawnVisualizationText() {}

    private static Component accent(Component text, int color) {
        return text.copy().withStyle(style -> style.withColor(color));
    }

    static Component pressure(double value) {
        return Component.literal(String.format(Locale.ROOT, "%.3f", value));
    }

    static Component tr(String key, Object... args) {
        return Component.translatable("npships.spawnvisualization." + key, args);
    }

    static Component schedule(Schedule schedule, long now) {
        if (!schedule.enabled()) return accent(tr("schedule_disabled"), WARNING_COLOR);
        if (!schedule.mode().isEmpty()) {
            String phase = schedule.phase();
            String stage =
                    phase.contains("SEARCH")
                            ? "search"
                            : phase.equals("PRELOAD_CHUNKS")
                                    ? "loading"
                                    : phase.equals("SPAWN")
                                            ? "preparing"
                                            : phase.equals("COMPLETE") || phase.equals("FAILED")
                                                    ? "finished"
                                                    : "selecting";
            long seconds =
                    schedule.elapsedTicks() / 20 + (schedule.elapsedTicks() % 20 == 0 ? 0 : 1);
            if (schedule.mode().equals("AUTOMATIC"))
                return accent(tr("schedule_active", tr("stage_" + stage), seconds), SCHEDULE_COLOR);
            return accent(
                    tr(
                            "schedule_busy",
                            tr("mode_" + schedule.mode().toLowerCase(Locale.ROOT)),
                            tr("stage_" + stage),
                            seconds),
                    WARNING_COLOR);
        }
        if (schedule.eligiblePlayers() == 0)
            return accent(tr("schedule_no_players"), WARNING_COLOR);
        if (schedule.nextSelectionTick() < 0)
            return accent(tr("schedule_initializing"), SCHEDULE_COLOR);
        long remaining = Math.max(0, schedule.nextSelectionTick() - now);
        if (remaining == 0) return accent(tr("schedule_due"), SCHEDULE_COLOR);
        long seconds = remaining / 20 + (remaining % 20 == 0 ? 0 : 1);
        long remainder = seconds % 60;
        return accent(
                tr(
                        "schedule_next",
                        tr("schedule_next_label"),
                        seconds / 60,
                        remainder < 10 ? "0" + remainder : Long.toString(remainder)),
                SCHEDULE_COLOR);
    }

    private static void appendMarkers(List<Component> lines, Frame frame, long now) {
        for (int i = frame.markers().size() - 1; i >= 0; i--) {
            var m = frame.markers().get(i);
            if (now >= m.expiresAt()) continue;
            lines.add(
                    accent(
                            tr(
                                            "marker",
                                            m.position().getX(),
                                            m.position().getY(),
                                            m.position().getZ(),
                                            (m.expiresAt() - now + 19) / 20)
                                    .copy()
                                    .withStyle(
                                            style ->
                                                    style.withClickEvent(
                                                                    new ClickEvent(
                                                                            ClickEvent.Action
                                                                                    .COPY_TO_CLIPBOARD,
                                                                            m.position().getX()
                                                                                    + " "
                                                                                    + m.position()
                                                                                            .getY()
                                                                                    + " "
                                                                                    + m.position()
                                                                                            .getZ()))
                                                            .withHoverEvent(
                                                                    new HoverEvent(
                                                                            HoverEvent.Action
                                                                                    .SHOW_TEXT,
                                                                            tr("copy_marker")))),
                            MARKER_COLOR));
        }
    }

    static List<Component> lines(
            Frame frame,
            long now,
            boolean fresh,
            boolean shader,
            Vec3 camera,
            Function<UUID, String> name) {
        var lines = new ArrayList<Component>();
        if (!fresh) {
            lines.add(accent(tr("stale"), WARNING_COLOR));
            // INITIAL_POINT is a frozen observation, not a live field/schedule estimate.
            appendMarkers(lines, frame, now);
            return lines;
        }
        if (frame.overflow()) {
            lines.add(accent(tr("overflow"), WARNING_COLOR));
        } else if (!shader) {
            lines.add(accent(tr("shader"), WARNING_COLOR));
        }
        var strongest =
                frame.fields().stream()
                        .filter(f -> camera.distanceToSqr(f.position()) <= f.radius() * f.radius())
                        .max(java.util.Comparator.comparingDouble(f -> f.pressure()))
                        .orElse(null);
        long radius = frame.fields().isEmpty() ? 0 : Math.round(frame.fields().getFirst().radius());
        if (!frame.overflow() && shader)
            lines.add(
                    accent(
                            tr(
                                    "summary",
                                    tr("fields_label", frame.fields().size()),
                                    strongest == null
                                            ? tr("pressure_outside")
                                            : pressure(strongest.pressure()),
                                    radius),
                            FIELD_COLOR));
        lines.add(schedule(frame.schedule(), now));
        appendMarkers(lines, frame, now);
        if (frame.schedule().mode().isEmpty() && !frame.schedule().lastOutcome().isEmpty())
            lines.add(
                    tr(
                            "schedule_last",
                            tr(
                                    "outcome_"
                                            + frame.schedule()
                                                    .lastOutcome()
                                                    .toLowerCase(Locale.ROOT))));
        if (strongest != null && frame.fields().size() > 1 && lines.size() < 4)
            lines.add(accent(tr("dominant", name.apply(strongest.owner())), FIELD_COLOR));
        return lines;
    }
}
