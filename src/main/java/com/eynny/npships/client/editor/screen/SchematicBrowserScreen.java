package com.eynny.npships.client.editor.screen;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.io.AuthoringFiles;
import com.eynny.npships.client.editor.SchematicEditorScreen;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorEditBox;
import com.eynny.npships.client.editor.widget.EditorIconButton;
import com.eynny.npships.client.editor.widget.EditorScrollBar;
import com.eynny.npships.client.editor.widget.EditorTooltips;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Entry point for previously saved NBT + JSON pairs on the author's client. */
public final class SchematicBrowserScreen extends Screen {
    @Override
    public void renderBackground(
            final GuiGraphics graphics,
            final int mouseX,
            final int mouseY,
            final float partialTick) {}

    private final Screen parent;
    private Path directory;
    private boolean importing;
    private List<String> files = List.of();
    private String filter = "";
    private EditorEditBox search;
    private static final int BODY_TOP = 86, PITCH = 24, GAP = 4;

    private int rows() {
        return Math.max(0, (height - 60 - BODY_TOP) / PITCH);
    }

    private int footerTop() {
        return BODY_TOP + rows() * PITCH + GAP;
    }

    private int page;
    private CompletableFuture<List<String>> listing;
    private CompletableFuture<SchematicDocument> opening;
    private String openingName;
    private boolean creatingFile;
    private boolean refreshOnReturn;
    private Component status = Component.empty();
    private boolean error;
    private long catalogRevision =
            com.eynny.npships.network.CatalogRefreshNetworking.clientRevision();
    private java.util.function.BiConsumer<String, SchematicDocument> fileSelected;

    public SchematicBrowserScreen(
            Screen parent, java.util.function.BiConsumer<String, SchematicDocument> selected) {
        this(parent);
        this.fileSelected = selected;
    }

    public SchematicBrowserScreen(final Screen parent) {
        super(Component.translatable("npships.editor.title"));
        this.parent = parent;
        directory = Minecraft.getInstance().gameDirectory.toPath().resolve("npships/schematics");
        refreshFiles();
    }

