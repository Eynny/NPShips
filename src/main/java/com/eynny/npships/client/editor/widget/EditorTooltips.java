package com.eynny.npships.client.editor.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** One deferred popup per frame, bounded by the current GUI size. */
public final class EditorTooltips {
    private EditorTooltips() {}

    /**
     * Preserve native placement preference, then keep the complete tooltip background on-screen.
     */
    public static net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner
            bounded(
                    net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner
                            nativePositioner) {
        return (width, height, mouseX, mouseY, tooltipWidth, tooltipHeight) -> {
            var position =
                    nativePositioner.positionTooltip(
                            width, height, mouseX, mouseY, tooltipWidth, tooltipHeight);
            return new org.joml.Vector2i(
                    Math.clamp(position.x(), 5, Math.max(5, width - tooltipWidth - 5)),
                    Math.clamp(position.y(), 5, Math.max(5, height - tooltipHeight - 5)));
        };
    }

    /**
     * Fit native and custom text using the same screen bounds; unrelated copy instructions are not
     * added.
     */
    public static List<FormattedCharSequence> fit(
            Font font, List<FormattedCharSequence> text, int screenWidth, int screenHeight) {
        if (font == null || screenWidth < 32 || screenHeight < 26) return List.of();
        int width = Math.max(1, Math.min(360, screenWidth - 16));
        int limit = Math.max(1, (screenHeight - 16) / 10);
        var lines = new ArrayList<FormattedCharSequence>();
        boolean inputTruncated = false;
        for (var line : text) {
            if (font.width(line) <= width) lines.add(line);
            else {
                var styled = Component.empty();
                var run = new StringBuilder();
                var active =
                        new net.minecraft.network.chat.Style[] {
                            net.minecraft.network.chat.Style.EMPTY
                        };
                int[] count = {0};
                boolean[] truncated = {false};
                line.accept(
                        (index, style, codepoint) -> {
                            if (++count[0] > width * (limit + 1) * 2) {
                                truncated[0] = true;
                                return false;
                            }
                            if (!active[0].equals(style) && !run.isEmpty()) {
                                styled.append(
                                        Component.literal(run.toString()).withStyle(active[0]));
                                run.setLength(0);
                            }
                            active[0] = style;
                            run.appendCodePoint(codepoint);
                            return true;
                        });
                if (!run.isEmpty())
                    styled.append(Component.literal(run.toString()).withStyle(active[0]));
                lines.addAll(font.split(styled, width));
                if (truncated[0]) {
                    inputTruncated = true;
                    break;
                }
            }
            if (lines.size() > limit) break;
        }
        if (inputTruncated || lines.size() > limit) {
            lines.subList(Math.min(limit - 1, lines.size()), lines.size()).clear();
            lines.add(Component.literal("...").getVisualOrderText());
        }
        return List.copyOf(lines);
    }

    public static void show(Font font, Component text, int x, int y) {
        var screen = Minecraft.getInstance().screen;
        if (screen != null)
            show(font, font.split(text, Math.max(1, Math.min(360, screen.width - 16))), x, y);
    }

    public static void show(Font font, List<FormattedCharSequence> text, int x, int y) {
        var screen = Minecraft.getInstance().screen;
        if (screen == null) return;
        var lines = fit(font, text, screen.width, screen.height);
        screen.setTooltipForNextRenderPass(
                lines,
                bounded(
                        (screenWidth, screenHeight, mouseX, mouseY, tooltipWidth, tooltipHeight) ->
                                net.minecraft.client.gui.screens.inventory.tooltip
                                        .DefaultTooltipPositioner.INSTANCE
                                        .positionTooltip(
                                                screenWidth,
                                                screenHeight,
                                                x,
                                                y,
                                                tooltipWidth,
                                                tooltipHeight)),
                true);
    }
}
