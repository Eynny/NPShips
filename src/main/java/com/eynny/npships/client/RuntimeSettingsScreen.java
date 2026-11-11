package com.eynny.npships.client;

import com.eynny.npships.network.RuntimeSettingsNetworking;
import com.google.gson.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/** Native settings controls; structure administration is an explicit, separate view. */
public final class RuntimeSettingsScreen extends Screen {
    private enum Page {
        GENERAL,
        STRUCTURES,
        DIAGNOSTICS
    }

    private final Screen parent;
    private final ModContainer container;
    private JsonObject data;
    private Page page = Page.GENERAL;
    private String query = "";
    private int offset, rowCount, windowStart;
    private long session, revision = -1, searchDue, nextPageAt;
    private Object connection, level;
    private boolean attached, wantPage;
    private Pending pending = Pending.NONE;
    private String transportError = "";
    private RequestContext inFlight;
    private Partial partial;
    private EditBox search;
    private Integer selectedToken;
    private long selectedGeneration;
    private java.util.function.Consumer<String> installedReceiver;
    private final List<Item> window = new ArrayList<>();
    private final Map<AbstractWidget, Item> rowItems = new IdentityHashMap<>();
    private static final int WINDOW_ROWS = 128, WINDOW_CHARS = 65536, TOOLTIP_CHARS = 4096;
    private static final long SEARCH_DELAY = 150L;

    private enum Pending {
        NONE,
        STATE,
        MUTATION,
        PAGE
    }

    private record RequestContext(
            long id,
            long session,
            long revision,
            String view,
            String query,
            boolean disabled,
            int offset,
            int textOffset) {}

    private record Item(
            int index,
            int token,
            String id,
            boolean enabled,
            boolean available,
            String source,
            String reason) {}

    private static final class Partial {
        final int index, token, length;
        final boolean enabled, available;
        final String source, reason;
        final StringBuilder text = new StringBuilder();

        Partial(int index, JsonObject row) {
            this.index = index;
            token = row.get("token").getAsInt();
            length = row.get("length").getAsInt();
            enabled = row.get("enabled").getAsBoolean();
            available = row.get("available").getAsBoolean();
            source = row.get("source").getAsString();
            reason = row.get("reason").getAsString();
            if (length < 0) throw new IllegalArgumentException("Negative row length");
        }

        boolean matches(JsonObject row) {
            return token == row.get("token").getAsInt()
                    && length == row.get("length").getAsInt()
                    && enabled == row.get("enabled").getAsBoolean()
                    && available == row.get("available").getAsBoolean()
                    && source.equals(row.get("source").getAsString())
                    && reason.equals(row.get("reason").getAsString());
        }

        Item item() {
            return new Item(index, token, text.toString(), enabled, available, source, reason);
        }
    }

    private boolean onlyDisabled;
    private boolean awaitingReply, closeAfterReply, requestTimedOut;
    private long requestStarted, requestId;
    private final Map<Button, Boolean> waitingButtons = new IdentityHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_IDS =
            new java.util.concurrent.atomic.AtomicLong();
    private final List<AbstractWidget> rows = new ArrayList<>();

    public RuntimeSettingsScreen(ModContainer container, Screen parent) {
        super(tr("title"));
        this.container = container;
        this.parent = parent;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("npships.settings." + key, args);
    }

    static Component restartSettingName(String path) {
        String full = "npships.configuration." + path;
        if (net.minecraft.locale.Language.getInstance().has(full))
            return Component.translatable(full);
        String leaf = "npships.configuration." + path.substring(path.lastIndexOf('.') + 1);
        return net.minecraft.locale.Language.getInstance().has(leaf)
                ? Component.translatable(leaf)
                : Component.literal(path);
    }

    private int left() {
        return Math.max(12, (width - 600) / 2);
    }

    private int span() {
        return Math.max(0, width - left() * 2);
    }

    private boolean editable() {
        return data != null && data.get("editable").getAsBoolean();
    }

    private Button button(Component label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(
                Button.builder(label, b -> action.run()).bounds(x, y, w, 20).build());
    }