    private void refreshFiles() {
        // Catalog refreshes must not dismiss an opening failure.
        if (!error) status = Component.translatable("npships.editor.loading");
        Path listedDirectory = directory;
        listing =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return AuthoringFiles.list(listedDirectory);
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        },
                        Util.ioPool());
    }

    @Override
    public void removed() {
        super.removed();
        listBar.release();
        // Retire only this screen's pending publications; detached IO may finish without applying.
        opening = null;
        listing = null;
        creatingFile = false;
        error = false;
        status = Component.empty();
        refreshOnReturn = true;
    }

    @Override
    protected void init() {
        if (refreshOnReturn) {
            refreshOnReturn = false;
            refreshFiles();
        }
        int x = Math.max(12, (width - 400) / 2);
        int w = width - 2 * x;
        if (search == null) {
            search =
                    new EditorEditBox(
                            font,
                            x,
                            34,
                            w - 26,
                            20,
                            Component.translatable("npships.editor.search"));
            search.setMaxLength(256);
        }
        search.setPosition(x, 34);
        search.setWidth(Math.max(0, w - 26));
        search.setTooltip(Tooltip.create(Component.translatable("npships.editor.search")));
        search.setHint(Component.translatable("npships.editor.search"));
        search.setValue(filter);
        search.setResponder(
                value -> {
                    filter = value;
                    page = 0;
                    rebuildRows();
                });
        addRenderableWidget(search);
        addRenderableWidget(
                new EditorIconButton(
                        "refresh",
                        Component.translatable("npships.editor.refresh"),
                        x + w - 20,
                        34,
                        () -> {
                            refreshFiles();
                            com.eynny.npships.network.CatalogRefreshNetworking.saved();
                        }));
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("npships.authoring.new"),
                                b -> newDocument(null))
                        .bounds(x, 60, (w - 8) / 3, 20)
                        .build());
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("npships.editor.import_nbt"),
                                b -> chooseImport())
                        .bounds(x + (w + 4) / 3, 60, (w - 8) / 3, 20)
                        .build());
        if (fileSelected == null)
            addRenderableWidget(
                    EditorButton.flat(
                                    Component.translatable("npships.addons.exchange"),
                                    b -> minecraft.setScreen(new AddonScreen(this)))
                            .bounds(x + 2 * (w + 4) / 3, 60, (w - 8) / 3, 20)
                            .build());
        rebuildRows();
    }

    private void newDocument(SchematicDocument imported) {
        if (opening != null) return;
        minecraft.setScreen(
                new AuthoringFormScreen(
                        this,
                        "npships.authoring.new",
                        imported == null
                                ? List.of(
                                        "npships.segments.name",
                                        "npships.creation.size_x",
                                        "npships.creation.size_y",
                                        "npships.creation.size_z",
                                        "npships.creation.profile")
                                : List.of("npships.segments.name", "npships.creation.profile"),
                        imported == null
                                ? List.of("new_ship", "16", "16", "16", "GROUND")
                                : List.of(
                                        Path.of(openingName)
                                                .getFileName()
                                                .toString()
                                                .replaceFirst("(?i)\\.nbt$", ""),
                                        "GROUND"),
                        values -> {
                            var profile =
                                    com.eynny.npships.authoring.io.ExportProfile.valueOf(
                                            values.get(values.size() - 1)
                                                    .trim()
                                                    .toUpperCase(Locale.ROOT));
                            var doc =
                                    imported == null
                                            ? com.eynny.npships.authoring.io.AuthoringCreation
                                                    .empty(
                                                            new net.minecraft.core.BlockPos(
                                                                    Integer.parseInt(values.get(1)),
                                                                    Integer.parseInt(values.get(2)),
                                                                    Integer.parseInt(
                                                                            values.get(3))),
                                                            profile)
                                            : com.eynny.npships.authoring.io.AuthoringCreation
                                                    .withProfile(imported, profile, 63);
                            var target =
                                    Minecraft.getInstance()
                                            .gameDirectory
                                            .toPath()
                                            .resolve("npships/schematics");
                            openingName =
                                    com.eynny.npships.authoring.io.AuthoringCreation.filename(
                                            values.get(0));
                            if (java.nio.file.Files.exists(target.resolve(openingName))
                                    || java.nio.file.Files.exists(
                                            target.resolve(
                                                    openingName.replaceFirst("\\.nbt$", ".json"))))
                                throw new IllegalArgumentException(
                                        Component.translatable("npships.creation.exists")
                                                .getString());
                            creatingFile = true;
                            String requestName = openingName;
                            opening =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    var converted =
                                                            imported == null
                                                                    ? doc
                                                                    : com.eynny.npships.authoring.io
                                                                            .ContraptionImport
                                                                            .convert(doc);
                                                    com.eynny.npships.authoring.io.AuthoringCreation
                                                            .create(target, requestName, converted);
                                                    return converted;
                                                } catch (Exception e) {
                                                    throw new CompletionException(e);
                                                }
                                            },
                                            Util.ioPool());
                            directory = target;
                            importing = false;
                            minecraft.setScreen(this);
                        }));
    }

    private void chooseImport() {
        if (opening != null) return;
        try {
            var path =
                    LocalFilePicker.open(
                            minecraft.gameDirectory.toPath().resolve("schematics"),
                            "nbt",
                            Component.translatable("npships.editor.import_nbt").getString());
            if (path != null) importFile(path);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void importFile(Path path) {
        if (opening != null) return;
        openingName = path.getFileName().toString();
        importing = true;
        error = false;
        status = Component.translatable("npships.editor.loading");
        opening =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return AuthoringFiles.readImported(
                                        path.toAbsolutePath().getParent(),
                                        path.getFileName().toString());
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        },
                        Util.ioPool());
        rebuildRows();
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (paths.size() == 1) importFile(paths.getFirst());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= BODY_TOP && y < BODY_TOP + rows() * PITCH) {
            page -= 3 * (int) Math.signum(vertical);
            rebuildRows();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private void rebuildRows() {
        // Keep the search field (and its focus/caret) when filtering.
        for (var child : List.copyOf(children())) {
            if (child instanceof Button button && button.getY() >= 84) removeWidget(button);
        }
        int x = Math.max(12, (width - 400) / 2);
        int w = width - 2 * x;
        int rows = rows(), fullWidth = w;
        List<String> visible =
                files.stream()
                        .sorted(
                                String.CASE_INSENSITIVE_ORDER.thenComparing(
                                        java.util.Comparator.naturalOrder()))
                        .filter(
                                name ->
                                        name.toLowerCase(Locale.ROOT)
                                                .contains(filter.toLowerCase(Locale.ROOT)))
                        .toList();
        page = Math.max(0, Math.min(page, Math.max(0, visible.size() - rows)));
        if (visible.size() > rows) w -= 8;
        for (int i = 0; i < rows && page + i < visible.size(); i++) {
            String name = visible.get(page + i);
            var button =
                    EditorButton.flat(Component.literal(name), b -> open(name))
                            .bounds(x, BODY_TOP + i * PITCH, Math.max(0, w - 72), 20)
                            .build()
                            .leftAligned();
            button.setTooltip(Tooltip.create(Component.literal(name)));
            button.active = opening == null;
            addRenderableWidget(button);
            addRenderableWidget(
                                    new EditorIconButton(
                                            "copy",
                                            Component.translatable("npships.editor.duplicate"),
                                            x + w - 68,
                                            86 + i * 24,
                                            () -> fileOperation(name, false)))
                            .active =
                    opening == null;
            addRenderableWidget(
                                    new EditorIconButton(
                                            "rename",
                                            Component.translatable("npships.editor.rename"),
                                            x + w - 44,
                                            86 + i * 24,
                                            () -> fileOperation(name, true)))
                            .active =
                    opening == null;
            addRenderableWidget(
                                    new EditorIconButton(
                                            "delete",
                                            Component.translatable("npships.editor.delete"),
                                            x + w - 20,
                                            86 + i * 24,
                                            () -> confirmDelete(name)))
                            .active =
                    opening == null;
        }
        addRenderableWidget(
                EditorButton.flat(Component.translatable("gui.back"), b -> onClose())
                        .bounds(x + fullWidth - 84, footerTop(), 84, 20)
                        .build());
        configureBar(rows, visible.size());
        if (listing == null && opening == null && !error) {
            status =
                    visible.isEmpty()
                            ? Component.translatable("npships.editor.empty")
                            : Component.empty();
        }
    }

    private void open(final String name) {
        if (opening != null) return;
        Path folder = directory;
        openingName = name;
        importing = false;
        error = false;
        status = Component.translatable("npships.editor.loading");
        opening =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return AuthoringFiles.read(folder, name);
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        },
                        Util.ioPool());
        rebuildRows();
    }

    private void fileOperation(String name, boolean rename) {
        Path folder = directory;
        String base = Path.of(name).getFileName().toString().replaceFirst("(?i)\\.nbt$", "");
        minecraft.setScreen(
                new AuthoringFormScreen(
                        this,
                        rename ? "npships.editor.rename" : "npships.editor.duplicate",
                        List.of("npships.segments.name"),
                        List.of(rename ? base : base + "_copy"),
                        values -> {
                            try {
                                if (rename) AuthoringFiles.rename(folder, name, values.getFirst());
                                else AuthoringFiles.duplicate(folder, name, values.getFirst());
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                            com.eynny.npships.network.CatalogRefreshNetworking.saved();
                            refreshFiles();
                            minecraft.setScreen(this);
                        }));
    }

    private void confirmDelete(String name) {
        Path targetDirectory = directory;
        minecraft.setScreen(
                new EditorConfirmScreen(
                        accepted -> {
                            minecraft.setScreen(this);
                            if (!accepted) return;
                            try {
                                AuthoringFiles.delete(targetDirectory, name);
                                com.eynny.npships.network.CatalogRefreshNetworking.saved();
                                refreshFiles();
                            } catch (Exception e) {
                                fail(e);
                            }
                        },
                        Component.translatable("npships.editor.delete"),
                        Component.translatable("npships.editor.delete_confirm", name)));
    }

    @Override
    public void tick() {
        long revision = com.eynny.npships.network.CatalogRefreshNetworking.clientRevision();
        if (revision != catalogRevision) {
            catalogRevision = revision;
            refreshFiles();
        }
        if (listing != null && listing.isDone()) {
            try {
                files = listing.join();
                if (!error && opening == null) status = Component.empty();
            } catch (CompletionException e) {
                fail(e);
            }
            listing = null;
            rebuildRows();
        }
        if (opening != null && opening.isDone()) {
            var result = opening;
            opening = null;
            boolean created = creatingFile;
            creatingFile = false;
            try {
                var document =
                        com.eynny.npships.authoring.patches.PatchDocuments.admitEditor(
                                result.join());
                if (created) com.eynny.npships.network.CatalogRefreshNetworking.saved();
                refreshFiles();
                error = false;
                status = Component.empty();
                if (fileSelected != null) fileSelected.accept(openingName, document);
                else if (importing || !document.hasConfig()) newDocument(document);
                else if (minecraft.level != null)
                    minecraft.setScreen(
                            new SchematicEditorScreen(
                                    this, directory, openingName, document, minecraft.level));
            } catch (Exception e) {
                fail(e);
                // setScreen can fail during editor initialization after changing the active screen.
                if (minecraft.screen != this) minecraft.setScreen(this);
            }
            rebuildRows();
        }
    }

    private void fail(final Exception exception) {
        error = true;
        Throwable cause =
                exception instanceof CompletionException && exception.getCause() != null
                        ? exception.getCause()
                        : exception;
        String detail = cause.getMessage();
        if (detail == null || detail.isBlank()) detail = cause.toString();
        status = Component.translatable("npships.editor.error", detail);
        com.mojang.logging.LogUtils.getLogger()
                .warn(
                        "[NPShips editor] Browser operation failed (directory={}, file={})",
                        directory,
                        openingName,
                        exception);
    }

    @Override
    public void render(
            final GuiGraphics graphics,
            final int mouseX,
            final int mouseY,
            final float partialTick) {
        graphics.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        graphics.drawCenteredString(font, title, width / 2, 12, EditorButtonTheme.text());
        EditorButtonTheme.widgets(this);
        super.render(graphics, mouseX, mouseY, partialTick);
        int rows = rows(),
                count =
                        (int)
                                files.stream()
                                        .filter(
                                                n ->
                                                        n.toLowerCase(Locale.ROOT)
                                                                .contains(
                                                                        filter.toLowerCase(
                                                                                Locale.ROOT)))
                                        .count();
        configureBar(rows, count);
        listBar.render(graphics);
        graphics.drawString(
                font,
                font.plainSubstrByWidth(
                        status.getString()
                                .replace("\r\n", " | ")
                                .replace('\r', ' ')
                                .replace("\n", " | ")
                                .replace('\t', ' '),
                        width - 24),
                12,
                height - 34,
                error ? 0xff5555 : 0xffff55);
        graphics.drawString(
                font,
                font.plainSubstrByWidth(directory.toString(), width - 24),
                12,
                height - 18,
                0x9caabd);
        if (error && mouseY >= height - 38 && mouseY <= height - 22) {
            var lines = new java.util.ArrayList<>(font.split(status, width - 24));
            lines.addAll(
                    font.split(Component.translatable("npships.editor.copy_message"), width - 24));
            EditorTooltips.show(font, lines, mouseX, mouseY);
        }
    }

    private void configureBar(int rows, int count) {
        listBar.configure(
                width - Math.max(12, (width - 400) / 2) - 6,
                BODY_TOP,
                Math.max(1, rows * PITCH - GAP),
                rows,
                count,
                page,
                v -> {
                    page = v;
                    rebuildRows();
                });
    }

    private final EditorScrollBar listBar = new EditorScrollBar();

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (error
                && com.eynny.npships.client.editor.widget.EditorMessageCopy.click(
                        minecraft,
                        status.getString(),
                        x,
                        y,
                        button,
                        12,
                        height - 38,
                        width - 24,
                        16)) return true;
        return listBar.click(x, y, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return button == 0 && listBar.drag(y) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        listBar.release();
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
