package com.eynny.npships.client.editor.screen;

import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.client.editor.EditorErrorText;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorEditBox;
import com.eynny.npships.client.editor.widget.EditorScrollBar;
import com.eynny.npships.client.editor.widget.EditorTooltips;
import com.eynny.npships.client.editor.widget.ProfileDropdown;
import com.eynny.npships.client.editor.widget.RegistryEditBox;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.config.RarityParameters;
import com.eynny.npships.config.RarityRule;
import com.google.gson.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

/** The author edits spawn rules without rewriting patches, loot or segment definitions. */
public final class SpawnConfigEditorScreen extends Screen {
    private static final String PREFIX = "npships.spawnconfig.";
    private static final String[] TABS = {"rarity", "environment", "restrictions", "placement"};
    private final Screen parent;
    private final SchematicDocument source;
    private final Consumer<SchematicDocument> accept;
    private final Runnable save;
    private final JsonObject initial;
    private JsonObject draft;
    private SchematicDocument raritySource;
    private RarityParameters displayedParameters;
    private int rarityLabel = -1, rarityY;
    private final Deque<JsonObject> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Map<String, String> invalid = new HashMap<>();
    private final List<PlacedWidget> fields = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final Map<Integer, String> hints = new HashMap<>();
    private String hoverHint = "";
    private long hintSince;

    private static Component help(String path) {
        String key = path.replaceAll("\\.\\d+", "").replace('.', '_');
        String id = PREFIX + "help." + key;
        if (!net.minecraft.client.resources.language.I18n.exists(id)) {
            int cut = key.lastIndexOf('_');
            if (cut > 0) id = PREFIX + "help." + key.substring(0, cut);
        }
        return net.minecraft.client.resources.language.I18n.exists(id)
                ? Component.translatable(id)
                : Component.empty();
    }

    private final EditorScrollBar bar = new EditorScrollBar();
    private int tab, left, formWidth, valueX, valueWidth, row, scroll, top, bottom;
    private String error = "";
    private Choice openChoice;
    private ProfileDropdown profileReset;
    private boolean requestedWeather;
    private List<String> weatherNames = List.of();

    @Override
    public void tick() {
        if (rarityLabel >= 0 && !Objects.equals(displayedParameters, previewParameters()))
            updateRarityPreview();
        var names = com.eynny.npships.network.WeatherNetworking.names();
        if (names != weatherNames) {
            weatherNames = names;
            for (var f : fields)
                if (f.widget instanceof RegistryEditBox box) box.invalidateSuggestions();
        }
    }

    private record PlacedWidget(AbstractWidget widget, int y) {}

    private record Label(Component text, int y, boolean heading) {}

    public SpawnConfigEditorScreen(
            Screen parent,
            SchematicDocument source,
            Consumer<SchematicDocument> accept,
            Runnable save) {
        super(tr("title"));
        this.parent = parent;
        this.source = source;
        this.accept = accept;
        this.save = save;
        JsonObject config = source.configCopy();
        initial = config == null ? new JsonObject() : config;
        draft = initial.deepCopy();
    }

    private static Component tr(String key) {
        return Component.translatable(PREFIX + key);
    }