    private void clearWindow() {
        window.clear();
        partial = null;
        rowCount = 0;
        windowStart = offset;
    }

    private void cancelRead() {
        if (pending == Pending.MUTATION) return;
        requestId = REQUEST_IDS.incrementAndGet();
        awaitingReply = false;
        pending = Pending.NONE;
        inFlight = null;
        partial = null;
        wantPage = false;
        searchDue = 0;
        restoreButtons();
    }

    private void page(Page next) {
        if (pending == Pending.MUTATION) return;
        cancelRead();
        page = next;
        selectedToken = null;
        offset = 0;
        query = "";
        clearWindow();
        requestTimedOut = false;
        transportError = "";
        wantPage = next != Page.GENERAL;
        rebuildWidgets();
        requestPageIfNeeded();
    }

    @Override
    protected void init() {
        if (!attached) {
            attached = true;
            session = REQUEST_IDS.incrementAndGet();
            connection = minecraft.getConnection();
            level = minecraft.level;
            installedReceiver = this::receive;
        }
        RuntimeSettingsNetworking.receiver = installedReceiver;
        search = null;
        int x = left(), w = span();
        if (page != Page.GENERAL) {
            search =
                    addRenderableWidget(
                            new EditBox(font, x, 48, Math.max(0, w - 112), 20, tr("search")));
            search.setMaxLength(256);
            search.setHint(tr("search"));
            search.setValue(query);
            search.setResponder(
                    value -> {
                        query = value;
                        requestTimedOut = false;
                        transportError = "";
                        selectedToken = null;
                        offset = 0;
                        clearWindow();
                        wantPage = true;
                        searchDue = net.minecraft.Util.getMillis() + SEARCH_DELAY;
                        buildRows();
                    });
            button(
                    tr(page == Page.STRUCTURES ? onlyDisabled ? "disabled" : "all" : "refresh"),
                    x + w - 106,
                    48,
                    106,
                    () -> {
                        if (page == Page.STRUCTURES) {
                            onlyDisabled = !onlyDisabled;
                            requestTimedOut = false;
                            transportError = "";
                            offset = 0;
                            clearWindow();
                            wantPage = true;
                            searchDue = 0;
                            rebuildWidgets();
                            requestPageIfNeeded();
                        } else refresh();
                    });
        }
        if (page == Page.GENERAL) {
            button(tr("structures"), x, height - 54, w, () -> page(Page.STRUCTURES));
            boolean advanced = true;
            button(
                    Component.translatable("gui.done"),
                    x,
                    height - 28,
                    advanced ? (w - 8) / 2 : w,
                    this::onClose);
            if (advanced) {
                var control =
                        button(
                                tr("advanced"),
                                x + (w + 8) / 2,
                                height - 28,
                                (w - 8) / 2,
                                () ->
                                        minecraft.setScreen(
                                                NPShipsAdvancedSettings.create(
                                                        container, this, editable())));
                control.setTooltip(Tooltip.create(tr("advanced_hint")));
            }
        } else {
            button(
                    Component.translatable("gui.back"),
                    x,
                    height - 28,
                    (w - 8) / 2,
                    () -> page(page == Page.DIAGNOSTICS ? Page.STRUCTURES : Page.GENERAL));
            button(
                    tr(
                            !editable()
                                    ? "refresh"
                                    : page == Page.STRUCTURES ? "diagnostics" : "structures"),
                    x + (w + 8) / 2,
                    height - 28,
                    (w - 8) / 2,
                    () -> {
                        if (!editable()) refresh();
                        else page(page == Page.STRUCTURES ? Page.DIAGNOSTICS : Page.STRUCTURES);
                    });
        }
        buildRows();
        if (pending == Pending.MUTATION) disableMutationButtons();
        if (data == null && !requestTimedOut && minecraft.getConnection() != null) send("", "");
    }

    private void refresh() {
        cancelRead();
        clearWindow();
        wantPage = page != Page.GENERAL;
        send("", "");
    }

    private void send(String key, String value) {
        send(key, value, 0);
    }

