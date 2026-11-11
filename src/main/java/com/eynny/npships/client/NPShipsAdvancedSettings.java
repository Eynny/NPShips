package com.eynny.npships.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.eynny.npships.client.editor.widget.RegistryEditBox;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.ship.NPShipsSchematicManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ValueSpec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Uses NeoForge's supported section factory; ConfigSpecs, Undo and restart ownership stay native.
 */
public final class NPShipsAdvancedSettings {
    private NPShipsAdvancedSettings() {}

    public static Screen create(ModContainer mod, Screen parent, boolean serverEditable) {
        return new Root(mod, parent, serverEditable);
    }

    /** Navigation only: values, native undo/save and restart confirmation retain their owner. */
    private static final class Root extends Screen {
        private final ModContainer mod;
        private final Screen parent;
        private final boolean editable;
        private net.neoforged.neoforge.common.ModConfigSpec.RestartType restart =
                net.neoforged.neoforge.common.ModConfigSpec.RestartType.NONE;

        Root(ModContainer mod, Screen parent, boolean editable) {
            super(Component.translatable("npships.settings.advanced"));
            this.mod = mod;
            this.parent = parent;
            this.editable = editable;
        }

        @Override
        protected void init() {
            int y = 45, w = Math.min(310, Math.max(0, width - 32));
            for (var type : java.util.List.of(ModConfig.Type.CLIENT, ModConfig.Type.SERVER))
                for (var config :
                        net.neoforged.fml.config.ModConfigs.getModConfigs(mod.getModId())) {
                    if (config.getType() != type) continue;
                    var label =
                            Component.translatable(
                                    "npships.settings.native_"
                                            + type.name().toLowerCase(java.util.Locale.ROOT));
                    var button =
                            addRenderableWidget(
                                    Button.builder(
                                                    label,
                                                    b ->
                                                            minecraft.setScreen(
                                                                    new Section(
                                                                            this, type, config,
                                                                            label, editable)))
                                            .bounds((width - w) / 2, y, w, 20)
                                            .build());
                    boolean remote =
                            type == ModConfig.Type.SERVER
                                    && (!minecraft.hasSingleplayerServer()
                                            || minecraft.getSingleplayerServer().isPublished());
                    button.active =
                            ((net.neoforged.neoforge.common.ModConfigSpec) config.getSpec())
                                            .isLoaded()
                                    && !remote;
                    if (!button.active)
                        button.setTooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                remote
                                                        ? "npships.settings.native_remote"
                                                        : "npships.settings.join")));
                    y += 28;
                }
            var patches =
                    addRenderableWidget(
                            Button.builder(
                                            Component.translatable("npships.patch_manager.open"),
                                            button ->
                                                    minecraft.setScreen(
                                                            new PatchManagerScreen(this)))
                                    .bounds((width - w) / 2, y, w, 20)
                                    .build());
            patches.active = minecraft.getConnection() != null;
            patches.setTooltip(
                    Tooltip.create(Component.translatable("npships.patch_manager.open_hint")));
            addRenderableWidget(
                    Button.builder(Component.translatable("gui.done"), b -> onClose())
                            .bounds((width - w) / 2, height - 28, w, 20)
                            .build());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics g, int x, int y, float dt) {
            super.render(g, x, y, dt);
            g.drawCenteredString(font, title, width / 2, 15, 0xffffff);
        }

        @Override
        public void onClose() {
            var nativeOwner = new ConfigurationScreen(mod, parent);
            nativeOwner.needsRestart = restart;
            // Native close/restart paths require the Screen lifecycle, even for this delegate.
            nativeOwner.init(minecraft, width, height);
            nativeOwner.onClose();
        }
    }

    /** Stored editor preferences have their own direct controls, not raw config-page fields. */
    public static boolean hiddenClientField(Object spec, String key) {
        return spec == com.eynny.npships.config.NPShipsEditorConfig.SPEC
                && java.util.Set.of(
                                "palette",
                                "inspector_preferred_width",
                                "pinned_brushes",
                                "view_preferences")
                        .contains(key);
    }

    public static String unloadSeconds(long millis) {
        return BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString();
    }

    public static long unloadMillis(String seconds) {
        long value = new BigDecimal(seconds).movePointRight(3).longValueExact();
        if (value < 0) throw new IllegalArgumentException("Negative unload grace");
        return value;
    }

    public static class Section extends ConfigurationSectionScreen {
        private final boolean readOnly;
        private FullLabel heading;

        public Section(
                Screen parent,
                ModConfig.Type type,
                ModConfig config,
                Component title,
                boolean serverEditable) {
            super(
                    parent,
                    type,
                    config,
                    title,
                    (context, key, element) -> {
                        if (hiddenClientField(context.modSpec(), key)) return null;
                        if ((context.modSpec() == NPShipsRuntimeConfig.SPEC)
                                && !serverEditable
                                && context.valueSpecs().get(key) instanceof ValueSpec) {
                            var widget = element.getWidget(Minecraft.getInstance().options);
                            widget.active = false;
                            return new Element(
                                    element.name(),
                                    (element.tooltip() == null
                                                    ? Component.empty()
                                                    : element.tooltip().copy())
                                            .append("\n")
                                            .append(
                                                    Component.translatable(
                                                            "npships.settings.read_only")),
                                    widget,
                                    false);
                        }
                        return element;
                    });
            readOnly = !serverEditable && (config.getSpec() == NPShipsRuntimeConfig.SPEC);
        }

        protected Section(
                Context context,
                Screen parent,
                java.util.Map<String, Object> specs,
                String key,
                java.util.Set<? extends UnmodifiableConfig.Entry> entries,
                Component title) {
            super(context, parent, specs, key, entries, title);
            readOnly = false;
        }

        private Section(
                Context context,
                Screen parent,
                UnmodifiableConfig spec,
                String key,
                UnmodifiableConfig values) {
            super(
                    context,
                    parent,
                    spec.valueMap(),
                    key,
                    values.entrySet(),
                    Component.translatable("npships.configuration." + key));
            readOnly = parent instanceof Section section && section.readOnly;
        }

        @Override
        protected void addContents() {
            // Only presentation changes: NeoForge still creates each value, callback and Undo
            // owner.
            list = layout.addToContents(new FullLabelOptionsList(minecraft, width, this));
            addOptions();
        }

        @Override
        protected void addTitle() {
            var lines = font.split(title, Math.max(1, width - 24));
            int height = Math.max(font.lineHeight, lines.size() * font.lineHeight);
            layout.setHeaderHeight(Math.max(33, height + 16));
            heading =
                    layout.addToHeader(
                            new FullLabel(Math.max(1, width - 24), height, title, font, true));
        }

        @Override
        protected void repositionElements() {
            if (heading != null) {
                heading.setWidth(Math.max(1, width - 24));
                heading.setHeight(
                        Math.max(
                                font.lineHeight,
                                font.split(title, heading.getWidth()).size() * font.lineHeight));
                layout.setHeaderHeight(Math.max(33, heading.getHeight() + 16));
            }
            super.repositionElements();
            if (list instanceof FullLabelOptionsList captions) captions.reflow();
        }

        @Override
        protected ConfigurationSectionScreen rebuild() {
            if (list instanceof FullLabelOptionsList captions) captions.rows.clear();
            return super.rebuild();
        }

        @Override
        public void onClose() {
            if (changed && lastScreen instanceof Root root)
                root.restart = root.restart.with(needsRestart);
            if (context.modSpec() == com.eynny.npships.config.NPShipsEditorConfig.SPEC)
                com.eynny.npships.config.NPShipsEditorConfig.withNativeOwner(super::onClose);
            else super.onClose();
        }

        @Override
        protected void createResetButton() {
            super.createResetButton();
            if (readOnly) setResetButtonstate(false);
        }

        @Override
        protected void createUndoButton() {
            super.createUndoButton();
            if (readOnly) setUndoButtonstate(false);
        }

        @Override
        protected void setUndoButtonstate(boolean state) {
            super.setUndoButtonstate(state && !readOnly);
        }

        @Override
        protected void setResetButtonstate(boolean state) {
            super.setResetButtonstate(state && !readOnly);
        }

        @Override
        protected net.minecraft.network.chat.MutableComponent getTranslationComponent(String key) {
            var label = super.getTranslationComponent(key);
            var spec = getValueSpec(key);
            if (spec != null
                    && spec.restartType()
                            != net.neoforged.neoforge.common.ModConfigSpec.RestartType.NONE)
                return label.withStyle(net.minecraft.ChatFormatting.YELLOW);
            return label;
        }

        @Override
        protected Component getTooltipComponent(
                String key, net.neoforged.neoforge.common.ModConfigSpec.Range<?> range) {
            var tooltip =
                    super.getTooltipComponent(key, key.equals("unload_timeout_ms") ? null : range);
            var spec = getValueSpec(key);
            if (spec == null
                    || spec.restartType()
                            == net.neoforged.neoforge.common.ModConfigSpec.RestartType.NONE)
                return tooltip;
            return tooltip.copy()
                    .append("\n")
                    .append(
                            Component.translatable(
                                            spec.restartType()
                                                            == net.neoforged.neoforge.common
                                                                    .ModConfigSpec.RestartType.GAME
                                                    ? "npships.settings.restart_game"
                                                    : "npships.settings.restart_world")
                                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
        }

        @Override
        protected Element createLongValue(
                String key, ValueSpec spec, Supplier<Long> source, Consumer<Long> target) {
            if (context.modSpec() != NPShipsRuntimeConfig.SPEC || !key.equals("unload_timeout_ms"))
                return super.createLongValue(key, spec, source, target);
            return createStringValue(
                    key,
                    text -> {
                        try {
                            return spec.test(unloadMillis(text));
                        } catch (IllegalArgumentException | ArithmeticException invalid) {
                            return false;
                        }
                    },
                    () -> unloadSeconds(source.get()),
                    text -> target.accept(unloadMillis(text)));
        }

        @Override
        protected <T> Element createList(
                String key,
                ModConfigSpec.ListValueSpec spec,
                ModConfigSpec.ConfigValue<List<T>> value) {
            if (context.modSpec() != NPShipsRuntimeConfig.SPEC
                    || !List.of(
                                    "disabled_addons",
                                    "disabled_structures",
                                    "entity_cleanup_whitelist")
                            .contains(key)) return super.createList(key, spec, value);
            return new Element(
                    getTranslationComponent(key),
                    getTooltipComponent(key, null),
                    Button.builder(
                                    Component.translatable("selectWorld.edit"),
                                    button -> {
                                        var screen =
                                                (CompletionList<?>)
                                                        sectionCache.computeIfAbsent(
                                                                key,
                                                                ignored ->
                                                                        new CompletionList<>(
                                                                                Context.list(
                                                                                        context,
                                                                                        this),
                                                                                key,
                                                                                Component.empty()
                                                                                        .append(
                                                                                                getTitle())
                                                                                        .append(
                                                                                                " > ")
                                                                                        .append(
                                                                                                getTranslationComponent(
                                                                                                        key)),
                                                                                spec,
                                                                                value));
                                        minecraft.setScreen(screen.rebuild());
                                    })
                            .tooltip(Tooltip.create(getTooltipComponent(key, null)))
                            .build(),
                    false);
        }

        @Override
        protected Element createSection(
                String key, UnmodifiableConfig spec, UnmodifiableConfig values) {
            if (spec.isEmpty()) return null;
            // Policy values remain in SERVER storage; the manager has one entry in the parent.
            if (context.modSpec() == NPShipsRuntimeConfig.SPEC && key.equals("patches"))
                return null;
            var label = getTranslationComponent(key);
            return new Element(
                    label,
                    getTooltipComponent(key, null),
                    Button.builder(
                                    Component.translatable("selectWorld.edit"),
                                    button -> {
                                        var section =
                                                (Section)
                                                        sectionCache.computeIfAbsent(
                                                                key,
                                                                ignored ->
                                                                        new Section(
                                                                                context, this, spec,
                                                                                key, values));
                                        minecraft.setScreen(section.rebuild());
                                    })
                            .tooltip(Tooltip.create(getTooltipComponent(key, null)))
                            .build(),
                    false);
        }
    }

    /** Keep native rows and controls; wrap captions instead of StringWidget's ellipsis. */
    private static final class FullLabelOptionsList
            extends net.minecraft.client.gui.components.OptionsList {
        private final net.minecraft.client.gui.Font font;

        private record Row(
                net.minecraft.client.gui.components.AbstractWidget left,
                net.minecraft.client.gui.components.AbstractWidget right) {}

        private final List<Row> rows = new ArrayList<>();

        FullLabelOptionsList(Minecraft mc, int width, Section owner) {
            super(mc, width, owner);
            font = mc.font;
        }

        @Override
        public void addSmall(
                net.minecraft.client.gui.components.AbstractWidget left,
                net.minecraft.client.gui.components.AbstractWidget right) {
            rows.add(new Row(left, right));
            install(left, right);
        }

        void reflow() {
            var focused = getFocused();
            var child = focused == null ? null : focused.getFocused();
            double scroll = getScrollAmount();
            children().clear();
            for (var row : rows) install(row.left(), row.right());
            if (child != null)
                for (var entry : children())
                    if (entry.children().contains(child)) {
                        setFocused(entry);
                        entry.setFocused(child);
                        break;
                    }
            setScrollAmount(scroll);
        }

        private void install(
                net.minecraft.client.gui.components.AbstractWidget left,
                net.minecraft.client.gui.components.AbstractWidget right) {
            if (!(left instanceof net.minecraft.client.gui.components.StringWidget)
                    || left.getMessage().getString().isEmpty()) {
                super.addSmall(left, right);
                return;
            }
            var lines = font.split(left.getMessage(), Math.max(1, left.getWidth()));
            if (lines.size() <= 2) {
                var label =
                        new FullLabel(
                                left.getWidth(), left.getHeight(), left.getMessage(), font, false);
                label.setTooltip(left.getTooltip());
                super.addSmall(label, right);
                return;
            }
            // Exceptionally long captions get their own full-width native rows. The same
            // value widget stays in the original right column with its native input owner.
            var wide = font.split(left.getMessage(), 310);
            for (int first = 0; first < wide.size(); first += 2) {
                var label = new FullLabel(310, 20, left.getMessage(), font, false);
                label.fixedLines =
                        List.copyOf(wide.subList(first, Math.min(first + 2, wide.size())));
                label.setTooltip(left.getTooltip());
                super.addSmall(label, null);
            }
            super.addSmall(
                    new net.minecraft.client.gui.components.StringWidget(
                            150, 20, Component.empty(), font),
                    right);
        }
    }

    private static final class FullLabel extends net.minecraft.client.gui.components.StringWidget {
        private final boolean centered;
        private List<net.minecraft.util.FormattedCharSequence> fixedLines;

        FullLabel(
                int width,
                int height,
                Component caption,
                net.minecraft.client.gui.Font font,
                boolean centered) {
            super(width, height, caption, font);
            this.centered = centered;
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            var font = getFont();
            var lines =
                    fixedLines == null
                            ? font.split(getMessage(), Math.max(1, getWidth()))
                            : fixedLines;
            int y = getY() + Math.max(0, (getHeight() - lines.size() * font.lineHeight) / 2);
            for (var line : lines) {
                int x = getX() + (centered ? (getWidth() - font.width(line)) / 2 : 0);
                g.drawString(font, line, x, y, getColor());
                y += font.lineHeight;
            }
        }
    }

    /** One native list draft/undo owner; suggestions are detached once on the integrated server. */
    private static final class CompletionList<T>
            extends ConfigurationScreen.ConfigurationListScreen<T> {
        private final List<RegistryEditBox> boxes = new ArrayList<>();
        private final CompletableFuture<List<String>> available;
        private boolean received;

        CompletionList(
                Context context,
                String key,
                Component title,
                ModConfigSpec.ListValueSpec spec,
                ModConfigSpec.ConfigValue<List<T>> value) {
            super(context, key, title, spec, value);
            var server = Minecraft.getInstance().getSingleplayerServer();
            available =
                    server == null
                            ? CompletableFuture.completedFuture(List.of())
                            : server.submit(
                                            () -> {
                                                if (key.equals("entity_cleanup_whitelist"))
                                                    return BuiltInRegistries.ENTITY_TYPE
                                                            .keySet()
                                                            .stream()
                                                            .map(Object::toString)
                                                            .sorted()
                                                            .toList();
                                                return NPShipsSchematicManager.getAll(server)
                                                        .stream()
                                                        .map(
                                                                info ->
                                                                        com.eynny.npships.ship
                                                                                .SchematicNames
                                                                                .parse(
                                                                                        info
                                                                                                .commandName()))
                                                        .map(
                                                                id ->
                                                                        key.equals(
                                                                                        "disabled_addons")
                                                                                ? id.getNamespace()
                                                                                : id.toString())
                                                        .distinct()
                                                        .sorted()
                                                        .toList();
                                            })
                                    .exceptionally(failure -> List.of());
        }

        @Override
        public ConfigurationSectionScreen rebuild() {
            boxes.clear();
            return super.rebuild();
        }

        @Override
        protected Element createStringListValue(int index, String value) {
            var box =
                    new RegistryEditBox(
                            0,
                            0,
                            Button.DEFAULT_WIDTH,
                            getTranslationComponent(key),
                            () -> available.getNow(List.of()));
            box.validateWith(text -> spec.test(List.of(text)));
            boxes.add(box);
            box.setMaxLength(Math.max(192, value.length()));
            box.setValue(value);
            box.setTooltip(Tooltip.create(Component.translatable("npships.settings.list_" + key)));
            box.setResponder(
                    text -> {
                        if (!spec.test(List.of(text)) || text.equals(cfgList.get(index))) return;
                        @SuppressWarnings("unchecked")
                        T replacement = (T) text;
                        undoManager.add(
                                v -> {
                                    cfgList.set(index, v);
                                    onChanged(key);
                                },
                                replacement,
                                v -> {
                                    cfgList.set(index, v);
                                    onChanged(key);
                                },
                                cfgList.get(index));
                    });
            return new Element(getTranslationComponent(key), getTooltipComponent(key, null), box);
        }

        private boolean displayed(RegistryEditBox box) {
            return box.active
                    && box.getY() >= list.getY()
                    && box.getY() + box.getHeight() <= list.getBottom();
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, float tick) {
            if (!received && available.isDone()) {
                received = true;
                boxes.forEach(RegistryEditBox::invalidateSuggestions);
            }
            super.render(graphics, x, y, tick);
            for (var box : boxes) if (displayed(box)) box.suggestions(graphics);
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            for (var box : boxes) if (displayed(box) && box.choose(x, y, button)) return true;
            return super.mouseClicked(x, y, button);
        }

        @Override
        public boolean mouseScrolled(double x, double y, double dx, double dy) {
            for (var box : boxes)
                if (displayed(box) && box.scrollSuggestions(x, y, dy)) return true;
            return super.mouseScrolled(x, y, dx, dy);
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            for (var box : boxes)
                if (displayed(box) && box.keyboardPopup() && box.keyPressed(key, scan, modifiers))
                    return true;
            return super.keyPressed(key, scan, modifiers);
        }
    }
}
