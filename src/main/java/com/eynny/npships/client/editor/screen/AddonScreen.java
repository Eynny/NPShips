package com.eynny.npships.client.editor.screen;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.io.AddonArchive;
import com.eynny.npships.authoring.io.AddonDependencies;
import com.eynny.npships.authoring.io.AddonDependency;
import com.eynny.npships.authoring.io.AddonImports;
import com.eynny.npships.authoring.io.AddonLicense;
import com.eynny.npships.authoring.io.AuthoringCreation;
import com.eynny.npships.authoring.io.AuthoringFiles;
import com.eynny.npships.client.editor.EditorErrorText;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorEditBox;
import com.eynny.npships.client.editor.widget.EditorScrollBar;
import com.eynny.npships.client.editor.widget.EditorTooltips;
import com.eynny.npships.client.editor.widget.RegistryEditBox;
import com.eynny.npships.network.CatalogRefreshNetworking;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Select files locally, then explicitly export or import working copies. */
public final class AddonScreen extends Screen {
    private enum View {
        WORKING,
        ARCHIVES,
        CONTENTS
    }

    private final Screen parent;
    private final Path working, archives;
    private View view = View.WORKING;
    private List<String> rows = List.of();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, String> names = new HashMap<>();
    private final EditorScrollBar listBar = new EditorScrollBar();
    private static final int GAP = 4, PITCH = 24, MARGIN = 12;
    private int page, preferredPage;
    private EditorEditBox search;
    private EditorButton importMode, exportMode, all, path, folder;
    private final List<EditorButton> footer = new ArrayList<>();
    private final Map<String, List<EditorButton>> rowControls = new LinkedHashMap<>();
    private View footerView;
    private int bodyTop, bodyBottom, statusTop, visibleRows;
    private boolean suspendedSearch;
    private String filter = "", status = "", statusDetails = "";
    private long statusVersion, jobMessageVersion;
    private AddonArchive.Contents contents;
    private CompletableFuture<?> job;
    private Runnable completed;
    private byte[] packIcon;
    private MetadataScreen exportingScreen;
    private List<AddonDependency> dependencies = List.of();

    public AddonScreen(Screen parent) {
        super(tr("title"));
        this.parent = parent;
        var root = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        working = root.resolve("npships/schematics");
        archives = root.resolve("npships/addons");
        list();
    }

    private static Component tr(String key, Object... arguments) {
        return Component.translatable("npships.addons." + key, arguments);
    }

    private EditorButton button(Component text, Runnable action) {
        var b = EditorButton.flat(text, v -> action.run()).build();
        String hint =
                text.equals(tr("import"))
                        ? "import_hint"
                        : text.equals(tr("export")) ? "export_hint" : "";
        b.setTooltip(Tooltip.create(hint.isEmpty() ? text : tr(hint)));
        attach(b);
        return b;
    }

    private void attach(AbstractWidget widget) {
        if (!children().contains(widget)) addRenderableWidget(widget);
    }

    /** Measured wrapping is local to these two addon dialogs; no editing callback is involved. */
    private static int actions(
            net.minecraft.client.gui.Font font,
            List<EditorButton> buttons,
            int x,
            int y,
            int available) {
        int left = x, top = y;
        for (var button : buttons) {
            int w =
                    Math.max(
                            0,
                            Math.min(
                                    available, Math.max(20, font.width(button.getMessage()) + 12)));
            if (left > x && left + w > x + available) {
                left = x;
                top += PITCH;
            }
            button.setPosition(left, top);
            button.setWidth(w);
            left += w + GAP;
        }
        return buttons.isEmpty() ? 0 : top - y + 20;
    }

    private static boolean inside(AbstractWidget widget, int left, int top, int right, int bottom) {
        return widget.getWidth() >= 20
                && widget.getHeight() > 0
                && widget.getX() >= left
                && widget.getY() >= top
                && widget.getRight() <= right
                && widget.getBottom() <= bottom;
    }

    private void list() {
        var folder = view == View.WORKING ? working : archives;
        run(
                () -> {
                    if (view == View.WORKING) return AuthoringFiles.list(folder);
                    if (!Files.isDirectory(folder)) return List.<String>of();
                    try (var stream = Files.list(folder)) {
                        return stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                                .map(p -> p.getFileName().toString())
                                .sorted()
                                .toList();
                    }
                },
                value -> {
                    rows = value;
                    selected.clear();
                    preferredPage = page = 0;
                });
    }