    private void send(String key, String value, int token) {
        if (awaitingReply || minecraft.getConnection() == null) return;
        if (key.equals("structure_token")) {
            selectedToken = token;
            selectedGeneration = data.get("generation").getAsLong();
        }
        begin(key.isEmpty() ? Pending.STATE : Pending.MUTATION, 0);
        if (pending == Pending.MUTATION) disableMutationButtons();
        PacketDistributor.sendToServer(
                new RuntimeSettingsNetworking.Request(
                        key, value, requestId, session, revision, token));
    }

    private void disableMutationButtons() {
        for (var child : children())
            if (child instanceof Button button) {
                waitingButtons.putIfAbsent(button, button.active);
                button.active = false;
            }
    }

    private String view() {
        return page == Page.STRUCTURES ? "structures" : "diagnostics";
    }

    private void begin(Pending kind, int textOffset) {
        requestTimedOut = false;
        transportError = "";
        awaitingReply = true;
        pending = kind;
        requestStarted = net.minecraft.Util.getMillis();
        requestId = REQUEST_IDS.incrementAndGet();
        inFlight =
                new RequestContext(
                        requestId,
                        session,
                        revision,
                        view(),
                        query,
                        onlyDisabled && page == Page.STRUCTURES,
                        partial == null ? offset : partial.index,
                        textOffset);
    }

    private void requestPageIfNeeded() {
        if (!wantPage
                || awaitingReply
                || page == Page.GENERAL
                || data == null
                || revision < 0
                || requestTimedOut
                || minecraft.getConnection() == null
                || net.minecraft.Util.getMillis() < Math.max(searchDue, nextPageAt)) return;
        begin(Pending.PAGE, partial == null ? 0 : partial.text.length());
        wantPage = false;
        // Match the server admission interval, including one very long fragmented row.
        nextPageAt = requestStarted + 50L;
        PacketDistributor.sendToServer(
                new RuntimeSettingsNetworking.PageRequest(
                        requestId,
                        session,
                        revision,
                        inFlight.view(),
                        inFlight.query(),
                        inFlight.disabled(),
                        inFlight.offset(),
                        inFlight.textOffset()));
    }

    private void receive(String json) {
        if (!attached
                || connection != minecraft.getConnection()
                || level != minecraft.level
                || RuntimeSettingsNetworking.receiver != installedReceiver) return;
        try {
            var reply = JsonParser.parseString(json).getAsJsonObject();
            if (!reply.has("request_id")
                    || !reply.has("session")
                    || reply.get("request_id").getAsLong() != requestId
                    || reply.get("session").getAsLong() != session
                    || !awaitingReply) return;
            Pending completed = pending;
            var expected = inFlight;
            if (expected == null) return;
            String kind = reply.get("kind").getAsString();
            if (kind.equals("state")
                    && completed == Pending.PAGE
                    && reply.has("error")
                    && !reply.get("error").getAsString().isEmpty()) {
                String error = reply.get("error").getAsString();
                if (!reply.get("editable").getAsBoolean()) data = reply;
                failRequest(error);
            } else if (kind.equals("page") && completed == Pending.PAGE) {
                receivePage(reply, expected);
            } else if (kind.equals("state") && completed != Pending.PAGE) {
                boolean wasEditable = editable();
                long nextRevision = reply.get("revision").getAsLong();
                if (nextRevision != revision || completed == Pending.MUTATION) clearWindow();
                revision = nextRevision;
                data = reply;
                finishRequest();
                if (!editable() && page == Page.DIAGNOSTICS) {
                    page = Page.STRUCTURES;
                    offset = 0;
                    query = "";
                    clearWindow();
                }
                if (closeAfterReply) {
                    closeAfterReply = false;
                    if (data.get("error").getAsString().isEmpty()) {
                        onClose();
                        return;
                    }
                }
                if (wasEditable != editable()) rebuildWidgets();
                else buildRows();
                if (page != Page.GENERAL && data.get("error").getAsString().isEmpty()) {
                    wantPage = true;
                    requestPageIfNeeded();
                }
            } else return;
        } catch (RuntimeException invalid) {
            failRequest("npships.settings.invalid");
        }
    }

