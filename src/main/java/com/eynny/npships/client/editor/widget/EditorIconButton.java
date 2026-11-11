package com.eynny.npships.client.editor.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/** Shared 20px actions. Optional 16px PNGs can replace the vanilla placeholder icons. */
public final class EditorIconButton extends Button {
    public enum Style {
        VANILLA,
        FLAT,
        OUTLINE,
        BEVEL,
        INSET,
        ROUND,
        TOOLBAR,
        TAB
    }

    private static Style currentStyle = Style.OUTLINE;

    public static Style currentStyle() {
        return currentStyle;
    }

    public static void useStyle(Style style) {
        currentStyle = style;
    }

    private Style fixedStyle;
    private EditorButtonTheme.Palette fixedPalette;
    private final EditorButtonTheme.Fade fade = new EditorButtonTheme.Fade();

    public EditorIconButton palette(EditorButtonTheme.Palette value) {
        fixedPalette = value;
        return this;
    }

    private boolean held, textButton, dropdownText;
    private long keyboardPressUntil;
    private Component hint;
    private java.util.function.Supplier<Component> hintSource;

    public EditorIconButton style(Style value) {
        fixedStyle = value;
        return this;
    }

    public EditorIconButton textButton(int width) {
        textButton = true;
        setWidth(width);
        return this;
    }

    public EditorIconButton dropdownTextButton(int width) {
        dropdownText = true;
        return textButton(width);
    }

    public void hint(Component value) {
        hint = value;
    }

    public void hint(java.util.function.Supplier<Component> source) {
        hintSource = source;
    }

    public Component baseHint() {
        return hint == null ? getMessage() : hint;
    }

    public Component tooltipText() {
        return hintSource == null ? baseHint() : hintSource.get();
    }

    private final ResourceLocation texture;
    private final boolean hasTexture;
    private final ItemStack fallback;
    private final String iconName;
    private int lastX = Integer.MIN_VALUE, lastY;
    private long stationarySince;
    private java.util.function.BooleanSupplier selected = () -> false;

    public EditorIconButton selected(java.util.function.BooleanSupplier value) {
        selected = value;
        return this;
    }

    public EditorIconButton(String id, Component label, int x, int y, Runnable action) {
        super(x, y, 20, 20, label, b -> action.run(), DEFAULT_NARRATION);
        if (label.getContents()
                instanceof net.minecraft.network.chat.contents.TranslatableContents text) {
            String key = text.getKey() + "_hint";
            if (net.minecraft.client.resources.language.I18n.exists(key))
                hint = Component.translatable(key);
        }
        String name = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        iconName = name;
        texture =
                ResourceLocation.fromNamespaceAndPath(
                        "npships",
                        "textures/gui/editor/"
                                + (name.startsWith("fitted_plane_")
                                                && !name.equals("fitted_plane_lock")
                                        ? name.replace("fitted_plane_", "shape_plane_")
                                        : name)
                                + ".png");
        boolean dye =
                java.util.Arrays.stream(net.minecraft.world.item.DyeColor.values())
                        .anyMatch(color -> color.getName().equals(name));
        hasTexture =
                !dye
                        && Minecraft.getInstance()
                                .getResourceManager()
                                .getResource(texture)
                                .isPresent();
        fallback = new ItemStack(icon(name));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (active && visible && button == 0 && isMouseOver(x, y)) held = true;
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        held = false;
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        boolean used = super.keyPressed(key, scan, modifiers);
        if (used) keyboardPressUntil = net.minecraft.Util.getMillis() + 150;
        return used;
    }

