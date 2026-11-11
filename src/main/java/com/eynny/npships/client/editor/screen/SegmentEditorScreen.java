package com.eynny.npships.client.editor.screen;

import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.io.AuthoringFiles;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.client.editor.EditorErrorText;
import com.eynny.npships.client.editor.SchematicEditorScreen;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorTooltips;
import com.eynny.npships.client.editor.widget.RegistryEditBox;
import com.google.gson.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Persistent segment list on the left; every segment/variant form stays on the right. */
public final class SegmentEditorScreen extends Screen {

    public void selectArea(String id) {
        var all = StructureSegments.read(config);
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).id().equals(id)) {
                selected = i;
                mode = "pool";
                init();
                return;
            }
    }

    private final Screen parent;
    private final Path directory;
    private final Consumer<SchematicDocument> accept;
    private final BlockPos initialOrigin, initialSize;
    private CompoundTag nbt;
    private JsonObject config;
    private int selected = -1, variant = -1, page, poolPage;
    private String mode = "pool", error = "";
    private final List<String> fields = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private int panelX, panelW;
    private java.util.concurrent.CompletableFuture<List<String>> files;
    private java.util.concurrent.CompletableFuture<SchematicDocument> loading;
    private List<String> filenames = List.of();
    private boolean createSource;

    private Path importDirectory() {
        return createSource
                ? net.minecraft.client.Minecraft.getInstance()
                        .gameDirectory
                        .toPath()
                        .resolve("schematics")
                : directory;
    }

    private void loadFiles() {
        var folder = importDirectory();
        files =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return AuthoringFiles.list(folder);
                            } catch (Exception e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                        },
                        net.minecraft.Util.ioPool());
    }

    private int filePage;
    private SchematicDocument importSource;

    public SegmentEditorScreen(
            Screen parent,
            Path directory,
            SchematicDocument document,
            BlockPos a,
            BlockPos b,
            Consumer<SchematicDocument> accept,
            boolean create) {
        super(Component.translatable("npships.segments.title"));
        this.parent = parent;
        this.directory = directory;
        this.accept = accept;
        nbt = document.nbtCopy();
        config = document.configCopy();
        initialOrigin =
                a == null || b == null
                        ? BlockPos.ZERO
                        : new BlockPos(
                                Math.min(a.getX(), b.getX()),
                                Math.min(a.getY(), b.getY()),
                                Math.min(a.getZ(), b.getZ()));
        initialSize =
                a == null || b == null
                        ? document.size()
                        : new BlockPos(
                                Math.abs(a.getX() - b.getX()) + 1,
                                Math.abs(a.getY() - b.getY()) + 1,
                                Math.abs(a.getZ() - b.getZ()) + 1);
        StructureSegments.validate(nbt, StructureSegments.read(config));
        if (create) prepareRegion(true);
        else if (!segments().isEmpty()) selected = 0;
    }

    private JsonArray segments() {
        return config.has("segments") ? config.getAsJsonArray("segments") : new JsonArray();
    }

    private SchematicDocument document() {
        return new SchematicDocument(nbt, config);
    }

    private StructureSegments.Segment definition() {
        return StructureSegments.read(config).get(selected);
    }

    private JsonObject segment() {
        return segments().get(selected).getAsJsonObject();
    }

    private JsonArray pool() {
        return segment().has("pool") ? segment().getAsJsonArray("pool") : new JsonArray();
    }

    private void replace(int index, JsonObject segment) {
        var next = config.deepCopy();
        var list = segments().deepCopy();
        if (segment == null) list.remove(index);
        else if (index < 0) list.add(segment);
        else list.set(index, segment);
        if (list.isEmpty()) next.remove("segments");
        else next.add("segments", list);
        EntityPatches.ensureOccurrences(nbt, next);
        StructureSegments.validate(nbt, StructureSegments.read(next));
        config = next;
    }

    private void button(String key, int x, int y, int w, Runnable action) {
        addRenderableWidget(
                EditorButton.flat(Component.translatable(key), b -> run(action))
                        .bounds(x, y, Math.max(20, w), 20)
                        .build());
    }

    private void run(Runnable action) {
        try {
            error = "";
            action.run();
        } catch (Exception e) {
            error = EditorErrorText.message(e);
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        panelX = width / 2 + 4;
        panelW = width - panelX - 12;
        button("gui.back", width - 88, 8, 76, this::onClose);
        button(
                "npships.segments.new",
                12,
                38,
                width / 2 - 20,
                () -> {
                    prepareRegion(true);
                    init();
                });
        int rows = Math.max(1, (height - 140) / 24),
                pages = Math.max(1, (segments().size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < segments().size(); i++) {
            int index = page * rows + i;
            String name = segments().get(index).getAsJsonObject().get("id").getAsString();
            button(
                    (selected == index ? "> " : "") + name,
                    12,
                    66 + i * 24,
                    width / 2 - 20,
                    () -> {
                        selected = index;
                        variant = -1;
                        mode = "pool";
                        poolPage = 0;
                        fields.clear();
                        init();
                    });
        }
        if (page > 0)
            button(
                    "<",
                    12,
                    height - 68,
                    28,
                    () -> {
                        page--;
                        init();
                    });
        if (page + 1 < pages)
            button(
                    ">",
                    44,
                    height - 68,
                    28,
                    () -> {
                        page++;
                        init();
                    });
        button(
                "npships.segments.random_preview",
                12,
                height - 42,
                width / 2 - 20,
                () ->
                        preview(
                                StructureSegments.choose(
                                        StructureSegments.read(config), RandomSource.create())));
        if (mode.equals("region") || mode.equals("variant") || mode.equals("import")) {
            form();
            return;
        }
        if (mode.equals("files")) {
            fileList();
            return;
        }
        if (selected < 0 || selected >= segments().size()) return;
        int half = (panelW - 4) / 2;
        button(
                "npships.segments.region",
                panelX,
                38,
                half,
                () -> {
                    prepareRegion(false);
                    init();
                });
        button(
                definition().enabled() ? "npships.segments.enabled" : "npships.segments.disabled",
                panelX + half + 4,
                38,
                half,
                () -> {
                    var s = segment().deepCopy();
                    s.addProperty("enabled", !definition().enabled());
                    replace(selected, s);
                    init();
                });
        button(
                "npships.segments.base_variant",
                panelX,
                62,
                half,
                () -> {
                    prepareImport(document(), "base", definition().origin());
                });
        button(
                "npships.segments.import",
                panelX + half + 4,
                62,
                half,
                () -> {
                    mode = "files";
                    loadFiles();
                    init();
                });
        int count = Math.max(1, (height - 166) / 24),
                pagesPool = Math.max(1, (pool().size() + count - 1) / count);
        poolPage = Math.min(poolPage, pagesPool - 1);
        for (int i = 0; i < count && poolPage * count + i < pool().size(); i++) {
            int index = poolPage * count + i;
            var v = definition().pool().get(index);
            button(
                    v.name() + " x" + v.weight(),
                    panelX,
                    90 + i * 24,
                    panelW,
                    () -> {
                        variant = index;
                        mode = "variant";
                        setFields(
                                List.of("npships.segments.name", "npships.segments.weight"),
                                List.of(v.name(), "" + v.weight()));
                        init();
                    });
        }
        if (poolPage > 0)
            button(
                    "<",
                    panelX,
                    height - 68,
                    28,
                    () -> {
                        poolPage--;
                        init();
                    });
        if (poolPage + 1 < pagesPool)
            button(
                    ">",
                    panelX + 32,
                    height - 68,
                    28,
                    () -> {
                        poolPage++;
                        init();
                    });
        button(
                "npships.segments.delete",
                panelX,
                height - 42,
                panelW,
                () -> {
                    mode = "delete";
                    initDelete();
                });
        if (mode.equals("delete")) initDelete();
    }

    private void initDelete() {
        // Confirmation remains in the right pane, leaving the segment list usable.
        for (var child : List.copyOf(children()))
            if (child instanceof AbstractWidget widget && widget.getX() >= panelX)
                removeWidget(widget);
        button(
                "gui.yes",
                panelX,
                66,
                panelW,
                () -> {
                    replace(selected, null);
                    selected =
                            segments().isEmpty() ? -1 : Math.min(selected, segments().size() - 1);
                    mode = "pool";
                    init();
                });
        button(
                "gui.cancel",
                panelX,
                94,
                panelW,
                () -> {
                    mode = "pool";
                    init();
                });
    }

    private void prepareRegion(boolean fresh) {
        if (fresh) selected = -1;
        mode = "region";
        setFields(
                List.of(
                        "npships.segments.name",
                        "npships.segments.origin",
                        "npships.segments.size"),
                List.of(
                        fresh ? "segment_" + (segments().size() + 1) : definition().id(),
                        xyz(fresh ? initialOrigin : definition().origin()),
                        xyz(fresh ? initialSize : definition().size())));
    }

    private void setFields(List<String> names, List<String> values) {
        labels.clear();
        labels.addAll(names);
        fields.clear();
        fields.addAll(values);
    }

    private void form() {
        for (int i = 0; i < fields.size(); i++) {
            int index = i;
            var box =
                    new RegistryEditBox(
                            panelX,
                            52 + i * 36,
                            panelW,
                            Component.translatable(labels.get(i)),
                            java.util.List::of);
            box.setTooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable("npships.tools.field_hint", box.getMessage())));
            box.setMaxLength(256);
            box.setValue(fields.get(i));
            box.setResponder(v -> fields.set(index, v));
            addRenderableWidget(box);
        }
        button("gui.done", panelX, height - 42, (panelW - 4) / 2, this::commitForm);
        button(
                "gui.cancel",
                panelX + (panelW + 4) / 2,
                height - 42,
                (panelW - 4) / 2,
                () -> {
                    mode = "pool";
                    init();
                });
        if (mode.equals("variant")) {
            button(
                    "npships.segments.preview",
                    panelX,
                    height - 94,
                    panelW,
                    () ->
                            preview(
                                    new StructureSegments.Plan(
                                            Map.of(
                                                    definition().id(),
                                                    definition().pool().get(variant).id()))));
            button(
                    "npships.segments.remove_variant",
                    panelX,
                    height - 68,
                    panelW,
                    () -> {
                        var s = segment().deepCopy();
                        var p = pool().deepCopy();
                        p.remove(variant);
                        if (p.isEmpty()) s.remove("pool");
                        else s.add("pool", p);
                        replace(selected, s);
                        mode = "pool";
                        init();
                    });
        }
    }

    private void commitForm() {
        if (mode.equals("region")) {
            var s = selected < 0 ? new JsonObject() : segment().deepCopy();
            s.addProperty("id", fields.get(0).trim());
            s.add("origin", vector(fields.get(1)));
            s.add("size", vector(fields.get(2)));
            replace(selected, s);
            if (selected < 0) selected = segments().size() - 1;
        } else if (mode.equals("variant")) {
            var s = segment().deepCopy();
            var p = pool().deepCopy();
            var v = p.get(variant).getAsJsonObject();
            v.addProperty("name", fields.get(0).trim());
            v.addProperty("weight", Integer.parseInt(fields.get(1)));
            s.add("pool", p);
            replace(selected, s);
        } else if (mode.equals("import")) {
            var origin = BlockEditorScreen.position(fields.get(1));
            var importTag = importSource.nbtCopy();
            SchematicPatches.embed(importTag, importSource.configCopy());
            var crop =
                    StructureSegments.crop(
                            new SchematicDocument(importTag, importSource.configCopy()),
                            origin,
                            definition().size());
            String id = UUID.randomUUID().toString();
            var entry = new JsonObject();
            entry.addProperty("variant", id);
            entry.addProperty("name", fields.get(0).trim());
            entry.addProperty("weight", Integer.parseInt(fields.get(2)));
            var mergedConfig = config.deepCopy();
            var importedTables = EmbeddedLoot.read(importSource.configCopy());
            for (var loot :
                    StructureSegments.cropLoot(
                            importSource.configCopy(), origin, definition().size())) {
                var table = loot.table();
                if (importedTables.has(table.toString())) {
                    var scoped =
                            net.minecraft.resources.ResourceLocation.parse(
                                    "npships:local/variant_"
                                            + id
                                            + "/"
                                            + table.getPath().substring("local/".length()));
                    mergedConfig =
                            EmbeddedLoot.assign(
                                    mergedConfig,
                                    scoped.toString(),
                                    importedTables.getAsJsonObject(table.toString()));
                    table = scoped;
                }
                entry = LootAssignments.assign(entry, loot.pos(), table, loot.rolls());
            }
            var next = nbt.copy();
            var all = next.getCompound(StructureSegments.VARIANTS);
            all.put(id, crop);
            next.put(StructureSegments.VARIANTS, all);
            var s = segment().deepCopy();
            var p = pool().deepCopy();
            p.add(entry);
            s.add("pool", p);
            var old = nbt;
            var oldConfig = config;
            nbt = next;
            config = mergedConfig;
            try {
                replace(selected, s);
            } catch (Exception e) {
                nbt = old;
                config = oldConfig;
                throw e;
            }
        }
        mode = "pool";
        init();
    }

    private void prepareImport(SchematicDocument source, String name, BlockPos origin) {
        importSource = source;
        mode = "import";
        setFields(
                List.of(
                        "npships.segments.name",
                        "npships.segments.source_origin",
                        "npships.segments.weight"),
                List.of(name, xyz(origin), "1"));
        init();
    }

    private void fileList() {
        button(
                createSource ? "npships.editor.title" : "npships.creation.create_folder",
                panelX,
                38,
                panelW,
                () -> {
                    createSource = !createSource;
                    filePage = 0;
                    loadFiles();
                    init();
                });
        int rows = Math.max(1, (height - 156) / 24),
                pages = Math.max(1, (filenames.size() + rows - 1) / rows);
        filePage = Math.min(filePage, pages - 1);
        for (int i = 0; i < rows && filePage * rows + i < filenames.size(); i++) {
            String name = filenames.get(filePage * rows + i);
            button(
                    name,
                    panelX,
                    66 + i * 24,
                    panelW,
                    () -> {
                        if (loading != null) return;
                        var folder = importDirectory();
                        loading =
                                java.util.concurrent.CompletableFuture.supplyAsync(
                                        () -> {
                                            try {
                                                return AuthoringFiles.read(folder, name);
                                            } catch (Exception e) {
                                                throw new java.util.concurrent.CompletionException(
                                                        e);
                                            }
                                        },
                                        net.minecraft.Util.ioPool());
                    });
        }
        if (filePage > 0)
            button(
                    "<",
                    panelX,
                    height - 68,
                    28,
                    () -> {
                        filePage--;
                        init();
                    });
        if (filePage + 1 < pages)
            button(
                    ">",
                    panelX + 32,
                    height - 68,
                    28,
                    () -> {
                        filePage++;
                        init();
                    });
        button(
                "gui.cancel",
                panelX,
                height - 42,
                panelW,
                () -> {
                    mode = "pool";
                    init();
                });
    }

    @Override
    public void tick() {
        if (files != null && files.isDone()) {
            try {
                filenames = files.join();
            } catch (Exception e) {
                error = EditorErrorText.message(e.getCause());
            }
            files = null;
            if (mode.equals("files")) init();
        }
        if (loading != null && loading.isDone()) {
            try {
                var doc = loading.join();
                if (mode.equals("files") && selected >= 0)
                    prepareImport(
                            doc,
                            "variant",
                            doc.size().equals(document().size())
                                    ? definition().origin()
                                    : BlockPos.ZERO);
            } catch (Exception e) {
                error = EditorErrorText.message(e);
            }
            loading = null;
        }
    }

    private void preview(StructureSegments.Plan plan) {
        var input = nbt.copy();
        var result =
                StructureSegments.compose(
                        input, StructureSegments.read(config), LootAssignments.read(config), plan);
        minecraft.setScreen(
                new SchematicEditorScreen(
                                this,
                                directory,
                                Component.translatable("npships.editor.preview_title").getString(),
                                new SchematicDocument(result.nbt(), null),
                                minecraft.level)
                        .rerollable(new SchematicDocument(nbt, config)));
    }

    private static String xyz(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static JsonArray vector(String text) {
        var p = BlockEditorScreen.position(text);
        var a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float tick) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float tick) {
        g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        g.fill(
                width / 2 - 3,
                34,
                width / 2 - 1,
                height - 28,
                EditorButtonTheme.surface(0xff405060));
        g.drawString(font, title, 12, 12, EditorButtonTheme.text());
        if (mode.equals("region") || mode.equals("variant") || mode.equals("import"))
            for (int i = 0; i < labels.size(); i++)
                g.drawString(
                        font, Component.translatable(labels.get(i)), panelX, 40 + i * 36, 0xaabbd0);
        if (mode.equals("delete"))
            g.drawString(
                    font, Component.translatable("npships.segments.delete"), panelX, 42, 0xffaaaa);
        var status =
                Component.literal(
                        error.isEmpty()
                                ? Component.translatable("npships.segments.draft_hint").getString()
                                : error);
        g.drawString(
                font,
                font.plainSubstrByWidth(status.getString(), width - 24),
                12,
                height - 16,
                error.isEmpty() ? 0xaabbd0 : 0xffaaaa);
        EditorButtonTheme.widgets(this);
        super.render(g, x, y, tick);
        if (!error.isEmpty() && y >= height - 24)
            EditorTooltips.show(font, font.split(status, width - 24), x, y);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        accept.accept(document());
        minecraft.setScreen(parent);
    }
}