    private <T> void run(Callable<T> work, java.util.function.Consumer<T> done) {
        if (job != null) return;
        long messageVersion = statusVersion;
        jobMessageVersion = messageVersion;
        var future =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return work.call();
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        },
                        Util.ioPool());
        job = future;
        completed =
                () -> {
                    done.accept(future.join());
                    if (statusVersion == messageVersion) setStatus("");
                };
    }

    private void setStatus(String message) {
        setStatus(message, message);
    }

    private void setStatus(String message, String details) {
        status = message;
        statusDetails = details;
        statusVersion++;
    }

    private int panelLeft() {
        return Math.max(12, (width - 400) / 2);
    }

    private int panelRight() {
        return width - panelLeft();
    }

    @Override
    protected void init() {
        if (search == null) {
            importMode = button(tr("import"), () -> switchView(View.ARCHIVES));
            exportMode = button(tr("export"), () -> switchView(View.WORKING));
            importMode.selected(() -> view != View.WORKING);
            exportMode.selected(() -> view == View.WORKING);
            all =
                    button(
                            tr("all"),
                            () -> {
                                var visible = filtered();
                                if (selected.containsAll(visible)) selected.removeAll(visible);
                                else selected.addAll(visible);
                                rebuildRows();
                            });
            path =
                    button(
                            tr("path"),
                            () -> {
                                var file =
                                        LocalFilePicker.open(
                                                archives, "jar", tr("open").getString());
                                if (file != null) open(file);
                            });
            folder =
                    button(
                            tr("folder"),
                            () -> {
                                try {
                                    Files.createDirectories(archives);
                                    Util.getPlatform().openFile(archives.toFile());
                                } catch (Exception e) {
                                    setStatus(e.getMessage());
                                }
                            });
            search = new EditorEditBox(font, 0, 0, 20, 20, tr("search"));
            search.setTooltip(Tooltip.create(tr("search")));
            search.setHint(tr("search"));
            search.setValue(filter);
            search.setResponder(
                    v -> {
                        filter = v;
                        preferredPage = page = 0;
                        rebuildRows();
                    });
        }
        for (var widget : List.of(importMode, exportMode, all, path, folder, search))
            attach(widget);
        for (var widget : footer) attach(widget);
        rebuildRows();
    }

    private void switchView(View next) {
        view = next;
        filter = "";
        search.setValue(filter);
        list();
        rebuildRows();
    }

    @Override
    protected void repositionElements() {
        listBar.release();
        if (search == null) super.repositionElements();
        else rebuildRows();
    }

    private List<String> filtered() {
        return rows.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private void rebuildRows() {
        if (search == null) return;
        // Opening an archive explicitly clears the filter; retained text follows that
        // semantic reset. A width/height reflow leaves equal search drafts untouched.
        if (!search.getValue().equals(filter)) {
            search.setValue(filter);
            return;
        }
        if (footerView != view) {
            for (var widget : footer) {
                if (getFocused() == widget) setFocused(null);
                removeWidget(widget);
            }
            footer.clear();
            if (view == View.WORKING) footer.add(button(tr("export"), this::export));
            if (view == View.CONTENTS) {
                footer.add(button(tr("import"), this::importSelected));
                footer.add(button(tr("info"), this::information));
            }
            footer.add(button(Component.translatable("gui.back"), this::onClose));
            footerView = view;
        }
        int x = panelLeft(), w = Math.max(0, panelRight() - x);
        int actionHeight = actions(font, footer, x, 0, w);
        statusTop = Math.max(0, height - MARGIN - font.lineHeight);
        int maximumFooterTop = statusTop - GAP - actionHeight;
        var navigation = List.of(importMode, exportMode, view == View.WORKING ? all : path, folder);
        int headerBottom = 32 + actions(font, navigation, x, 32, w);
        for (var widget : List.of(importMode, exportMode, all, path, folder)) {
            widget.visible = navigation.contains(widget);
            widget.active = widget.visible && job == null;
        }
        search.setPosition(x, headerBottom + GAP);
        search.setWidth(w);
        bodyTop = search.getBottom() + GAP;
        var list = filtered();
        int capacity = Math.max(0, (maximumFooterTop - bodyTop) / PITCH);
        int rowsHeight = Math.min(capacity, Math.max(1, list.size())) * PITCH;
        int footerTop = Math.min(maximumFooterTop, bodyTop + rowsHeight);
        actions(font, footer, x, footerTop, w);
        for (var widget : footer) {
            widget.visible = inside(widget, x, 28, x + w, statusTop - GAP);
            widget.active = widget.visible && job == null;
        }
        for (var widget : navigation)
            widget.visible &= inside(widget, x, 28, x + w, footerTop - GAP);
        search.visible = inside(search, x, 28, x + w, footerTop - GAP);
        search.active = search.visible;
        bodyBottom = Math.max(bodyTop, footerTop - GAP);
        visibleRows = Math.max(0, (bodyBottom - bodyTop + GAP) / PITCH);
        if (w < 48) visibleRows = 0;
        page = Math.clamp(preferredPage, 0, Math.max(0, list.size() - Math.max(1, visibleRows)));
        var keep = new HashSet<String>();
        int listRight = x + w - (list.size() > visibleRows ? 8 : 0);
        int rowWidth = Math.max(0, listRight - x);
        for (int i = 0; i < visibleRows && page + i < list.size(); i++) {
            String name = list.get(page + i);
            String identity = view + ":" + name;
            keep.add(identity);
            var cells = rowControls.get(identity);
            if (cells == null) {
                cells = new ArrayList<>();
                cells.add(
                        button(
                                Component.literal(name),
                                () -> {
                                    if (view == View.ARCHIVES) open(archives.resolve(name));
                                    else {
                                        if (!selected.remove(name)) selected.add(name);
                                        rebuildRows();
                                    }
                                }));
                if (view == View.CONTENTS)
                    cells.add(
                            button(
                                    Component.literal(names.get(name)),
                                    () ->
                                            minecraft.setScreen(
                                                    new AuthoringFormScreen(
                                                            this,
                                                            "npships.addons.target_name",
                                                            List.of("npships.segments.name"),
                                                            List.of(names.get(name)),
                                                            v -> {
                                                                names.put(
                                                                        name,
                                                                        AuthoringCreation.filename(
                                                                                        v
                                                                                                .getFirst())
                                                                                .replaceFirst(
                                                                                        "\\.nbt$",
                                                                                        ""));
                                                                minecraft.setScreen(this);
                                                            }))));
                rowControls.put(identity, cells);
            }
            int renameWidth = view == View.CONTENTS ? Math.min(104, Math.max(40, rowWidth / 3)) : 0;
            var entry = cells.getFirst();
            entry.setMessage(Component.literal(name));
            if (view == View.ARCHIVES) entry.leftAligned();
            else entry.listRow(() -> selected.contains(name));
            entry.setTooltip(Tooltip.create(entry.getMessage()));
            entry.setPosition(x, bodyTop + i * PITCH);
            entry.setWidth(Math.max(0, rowWidth - (renameWidth == 0 ? 0 : renameWidth + GAP)));
            for (int j = 0; j < cells.size(); j++) {
                var cell = cells.get(j);
                attach(cell);
                if (j == 1) {
                    cell.setMessage(Component.literal(names.get(name)));
                    cell.setTooltip(Tooltip.create(cell.getMessage()));
                    cell.setPosition(listRight - renameWidth, entry.getY());
                    cell.setWidth(renameWidth);
                }
                cell.visible = inside(cell, x, bodyTop, listRight, bodyBottom);
                cell.active = cell.visible && job == null;
            }
        }
        rowControls
                .entrySet()
                .removeIf(
                        e -> {
                            if (keep.contains(e.getKey())) return false;
                            for (var widget : e.getValue()) {
                                if (getFocused() == widget) setFocused(null);
                                removeWidget(widget);
                            }
                            return true;
                        });
        if (visibleRows > 0)
            listBar.configure(
                    panelRight() - 6,
                    bodyTop,
                    visibleRows * PITCH - GAP,
                    visibleRows,
                    list.size(),
                    page,
                    v -> {
                        preferredPage = page = v;
                        rebuildRows();
                    });
        else listBar.release();
        if (!search.visible && getFocused() == search) {
            suspendedSearch = true;
            setFocused(null);
        } else if (search.visible && suspendedSearch && getFocused() == null) {
            setFocused(search);
            suspendedSearch = false;
        }
    }

    private void information() {
        var metadata = contents.metadata();
        var lines = new ArrayList<String>();
        var values =
                List.of(
                        metadata.name(),
                        metadata.id(),
                        metadata.version(),
                        metadata.author(),
                        metadata.description());
        var keys = List.of("name", "id", "version", "author", "description");
        for (int i = 0; i < keys.size(); i++) {
            String value = values.get(i);
            lines.add(
                    tr(keys.get(i)).getString()
                            + ": "
                            + value.substring(0, Math.min(256, value.length())));
        }
        for (var dependency : contents.dependencies())
            lines.add(
                    dependency.modId()
                            + " ("
                            + tr(dependency.required()
                                            ? "dependency_required"
                                            : "dependency_optional")
                                    .getString()
                            + ") "
                            + dependency.versionRange());
        setStatus(String.join("\n", lines));
    }

    private void open(Path path) {
        if (path.getFileName() == null
                || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            setStatus(tr("unsupported_file").getString());
            return;
        }
        run(
                () -> {
                    return AddonArchive.read(path);
                },
                value -> {
                    contents = value;
                    view = View.CONTENTS;
                    rows = contents.ships();
                    selected.clear();
                    names.clear();
                    var used = new HashSet<String>();
                    for (String ship : rows) {
                        String base =
                                ship.substring(ship.lastIndexOf('/') + 1, ship.length() - 4)
                                        .replaceAll("[^A-Za-z0-9_-]", "_");
                        if (base.isEmpty()) base = "ship";
                        try {
                            AuthoringCreation.filename(base);
                        } catch (IllegalArgumentException e) {
                            base = "ship_" + base;
                        }
                        if (base.length() > 48) base = base.substring(0, 48);
                        String n = base;
                        int index = 2;
                        while (!used.add(n.toLowerCase(Locale.ROOT))) n = base + "_" + index++;
                        names.put(ship, n);
                    }
                    preferredPage = page = 0;
                    filter = "";
                    setStatus(tr("loaded").getString());
                });
    }

    private void export() {
        if (selected.isEmpty()) {
            setStatus(tr("select").getString());
            return;
        }
        var files = List.copyOf(selected);
        run(
                () -> {
                    var documents = new LinkedHashMap<String, SchematicDocument>();
                    for (String file : files)
                        documents.put(file, AuthoringFiles.read(working, file));
                    return documents;
                },
                documents -> {
                    // Registry ownership is inspected on the client owner thread. Export keeps
                    // these exact detached documents, even if a working file changes later.
                    var inspection = AddonDependencies.inspect(documents.values());
                    exportingScreen =
                            new MetadataScreen(
                                    this,
                                    inspection,
                                    meta -> {
                                        var registries = minecraft.level.registryAccess();
                                        var icon = packIcon == null ? null : packIcon.clone();
                                        var requirements = List.copyOf(dependencies);
                                        run(
                                                () -> {
                                                    var bytes =
                                                            AddonArchive.export(
                                                                    meta,
                                                                    documents,
                                                                    icon,
                                                                    requirements);
                                                    AddonArchive.validateLoot(
                                                            AddonArchive.read(bytes), registries);
                                                    return AddonArchive.saveJar(
                                                            archives,
                                                            meta.id()
                                                                    + "-"
                                                                    + meta.version()
                                                                    + ".jar",
                                                            bytes);
                                                },
                                                saved -> {
                                                    setStatus(
                                                            tr("exported", saved.toString())
                                                                    .getString());
                                                    exportingScreen = null;
                                                    minecraft.setScreen(this);
                                                });
                                    });
                    minecraft.setScreen(exportingScreen);
                });
    }

    private void importSelected() {
        if (selected.isEmpty()) {
            setStatus(tr("select").getString());
            return;
        }
        var requests = new LinkedHashMap<String, String>();
        for (String ship : selected) requests.put(ship, names.get(ship));
        minecraft.setScreen(
                new EditorConfirmScreen(
                        yes -> {
                            minecraft.setScreen(this);
                            if (yes) doImport(requests, false);
                        },
                        tr("import"),
                        tr("import_confirm"),
                        tr("keep_names"),
                        Component.translatable("gui.cancel")));
    }

    private void doImport(Map<String, String> requests, boolean replace) {
        run(
                () -> AddonImports.apply(contents, requests, working, replace),
                count -> {
                    CatalogRefreshNetworking.saved();
                    setStatus(tr("imported").getString() + " " + count);
                });
    }

    @Override
    public void tick() {
        if (job != null && job.isDone()) {
            var callback = completed;
            long messageVersion = jobMessageVersion;
            job = null;
            completed = null;
            try {
                callback.run();
            } catch (Exception e) {
                Throwable cause = e;
                // Unwrap asynchronous transport only. Archive IOExceptions carry the source,
                // entry and validation stage; walking to their root cause loses that context.
                while ((cause instanceof java.util.concurrent.CompletionException
                                || cause instanceof java.util.concurrent.ExecutionException)
                        && cause.getCause() != null) cause = cause.getCause();
                String message =
                        cause instanceof AddonArchive.ExportCollision collision
                                ? tr(
                                                "exists",
                                                collision.id(),
                                                collision.version(),
                                                collision.folder().toString())
                                        .getString()
                                : EditorErrorText.message(cause);
                String summary =
                        cause instanceof AddonArchive.ExportCollision
                                ? tr("exists_short").getString()
                                : message;
                if (statusVersion == messageVersion) setStatus(summary, message);
                if (exportingScreen != null) exportingScreen.setError(summary, message);
                if (cause instanceof AddonImports.Collision) {
                    var requests = new LinkedHashMap<String, String>();
                    for (String ship : selected) requests.put(ship, names.get(ship));
                    minecraft.setScreen(
                            new EditorConfirmScreen(
                                    yes -> {
                                        minecraft.setScreen(this);
                                        if (yes) doImport(requests, true);
                                    },
                                    tr("replace"),
                                    Component.literal(message)));
                }
            }
            rebuildRows();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (visibleRows > 0
                && x >= panelLeft()
                && x < panelRight()
                && y >= bodyTop
                && y < bodyBottom) {
            preferredPage =
                    Math.clamp(
                            page - 3 * (int) Math.signum(vertical),
                            0,
                            Math.max(0, filtered().size() - visibleRows));
            rebuildRows();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (job == null && paths.size() == 1) open(paths.getFirst());
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float t) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float t) {
        g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        if (width >= 48 && height >= 48)
            g.drawCenteredString(font, title, width / 2, 12, EditorButtonTheme.text());
        EditorButtonTheme.widgets(this);
        for (var child : children())
            if (child instanceof Button b) b.active = b.visible && job == null;
        super.render(g, x, y, t);
        if (visibleRows > 0) listBar.render(g);
        String message = job != null ? tr("busy").getString() : status == null ? "" : status;
        if (message.isEmpty() && visibleRows == 0 && !filtered().isEmpty())
            message = Component.translatable("npships.editor.window_small").getString();
        if (statusTop >= 28)
            g.drawString(
                    font,
                    font.plainSubstrByWidth(
                            message.replace('\n', ' '), Math.max(0, panelRight() - panelLeft())),
                    panelLeft(),
                    statusTop,
                    0xffcf80);
        if (statusTop >= 28
                && x >= panelLeft()
                && x < panelRight()
                && y >= statusTop
                && y < statusTop + font.lineHeight
                && !message.isEmpty())
            EditorTooltips.show(
                    font,
                    Component.literal(job == null && !status.isEmpty() ? statusDetails : message),
                    x,
                    y);
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
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) suspendedSearch = false;
        return visibleRows > 0 && y >= bodyTop && y < bodyBottom && listBar.click(x, y, button)
                || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) suspendedSearch = false;
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (job == null) minecraft.setScreen(parent);
    }

    private static final class MetadataScreen extends Screen {
        private final AddonScreen parent;
        private final java.util.function.Consumer<AddonArchive.Metadata> submit;
        private final List<String> keys =
                List.of(
                        "id",
                        "name",
                        "version",
                        "author",
                        "description",
                        "license_holder",
                        "license_year");
        private final String[] values = {
            "my_fleet",
            "My Fleet",
            "1.0.0",
            "",
            "Ships for NPShips",
            "",
            Integer.toString(java.time.Year.now().getValue())
        };
        private String error = "", errorDetails = "";

        private void setError(String message) {
            setError(message, message);
        }

        private void setError(String message, String details) {
            error = message;
            errorDetails = details;
        }

        private enum LicenseChoice {
            NONE,
            MIT,
            CUSTOM
        }

        private LicenseChoice licenseChoice = LicenseChoice.NONE;
        private boolean showLicense, showDependencyDetails;
        private final Map<String, byte[]> extraNotices = new LinkedHashMap<>();
        private final Map<String, String> installedMods = new TreeMap<>();
        private final Set<String> runtimeMods = AddonDependencies.runtimeMods();
        private AddonLicense customTerms;
        private String sourceLicenseError = "";
        private boolean retainedTerms;
        private final List<EditorButton> licensing = new ArrayList<>();
        private MultiLineTextWidget licenseHelp, licensePreview;
        private final List<RegistryEditBox> fields = new ArrayList<>();
        private final List<MultiLineTextWidget> labels = new ArrayList<>();
        private final List<EditorButton> images = new ArrayList<>(), footer = new ArrayList<>();
        private final EditorScrollBar bar = new EditorScrollBar();
        private int bodyTop, bodyBottom, bodyHeight, scroll, preferredScroll, statusTop;
        private RegistryEditBox suspendedFocus;
        private AbstractWidget validationFocus;
        private boolean validationRequested;
        private String metadataError = "";
        private final AddonDependencies.Inspection inspection;
        private final List<DependencyRow> dependencyRows = new ArrayList<>();
        private final List<EditorButton> dependencyActions = new ArrayList<>();
        private MultiLineTextWidget dependencyHelp, runtimeHelp, dependencyDetails;
        private boolean dependenciesConfirmed;

        MetadataScreen(
                AddonScreen parent, java.util.function.Consumer<AddonArchive.Metadata> submit) {
            this(parent, AddonDependencies.inspect(List.of()), submit);
        }

        MetadataScreen(
                AddonScreen parent,
                AddonDependencies.Inspection inspection,
                java.util.function.Consumer<AddonArchive.Metadata> submit) {
            super(tr("export"));
            this.parent = parent;
            this.inspection = inspection;
            this.submit = submit;
            for (var mod : net.neoforged.fml.ModList.get().getMods())
                installedMods.put(mod.getModId(), mod.getDisplayName());
            if (parent.contents != null) {
                try {
                    customTerms = AddonLicense.existing(parent.contents);
                    retainedTerms = customTerms != null;
                    if (retainedTerms) licenseChoice = LicenseChoice.CUSTOM;
                } catch (IllegalArgumentException e) {
                    sourceLicenseError = e.getMessage();
                    retainedTerms = true;
                    licenseChoice = LicenseChoice.CUSTOM;
                }
            }
        }

        protected void init() {
            if (!fields.isEmpty()) {
                for (var field : fields) attach(field);
                for (var button : images) attach(button);
                for (var button : licensing) attach(button);
                for (var row : dependencyRows) for (var control : row.controls()) attach(control);
                for (var button : dependencyActions) attach(button);
                for (var button : footer) attach(button);
                arrange();
                return;
            }
            int row = 0;
            for (String key : keys) {
                int index = row++;
                var field =
                        addRenderableWidget(
                                new RegistryEditBox(
                                        0, 0, 20, fieldLabel(index), java.util.List::of));
                field.setMaxLength(256);
                field.setValue(values[index]);
                field.setHint(tr(key));
                field.setTooltip(Tooltip.create(fieldLabel(index)));
                fields.add(field);
                labels.add(new MultiLineTextWidget(fieldLabel(index), font));

                field.setResponder(
                        v -> {
                            values[index] = v;
                            validateFields(false);
                            if (licenseHelp != null) refreshLicense();
                        });
            }
            dependencyHelp = new MultiLineTextWidget(Component.empty(), font);
            runtimeHelp = new MultiLineTextWidget(Component.empty(), font);
            dependencyDetails = new MultiLineTextWidget(Component.empty(), font);
            for (var dependency : inspection.defaults())
                addDependency(dependency, dependency.required());
            dependencyActions.add(
                    addRenderableWidget(
                            EditorButton.flat(
                                            tr("dependency_add"),
                                            b -> {
                                                addDependency(null, false);
                                                dependenciesChanged();
                                                reveal(dependencyRows.getLast().id);
                                            })
                                    .bounds(0, 0, 20, 20)
                                    .build()));
            dependencyActions.add(
                    addRenderableWidget(
                            EditorButton.flat(
                                            tr("dependency_confirm"),
                                            b -> {
                                                try {
                                                    acceptedDependencies(false);
                                                    dependenciesConfirmed = !dependenciesConfirmed;
                                                    setError("");
                                                    refreshDependencies();
                                                } catch (IllegalArgumentException e) {
                                                    setError(EditorErrorText.message(e));
                                                }
                                            })
                                    .bounds(0, 0, 20, 20)
                                    .build()));
            if (!inspection.unresolvedReferences().isEmpty())
                dependencyActions.add(
                        addRenderableWidget(
                                EditorButton.flat(
                                                tr(
                                                        "dependency_details",
                                                        inspection.unresolvedReferences().size()),
                                                b -> {
                                                    showDependencyDetails = !showDependencyDetails;
                                                    refreshDependencies();
                                                })
                                        .bounds(0, 0, 20, 20)
                                        .build()));
            dependencyActions.get(0).setTooltip(Tooltip.create(tr("dependency_add_help")));
            dependencyActions.get(1).selected(() -> dependenciesConfirmed);
            refreshDependencies();
            licenseHelp = new MultiLineTextWidget(tr("license_rights"), font);
            licensePreview = new MultiLineTextWidget(Component.empty(), font);
            licensing.add(
                    addRenderableWidget(
                            EditorButton.flat(
                                            tr("license_file"),
                                            b -> {
                                                try {
                                                    if (retainedTerms)
                                                        throw new IllegalArgumentException(
                                                                tr("license_replace_blocked")
                                                                        .getString());
                                                    var path =
                                                            LocalFilePicker.open(
                                                                    parent.archives,
                                                                    "*",
                                                                    tr("license_file").getString());
                                                    if (path != null) loadLicense(path);
                                                } catch (Exception e) {
                                                    setError(EditorErrorText.message(e));
                                                }
                                            })
                                    .bounds(0, 0, 20, 20)
                                    .build()));
            licensing.add(
                    addRenderableWidget(
                            EditorButton.flat(
                                            tr("license_notice_file"),
                                            b -> {
                                                try {
                                                    var path =
                                                            LocalFilePicker.open(
                                                                    parent.archives,
                                                                    "*",
                                                                    tr("license_notice_file")
                                                                            .getString());
                                                    if (path != null) loadNotice(path);
                                                } catch (Exception e) {
                                                    setError(EditorErrorText.message(e));
                                                }
                                            })
                                    .bounds(0, 0, 20, 20)
                                    .build()));
            licensing.add(
                    addRenderableWidget(
                            EditorButton.flat(
                                            tr("license_preview"),
                                            b -> {
                                                showLicense = !showLicense;
                                                refreshLicense();
                                            })
                                    .bounds(0, 0, 20, 20)
                                    .build()));
            licensing.add(
                    addRenderableWidget(
                                    EditorButton.flat(
                                                    tr("license_use_mit"),
                                                    b -> {
                                                        if (retainedTerms) return;
                                                        licenseChoice =
                                                                licenseChoice == LicenseChoice.MIT
                                                                        ? LicenseChoice.NONE
                                                                        : LicenseChoice.MIT;
                                                        setError("");
                                                        refreshLicense();
                                                    })
                                            .bounds(0, 0, 20, 20)
                                            .build())
                            .selected(() -> licenseChoice == LicenseChoice.MIT));
            licensing.get(3).setTooltip(Tooltip.create(tr("license_use_mit_help")));
            licensing
                    .get(0)
                    .setTooltip(
                            Tooltip.create(
                                    retainedTerms
                                            ? tr(
                                                    "license_retained_file_help",
                                                    tr("license_preview"))
                                            : tr("license_file_help")));
            licensing.get(1).setTooltip(Tooltip.create(tr("license_notice_help")));
            licensing.get(2).setTooltip(Tooltip.create(tr("license_preview_help")));
            images.add(
                    addRenderableWidget(
                            EditorButton.flat(
                                            tr("image_file"),
                                            b -> {
                                                try {
                                                    var path =
                                                            LocalFilePicker.open(
                                                                    parent.archives,
                                                                    "png",
                                                                    tr("image_file").getString());
                                                    if (path != null) {
                                                        parent.packIcon =
                                                                AddonArchive.readIcon(path);
                                                        refreshLicense();
                                                    }
                                                } catch (Exception e) {
                                                    setError(EditorErrorText.message(e));
                                                }
                                            })
                                    .bounds(0, 0, 20, 20)
                                    .build()));
            images.add(
                    addRenderableWidget(
                            EditorButton.flat(
                                            tr("image_preview"),
                                            b -> {
                                                minecraft.setScreen(
                                                        new SchematicBrowserScreen(
                                                                this,
                                                                (name, document) ->
                                                                        minecraft.setScreen(
                                                                                com.eynny.npships
                                                                                        .client
                                                                                        .editor
                                                                                        .SchematicEditorScreen
                                                                                        .iconPreview(
                                                                                                this,
                                                                                                document,
                                                                                                bytes -> {
                                                                                                    parent.packIcon =
                                                                                                            bytes;
                                                                                                    refreshLicense();
                                                                                                    minecraft
                                                                                                            .setScreen(
                                                                                                                    this);
                                                                                                }))));
                                            })
                                    .bounds(0, 0, 20, 20)
                                    .build()));
            footer.add(
                    addRenderableWidget(
                            EditorButton.flat(
                                            tr("export"),
                                            b -> {
                                                try {
                                                    if (parent.job != null) return;
                                                    validationRequested = true;
                                                    if (!validateFields(true)) return;
                                                    var acceptedRequirements =
                                                            acceptedDependencies(true);
                                                    var acceptedTerms = acceptedLicense();
                                                    parent.dependencies = acceptedRequirements;
                                                    submit.accept(
                                                            new AddonArchive.Metadata(
                                                                    values[0],
                                                                    values[1],
                                                                    values[2],
                                                                    values[3],
                                                                    values[4],
                                                                    acceptedTerms));
                                                    arrange();
                                                } catch (Exception e) {
                                                    setError(EditorErrorText.message(e));
                                                }
                                            })
                                    .bounds(0, 0, 20, 20)
                                    .build()));
            footer.add(
                    addRenderableWidget(
                            EditorButton.flat(Component.translatable("gui.cancel"), b -> onClose())
                                    .bounds(0, 0, 20, 20)
                                    .build()));
            for (var button : images) button.setTooltip(Tooltip.create(button.getMessage()));
            for (var button : footer) button.setTooltip(Tooltip.create(button.getMessage()));
            refreshLicense();
        }

        /**
         * Dependency controls and their text histories survive scrolling and screen
         * reinitialization.
         */
        private final class DependencyRow {
            private final RegistryEditBox id, range;
            private final EditorButton type, remove;
            private final MultiLineTextWidget idLabel, rangeLabel;
            private final boolean locked, runtime;
            private boolean required;

            DependencyRow(AddonDependency dependency, boolean locked) {
                this.locked = locked;
                runtime = dependency != null && locked && runtimeMods.contains(dependency.modId());
                required = dependency == null || dependency.required();
                id =
                        new RegistryEditBox(
                                        0,
                                        0,
                                        20,
                                        tr("dependency_id"),
                                        () ->
                                                installedMods.keySet().stream()
                                                        .filter(
                                                                value ->
                                                                        !value.equals(values[0])
                                                                                && dependencyRows
                                                                                        .stream()
                                                                                        .noneMatch(
                                                                                                row ->
                                                                                                        row.id
                                                                                                                .getValue()
                                                                                                                .equals(
                                                                                                                        value)))
                                                        .toList())
                                .suggestionLabel(
                                        value ->
                                                installedMods.getOrDefault(value, value)
                                                        + " ("
                                                        + value
                                                        + ")");
                range = new RegistryEditBox(0, 0, 20, tr("dependency_range"), List::of);
                id.setMaxLength(64);
                range.setMaxLength(256);
                id.setValue(dependency == null ? "" : dependency.modId());
                range.setValue(dependency == null ? "" : dependency.versionRange());
                id.setEditable(!locked);
                id.setHint(tr("dependency_id"));
                range.setHint(tr("dependency_range_hint"));
                id.setTooltip(Tooltip.create(tr(locked ? "dependency_locked" : "dependency_id")));
                range.setTooltip(
                        Tooltip.create(
                                tr(
                                        dependency != null && dependency.modId().equals("minecraft")
                                                ? "dependency_game_help"
                                                : "dependency_range_help")));
                idLabel = new MultiLineTextWidget(tr("dependency_id"), font);
                rangeLabel = new MultiLineTextWidget(tr("dependency_range"), font);
                type =
                        EditorButton.flat(
                                        tr(
                                                required
                                                        ? "dependency_required"
                                                        : "dependency_optional"),
                                        b -> {
                                            if (locked) return;
                                            required = !required;
                                            dependenciesChanged();
                                        })
                                .bounds(0, 0, 20, 20)
                                .build();
                remove =
                        EditorButton.flat(
                                        tr("dependency_remove"),
                                        b -> {
                                            if (locked) return;
                                            for (var control : controls()) {
                                                if (getFocused() == control) setFocused(null);
                                                removeWidget(control);
                                            }
                                            if (suspendedFocus == id || suspendedFocus == range)
                                                suspendedFocus = null;
                                            int removedIndex = dependencyRows.indexOf(this);
                                            dependencyRows.remove(this);
                                            dependenciesChanged();
                                            var next =
                                                    dependencyRows.isEmpty()
                                                            ? fields.getLast()
                                                            : dependencyRows.get(
                                                                            Math.min(
                                                                                    removedIndex,
                                                                                    dependencyRows
                                                                                                    .size()
                                                                                            - 1))
                                                                    .id;
                                            if (next.visible) {
                                                validationFocus = next;
                                                setFocused(next);
                                                next.focusRetained();
                                            } else reveal(next);
                                        })
                                .bounds(0, 0, 20, 20)
                                .build();
                remove.setTooltip(Tooltip.create(tr("dependency_remove")));
                id.setResponder(
                        v -> {
                            if (range.getValue().isBlank() && installedMods.containsKey(v))
                                range.setValue(AddonDependencies.installedRange(v));
                            dependenciesChanged();
                        });
                range.setResponder(v -> dependenciesChanged());
            }

            List<AbstractWidget> controls() {
                if (runtime()) return List.of(range);
                return locked ? List.of(id, type, range) : List.of(id, type, remove, range);
            }

            boolean runtime() {
                return runtime;
            }

            void refresh() {
                idLabel.setMessage(
                        runtime()
                                ? Component.literal(
                                        installedMods.getOrDefault(id.getValue(), id.getValue()))
                                : tr("dependency_id"));
                rangeLabel.setMessage(
                        tr(
                                id.getValue().equals("minecraft")
                                        ? "dependency_game_version"
                                        : "dependency_range"));
                type.setMessage(tr(required ? "dependency_required" : "dependency_optional"));
                type.setTooltip(
                        Tooltip.create(tr(locked ? "dependency_locked" : "dependency_type_help")));
                boolean validId = id.getValue().matches("^[a-z][a-z0-9_]{1,63}$");
                id.setTextColor(validId ? 0xffffff : id.getValue().isBlank() ? 0xffcc55 : 0xff6666);
                boolean validRange;
                try {
                    new AddonDependency("validation", required, range.getValue());
                    validRange = true;
                } catch (IllegalArgumentException e) {
                    validRange = false;
                }
                range.setTextColor(
                        validRange ? 0xffffff : range.getValue().isBlank() ? 0xffcc55 : 0xff6666);
            }

            int layout(int x, int y, int width) {
                idLabel.setMaxWidth(Math.max(1, width));
                idLabel.setPosition(x, y);
                y += idLabel.getHeight() + GAP;
                if (runtime()) {
                    range.setPosition(x, y);
                    range.setWidth(width);
                    return y + PITCH + GAP;
                }
                id.setPosition(x, y);
                id.setWidth(width);
                y += PITCH;
                var actions = locked ? List.of(type) : List.of(type, remove);
                y += AddonScreen.actions(font, actions, x, y, width) + GAP;
                rangeLabel.setMaxWidth(Math.max(1, width));
                rangeLabel.setPosition(x, y);
                y += rangeLabel.getHeight() + GAP;
                range.setPosition(x, y);
                range.setWidth(width);
                return y + PITCH + GAP;
            }

            void position(int x) {
                idLabel.setY(bodyTop + idLabel.getY() - scroll);
                rangeLabel.setY(bodyTop + rangeLabel.getY() - scroll);
                for (var control : controls()) {
                    control.setY(bodyTop + control.getY() - scroll);
                    control.visible = inside(control, x, bodyTop, right() - 8, bodyBottom);
                    control.active =
                            control.visible && parent.job == null && (control != type || !locked);
                }
            }

            void render(GuiGraphics g, int x, int y, float t) {
                idLabel.render(g, x, y, t);
                if (!runtime()) rangeLabel.render(g, x, y, t);
                for (var control : controls()) control.render(g, x, y, t);
            }
        }

        private void addDependency(AddonDependency dependency, boolean locked) {
            var row = new DependencyRow(dependency, locked);
            dependencyRows.add(row);
            for (var control : row.controls()) attach(control);
        }

        private void dependenciesChanged() {
            dependenciesConfirmed = false;
            refreshDependencies();
        }

        private void refreshDependencies() {
            if (dependencyHelp == null) return;
            dependencyHelp.setMessage(tr("dependency_help"));
            var listed =
                    dependencyRows.stream()
                            .map(row -> row.id.getValue())
                            .collect(java.util.stream.Collectors.toSet());
            var inherited =
                    runtimeMods.stream()
                            .filter(id -> !listed.contains(id))
                            .map(id -> installedMods.getOrDefault(id, id))
                            .toList();
            runtimeHelp.setMessage(
                    tr("dependency_runtime")
                            .copy()
                            .append(
                                    inherited.isEmpty()
                                            ? Component.empty()
                                            : tr(
                                                    "dependency_inherited",
                                                    String.join(", ", inherited))));
            dependencyDetails.setMessage(
                    showDependencyDetails
                            ? Component.literal(
                                    String.join("\n", inspection.unresolvedReferences()))
                            : Component.empty());
            for (var row : dependencyRows) row.refresh();
            if (dependencyActions.size() > 1) {
                var confirm = dependencyActions.get(1);
                confirm.setMessage(
                        confirmation(
                                "dependency_confirm",
                                "dependency_confirmed",
                                dependenciesConfirmed));
                confirm.setTooltip(Tooltip.create(tr("dependency_confirm_help")));
            }
            if (licenseHelp != null) arrange();
        }

        private List<AddonDependency> acceptedDependencies(boolean requireConfirmation) {
            var accepted = new ArrayList<AddonDependency>();
            var ids = new HashSet<String>();
            for (var row : dependencyRows) {
                RegistryEditBox invalidField = row.id;
                try {
                    if (row.id.getValue().matches("^[a-z][a-z0-9_]{1,63}$"))
                        invalidField = row.range;
                    var dependency =
                            new AddonDependency(
                                    row.id.getValue(), row.required, row.range.getValue());
                    invalidField = row.id;
                    if (!ids.add(dependency.modId()))
                        throw new IllegalArgumentException(
                                tr("dependency_duplicate", dependency.modId()).getString());
                    invalidField = fields.getFirst();
                    if (dependency.modId().equals(values[0]))
                        throw new IllegalArgumentException(tr("dependency_self").getString());
                    accepted.add(dependency);
                } catch (IllegalArgumentException e) {
                    reveal(invalidField);
                    throw e;
                }
            }
            if (requireConfirmation && !dependenciesConfirmed) {
                reveal(dependencyActions.get(1));
                throw new IllegalArgumentException(tr("dependency_confirm_needed").getString());
            }
            return List.copyOf(accepted);
        }

        private void reveal(AbstractWidget field) {
            preferredScroll =
                    Math.clamp(
                            field.getY() + scroll - bodyTop - 20,
                            0,
                            Math.max(0, bodyHeight - (bodyBottom - bodyTop)));
            suspendedFocus = null;
            arrange();
            if (field.visible) {
                validationFocus = field;
                setFocused(field);
                if (field instanceof RegistryEditBox text) text.focusRetained();
            }
        }

        private void loadLicense(Path path) throws java.io.IOException {
            if (retainedTerms)
                throw new IllegalArgumentException(tr("license_replace_blocked").getString());
            customTerms = AddonLicense.custom(path);
            licenseChoice = LicenseChoice.CUSTOM;
            refreshLicense();
        }

        private void loadNotice(Path path) throws java.io.IOException {
            byte[] bytes;
            try (var stream = Files.newInputStream(path)) {
                bytes = stream.readNBytes(AddonLicense.MAX_TEXT + 1);
            }
            String name = path.getFileName().toString();
            chosenLicense()
                    .withNotice(
                            name, bytes); // Validate the complete bundle before changing the draft.
            extraNotices.put(name, bytes.clone());
            refreshLicense();
        }

        private AddonLicense chosenLicense() {
            if (!sourceLicenseError.isEmpty())
                throw new IllegalArgumentException(sourceLicenseError);
            AddonLicense result;
            if (licenseChoice == LicenseChoice.CUSTOM) {
                if (customTerms == null)
                    throw new IllegalArgumentException(tr("license_choose_file").getString());
                result = customTerms;
            } else if (licenseChoice == LicenseChoice.MIT)
                result = AddonLicense.mit(values[5], values[6]);
            else throw new IllegalArgumentException(tr("license_choose_terms").getString());
            for (var notice : extraNotices.entrySet())
                result = result.withNotice(notice.getKey(), notice.getValue());
            return result;
        }

        private AddonLicense acceptedLicense() {
            if (licenseChoice == LicenseChoice.NONE) reveal(licensing.get(3));
            return chosenLicense();
        }

        private static Component confirmation(String required, String confirmed, boolean accepted) {
            return accepted
                    ? tr(confirmed)
                    : Component.empty()
                            .append(
                                    Component.literal("* ")
                                            .withStyle(style -> style.withColor(0xffaa00)))
                            .append(tr(required));
        }

        private void refreshLicense() {
            licensing.get(2).setMessage(tr(showLicense ? "license_hide" : "license_preview"));
            String terms =
                    switch (licenseChoice) {
                        case NONE -> "license_unselected";
                        case MIT -> "license_mit";
                        case CUSTOM -> "license_custom";
                    };
            licenseHelp.setMessage(
                    tr(terms)
                            .copy()
                            .append("\n")
                            .append(tr("license_rights"))
                            .append(
                                    retainedTerms
                                            ? Component.literal("\n").append(tr("license_retained"))
                                            : Component.empty()));
            for (int i = 0; i < fields.size(); i++) {
                var label = fieldLabel(i);
                labels.get(i).setMessage(label);
                fields.get(i).setMessage(label);
                fields.get(i).setTooltip(Tooltip.create(label));
            }
            validateFields(false);
            if (!footer.isEmpty()) footer.getFirst().setTooltip(Tooltip.create(tr("export_steps")));
            try {
                licensePreview.setMessage(
                        showLicense
                                ? Component.literal(chosenLicense().allTexts())
                                : Component.empty());
            } catch (IllegalArgumentException e) {
                licensePreview.setMessage(Component.literal(EditorErrorText.message(e)));
            }
            arrange();
        }

        private boolean requiredField(int index) {
            return index < 5 || licenseChoice == LicenseChoice.MIT;
        }

        private Component fieldLabel(int index) {
            return tr(keys.get(index))
                    .copy()
                    .append(" (")
                    .append(
                            tr(requiredField(index) ? "required" : "optional")
                                    .copy()
                                    .withStyle(
                                            requiredField(index)
                                                    ? net.minecraft.ChatFormatting.GOLD
                                                    : net.minecraft.ChatFormatting.GRAY))
                    .append(")");
        }

        /** Use the export validators independently, so another blank field cannot mask this one. */
        private String fieldProblem(int index) {
            if (index >= 5 && licenseChoice != LicenseChoice.MIT) return "";
            try {
                if (index >= 5)
                    AddonLicense.mit(
                            index == 5 ? values[index] : "Validation",
                            index == 6 ? values[index] : "2026");
                else {
                    String[] sample = {"validation", "Name", "1", "Author", "Description"};
                    sample[index] = values[index];
                    new AddonArchive.Metadata(
                            sample[0], sample[1], sample[2], sample[3], sample[4]);
                }
                return "";
            } catch (IllegalArgumentException invalid) {
                return values[index].isBlank() && requiredField(index)
                        ? tr("required").getString()
                        : EditorErrorText.message(invalid);
            }
        }

        /** Failed submit focuses and scrolls to the first invalid field before any export work. */
        private boolean validateFields(boolean reveal) {
            int first = -1;
            String problem = "";
            for (int i = 0; i < fields.size(); i++) {
                String detail = fieldProblem(i);
                fields.get(i)
                        .setTextColor(
                                detail.isEmpty()
                                        ? 0xffffff
                                        : values[i].isBlank() ? 0xffcc55 : 0xff6666);
                if (!detail.isEmpty() && first < 0) {
                    first = i;
                    problem = tr("invalid_field", tr(keys.get(i)), detail).getString();
                }
            }
            String previousMetadataError = metadataError;
            metadataError = problem;
            if (validationRequested
                    && (reveal || error.isEmpty() || error.equals(previousMetadataError)))
                setError(problem);
            if (reveal && first >= 0) {
                reveal(fields.get(first));
            }
            return first < 0;
        }

        private void attach(AbstractWidget widget) {
            if (!children().contains(widget)) addRenderableWidget(widget);
        }

        private int left() {
            return Math.max(MARGIN, (width - 400) / 2);
        }

        private int right() {
            return width - left();
        }

        @Override
        protected void repositionElements() {
            bar.release();
            if (fields.isEmpty()) super.repositionElements();
            else arrange();
        }

        private void arrange() {
            int x = left(), w = Math.max(0, right() - x);
            statusTop = Math.max(0, height - MARGIN - font.lineHeight);
            int footerTop = statusTop - GAP - actions(font, footer, x, 0, w);
            actions(font, footer, x, footerTop, w);
            bodyTop = 32;
            bodyBottom = Math.max(bodyTop, footerTop - GAP);
            // Reserve the same inner scrollbar gutter at every body height: no width oscillation.
            int fieldWidth = Math.max(0, w - 8), y = 0;
            for (int i = 0; i < fields.size(); i++) {
                var label = labels.get(i);
                label.setMaxWidth(Math.max(1, fieldWidth));
                label.setPosition(x, y);
                y += label.getHeight() + GAP;
                fields.get(i).setPosition(x, y);
                fields.get(i).setWidth(fieldWidth);
                y += 20 + GAP;
            }
            runtimeHelp.setMaxWidth(Math.max(1, fieldWidth));
            runtimeHelp.setPosition(x, y);
            y += runtimeHelp.getHeight() + GAP;
            var runtimeRows =
                    dependencyRows.stream()
                            .filter(DependencyRow::runtime)
                            .sorted(
                                    java.util.Comparator.comparingInt(
                                                    (DependencyRow row) ->
                                                            switch (row.id.getValue()) {
                                                                case "minecraft" -> 0;
                                                                case "neoforge" -> 1;
                                                                case "npships" -> 3;
                                                                default -> 2;
                                                            })
                                            .thenComparing(
                                                    row ->
                                                            installedMods.getOrDefault(
                                                                    row.id.getValue(),
                                                                    row.id.getValue()),
                                                    String.CASE_INSENSITIVE_ORDER)
                                            .thenComparing(row -> row.id.getValue()))
                            .toList();
            for (var row : runtimeRows) y = row.layout(x, y, fieldWidth);
            y += GAP;
            dependencyHelp.setMaxWidth(Math.max(1, fieldWidth));
            dependencyHelp.setPosition(x, y);
            y += dependencyHelp.getHeight() + GAP;
            for (var row : dependencyRows) if (!row.runtime()) y = row.layout(x, y, fieldWidth);
            y += actions(font, List.of(dependencyActions.get(0)), x, y, fieldWidth) + GAP;
            if (dependencyActions.size() > 2)
                y += actions(font, List.of(dependencyActions.get(2)), x, y, fieldWidth) + GAP;
            dependencyDetails.setMaxWidth(Math.max(1, fieldWidth));
            dependencyDetails.setPosition(x, y);
            if (showDependencyDetails) y += dependencyDetails.getHeight() + GAP;
            y += 8;
            dependencyActions.get(1).setPosition(x, y);
            dependencyActions.get(1).setWidth(fieldWidth);
            y += PITCH + 8;
            y += actions(font, licensing, x, y, fieldWidth) + GAP;
            licenseHelp.setMaxWidth(Math.max(1, fieldWidth));
            licenseHelp.setPosition(x, y);
            y += licenseHelp.getHeight() + GAP;
            licensePreview.setMaxWidth(Math.max(1, fieldWidth));
            licensePreview.setPosition(x, y);
            if (showLicense) y += licensePreview.getHeight() + GAP;
            int imageTop = y + GAP;
            bodyHeight = imageTop + actions(font, images, x, imageTop, fieldWidth);
            scroll =
                    Math.clamp(
                            preferredScroll, 0, Math.max(0, bodyHeight - (bodyBottom - bodyTop)));
            for (int i = 0; i < fields.size(); i++) {
                var field = fields.get(i);
                var label = labels.get(i);
                label.setY(bodyTop + label.getY() - scroll);
                field.setY(bodyTop + field.getY() - scroll);
                field.visible = inside(field, x, bodyTop, right() - 8, bodyBottom);
                field.active =
                        parent.job == null && (i < 5 || licenseChoice != LicenseChoice.CUSTOM);
            }
            runtimeHelp.setY(bodyTop + runtimeHelp.getY() - scroll);
            dependencyDetails.setY(bodyTop + dependencyDetails.getY() - scroll);
            dependencyHelp.setY(bodyTop + dependencyHelp.getY() - scroll);
            for (var row : dependencyRows) row.position(x);
            for (var button : dependencyActions) {
                button.setY(bodyTop + button.getY() - scroll);
                button.visible = inside(button, x, bodyTop, right() - 8, bodyBottom);
                button.active = button.visible && parent.job == null;
            }
            licenseHelp.setY(bodyTop + licenseHelp.getY() - scroll);
            licensePreview.setY(bodyTop + licensePreview.getY() - scroll);
            for (var button : licensing) {
                button.setY(bodyTop + button.getY() - scroll);
                button.visible = inside(button, x, bodyTop, right() - 8, bodyBottom);
                button.active = button.visible && parent.job == null;
            }
            licensing.get(0).active &= !retainedTerms;
            licensing.get(3).active &= !retainedTerms;
            for (var button : images) {
                button.setY(bodyTop + button.getY() - scroll);
                button.visible = inside(button, x, bodyTop, right() - 8, bodyBottom);
                button.active = button.visible && parent.job == null;
            }
            for (var button : footer) {
                button.visible = inside(button, x, 28, right(), statusTop - GAP);
                button.active = button.visible && parent.job == null;
            }
            if (getFocused() instanceof RegistryEditBox field && !field.visible) {
                suspendedFocus = field;
                setFocused(null);
            } else if (suspendedFocus != null && suspendedFocus.visible && getFocused() == null) {
                suspendedFocus.focusRetained();
                setFocused(suspendedFocus);
                suspendedFocus = null;
            }
            if (bodyBottom > bodyTop && w >= 28)
                bar.configure(
                        right() - 6,
                        bodyTop,
                        bodyBottom - bodyTop,
                        bodyBottom - bodyTop,
                        bodyHeight,
                        scroll,
                        value -> {
                            preferredScroll = value;
                            arrange();
                        });
            else bar.release();
        }

        @Override
        public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
            if (getFocused() instanceof RegistryEditBox text
                    && text.scrollSuggestions(x, y, vertical)) return true;
            if (x >= left() && x < right() && y >= bodyTop && y < bodyBottom) {
                preferredScroll =
                        Math.clamp(
                                scroll - (int) (vertical * PITCH),
                                0,
                                Math.max(0, bodyHeight - (bodyBottom - bodyTop)));
                arrange();
                return true;
            }
            return super.mouseScrolled(x, y, horizontal, vertical);
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            if (parent.job == null
                    && getFocused() instanceof RegistryEditBox text
                    && text.choose(x, y, button)) return true;
            if (button == 0) suspendedFocus = null;
            validationFocus = null;
            boolean handled =
                    bodyBottom > bodyTop
                                    && y >= bodyTop
                                    && y < bodyBottom
                                    && bar.click(x, y, button)
                            || super.mouseClicked(x, y, button);
            // Native ContainerEventHandler focuses the clicked button after its callback returns.
            // A refused submit owns its invalid control after that dispatch, within this event.
            var refusedField = validationFocus;
            validationFocus = null;
            if (refusedField != null && refusedField.visible && minecraft.screen == this) {
                setFocused(refusedField);
                if (refusedField instanceof RegistryEditBox text) text.focusRetained();
                setDragging(false);
            }
            return handled;
        }

        @Override
        public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
            return button == 0 && bar.drag(y) || super.mouseDragged(x, y, button, dx, dy);
        }

        @Override
        public boolean mouseReleased(double x, double y, int button) {
            bar.release();
            return super.mouseReleased(x, y, button);
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            if (getFocused() instanceof RegistryEditBox text && text.keyboardPopupActivation(key))
                return text.keyPressed(key, scan, modifiers);
            if (key != org.lwjgl.glfw.GLFW.GLFW_KEY_TAB)
                return super.keyPressed(key, scan, modifiers);
            var controls = new ArrayList<AbstractWidget>();
            controls.addAll(fields);
            for (var row : dependencyRows) controls.addAll(row.controls());
            controls.addAll(dependencyActions);
            controls.addAll(licensing);
            controls.addAll(images);
            controls.addAll(footer);
            int index = controls.indexOf(getFocused());
            int direction = (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) == 0 ? 1 : -1;
            if (index < 0 && direction < 0) index = 0;
            for (int i = 0; i < controls.size(); i++) {
                index = Math.floorMod(index + direction, controls.size());
                var next = controls.get(index);
                if (!footer.contains(next)) {
                    int top = next.getY() + scroll - bodyTop;
                    preferredScroll =
                            Math.clamp(top, 0, Math.max(0, bodyHeight - (bodyBottom - bodyTop)));
                    suspendedFocus = null;
                    arrange();
                }
                if (!next.visible || !next.active) continue;
                suspendedFocus = null;
                setFocused(next);
                return true;
            }
            return true;
        }

        @Override
        public void tick() {
            boolean busy = parent.job != null;
            parent.tick();
            if (busy != (parent.job != null)) arrange();
        }

        public void renderBackground(GuiGraphics g, int x, int y, float t) {}

        public void render(GuiGraphics g, int x, int y, float t) {
            g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
            if (width >= 48 && height >= 48)
                g.drawCenteredString(font, title, width / 2, 12, EditorButtonTheme.text());
            EditorButtonTheme.widgets(this);
            if (bodyBottom > bodyTop) {
                g.enableScissor(left(), bodyTop, Math.max(left(), right() - 8), bodyBottom);
                for (int i = 0; i < fields.size(); i++) {
                    var label = labels.get(i);
                    if (label.getY() + label.getHeight() > bodyTop && label.getY() < bodyBottom)
                        label.render(g, x, y, t);
                    fields.get(i).render(g, x, y, t);
                }
                runtimeHelp.render(g, x, y, t);
                dependencyHelp.render(g, x, y, t);
                if (showDependencyDetails) dependencyDetails.render(g, x, y, t);
                for (var row : dependencyRows) row.render(g, x, y, t);
                for (var button : dependencyActions) button.render(g, x, y, t);
                for (var button : licensing) button.render(g, x, y, t);
                licenseHelp.render(g, x, y, t);
                if (showLicense) licensePreview.render(g, x, y, t);
                for (var button : images) button.render(g, x, y, t);
                g.disableScissor();
                bar.render(g);
            }
            for (var button : footer) button.render(g, x, y, t);
            if (getFocused() instanceof RegistryEditBox text && parent.job == null)
                text.suggestions(g);
            String message = parent.job == null ? error : tr("busy").getString();
            if (message.isEmpty() && bodyBottom - bodyTop < 20)
                message = Component.translatable("npships.editor.window_small").getString();
            if (statusTop >= 28)
                g.drawString(
                        font,
                        font.plainSubstrByWidth(message, Math.max(0, right() - left())),
                        left(),
                        statusTop,
                        0xff5555);
            if (statusTop >= 28
                    && !message.isEmpty()
                    && x >= left()
                    && x < right()
                    && y >= statusTop
                    && y < statusTop + font.lineHeight)
                EditorTooltips.show(
                        font,
                        Component.literal(
                                parent.job == null && !error.isEmpty() ? errorDetails : message),
                        x,
                        y);
        }

        public boolean isPauseScreen() {
            return false;
        }

        public void onClose() {
            if (parent.job != null) return;
            parent.exportingScreen = null;
            minecraft.setScreen(parent);
        }
    }
}
