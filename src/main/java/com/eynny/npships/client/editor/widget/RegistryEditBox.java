package com.eynny.npships.client.editor.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Supplier;

/** Completion for typed registry IDs. Custom IDs remain editable, even if absent locally. */
public class RegistryEditBox extends EditorEditBox {
    private final Supplier<List<String>> options;
    private final boolean registryId;
    private java.util.function.Function<String, String> suggestionLabel = value -> value;

    public RegistryEditBox suggestionLabel(java.util.function.Function<String, String> label) {
        suggestionLabel = java.util.Objects.requireNonNull(label);
        invalidateSuggestions();
        return this;
    }

    private java.util.function.Predicate<String> inputValidation;

    /** Keep the field owner's validation when options arrive asynchronously. */
    public RegistryEditBox validateWith(java.util.function.Predicate<String> validation) {
        inputValidation = java.util.Objects.requireNonNull(validation);
        return this;
    }

    private String query;
    private List<String> matches = List.of();
    private int choice;
    private boolean dismissed;
    private boolean pointerFocus;

    public void invalidateSuggestions() {
        query = null;
    }

    public RegistryEditBox(int x, int y, int w, Component label, Supplier<List<String>> options) {
        super(Minecraft.getInstance().font, x, y, w, 20, label);
        this.options = options;
        registryId = !options.get().isEmpty();
    }

    private boolean nameField() {
        return getMessage().getContents()
                        instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && (text.getKey().endsWith(".name") || text.getKey().endsWith("_name"));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        // Native containers reassert focus after this callback. Keep the caret chosen
        // by the click instead of replacing it with keyboard-entry select-all.
        pointerFocus = true;
        boolean handled = super.mouseClicked(x, y, button);
        if (!handled) pointerFocus = false;
        return handled;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        pointerFocus = false;
        return super.mouseReleased(x, y, button);
    }

    @Override
    public void setFocused(boolean focused) {
        boolean entering = focused && !isFocused();
        super.setFocused(focused);
        if (entering && !pointerFocus && nameField()) selectName();
    }

    /** Return from presentation hiding without repeating first-entry name selection. */
    public void focusRetained() {
        super.setFocused(true);
    }