    private void finishRequest() {
        restoreButtons();
        awaitingReply = false;
        pending = Pending.NONE;
        inFlight = null;
        requestTimedOut = false;
        transportError = "";
    }

    private void receivePage(JsonObject reply, RequestContext expected) {
        if (!expected.view().equals(view())
                || !expected.query().equals(query)
                || expected.offset() != offset
                || expected.disabled() != (onlyDisabled && page == Page.STRUCTURES)) {
            finishRequest();
            wantPage = true;
            requestPageIfNeeded();
            return;
        }
        if (!reply.get("view").getAsString().equals(expected.view())
                || !reply.get("query").getAsString().equals(expected.query())
                || reply.get("disabled").getAsBoolean() != expected.disabled()
                || reply.get("offset").getAsInt() != expected.offset()
                || reply.get("text_offset").getAsInt() != expected.textOffset()) return;
        boolean wasEditable = editable();
        var metadata = new JsonObject();
        for (String key :
                List.of(
                        "kind",
                        "editable",
                        "error",
                        "enabled",
                        "developer_mode",
                        "mob_movement_fix",
                        "mob_cross_structure_fix",
                        "mob_mounted_attack_fix",
                        "pending_restart",
                        "catalogue_failed",
                        "excluded_entries",
                        "generation",
                        "revision",
                        "request_id",
                        "session")) if (reply.has(key)) metadata.add(key, reply.get(key));
        // Page metadata is tiny; do not retain its JSON fragment array alongside the row window.
        data = metadata;
        if (!editable() && page == Page.DIAGNOSTICS) {
            finishRequest();
            revision = reply.get("revision").getAsLong();
            page = Page.STRUCTURES;
            offset = 0;
            query = "";
            clearWindow();
            wantPage = true;
            rebuildWidgets();
            requestPageIfNeeded();
            return;
        }
        if (reply.get("stale").getAsBoolean()
                || reply.get("revision").getAsLong() != expected.revision()) {
            finishRequest();
            revision = reply.get("revision").getAsLong();
            clearWindow();
            wantPage = true;
            if (wasEditable != editable()) rebuildWidgets();
            else buildRows();
            requestPageIfNeeded();
            return;
        }
        int total = reply.get("total").getAsInt();
        if (total < 0) throw new IllegalArgumentException("Negative page size");
        var fragments = reply.getAsJsonArray("rows");
        if (fragments.size() > 64) throw new IllegalArgumentException("Excess page rows");
        int cursor = expected.offset();
        int textOffset = expected.textOffset();
        if (cursor >= total && fragments.isEmpty()) {
            finishRequest();
            clearWindow();
            rowCount = total;
            offset = Math.max(0, total - visibleRows());
            wantPage = total > 0;
            if (wasEditable != editable()) rebuildWidgets();
            else buildRows();
            requestPageIfNeeded();
            return;
        }
        if (reply.has("error") && !reply.get("error").getAsString().isEmpty()) {
            failRequest(reply.get("error").getAsString());
            return;
        }
        if (partial == null) {
            window.clear();
            windowStart = cursor;
        }
        for (var raw : fragments) {
            var fragment = raw.getAsJsonObject();
            String text = fragment.get("id").getAsString();
            int start = fragment.get("start").getAsInt();
            if (partial == null) partial = new Partial(cursor, fragment);
            if (partial.index != cursor
                    || !partial.matches(fragment)
                    || start != textOffset
                    || partial.text.length() != textOffset
                    || text.length() > 4096
                    || start < 0
                    || start > partial.length
                    || text.length() > partial.length - start
                    || text.isEmpty() && partial.length != 0)
                throw new IllegalArgumentException("Invalid row fragment");
            partial.text.append(text);
            textOffset += text.length();
            if (textOffset == partial.length) {
                window.add(partial.item());
                partial = null;
                cursor++;
                textOffset = 0;
            }
        }
        if (reply.get("next_offset").getAsInt() != cursor
                || reply.get("next_text_offset").getAsInt() != textOffset
                || cursor > total
                || cursor == total && textOffset != 0
                || fragments.isEmpty() && expected.offset() < total)
            throw new IllegalArgumentException("Invalid page progress");
        rowCount = total;
        offset = Math.clamp(offset, 0, Math.max(0, total - 1));
        int chars = 0, keep = 0;
        for (var item : window) {
            if (keep >= WINDOW_ROWS || keep > 0 && item.id().length() > WINDOW_CHARS - chars) break;
            chars =
                    item.id().length() > WINDOW_CHARS - chars
                            ? WINDOW_CHARS
                            : chars + item.id().length();
            keep++;
        }
        while (window.size() > keep) window.removeLast();
        finishRequest();
        // Continue only to finish the first visible long row, never to download the catalogue.
        boolean continuation = window.isEmpty() && partial != null;
        if (!continuation) partial = null;
        wantPage = continuation || total > 0 && !hasRow(offset);
        if (wasEditable != editable()) rebuildWidgets();
        else buildRows();
        requestPageIfNeeded();
    }