    @Override
    protected void init() {
        if (!requestedWeather && minecraft.getConnection() != null) {
            requestedWeather = true;
        }
        clearWidgets();
        fields.clear();
        labels.clear();
        rarityLabel = -1;
        hints.clear();
        openChoice = null;
        row = 0;
        formWidth = Math.min(720, width - 32);
        left = (width - formWidth) / 2;
        top = 66;
        bottom = height - 42;
        int labelWidth = Math.min(250, formWidth * 44 / 100);
        valueX = left + labelWidth;
        valueWidth = formWidth - labelWidth - 12;
        addRenderableWidget(
                EditorButton.flat(tr("text"), b -> openText())
                        .bounds(width - 262, 8, 80, 20)
                        .build());
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("npships.loot.save"),
                                b -> {
                                    if (apply()) save.run();
                                })
                        .bounds(width - 176, 8, 80, 20)
                        .build());
        addRenderableWidget(
                EditorButton.flat(Component.translatable("gui.cancel"), b -> onClose())
                        .bounds(width - 90, 8, 80, 20)
                        .build());
        int tabWidth = (formWidth - 9) / 4;
        for (int i = 0; i < TABS.length; i++) {
            int selected = i;
            addRenderableWidget(
                            EditorButton.flat(
                                            tr(TABS[i]),
                                            b -> {
                                                tab = selected;
                                                scroll = 0;
                                                init();
                                            })
                                    .bounds(left + i * (tabWidth + 3), 37, tabWidth, 20)
                                    .build())
                    .selected(() -> tab == selected);
        }
        var reset =
                new ProfileDropdown(
                        Math.max(8, width - 420),
                        8,
                        150,
                        "EMPTY",
                        name -> {
                            var profile =
                                    com.eynny.npships.authoring.io.ExportProfile.valueOf(name);
                            remember(
                                    com.eynny.npships.authoring.io.AuthoringCreation
                                            .resetSpawnProfile(
                                                    source.withConfig(draft),
                                                    profile,
                                                    minecraft.level == null
                                                            ? 63
                                                            : minecraft.level.getSeaLevel())
                                            .configCopy());
                            invalid.clear();
                            init();
                        });
        reset.setMessage(tr("reset_short"));
        profileReset = reset;
        addRenderableWidget(reset);
        switch (tab) {
            case 0 -> rarity();
            case 1 -> {
                environment("under");
                environment("above");
                inside();
            }
            case 2 -> restrictions();
            case 3 -> {
                toggle("avoid_physical_structures", "placement.avoid_physical_structures", true);
                toggle("disable_auto_rotation", "placement.disable_auto_rotation", false);
                number(
                        "rotation_spread_degrees",
                        "placement.rotation_spread_degrees",
                        "45",
                        false,
                        false);
                toggle("require_rotation_space", "placement.require_rotation_space", false);
                note("rotation_hint");
            }
        }
        layout();
    }

    private void rarity() {
        raritySource = (raritySource == null ? source : raritySource).withConfig(draft);
        choice(
                "rarity_mode",
                "rarity.mode",
                string("rarity.mode", "automatic"),
                new String[] {"automatic", "manual"},
                value -> {
                    var next = object("rarity").deepCopy();
                    next.addProperty("mode", value);
                    if (value.equals("automatic")) next.remove("probability");
                    else if (!next.has("probability"))
                        next.addProperty("probability", referenceProbability());
                    replace("rarity", next);
                    invalid.remove("rarity.probability");
                    init();
                });
        if (string("rarity.mode", "automatic").equals("manual"))
            number("rarity_probability", "rarity.probability", "1", false, false);
        number("selection_weight", "selection_weight", "1", false, true);
        note("rarity_reference_hint");
        rarityY = row;
        rarityLabel = labels.size();
        labels.add(new Label(Component.empty(), rarityY, false));
        updateRarityPreview();
    }

    private RarityParameters previewParameters() {
        // Integrated server shares the actual live SERVER values. A remote/offline editor
        // cannot infer that server's current values from a local defaults file.
        return minecraft.getSingleplayerServer() != null
                ? NPShipsRuntimeConfig.rarity()
                : RarityParameters.DEFAULTS;
    }

    private double referenceProbability() {
        try {
            var metadata = raritySource.rarityMetadata();
            return metadata.available()
                    ? previewParameters().automaticProbability(metadata.range().maximum())
                    : 1;
        } catch (IllegalArgumentException e) {
            return 1;
        }
    }

    private void updateRarityPreview() {
        if (rarityLabel < 0) return;
        Component preview;
        var parameters = previewParameters();
        displayedParameters = parameters;
        try {
            var metadata = raritySource.rarityMetadata();
            if (!metadata.available()) throw new IllegalArgumentException(metadata.error());
            var range = metadata.range();
            preview =
                    Component.translatable(
                            PREFIX + "rarity_preview",
                            metadata.count(0, new StructureSegments.Plan(Map.of())),
                            range.minimum(),
                            range.maximum(),
                            metadata.paletteCount(),
                            percent(parameters.automaticProbability(range.maximum())));
            if (minecraft.getSingleplayerServer() == null)
                preview = preview.copy().append(" ").append(tr("rarity_defaults"));
            if (string("rarity.mode", "automatic").equals("manual"))
                preview =
                        preview.copy()
                                .append(" ")
                                .append(
                                        Component.translatable(
                                                PREFIX + "rarity_manual_preview",
                                                invalid.containsKey("rarity.probability")
                                                        ? tr("invalid_number").getString()
                                                        : percent(
                                                                RarityRule.read(draft)
                                                                        .manualProbability())));
        } catch (IllegalArgumentException | IllegalStateException e) {
            preview = Component.translatable(PREFIX + "rarity_unavailable", e.getMessage());
        }
        labels.set(rarityLabel, new Label(preview, rarityY, false));
        row =
                rarityY
                        + font.split(preview, Math.max(1, formWidth - 12)).size() * font.lineHeight
                        + 12;
        layout();
    }

    private static String percent(double probability) {
        if (probability > 0 && probability < .000001) return "<0.0001%";
        return java.math.BigDecimal.valueOf(probability * 100)
                        .round(new java.math.MathContext(6))
                        .stripTrailingZeros()
                        .toPlainString()
                + "%";
    }

    private void environment(String side) {
        heading(side);
        String path = "environment." + side;
        String type = string(path + ".type", "none");
        choice(
                "type",
                path + ".type",
                type,
                new String[] {"none", "air", "fluid", "all", "surface"},
                value -> {
                    JsonObject next = object(path).deepCopy();
                    for (String key :
                            List.of(
                                    "type",
                                    "depth",
                                    "fluid",
                                    "blocks",
                                    "tags",
                                    "exclude_blocks",
                                    "exclude_tags",
                                    "allow_air",
                                    "allow_non_support")) next.remove(key);
                    next.addProperty("type", value);
                    if (!value.equals("none"))
                        next.addProperty("depth", Math.max(1, integer(path + ".depth", 8)));
                    if (value.equals("fluid"))
                        next.addProperty("fluid", string(path + ".fluid", "minecraft:water"));
                    if (value.equals("all") || value.equals("surface")) {
                        for (String key :
                                List.of(
                                        "blocks",
                                        "tags",
                                        "exclude_blocks",
                                        "exclude_tags",
                                        "allow_air",
                                        "allow_non_support")) {
                            JsonElement old = get(path + "." + key);
                            if (old != null) next.add(key, old.deepCopy());
                        }
                    }
                    replace(path, next);
                    invalid.keySet().removeIf(key -> key.startsWith(path + "."));
                    init();
                });
        if (type.equals("none")) return;
        number("depth", path + ".depth", "8", true, false);
        if (type.equals("fluid")) {
            text("fluid_id", path + ".fluid", "", true);
            note("fluid_optional");
        }
        if (type.equals("all") || type.equals("surface")) {
            list("blocks", path + ".blocks");
            list("tags", path + ".tags");
            list("exclude_blocks", path + ".exclude_blocks");
            list("exclude_tags", path + ".exclude_tags");
            toggle("allow_air", path + ".allow_air", false);
            toggle("allow_non_support", path + ".allow_non_support", false);
        }
    }

    private void inside() {
        heading("inside");
        String path = "environment.inside", type = string(path + ".type", "clear");
        choice(
                "type",
                path + ".type",
                type,
                new String[] {"clear", "ignore", "fluid"},
                value -> {
                    JsonObject next = object(path).deepCopy();
                    next.addProperty("type", value);
                    next.remove("fluid");
                    if (value.equals("fluid"))
                        next.addProperty("fluid", string(path + ".fluid", "minecraft:water"));
                    replace(path, next);
                    invalid.keySet().removeIf(key -> key.startsWith(path + "."));
                    init();
                });
        if (type.equals("fluid")) text("fluid_id", path + ".fluid", "minecraft:water", false);
    }

    private void restrictions() {
        String p = "restrictions.";
        heading("difficulty");
        choice(
                "min",
                p + "difficulty.min",
                string(p + "difficulty.min", "peaceful"),
                new String[] {"peaceful", "easy", "normal", "hard"},
                null);
        choice(
                "max",
                p + "difficulty.max",
                string(p + "difficulty.max", ""),
                new String[] {"", "peaceful", "easy", "normal", "hard"},
                null);
        heading("height_ranges");
        note("height_hint");
        ranges(p + "height_ranges", "min", "max", "", "", true, true);
        heading("dimensions");
        list("allow", p + "dimensions.allow");
        list("forbid", p + "dimensions.forbid");
        heading("biomes");
        list("allow", p + "biomes.allow");
        list("allow_tags", p + "biomes.allow_tags");
        list("forbid", p + "biomes.forbid");
        list("forbid_tags", p + "biomes.forbid_tags");
        heading("time_of_day");
        note("time_hint");
        intervals(p + "time_of_day", "0", "23999", true);
        heading("weather");
        weather("allow", p + "weather.allow");
        weather("forbid", p + "weather.forbid");
        heading("real_date");
        note("date_hint");
        intervals(p + "real_date", "01-01", "12-31", false);
        heading("real_time");
        note("clock_hint");
        intervals(p + "real_time", "00:00", "23:59", false);
    }

    private void intervals(String path, String from, String to, boolean numeric) {
        // A compact from/to pair and an interval array can coexist in older files.
        if (get(path + ".from") != null || get(path + ".to") != null) {
            pair(
                    path,
                    "from",
                    "to",
                    from,
                    to,
                    numeric,
                    false,
                    () -> {
                        JsonObject next = object(path).deepCopy();
                        next.remove("from");
                        next.remove("to");
                        replace(path, next);
                        init();
                    });
        }
        ranges(path + ".intervals", "from", "to", from, to, numeric, false);
    }

    private void ranges(
            String path,
            String min,
            String max,
            String fallbackMin,
            String fallbackMax,
            boolean numeric,
            boolean optional) {
        JsonArray array = array(path);
        for (int i = 0; i < array.size(); i++) {
            int index = i;
            pair(
                    path + "." + i,
                    min,
                    max,
                    fallbackMin,
                    fallbackMax,
                    numeric,
                    optional,
                    () -> removeEntry(path, index));
        }
        action(
                "add_range",
                () -> {
                    JsonArray next = array(path).deepCopy();
                    JsonObject range = new JsonObject();
                    if (!fallbackMin.isBlank())
                        range.add(
                                min,
                                numeric
                                        ? new JsonPrimitive(Integer.parseInt(fallbackMin))
                                        : new JsonPrimitive(fallbackMin));
                    if (!fallbackMax.isBlank())
                        range.add(
                                max,
                                numeric
                                        ? new JsonPrimitive(Integer.parseInt(fallbackMax))
                                        : new JsonPrimitive(fallbackMax));
                    next.add(range);
                    replace(path, next);
                    init();
                });
    }

    private void pair(
            String path,
            String min,
            String max,
            String fallbackMin,
            String fallbackMax,
            boolean numeric,
            boolean optional,
            Runnable remove) {
        hints.put(row, path);
        labels.add(
                new Label(
                        Component.literal(tr(min).getString() + " / " + tr(max).getString()),
                        row,
                        false));
        int w = (valueWidth - 26) / 2;
        input(
                path + "." + min,
                string(path + "." + min, fallbackMin),
                valueX,
                w,
                numeric,
                true,
                optional);
        input(
                path + "." + max,
                string(path + "." + max, fallbackMax),
                valueX + w + 3,
                w,
                numeric,
                true,
                optional);
        place(
                EditorButton.flat(Component.literal("−"), b -> remove.run())
                        .bounds(valueX + valueWidth - 20, 0, 20, 20)
                        .build());
        advanceRow(25);
    }

    private void list(String label, String path) {
        hints.put(row, path);
        labels.add(new Label(tr(label), row, false));
        place(
                EditorButton.flat(
                                tr("add"),
                                b -> {
                                    JsonArray next = array(path).deepCopy();
                                    next.add("");
                                    replace(path, next);
                                    init();
                                })
                        .bounds(valueX, 0, valueWidth, 20)
                        .build());
        advanceRow(25);
        JsonArray entries = array(path);
        for (int i = 0; i < entries.size(); i++) {
            int index = i;
            input(
                    path + "." + i,
                    entries.get(i).getAsString(),
                    valueX,
                    valueWidth - 24,
                    false,
                    false,
                    false);
            place(
                    EditorButton.flat(Component.literal("−"), b -> removeEntry(path, index))
                            .bounds(valueX + valueWidth - 20, 0, 20, 20)
                            .build());
            advanceRow(25);
        }
    }

    private void weather(String label, String path) {
        weatherButtons(label, path);
        list("custom_weather", path);
    }

    private void weatherButtons(String label, String path) {
        hints.put(row, path);
        labels.add(new Label(tr(label), row, false));
        String[] values = {"clear", "rain", "thunder"};
        int w = (valueWidth - 6) / 3;
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            place(
                            EditorButton.flat(
                                            tr("weather_" + value),
                                            b -> {
                                                JsonArray next = array(path).deepCopy();
                                                JsonPrimitive item = new JsonPrimitive(value);
                                                if (!next.remove(item)) next.add(item);
                                                replace(path, next);
                                                init();
                                            })
                                    .bounds(valueX + i * (w + 3), 0, w, 20)
                                    .build())
                    .selected(() -> array(path).contains(new JsonPrimitive(value)));
        }
        advanceRow(25);
    }

    private void removeEntry(String path, int index) {
        JsonArray next = array(path).deepCopy();
        next.remove(index);
        replace(path, next);
        invalid.keySet().removeIf(key -> key.startsWith(path + "."));
        init();
    }

    private void heading(String key) {
        if (row > 0) row += 10;
        hints.put(row, key);
        labels.add(new Label(tr(key), row, true));
        advanceRow(22);
    }

    private void note(String key) {
        labels.add(new Label(tr(key), row, false));
        advanceRow(0);
    }

    /**
     * Labels and notes reserve the same wrapped extent that render uses, plus clear button spacing.
     */
    private void advanceRow(int minimum) {
        int extent = minimum;
        boolean field = fields.stream().anyMatch(item -> item.y == row);
        for (var label : labels)
            if (label.y == row) {
                int width =
                        Math.max(1, label.heading || !field ? formWidth - 12 : valueX - left - 8);
                extent =
                        Math.max(
                                extent,
                                font.split(label.text, width).size() * font.lineHeight + 12);
            }
        row += extent;
    }

    private void action(String key, Runnable action) {
        place(
                EditorButton.flat(tr(key), b -> action.run())
                        .bounds(valueX, 0, valueWidth, 20)
                        .build());
        advanceRow(25);
    }

    private void number(
            String label, String path, String fallback, boolean integer, boolean optional) {
        field(label, path, fallback, true, integer, optional);
    }

    private void text(String label, String path, String fallback, boolean optional) {
        field(label, path, fallback, false, false, optional);
    }

    private void field(
            String label,
            String path,
            String fallback,
            boolean numeric,
            boolean integer,
            boolean optional) {
        hints.put(row, path);
        labels.add(new Label(tr(label), row, false));
        input(path, string(path, fallback), valueX, valueWidth, numeric, integer, optional);
        advanceRow(25);
    }

    private void input(
            String path,
            String value,
            int x,
            int w,
            boolean numeric,
            boolean integer,
            boolean optional) {
        hints.put(row, path);
        EditBox box =
                numeric
                        ? new EditorEditBox(font, x, 0, w, 20, Component.literal(path))
                        : new RegistryEditBox(
                                x,
                                0,
                                w,
                                Component.literal(path),
                                () -> RegistryEditBox.names(path));
        box.setMaxLength(1024);
        box.setValue(invalid.getOrDefault(path, value));
        box.setTextColor(invalid.containsKey(path) ? 0xff6666 : 0xffffff);
        if (optional)
            box.setHint(path.equals("selection_weight") ? Component.literal("1") : tr("unlimited"));
        box.setResponder(
                text -> {
                    try {
                        JsonElement next;
                        if (optional && text.isBlank()) next = null;
                        else if (numeric) {
                            if (integer) next = new JsonPrimitive(Integer.parseInt(text));
                            else {
                                double v = Double.parseDouble(text);
                                if (!Double.isFinite(v)
                                        || path.equals("rarity.probability") && (v <= 0 || v > 1)
                                        || path.equals("selection_weight") && v <= 0)
                                    throw new NumberFormatException();
                                next = new JsonPrimitive(v);
                            }
                        } else {
                            String v = text.trim();
                            if (path.contains("tags.") && v.startsWith("#")) v = v.substring(1);
                            next = new JsonPrimitive(v);
                        }
                        invalid.remove(path);
                        box.setTextColor(0xffffff);
                        replace(path, next);
                    } catch (NumberFormatException e) {
                        invalid.put(path, text);
                        box.setTextColor(0xff6666);
                    }
                    if (path.equals("rarity.probability")) updateRarityPreview();
                });
        place(box);
    }

    private void toggle(String label, String path, boolean fallback) {
        hints.put(row, path);
        labels.add(new Label(tr(label), row, false));
        EditorButton button =
                EditorButton.flat(
                                bool(path, fallback) ? tr("yes") : tr("no"),
                                b -> {
                                    boolean value = !bool(path, fallback);
                                    replace(path, new JsonPrimitive(value));
                                    b.setMessage(value ? tr("yes") : tr("no"));
                                })
                        .bounds(valueX, 0, valueWidth, 20)
                        .build();
        place(button).selected(() -> bool(path, fallback));
        advanceRow(25);
    }

    private void choice(
            String label, String path, String value, String[] options, Consumer<String> changed) {
        hints.put(row, path);
        labels.add(new Label(tr(label), row, false));
        place(
                new Choice(
                        path,
                        valueX,
                        valueWidth,
                        value,
                        options,
                        v -> {
                            if (changed != null) changed.accept(v);
                            else {
                                replace(path, v.isBlank() ? null : new JsonPrimitive(v));
                                init();
                            }
                        }));
        advanceRow(25);
    }

    private <T extends AbstractWidget> T place(T widget) {
        fields.add(new PlacedWidget(widget, row));
        addWidget(widget);
        return widget;
    }

    private JsonElement get(String path) {
        JsonElement at = draft;
        for (String part : path.split("\\.")) {
            if (at == null) return null;
            if (at.isJsonObject()) at = at.getAsJsonObject().get(part);
            else if (at.isJsonArray()) {
                int i = Integer.parseInt(part);
                at = i < at.getAsJsonArray().size() ? at.getAsJsonArray().get(i) : null;
            } else return null;
        }
        return at;
    }

    private JsonObject object(String path) {
        JsonElement e = get(path);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private JsonArray array(String path) {
        JsonElement e = get(path);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private String string(String path, String fallback) {
        JsonElement e = get(path);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    private int integer(String path, int fallback) {
        try {
            return Integer.parseInt(string(path, ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean bool(String path, boolean fallback) {
        JsonElement e = get(path);
        return e == null ? fallback : e.getAsBoolean();
    }

    private void replace(String path, JsonElement value) {
        if (Objects.equals(get(path), value)) return;
        // Copy only the edited branch: embedded patches/programs may be large and are immutable
        // here.
        JsonObject next = shallow(draft).getAsJsonObject();
        String[] parts = path.split("\\.");
        JsonElement at = next;
        for (int i = 0; i < parts.length - 1; i++) {
            if (at.isJsonArray()) {
                int index = Integer.parseInt(parts[i]);
                JsonArray array = at.getAsJsonArray();
                JsonElement child = shallow(array.get(index));
                array.set(index, child);
                at = child;
            } else {
                JsonObject o = at.getAsJsonObject();
                JsonElement child = o.get(parts[i]);
                child = child == null ? new JsonObject() : shallow(child);
                o.add(parts[i], child);
                at = child;
            }
        }
        String key = parts[parts.length - 1];
        if (at.isJsonArray())
            at.getAsJsonArray()
                    .set(Integer.parseInt(key), value == null ? JsonNull.INSTANCE : value);
        else if (value == null) at.getAsJsonObject().remove(key);
        else at.getAsJsonObject().add(key, value);
        remember(next);
    }

    private static JsonElement shallow(JsonElement source) {
        if (source.isJsonObject()) {
            JsonObject result = new JsonObject();
            source.getAsJsonObject().entrySet().forEach(e -> result.add(e.getKey(), e.getValue()));
            return result;
        }
        if (source.isJsonArray()) {
            JsonArray result = new JsonArray();
            source.getAsJsonArray().forEach(result::add);
            return result;
        }
        return source;
    }

    private void remember(JsonObject next) {
        if (next.equals(draft)) return;
        undo.addLast(draft);
        while (undo.size() > 64) undo.removeFirst();
        draft = next;
        redo.clear();
        error = "";
    }

    private void layout() {
        scroll = Mth.clamp(scroll, 0, Math.max(0, row - (bottom - top)));
        for (PlacedWidget f : fields) {
            int y = top + f.y - scroll;
            f.widget.setY(y);
            f.widget.visible = y >= top && y + f.widget.getHeight() <= bottom;
        }
        bar.configure(
                left + formWidth - 6,
                top,
                bottom - top,
                bottom - top,
                row,
                scroll,
                value -> {
                    scroll = value;
                    openChoice = null;
                    layout();
                });
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= top && y < bottom) {
            scroll -= (int) (vertical * 30);
            openChoice = null;
            layout();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (com.eynny.npships.client.editor.widget.EditorMessageCopy.click(
                minecraft, error, x, y, button, 12, height - 34, width - 24, 34)) return true;
        if (profileReset != null
                && profileReset.expanded()
                && profileReset.mouseClicked(x, y, button)) return true;
        if (getFocused() instanceof RegistryEditBox box && box.choose(x, y, button)) return true;
        if (openChoice != null) {
            Choice choice = openChoice;
            openChoice = null;
            if (choice.pick(x, y) || choice.isMouseOver(x, y)) return true;
        }
        if (bar.click(x, y, button)) return true;
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && bar.drag(y)) return true;
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        bar.release();
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (hasControlDown() && (key == GLFW.GLFW_KEY_Z || key == GLFW.GLFW_KEY_Y)) {
            boolean forward = key == GLFW.GLFW_KEY_Y || hasShiftDown();
            var from = forward ? redo : undo;
            var to = forward ? undo : redo;
            if (!from.isEmpty()) {
                to.addLast(draft);
                draft = from.removeLast();
                invalid.clear();
                error = "";
                init();
            }
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private SchematicDocument validated() {
        if (!invalid.isEmpty())
            throw new IllegalArgumentException(
                    tr("invalid_number").getString() + ": " + invalid.keySet().iterator().next());
        return ConfigEditorScreen.validate(source, draft);
    }

    private boolean apply() {
        try {
            if (!invalid.isEmpty() || !draft.equals(initial)) {
                SchematicDocument next = validated();
                accept.accept(next);
            }
            return true;
        } catch (Exception e) {
            error = EditorErrorText.message(e);
            return false;
        }
    }

    private void openText() {
        try {
            SchematicDocument next = validated();
            minecraft.setScreen(
                    new ConfigEditorScreen(
                            this,
                            next,
                            edited -> {
                                remember(edited.configCopy());
                                invalid.clear();
                            },
                            () -> {
                                if (apply()) save.run();
                            }));
        } catch (Exception e) {
            error = EditorErrorText.message(e);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float tick) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float tick) {
        g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        String caption =
                (draft.equals(initial) && invalid.isEmpty() ? "" : "* ") + title.getString();
        g.drawString(
                font,
                font.plainSubstrByWidth(caption, Math.max(0, width - 440)),
                12,
                14,
                EditorButtonTheme.text());
        g.fill(
                left - 5,
                top - 3,
                left + formWidth + 1,
                bottom + 3,
                EditorButtonTheme.surface(0xff1b2633));
        g.enableScissor(left - 3, top, left + formWidth, bottom);
        for (Label label : labels) {
            int labelY = top + label.y - scroll;
            if (labelY + font.split(label.text, formWidth - 12).size() * font.lineHeight >= top
                    && labelY < bottom) {
                int color = label.heading ? EditorButtonTheme.accent() : EditorButtonTheme.muted();
                // Help text spans the form; normal labels stop before their field.
                boolean full = label.heading || fields.stream().noneMatch(f -> f.y == label.y);
                g.drawWordWrap(
                        font,
                        label.text,
                        left,
                        labelY + 6,
                        full ? formWidth - 12 : valueX - left - 8,
                        color);
            }
        }
        for (PlacedWidget f : fields) if (f.widget.visible) f.widget.render(g, x, y, tick);
        g.disableScissor();
        bar.render(g);
        EditorButtonTheme.widgets(this);
        super.render(g, x, y, tick);
        if (openChoice != null) openChoice.renderChoices(g, x, y);
        if (getFocused() instanceof RegistryEditBox box) box.suggestions(g);
        String hovered = "";
        if (openChoice == null && x >= left && x < left + formWidth && y >= top && y < bottom) {
            int localY = y - top + scroll;
            for (var hint : hints.entrySet())
                if (localY >= hint.getKey() && localY < hint.getKey() + 22) {
                    hovered = hint.getValue();
                    break;
                }
        }
        if (!hovered.equals(hoverHint)) {
            hoverHint = hovered;
            hintSince = net.minecraft.Util.getMillis();
        }
        if (!hoverHint.isEmpty() && net.minecraft.Util.getMillis() - hintSince >= 500) {
            var hint = help(hoverHint);
            if (!hint.getString().isEmpty())
                EditorTooltips.show(font, font.split(hint, Math.min(360, width - 28)), x, y);
        }
        g.drawWordWrap(
                font,
                Component.literal(error == null ? "" : error),
                12,
                height - 34,
                width - 24,
                0xff6666);
    }

    private final class Choice extends EditorButton {
        private final String[] options;
        private final Consumer<String> changed;
        private String path, hoveredOption = "";
        private long optionSince;

        Choice(
                String path,
                int x,
                int width,
                String value,
                String[] options,
                Consumer<String> changed) {
            super(
                    x,
                    0,
                    width,
                    20,
                    tr(value.isEmpty() ? "unlimited" : value),
                    b -> {},
                    DEFAULT_NARRATION);
            this.options = options;
            this.changed = changed;
            this.path = path;
            selected(() -> openChoice == this);
        }

        @Override
        public void onPress() {
            openChoice = openChoice == this ? null : this;
        }

        private int popupY() {
            return Math.max(32, Math.min(getY() + 20, bottom - options.length * 20));
        }

        boolean pick(double x, double y) {
            int top = popupY();
            if (x < getX() || x >= getX() + getWidth() || y < top || y >= top + 20 * options.length)
                return false;
            changed.accept(options[(int) (y - top) / 20]);
            return true;
        }

        void renderChoices(GuiGraphics g, int mx, int my) {
            int y = popupY();
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.fill(
                    getX() - 2,
                    y - 2,
                    getX() + getWidth() + 2,
                    y + options.length * 20 + 2,
                    EditorButtonTheme.surface(0xff7b94ad));
            for (String value : options) {
                boolean hover = mx >= getX() && mx < getX() + getWidth() && my >= y && my < y + 20;
                g.fill(
                        getX(),
                        y,
                        getX() + getWidth(),
                        y + 20,
                        hover
                                ? EditorButtonTheme.surface(0xff466078)
                                : EditorButtonTheme.surface(0xff253343));
                g.fill(
                        getX(),
                        y + 19,
                        getX() + getWidth(),
                        y + 20,
                        EditorButtonTheme.surface(0xff53657a));
                g.drawString(
                        font,
                        tr(value.isEmpty() ? "unlimited" : value),
                        getX() + 5,
                        y + 6,
                        EditorButtonTheme.text());
                y += 20;
            }
            g.pose().popPose();
            int index = (my - popupY()) / 20;
            if (mx >= getX()
                    && mx < getX() + getWidth()
                    && my >= popupY()
                    && index < options.length) {
                String option = options[index];
                if (!hoveredOption.equals(option)) {
                    hoveredOption = option;
                    optionSince = net.minecraft.Util.getMillis();
                }
                var hint = help(path + "." + option);
                if (net.minecraft.Util.getMillis() - optionSince >= 500
                        && !hint.getString().isEmpty())
                    EditorTooltips.show(
                            font,
                            font.split(
                                    hint, Math.min(320, SpawnConfigEditorScreen.this.width - 24)),
                            mx,
                            my);
            }
        }
    }
}