    public void selectName() {
        moveCursorToEnd(false);
        setHighlightPos(0);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float tick) {
        String value = getValue();
        int bracket = value.indexOf('[');
        String id = bracket < 0 ? value : value.substring(0, bracket);
        boolean invalidCharacters =
                registryId
                        && java.util.Arrays.stream(id.split(",", -1))
                                .anyMatch(token -> !token.trim().matches("[#a-z0-9_./:-]*"));
        boolean invalid =
                invalidCharacters || inputValidation != null && !inputValidation.test(value);
        if (registryId || inputValidation != null)
            setTextColor(invalid ? 0xffff5555 : EditorButtonTheme.text());
        super.renderWidget(g, mx, my, tick);
        if (invalidCharacters && isFocused()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 700);
            EditorTooltips.show(
                    Minecraft.getInstance().font,
                    Component.translatable("npships.tools.invalid_id_characters"),
                    getX(),
                    getY() + 20);
            g.pose().popPose();
        }
    }

    public static List<String> registry(Registry<?> registry, boolean tags, boolean hash) {
        var result = new TreeSet<String>();
        if (!tags) registry.keySet().forEach(id -> result.add(id.toString()));
        if (tags || hash)
            registry.getTagNames().forEach(t -> result.add((hash ? "#" : "") + t.location()));
        return List.copyOf(result);
    }

    public static List<String> names(String path) {
        var mc = Minecraft.getInstance();
        boolean tag = path.contains("tags");
        if (path.contains("biomes") && mc.level != null)
            return registry(
                    mc.level.registryAccess().registryOrThrow(Registries.BIOME), tag, false);
        if (path.contains("dimensions") && mc.getConnection() != null)
            return mc.getConnection().levels().stream()
                    .map(k -> k.location().toString())
                    .sorted()
                    .toList();
        if (path.contains("fluid") && !path.contains("amount"))
            return registry(BuiltInRegistries.FLUID, false, false);
        if (path.equals("profession"))
            return registry(BuiltInRegistries.VILLAGER_PROFESSION, false, false);
        if (path.equals("villager_type"))
            return registry(BuiltInRegistries.VILLAGER_TYPE, false, false);
        if (path.contains("entity_type") || path.equals("new_pool_type"))
            return registry(BuiltInRegistries.ENTITY_TYPE, false, path.equals("new_pool_type"));
        if (Set.of("item", "trade_buy", "trade_sell").contains(path))
            return registry(BuiltInRegistries.ITEM, false, false);
        if (path.startsWith("environment.") || Set.of("state", "material").contains(path))
            return registry(BuiltInRegistries.BLOCK, tag, false);
        if (path.contains("weather")) return com.eynny.npships.network.WeatherNetworking.names();
        return List.of();
    }

    public static List<String> states(String text) {
        int bracket = text.indexOf('[');
        if (bracket < 0) return names("state");
        var id = net.minecraft.resources.ResourceLocation.tryParse(text.substring(0, bracket));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id) || text.endsWith("]"))
            return List.of();
        var definition = BuiltInRegistries.BLOCK.get(id).getStateDefinition();
        int start = Math.max(bracket, text.lastIndexOf(',')) + 1;
        String prefix = text.substring(0, start), tail = text.substring(start);
        int equals = tail.indexOf('=');
        if (equals < 0)
            return definition.getProperties().stream()
                    .map(p -> prefix + p.getName() + "=")
                    .filter(s -> s.startsWith(text))
                    .sorted()
                    .toList();
        var property = definition.getProperty(tail.substring(0, equals));
        if (property == null) return List.of();
        if (com.eynny.npships.authoring.document.SchematicRotations.orientation(property)
                && Minecraft.getInstance().level != null) {
            var state = definition.getOwner().defaultBlockState();
            try {
                String existing = prefix.substring(bracket + 1);
                if (existing.endsWith(",")) existing = existing.substring(0, existing.length() - 1);
                if (!existing.isEmpty())
                    state =
                            net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                                            Minecraft.getInstance()
                                                    .level
                                                    .holderLookup(Registries.BLOCK),
                                            text.substring(0, bracket) + "[" + existing + "]",
                                            false)
                                    .blockState();
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
                return List.of();
            }
            return orientationValues(prefix, property, state);
        }
        return values(prefix, property);
    }

    private static <T extends Comparable<T>> List<String> orientationValues(
            String prefix,
            net.minecraft.world.level.block.state.properties.Property<T> property,
            net.minecraft.world.level.block.state.BlockState state) {
        return com.eynny.npships.authoring.document.SchematicRotations.allowed(
                        state, Minecraft.getInstance().level)
                .stream()
                .filter(
                        s ->
                                state.getProperties().stream()
                                        .allMatch(
                                                p ->
                                                        p == property
                                                                || !com.eynny.npships.authoring
                                                                        .document.SchematicRotations
                                                                        .orientation(p)
                                                                || s.getValue(p)
                                                                        .equals(state.getValue(p))))
                .map(
                        s ->
                                prefix
                                        + property.getName()
                                        + "="
                                        + property.getName(s.getValue(property))
                                        + "]")
                .distinct()
                .sorted()
                .toList();
    }

    private static <T extends Comparable<T>> List<String> values(
            String prefix, net.minecraft.world.level.block.state.properties.Property<T> property) {
        return property.getPossibleValues().stream()
                .map(v -> prefix + property.getName() + "=" + property.getName(v) + "]")
                .toList();
    }

    private List<String> choices() {
        String value = getValue().trim().toLowerCase(Locale.ROOT);
        if (!Objects.equals(query, value)) {
            query = value;
            choice = 0;
            dismissed = false;
            var available = options.get();
            String filter =
                    value.startsWith("#") && available.stream().noneMatch(n -> n.startsWith("#"))
                            ? value.substring(1)
                            : value;
            matches =
                    available.stream()
                            .filter(
                                    n ->
                                            (n.toLowerCase(Locale.ROOT).contains(filter)
                                                            || suggestionLabel
                                                                    .apply(n)
                                                                    .toLowerCase(Locale.ROOT)
                                                                    .contains(filter))
                                                    && !n.equals(value))
                            .distinct()
                            .toList();
        }
        return dismissed ? List.of() : matches;
    }

    private int rows() {
        return Math.min(
                7,
                Math.min(
                        choices().size(),
                        Math.max(
                                1,
                                (Minecraft.getInstance().getWindow().getGuiScaledHeight() - 48)
                                        / 17)));
    }

    private int start() {
        return Math.max(0, Math.min(choice - rows() + 1, choices().size() - rows()));
    }

    public boolean scrollSuggestions(double x, double y, double delta) {
        if (!isFocused()
                || !visible
                || choices().isEmpty()
                || x < getX()
                || x >= getX() + popupWidth()
                || y < popupY()
                || y >= popupY() + rows() * 17) return false;
        choice = Math.clamp(choice - (int) Math.signum(delta), 0, choices().size() - 1);
        return true;
    }

    private int popupY() {
        int height = rows() * 17;
        return getY() + 20 + height > Minecraft.getInstance().getWindow().getGuiScaledHeight() - 8
                ? Math.max(0, getY() - height)
                : getY() + 20;
    }

    private int popupWidth() {
        return Math.min(
                Math.max(0, width - 2),
                Minecraft.getInstance().getWindow().getGuiScaledWidth() - getX() - 4);
    }

    public void suggestions(GuiGraphics g) {
        if (!isFocused() || !visible) return;
        var list = choices();
        if (list.isEmpty()) return;
        int y = popupY(), w = popupWidth();
        g.pose().pushPose();
        g.pose().translate(0, 0, 600);
        g.fill(getX(), y - 1, getX() + w + 2, y + rows() * 17 + 1, EditorButtonTheme.popupEdge());
        for (int i = 0; i < rows(); i++) {
            g.fill(
                    getX() + 1,
                    y + i * 17,
                    getX() + w + 1,
                    y + (i + 1) * 17,
                    i + start() == choice
                            ? EditorButtonTheme.mix(
                                    EditorButtonTheme.panel(), EditorButtonTheme.accent(), .25f)
                            : EditorButtonTheme.surface(0xff202d3b));
            g.fill(
                    getX(),
                    y + (i + 1) * 17 - 1,
                    getX() + w,
                    y + (i + 1) * 17,
                    EditorButtonTheme.edge());
            g.drawString(
                    Minecraft.getInstance().font,
                    Minecraft.getInstance()
                            .font
                            .plainSubstrByWidth(
                                    suggestionLabel.apply(list.get(i + start())), w - 8),
                    getX() + 4,
                    y + i * 17 + 4,
                    0xffffff);
        }
        if (list.size() > rows()) {
            int top =
                    y + (int) ((double) choice / Math.max(1, list.size() - 1) * (rows() * 17 - 8));
            g.fill(getX() + w - 3, y, getX() + w - 1, y + rows() * 17, EditorButtonTheme.edge());
            g.fill(getX() + w - 3, top, getX() + w - 1, top + 8, EditorButtonTheme.accent());
        }
        g.pose().popPose();
    }

    private void accept(int index) {
        String value = choices().get(index);
        setValue(value);
        query = null;
        choices();
        dismissed = !value.endsWith("=");
        setCursorPosition(getValue().length());
    }

    public boolean popupContains(double x, double y) {
        return isFocused()
                && visible
                && !choices().isEmpty()
                && x >= getX()
                && x < getX() + popupWidth()
                && y >= popupY()
                && y < popupY() + rows() * 17;
    }

    public boolean choose(double x, double y, int button) {
        if (!isFocused() || !visible || button != 0) return false;
        var list = choices();
        int top = popupY(), i = (int) ((y - top) / 17);
        if (y < top || i >= rows() || x < getX() || x >= getX() + popupWidth()) return false;
        accept(i + start());
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        var list = choices();
        if (isFocused() && !list.isEmpty()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                dismissed = true;
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
                choice = Math.floorMod(choice + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), list.size());
                return true;
            }
            if (keyboardPopupActivation(key)) {
                accept(Math.min(choice, list.size() - 1));
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    public boolean keyboardPopup() {
        return isFocused() && visible && !choices().isEmpty();
    }

    public boolean keyboardPopupActivation(int key) {
        return keyboardPopup() && (key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_ENTER);
    }
}