    private boolean hasRow(int index) {
        return index >= windowStart && index - windowStart < window.size();
    }

    private void failRequest(String error) {
        finishRequest();
        partial = null;
        wantPage = false;
        searchDue = 0;
        requestTimedOut = true;
        transportError = error;
        // Keep timeout/invalid-response feedback visible instead of closing over it.
        closeAfterReply = false;
        rebuildWidgets();
    }

    private void restoreButtons() {
        waitingButtons.forEach((button, active) -> button.active = active);
        waitingButtons.clear();
    }

    private record Row(Component label, Component hint, Runnable action) {}

    private int top() {
        return page == Page.GENERAL
                ? (data != null
                                && data.has("pending_restart")
                                && !data.getAsJsonArray("pending_restart").isEmpty()
                        ? 78
                        : 45)
                : 76;
    }

    private int visibleRows() {
        return Math.clamp((height - top() - (page == Page.GENERAL ? 62 : 36)) / 24, 1, WINDOW_ROWS);
    }

    private void buildRows() {
        int focusedRow = rows.indexOf(getFocused());
        if (focusedRow >= 0) setFocused(null);
        for (var row : rows) removeWidget(row);
        rows.clear();
        rowItems.clear();
        if (data == null && page != Page.GENERAL) return;
        var items = new ArrayList<Row>();
        if (page == Page.GENERAL) {
            for (String key : data == null ? List.<String>of() : List.of("enabled")) {
                boolean value = data.has(key) && data.get(key).getAsBoolean();
                var hint = tr(key + "_hint");
                items.add(
                        new Row(
                                tr(key).copy()
                                        .append(": ")
                                        .append(
                                                Component.translatable(
                                                        value ? "options.on" : "options.off")),
                                hint,
                                editable() ? () -> send(key, Boolean.toString(!value)) : null));
            }
            if (requestTimedOut)
                items.add(new Row(tr("refresh"), tr("reply_timeout"), () -> send("", "")));

        } else {
            for (int i = offset; i < offset + visibleRows() && hasRow(i); i++) {
                var item = window.get(i - windowStart);
                String prefix = item.id().substring(0, Math.min(TOOLTIP_CHARS, item.id().length()));
                Component label =
                        item.source().equals("resource")
                                ? Component.literal(prefix + " : ")
                                        .append(
                                                Component.translatable(
                                                        !item.available()
                                                                ? "npships.settings.unavailable_short"
                                                                : item.enabled()
                                                                        ? "options.on"
                                                                        : "options.off"))
                                : tr("source_" + item.source()).copy().append(": ").append(prefix);
                int tooltipLines = Math.clamp((height - 80) / font.lineHeight - 6, 1, 6);
                String tooltipPrefix =
                        font.plainSubstrByWidth(
                                prefix, Math.clamp(width / 2, 80, 240) * tooltipLines);
                boolean interactive = editable() && item.available();
                var hint =
                        interactive
                                ? tr("row_" + item.reason()).copy()
                                : Component.literal(tooltipPrefix + "\n")
                                        .append(tr("row_" + item.reason()));
                if (!interactive || tooltipPrefix.length() < item.id().length())
                    hint.append("\n")
                            .append(
                                    tr(
                                            tooltipPrefix.length() < item.id().length()
                                                    ? "long_value"
                                                    : "copy_value"));
                int token = item.token();
                boolean nextEnabled = !item.enabled();
                items.add(
                        new Row(
                                label,
                                hint,
                                editable() && item.available()
                                        ? () ->
                                                send(
                                                        "structure_token",
                                                        Boolean.toString(nextEnabled),
                                                        token)
                                        : null));
            }
        }
        if (page == Page.GENERAL) {
            rowCount = items.size();
            offset = Math.clamp(offset, 0, Math.max(0, rowCount - visibleRows()));
        }
        int start = page == Page.GENERAL ? offset : 0;
        for (int i = start; i < Math.min(items.size(), start + visibleRows()); i++) {
            var item = items.get(i);
            var label =
                    Component.literal(
                            font.plainSubstrByWidth(
                                    item.label().getString(), Math.max(0, span() - 12)));
            int y = top() + (i - start) * 24;
            var control =
                    Button.builder(
                                    label,
                                    b -> {
                                        if (item.action() != null && !awaitingReply)
                                            item.action().run();
                                    })
                            .bounds(left(), y, span(), 20)
                            .build();
            control.active = item.action() != null && !awaitingReply;
            var hint =
                    (page == Page.GENERAL
                                    ? item.label().copy().append("\n").append(item.hint())
                                    : item.hint())
                            .copy();
            if (page == Page.GENERAL && item.action() == null && !editable())
                hint.append("\n").append(tr("read_only"));
            control.setTooltip(Tooltip.create(hint));
            rows.add(addRenderableWidget(control));
            if (page != Page.GENERAL && hasRow(offset + i)) {
                var nativeRow = window.get(offset + i - windowStart);
                rowItems.put(control, nativeRow);
                if (getFocused() == null
                        && selectedToken != null
                        && selectedGeneration == data.get("generation").getAsLong()
                        && nativeRow.token() == selectedToken) setFocused(control);
            }
            if (rows.size() - 1 == focusedRow) setFocused(control);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= left()
                && x < left() + span()
                && y >= top()
                && y < height - (page == Page.GENERAL ? 60 : 32)) {
            // A short list viewport must not skip rows that can never be reached by the wheel.
            int step = Math.min(3, visibleRows());
            if (page != Page.GENERAL && hasRow(offset))
                step = Math.min(step, Math.max(1, windowStart + window.size() - offset));
            moveOffset(offset - (int) Math.signum(vertical) * step);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private void moveOffset(int next) {
        offset =
                Math.clamp(
                        next,
                        0,
                        Math.max(0, rowCount - (page == Page.GENERAL ? visibleRows() : 1)));
        if (page != Page.GENERAL && !hasRow(offset)) {
            int total = rowCount;
            clearWindow();
            rowCount = total;
            wantPage = true;
            searchDue = 0;
            requestPageIfNeeded();
        }
        buildRows();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) {
            for (var row : rowItems.keySet())
                // Native isMouseOver deliberately excludes inactive widgets.
                if (!row.active
                        && row.visible
                        && x >= row.getX()
                        && x < row.getX() + row.getWidth()
                        && y >= row.getY()
                        && y < row.getY() + row.getHeight()) {
                    setFocused(row);
                    return true;
                }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        var item = rowItems.get(getFocused());
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_C
                && ((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0 || hasControlDown())
                && item != null) {
            minecraft.keyboardHandler.setClipboard(item.id());
            return true;
        }
        if (page != Page.GENERAL) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && getFocused() == search) {
                searchDue = 0;
                requestPageIfNeeded();
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN
                    || key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
                moveOffset(
                        offset
                                + (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1)
                                        * Math.max(
                                                1,
                                                Math.min(
                                                        visibleRows(),
                                                        window.isEmpty() ? 1 : window.size())));
                return true;
            }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        // Native Screen owns the background pass. Headings follow it so blur cannot erase them.
        super.render(g, mx, my, partial);
        g.drawCenteredString(
                font,
                page == Page.GENERAL
                        ? title
                        : tr(page == Page.STRUCTURES ? "structures" : "diagnostics"),
                width / 2,
                10,
                0xffffff);
        var status =
                !transportError.isEmpty()
                        ? Component.translatable(transportError)
                        : data == null
                                ? tr(
                                        requestTimedOut
                                                ? "reply_timeout"
                                                : minecraft.level == null ? "join" : "loading")
                                : !data.get("error").getAsString().isEmpty()
                                        ? Component.translatable(data.get("error").getAsString())
                                        : data.get("catalogue_failed").getAsBoolean()
                                                ? tr("catalogue_failed")
                                                : editable() ? Component.empty() : tr("read_only");
        g.drawCenteredString(
                font,
                Component.literal(
                        font.plainSubstrByWidth(status.getString(), Math.max(0, width - 24))),
                width / 2,
                27,
                0xffffff);
        if (page == Page.GENERAL) {
            var pending = data == null ? new JsonArray() : data.getAsJsonArray("pending_restart");
            var hint =
                    pending.isEmpty() ? Component.empty() : tr("restart_pending", pending.size());
            g.drawWordWrap(font, hint, left(), 43, span(), pending.isEmpty() ? 0xdddddd : 0xffaa00);
            if (!pending.isEmpty() && my >= 43 && my < 74) {
                var names = Component.empty();
                for (var entry : pending) {
                    String path = entry.getAsString();
                    names.append(restartSettingName(path)).append("\n");
                }
                setTooltipForNextRenderPass(names);
            }
        } else {
            if (rows.isEmpty() && data != null)
                g.drawCenteredString(
                        font,
                        awaitingReply || wantPage ? tr("loading") : tr("empty"),
                        width / 2,
                        top() + 5,
                        0xffffff);
            int shown = rows.size();
            g.drawCenteredString(
                    font,
                    Component.literal(
                            font.plainSubstrByWidth(
                                    tr(
                                                    "page_range",
                                                    shown == 0 ? 0 : offset + 1,
                                                    offset + shown,
                                                    rowCount)
                                            .getString(),
                                    Math.max(0, span() - 8))),
                    width / 2,
                    height - 39,
                    0xdddddd);
        }
    }

    @Override
    public void removed() {
        attached = false;
        closeAfterReply = false;
        if (RuntimeSettingsNetworking.receiver == installedReceiver)
            RuntimeSettingsNetworking.receiver = value -> {};
        cancelRead();
        restoreButtons();
        setFocused(null);
        for (var row : rows) removeWidget(row);
        rows.clear();
        rowItems.clear();
        awaitingReply = false;
        pending = Pending.NONE;
        inFlight = null;
        partial = null;
        clearWindow();
        data = null;
        revision = -1;
        session = REQUEST_IDS.incrementAndGet();
    }

    @Override
    public void onClose() {
        if (awaitingReply && pending == Pending.MUTATION) {
            closeAfterReply = true;
            return;
        }
        cancelRead();
        if (page != Page.GENERAL) page(page == Page.DIAGNOSTICS ? Page.STRUCTURES : Page.GENERAL);
        else minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return !awaitingReply && !wantPage;
    }

    @Override
    public void tick() {
        super.tick();
        if (connection != minecraft.getConnection() || level != minecraft.level) {
            cancelRead();
            // A previous connection/world can never complete this screen's new request epoch.
            awaitingReply = false;
            closeAfterReply = false;
            pending = Pending.NONE;
            inFlight = null;
            restoreButtons();
            connection = minecraft.getConnection();
            level = minecraft.level;
            session = REQUEST_IDS.incrementAndGet();
            clearWindow();
            data = null;
            revision = -1;
            requestTimedOut = false;
            transportError = "";
            if (connection != null) send("", "");
        }
        if (awaitingReply
                && (minecraft.getConnection() == null
                        || net.minecraft.Util.getMillis() - requestStarted >= 10000)) {
            failRequest("npships.settings.reply_timeout");
        }
        requestPageIfNeeded();
    }
}