    private static Item icon(String id) {
        return switch (id) {
            case "mode_select" -> Items.ARROW;
            case "mode_create" -> Items.BRICKS;
            case "mode_delete" -> Items.SHEARS;
            case "config" -> Items.WRITABLE_BOOK;
            case "editor_settings" -> Items.KNOWLEDGE_BOOK;
            case "blocks", "single_blocks", "area_fill", "area_blocks" -> Items.STONE;
            case "entities", "area_entities" -> Items.PIG_SPAWN_EGG;
            case "segments", "area", "resize", "area_select", "area_new_hint", "editor" ->
                    Items.STRUCTURE_BLOCK;
            case "zones", "area_zones" -> Items.SPAWNER;
            case "loot" -> Items.CHEST;
            case "patches" -> Items.COMPARATOR;
            case "glue" -> Items.SLIME_BALL;
            case "physics" -> Items.HEAVY_WEIGHTED_PRESSURE_PLATE;
            case "physics_region" -> Items.COMPASS;
            case "fitted_plane_lock" -> Items.IRON_DOOR;
            case "fitted_plane" -> Items.LIGHT_BLUE_STAINED_GLASS_PANE;
            case "area_shape" -> Items.CLAY_BALL;
            case "area_generate" -> Items.BRICKS;
            case "area_bounds_only" -> Items.STRUCTURE_VOID;
            case "area_sticky" -> Items.SLIME_BALL;
            case "area_transparent" -> Items.TINTED_GLASS;
            case "area_apply" -> Items.KNOWLEDGE_BOOK;
            case "area_merge" -> Items.HOPPER;
            case "area_subtract" -> Items.SHEARS;
            case "area_random_enabled" -> Items.LEVER;
            case "area_random", "area_reroll" -> Items.AMETHYST_SHARD;
            case "area_random_add" -> Items.GRASS_BLOCK;
            case "deform_shape" -> Items.COMPASS;
            case "deform_reset" -> Items.ENDER_EYE;
            case "rotation_snap" -> Items.COMPASS;
            case "grid", "grid_paper" -> Items.IRON_BARS;
            case "grid_black", "black" -> Items.BLACK_DYE;
            case "white" -> Items.WHITE_DYE;
            case "orange" -> Items.ORANGE_DYE;
            case "magenta" -> Items.MAGENTA_DYE;
            case "light_blue" -> Items.LIGHT_BLUE_DYE;
            case "yellow" -> Items.YELLOW_DYE;
            case "lime" -> Items.LIME_DYE;
            case "pink" -> Items.PINK_DYE;
            case "gray" -> Items.GRAY_DYE;
            case "light_gray" -> Items.LIGHT_GRAY_DYE;
            case "cyan" -> Items.CYAN_DYE;
            case "purple" -> Items.PURPLE_DYE;
            case "blue" -> Items.BLUE_DYE;
            case "brown" -> Items.BROWN_DYE;
            case "green" -> Items.GREEN_DYE;
            case "red" -> Items.RED_DYE;
            case "grid_fade", "grid_fade_cells" -> Items.GRAY_DYE;
            case "brightness" -> Items.GLOWSTONE_DUST;
            case "symmetry", "symmetric_resize" -> Items.ARMOR_STAND;
            case "bounds", "grid_pin" -> Items.STRUCTURE_VOID;
            case "mass_planes" -> Items.YELLOW_STAINED_GLASS_PANE;
            case "camera_default", "camera_legacy" -> Items.COMPASS;
            case "camera_front" -> Items.OBSERVER;
            case "camera_side" -> Items.PISTON;
            case "camera_top" -> Items.MAP;
            case "camera_cycle" -> Items.REPEATER;
            case "assembly" -> Items.END_ROD;
            case "area_hollow" -> Items.GLASS;
            case "save", "export_save" -> Items.WRITABLE_BOOK;
            case "export_profile" -> Items.MAP;
            case "styles" -> Items.PAINTING;
            case "back", "turn_left", "area_turn_left", "turn_right", "area_turn_right" ->
                    Items.ARROW;
            case "delete", "area_delete", "area_cut" -> Items.SHEARS;
            case "copy", "area_copy", "area_paste" -> Items.PAPER;
            case "rename" -> Items.NAME_TAG;
            case "refresh", "preview", "reset_view" -> Items.ENDER_EYE;
            case "warnings" -> Items.REDSTONE_TORCH;
            case "import_area", "area_move" -> Items.HOPPER;
            default -> Items.CRAFTING_TABLE;
        };
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float tick) {
        long now = net.minecraft.Util.getMillis();
        if (mx != lastX || my != lastY || !isMouseOver(mx, my)) {
            lastX = mx;
            lastY = my;
            stationarySince = now;
        }
        // Retain native tooltip/narration ownership; do not also queue a second mouse tooltip.
        Component help = tooltipText();
        boolean captionOnly =
                textButton
                        && help.getString().equals(getMessage().getString())
                        && Minecraft.getInstance().font.width(getMessage())
                                <= width - (dropdownText ? 17 : 8);
        setTooltip(
                isMouseOver(mx, my) && now - stationarySince >= 500 && !captionOnly
                        ? Tooltip.create(help)
                        : null);
        if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                        Minecraft.getInstance().getWindow().getWindow(), 0)
                != org.lwjgl.glfw.GLFW.GLFW_PRESS) held = false;
        boolean down = active && (held && isMouseOver(mx, my) || now < keyboardPressUntil),
                chosen = selected.getAsBoolean();
        Style style = fixedStyle == null ? (textButton ? Style.BEVEL : currentStyle) : fixedStyle;
        if (style == Style.VANILLA) {
            super.renderWidget(g, mx, my, tick);
            if (down || chosen)
                g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0x88405268);
        } else
            background(
                    g,
                    style,
                    down,
                    chosen,
                    (isHovered()
                                    || isFocused()
                                            && Minecraft.getInstance()
                                                    .getLastInputType()
                                                    .isKeyboard())
                            && active);
        int offset = down ? 1 : 0;
        if (textButton) {
            var font = Minecraft.getInstance().font;
            int available = Math.max(0, width - (dropdownText ? 17 : 8));
            String caption = getMessage().getString();
            if (font.width(caption) > available) {
                String suffix = font.width("...") <= available ? "..." : "";
                caption =
                        font.plainSubstrByWidth(
                                        caption, Math.max(0, available - font.width(suffix)))
                                + suffix;
            }
            g.drawCenteredString(
                    font,
                    caption,
                    getX() + (width - (dropdownText ? 9 : 0)) / 2 + offset,
                    getY() + 6 + offset,
                    active ? EditorButtonTheme.text(fixedPalette) : EditorButtonTheme.muted());
            if (dropdownText)
                EditorButton.arrow(
                        g,
                        getX() + width - 7 + offset,
                        getY() + height / 2 + offset,
                        active ? EditorButtonTheme.text(fixedPalette) : EditorButtonTheme.muted(),
                        false);
        } else {
            if (hasTexture) {
                g.setColor(.25f, .25f, .25f, 1);
                g.blit(texture, getX() + 3 + offset, getY() + 3 + offset, 0, 0, 16, 16, 16, 16);
                g.setColor(1, 1, 1, 1);
                g.blit(texture, getX() + 2 + offset, getY() + 2 + offset, 0, 0, 16, 16, 16, 16);
            } else if (drawAreaTool(
                    g,
                    getX() + offset,
                    getY() + offset,
                    active ? EditorButtonTheme.text(fixedPalette) : EditorButtonTheme.muted())) {
                // Theme-aware built-ins remain replaceable by the normal PNG resource path.
            } else if (iconName.startsWith("shape_")) {
                drawShape(
                        g,
                        getX() + offset,
                        getY() + offset,
                        iconName.substring(6),
                        active ? EditorButtonTheme.text() : EditorButtonTheme.muted());
            } else if (iconName.equals("undo") || iconName.equals("redo")) {
                g.pose().pushPose();
                g.pose()
                        .translate(
                                getX() + offset + (iconName.equals("redo") ? 20 : 0),
                                getY() + offset,
                                0);
                if (iconName.equals("redo")) g.pose().scale(-1, 1, 1);
                int ink = active ? 0xffe3eff9 : EditorButtonTheme.surface(0xff77818b);
                g.fill(5, 6, 14, 8, ink);
                g.fill(13, 7, 15, 14, ink);
                g.fill(8, 13, 14, 15, ink);
                for (int i = 0; i < 4; i++) g.fill(3 + i, 6 - i, 5 + i, 8 + i, ink);
                g.pose().popPose();
            } else g.renderItem(fallback, getX() + 2 + offset, getY() + 2 + offset);
            if (!active) g.fill(getX() + 2, getY() + 2, getX() + 18, getY() + 18, 0x88202a34);
        }
        if (chosen && style == Style.VANILLA)
            g.fill(getX() + 2, getY() + 18, getX() + width - 2, getY() + 20, 0xff70ffb0);
    }

    private boolean drawAreaTool(GuiGraphics g, int x, int y, int ink) {
        if (iconName.startsWith("direct_axis_")) {
            g.drawCenteredString(
                    Minecraft.getInstance().font,
                    iconName.substring(iconName.length() - 1).toUpperCase(Locale.ROOT),
                    x + 10,
                    y + 6,
                    ink);
            return true;
        }
        int[][] lines =
                switch (iconName) {
                    case "direct_rotation" ->
                            new int[][] {
                                {4, 8, 5, 5},
                                {5, 5, 13, 4},
                                {13, 4, 16, 8},
                                {16, 8, 15, 14},
                                {15, 14, 9, 16},
                                {4, 4, 4, 8},
                                {4, 8, 8, 8}
                            };
                    case "direct_bend" ->
                            new int[][] {
                                {4, 15, 4, 8},
                                {4, 8, 7, 4},
                                {7, 4, 12, 3},
                                {12, 3, 16, 5},
                                {8, 15, 8, 9},
                                {8, 9, 10, 7},
                                {10, 7, 13, 7},
                                {13, 7, 15, 9}
                            };
                    case "direct_taper" ->
                            new int[][] {
                                {4, 15, 7, 4},
                                {7, 4, 12, 4},
                                {12, 4, 15, 15},
                                {15, 15, 4, 15},
                                {5, 11, 14, 11}
                            };
                    case "direct_shear" ->
                            new int[][] {
                                {3, 15, 12, 15},
                                {5, 10, 14, 10},
                                {7, 5, 16, 5},
                                {3, 15, 7, 5},
                                {12, 15, 16, 5}
                            };
                    case "direct_bulge" ->
                            new int[][] {
                                {3, 14, 5, 13},
                                {5, 13, 8, 6},
                                {8, 6, 11, 6},
                                {11, 6, 14, 13},
                                {14, 13, 17, 14},
                                {3, 16, 17, 16}
                            };
                    case "direct_scale", "direct_scale_all" ->
                            new int[][] {
                                {4, 12, 4, 16},
                                {4, 16, 8, 16},
                                {8, 16, 8, 12},
                                {8, 12, 4, 12},
                                {10, 10, 16, 4},
                                {12, 4, 16, 4},
                                {16, 4, 16, 8}
                            };
                    case "direct_both" ->
                            new int[][] {
                                {3, 10, 17, 10},
                                {10, 3, 10, 17},
                                {3, 10, 6, 7},
                                {3, 10, 6, 13},
                                {17, 10, 14, 7},
                                {17, 10, 14, 13}
                            };
                    case "direct_fixed_end" ->
                            new int[][] {
                                {4, 4, 4, 16},
                                {7, 10, 16, 10},
                                {7, 10, 10, 7},
                                {7, 10, 10, 13},
                                {16, 10, 13, 7},
                                {16, 10, 13, 13}
                            };
                    case "direct_apply_values" -> new int[][] {{4, 10, 8, 14}, {8, 14, 16, 5}};
                    case "direct_reset" -> new int[][] {{5, 5, 15, 15}, {15, 5, 5, 15}};
                    case "direct_reverse" ->
                            new int[][] {
                                {4, 7, 15, 7},
                                {15, 7, 15, 15},
                                {15, 15, 8, 15},
                                {4, 7, 8, 3},
                                {4, 7, 8, 11}
                            };
                    case "direct_pick_center", "direct_center_reset" ->
                            new int[][] {
                                {3, 10, 17, 10},
                                {10, 3, 10, 17},
                                {6, 6, 14, 6},
                                {14, 6, 14, 14},
                                {14, 14, 6, 14},
                                {6, 14, 6, 6}
                            };
                    case "continuous_surface" ->
                            new int[][] {
                                {3, 5, 8, 5},
                                {8, 5, 8, 10},
                                {8, 10, 13, 10},
                                {13, 10, 13, 15},
                                {13, 15, 17, 15},
                                {3, 9, 5, 9},
                                {5, 9, 5, 14},
                                {5, 14, 10, 14}
                            };
                    case "fitted_plane_xz" ->
                            new int[][] {
                                {3, 12, 10, 7}, {10, 7, 17, 10}, {17, 10, 10, 15}, {10, 15, 3, 12}
                            };
                    case "fitted_plane_xy" ->
                            new int[][] {
                                {4, 4, 15, 4}, {15, 4, 15, 15}, {15, 15, 4, 15}, {4, 15, 4, 4}
                            };
                    case "fitted_plane_yz" ->
                            new int[][] {
                                {7, 3, 14, 6}, {14, 6, 14, 17}, {14, 17, 7, 14}, {7, 14, 7, 3}
                            };
                    default -> null;
                };
        if (lines == null) return false;
        for (var line : lines) {
            int steps = Math.max(Math.abs(line[2] - line[0]), Math.abs(line[3] - line[1]));
            for (int i = 0; i <= steps; i++) {
                int px =
                        x
                                + Math.round(
                                        line[0]
                                                + (line[2] - line[0])
                                                        * i
                                                        / (float) Math.max(1, steps));
                int py =
                        y
                                + Math.round(
                                        line[1]
                                                + (line[3] - line[1])
                                                        * i
                                                        / (float) Math.max(1, steps));
                g.fill(px, py, px + 1, py + 1, ink);
            }
        }
        return true;
    }

    private static void drawShape(GuiGraphics g, int x, int y, String shape, int color) {
        if (shape.equals("none")) {
            for (int i = 3; i < 17; i++) {
                g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
                g.fill(x + 19 - i, y + i, x + 20 - i, y + i + 1, color);
            }
            return;
        }
        for (int py = 3; py < 17; py++)
            for (int px = 3; px < 17; px++) {
                double u = (px - 9.5) / 6.5, v = (py - 9.5) / 6.5;
                boolean inside;
                if (shape.contains("hemisphere"))
                    inside = u * u + v * v <= 1 && (shape.endsWith("down") ? v >= 0 : v <= 0);
                else if (shape.equals("ellipsoid")) inside = u * u + v * v <= 1;
                else if (shape.equals("diamond")) inside = Math.abs(u) + Math.abs(v) <= 1;
                else if (shape.contains("tetrahedron") || shape.equals("pyramid"))
                    inside =
                            shape.endsWith("side")
                                    ? Math.abs(v) <= (1 - u) / 2
                                    : Math.abs(u) <= (v + 1) / 2;
                else if (shape.contains("prism")) inside = Math.abs(u) + Math.abs(v) * .5 <= 1;
                else if (shape.startsWith("plane"))
                    inside =
                            shape.endsWith("xz")
                                    ? Math.abs(v) < .25
                                    : shape.endsWith("yz")
                                            ? Math.abs(u) < .25
                                            : Math.abs(u) < .8 && Math.abs(v) < .8;
                else if (shape.contains("cylinder"))
                    inside =
                            shape.endsWith("x")
                                    ? Math.abs(v) < .65
                                    : shape.endsWith("tilted")
                                            ? Math.abs(u - v) < .55
                                            : Math.abs(u) < .65;
                else inside = Math.abs(u) < .85 && Math.abs(v) < .85;
                if (inside) g.fill(x + px, y + py, x + px + 1, y + py + 1, color);
            }
    }

    private void background(
            GuiGraphics g, Style style, boolean down, boolean chosen, boolean hover) {
        if (style == Style.OUTLINE || style == Style.BEVEL) {
            EditorButtonTheme.draw(
                    g,
                    getX(),
                    getY(),
                    width,
                    height,
                    style == Style.BEVEL,
                    active,
                    down,
                    chosen,
                    fade.hover(hover),
                    fixedPalette);
            return;
        }
        int x = getX(), y = getY(), r = x + width, b = y + height;
        int base =
                !active
                        ? EditorButtonTheme.surface(0xff252d36)
                        : down
                                ? EditorButtonTheme.surface(0xff14232e)
                                : chosen
                                        ? EditorButtonTheme.accent()
                                        : hover
                                                ? EditorButtonTheme.surface(0xff40546b)
                                                : EditorButtonTheme.surface(0xff2d3b4c);
        int border =
                chosen
                        ? EditorButtonTheme.accent()
                        : hover
                                ? EditorButtonTheme.surface(0xff93aabe)
                                : EditorButtonTheme.surface(0xff596a7c);
        switch (style) {
            case FLAT -> {
                g.fill(x, y, r, b, base);
                if (chosen) g.fill(x, y, r, y + 2, border);
            }
            case INSET -> {
                g.fill(x, y, r, b, base);
                g.fill(x, y, r, y + 2, EditorButtonTheme.surface(0xff14202c));
                g.fill(x, y, x + 2, b, EditorButtonTheme.surface(0xff14202c));
                g.fill(x + 2, b - 2, r, b, EditorButtonTheme.surface(0xff61788c));
                g.fill(r - 2, y + 2, r, b, EditorButtonTheme.surface(0xff61788c));
            }
            case ROUND -> {
                g.fill(x + 2, y, r - 2, b, border);
                g.fill(x, y + 2, r, b - 2, border);
                g.fill(x + 2, y + 1, r - 2, b - 1, base);
                g.fill(x + 1, y + 2, r - 1, b - 2, base);
            }
            case TOOLBAR -> {
                g.fill(
                        x,
                        y,
                        r,
                        b,
                        hover || chosen || down ? base : EditorButtonTheme.surface(0xff202e3c));
                g.fill(r - 1, y + 3, r, b - 3, EditorButtonTheme.surface(0xff405164));
                if (chosen || down) g.fill(x + 2, b - 2, r - 2, b, EditorButtonTheme.accent());
            }
            case TAB -> {
                g.fill(x, y, r, b, base);
                g.fill(
                        x,
                        b - 2,
                        r,
                        b,
                        chosen || down
                                ? EditorButtonTheme.accent()
                                : EditorButtonTheme.surface(0xff16212d));
                if (hover) g.fill(x, y, r, y + 1, border);
            }
            default -> {}
        }
    }

    @Override
    public void renderString(GuiGraphics g, net.minecraft.client.gui.Font font, int color) {}
}
