package com.eynny.npships.client;

import com.eynny.npships.client.editor.widget.EditorScrollBar;
import com.eynny.npships.network.PatchManagerModel;
import com.eynny.npships.network.RuntimeSettingsNetworking;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** SERVER-derived provider administration; native input edits only this screen's detached draft. */
public final class PatchManagerScreen extends Screen {
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static final int ROW_HEIGHT = 30, PART_CHARS = 4000, MAX_POLICY_CHARS = 262144;
    private static final long TIMEOUT = 10000;
    private final Screen parent;
    private JsonObject data;
    private PatchManagerDraft draft;
    private List<String> visibleIds = List.of();
    private Set<String> visibleSet = Set.of();
    private int visibleSelected;
    private final List<PatchManagerModel.Row> receivedRows = new ArrayList<>();
    private final Set<String> receivedIds = new HashSet<>();
    private String query = "", pendingAction = "", uploadText = "";
    private String epoch = "", registry = "", configuredRevision = "", effectiveRevision = "";
    private Component error = Component.empty();
    private int scroll,
            pageOffset,
            textOffset,
            total = -1,
            fragmentLength = -1,
            uploadOffset,
            sentPartEnd;
    private StringBuilder fragment;
    private long session, requestId, requestStarted, upload, nextSendAt;
    private String queuedAction = "";
    private JsonObject queuedBody;
    private Object connection, level;
    private boolean attached, awaitingReply, mutating, loading, needsRefresh, closeAfterReply;
    private Consumer<String> installedReceiver;
    private EditBox search;
    private ProviderList list;
    private Button apply, cancel, reset, refresh;
    private final EditorScrollBar scrollBar = new EditorScrollBar();
    private String armedId, tooltipId, dropError = "";
    private List<FormattedCharSequence> rowTooltip = List.of();
    private double pressX, pressY, lastMouseX, lastMouseY;
    private boolean dragging, collapseOnRelease;
    private int dropIndex, modifierMask;

    public PatchManagerScreen(Screen parent) {
        super(tr("title"));
        this.parent = parent;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("npships.patch_manager." + key, args);
    }

    private int left() {
        return Math.max(8, (width - 800) / 2);
    }

    private int span() {
        return Math.max(1, width - left() * 2);
    }

    private boolean editable() {
        return data != null && data.get("editable").getAsBoolean();
    }

    private boolean canEdit() {
        return editable()
                && draft != null
                && !loading
                && !mutating
                && !awaitingReply
                && queuedAction.isEmpty()
                && !needsRefresh;
    }

    private boolean canReorder() {
        return canEdit() && query.isBlank();
    }

    private int visibleRows() {
        return list == null ? 1 : Math.max(1, list.getHeight() / ROW_HEIGHT);
    }

    private Button button(Component label, int x, int y, int w, Runnable action) {
        var widget =
                Button.builder(label, b -> action.run()).bounds(x, y, Math.max(1, w), 20).build();
        widget.setTooltip(Tooltip.create(label));
        return addRenderableWidget(widget);
    }

    @Override
    protected void init() {
        cancelGesture();
        modifierMask = 0;
        if (!attached) {
            attached = true;
            session = REQUEST_IDS.incrementAndGet();
            connection = minecraft.getConnection();
            level = minecraft.level;
            installedReceiver = this::receive;
        }
        RuntimeSettingsNetworking.policyReceiver = installedReceiver;
        tooltipId = null;
        int x = left(), w = span();
        search =
                addRenderableWidget(
                        new EditBox(font, x, 44, Math.max(1, w - 176), 20, tr("search")));
        search.setMaxLength(256);
        search.setHint(tr("search"));
        search.setValue(query);
        search.setResponder(
                value -> {
                    query = value;
                    cancelGesture();
                    scroll = 0;
                    updateVisible();
                });
        reset = button(tr("reset"), x + w - 172, 44, 82, this::defaults);
        reset.setTooltip(Tooltip.create(tr("reset_hint")));
        refresh = button(tr("refresh"), x + w - 86, 44, 86, this::refresh);
        refresh.setTooltip(Tooltip.create(tr("refresh_hint")));
        list =
                addRenderableWidget(
                        new ProviderList(
                                x,
                                70,
                                w,
                                Math.max(1, ((height - 70 - 44) / ROW_HEIGHT) * ROW_HEIGHT)));
        apply = button(tr("apply"), x, height - 26, (w - 6) / 2, this::apply);
        cancel = button(tr("cancel"), x + (w + 6) / 2, height - 26, (w - 6) / 2, this::onClose);
        cancel.setTooltip(Tooltip.create(tr("cancel_hint")));
        updateVisible();
        if (data == null && !awaitingReply && connection != null && !needsRefresh) refresh();
    }

    private void updateVisible() {
        tooltipId = null;
        visibleIds =
                draft == null
                        ? List.of()
                        : draft.visible("").stream()
                                .filter(
                                        id -> {
                                            var row = draft.byId.get(id);
                                            String q =
                                                    query.toLowerCase(java.util.Locale.ROOT)
                                                            .strip();
                                            return q.isEmpty()
                                                    || id.toLowerCase(java.util.Locale.ROOT)
                                                            .contains(q)
                                                    || name(row)
                                                            .getString()
                                                            .toLowerCase(java.util.Locale.ROOT)
                                                            .contains(q)
                                                    || row.ownerDisplayName()
                                                            .toLowerCase(java.util.Locale.ROOT)
                                                            .contains(q);
                                        })
                                .toList();
        visibleSet = Set.copyOf(visibleIds);
        if (list != null) list.controls.clear();
        scroll = Math.clamp(scroll, 0, Math.max(0, visibleIds.size() - visibleRows()));
        updateButtons();
    }

    private void updateButtons() {
        if (apply == null) return;
        visibleSelected =
                draft == null
                        ? 0
                        : (int) draft.selected.stream().filter(visibleSet::contains).count();
        reset.active = canEdit();
        apply.active = canEdit() && draft.dirty();
        refresh.active = !mutating && !awaitingReply && queuedAction.isEmpty();
        cancel.active = !mutating;
    }

    private static Component display(String key, String fallback) {
        return key.isEmpty()
                ? Component.literal(fallback)
                : Component.translatableWithFallback(key, fallback);
    }

    private static Component name(PatchManagerModel.Row row) {
        return row.metadata() == null
                ? Component.literal(row.displayName())
                : display(row.metadata().nameKey(), row.metadata().name());
    }

    private static Component reason(String value) {
        if (value.isEmpty()) return Component.empty();
        String[] fields = value.split("\\|", -1);
        if (fields.length == 3 && fields[0].equals("npships.patch_manager.required_unavailable"))
            return tr("required_unavailable", fields[1], fields[2]);
        if (fields.length == 4 && fields[0].equals("npships.patch_manager.required_order"))
            return tr(
                    "required_order",
                    fields[1],
                    fields[2],
                    tr("stage." + fields[3].toLowerCase(java.util.Locale.ROOT)));
        if (value.startsWith("missing_mod:")) return tr("missing_mod", value.substring(12));
        if (value.startsWith("missing_provider:"))
            return tr("missing_provider", value.substring(17));
        return value.startsWith("npships.") ? Component.translatable(value) : tr("refused", value);
    }

    private void result(String value) {
        error = reason(value);
        updateVisible();
    }

    private void enabled(boolean enabled) {
        cancelGesture();
        if (canEdit()) {
            var targets = new HashSet<>(draft.selected);
            targets.retainAll(visibleSet);
            result(draft.enabled(enabled, targets));
        }
    }

    private void move(int direction, boolean edge) {
        cancelGesture();
        if (!query.isBlank()) {
            error = tr("clear_search_to_reorder");
            return;
        }
        if (canEdit()) {
            result(draft.move(direction, edge));
            ensureFocusedVisible();
        }
    }

    private void defaults() {
        cancelGesture();
        if (canEdit()) result(draft.defaults());
    }

    private void clearSelection() {
        if (draft == null || loading || mutating) return;
        cancelGesture();
        draft.selected.clear();
        draft.anchor = null;
        setFocused(list);
        updateButtons();
    }

    private void selectAll() {
        if (draft == null || loading || mutating) return;
        draft.selectAll(visibleIds);
        setFocused(list);
        updateButtons();
    }

    private void refresh() {
        if (mutating || awaitingReply || !queuedAction.isEmpty()) return;
        cancelGesture();
        error = Component.empty();
        needsRefresh = false;
        loading = true;
        receivedRows.clear();
        receivedIds.clear();
        pageOffset = textOffset = 0;
        total = fragmentLength = -1;
        fragment = null;
        epoch = registry = configuredRevision = effectiveRevision = "";
        data = null;
        sendPage();
    }

    private JsonObject fingerprints() {
        var json = new JsonObject();
        json.addProperty("epoch", epoch);
        json.addProperty("registry", registry);
        json.addProperty("configured_revision", configuredRevision);
        json.addProperty("effective_revision", effectiveRevision);
        return json;
    }

    private void sendPage() {
        var json = fingerprints();
        json.addProperty("offset", pageOffset);
        json.addProperty("text_offset", textOffset);
        json.addProperty("query", "");
        send("page", json);
    }

    private void send(String action, JsonObject json) {
        if (minecraft.getConnection() == null || awaitingReply || !queuedAction.isEmpty()) return;
        String encoded = json.toString();
        if (encoded.length() > 12000) {
            fail(tr("invalid"));
            return;
        }
        long now = net.minecraft.Util.getMillis();
        if (now < nextSendAt) {
            queuedAction = action;
            queuedBody = json;
            updateButtons();
            return;
        }
        nextSendAt = now + 50L;
        awaitingReply = true;
        pendingAction = action;
        requestStarted = net.minecraft.Util.getMillis();
        requestId = REQUEST_IDS.incrementAndGet();
        PacketDistributor.sendToServer(
                new RuntimeSettingsNetworking.PolicyRequest(action, encoded, requestId, session));
        updateButtons();
    }

    private boolean fingerprintsMatch(JsonObject json) {
        return epoch.isEmpty()
                || epoch.equals(json.get("epoch").getAsString())
                        && registry.equals(json.get("registry").getAsString())
                        && configuredRevision.equals(json.get("configured_revision").getAsString())
                        && effectiveRevision.equals(json.get("effective_revision").getAsString());
    }

    private void metadata(JsonObject reply) {
        epoch = reply.get("epoch").getAsString();
        registry = reply.get("registry").getAsString();
        configuredRevision = reply.get("configured_revision").getAsString();
        effectiveRevision = reply.get("effective_revision").getAsString();
        data = new JsonObject();
        for (var key :
                List.of(
                        "epoch",
                        "registry",
                        "configured_revision",
                        "effective_revision",
                        "editable",
                        "pending_restart",
                        "status",
                        "reason")) data.add(key, reply.get(key));
    }

    private void receive(String json) {
        if (!attached
                || connection != minecraft.getConnection()
                || level != minecraft.level
                || RuntimeSettingsNetworking.policyReceiver != installedReceiver
                || !awaitingReply) return;
        try {
            var reply = JsonParser.parseString(json).getAsJsonObject();
            if (!reply.get("kind").getAsString().equals("patch_state")
                    || reply.get("request_id").getAsLong() != requestId
                    || reply.get("session").getAsLong() != session) return;
            String action = pendingAction, status = reply.get("status").getAsString();
            awaitingReply = false;
            if (status.equals("refused") || status.equals("stale")) {
                if (reply.has("editable") && !reply.get("editable").getAsBoolean()) metadata(reply);
                String reason = reply.get("reason").getAsString();
                fail(status.equals("stale") ? tr("stale") : reason(reason));
                return;
            }
            if (action.equals("page")) {
                if (!status.equals("page"))
                    throw new IllegalArgumentException("Unexpected page reply");
                if (!fingerprintsMatch(reply)) {
                    fail(tr("stale"));
                    return;
                }
                metadata(reply);
                receivePage(reply);
            } else if (action.equals("begin")) {
                if (!status.equals("begun") || !fingerprintsMatch(reply))
                    throw new IllegalArgumentException("Unexpected upload start");
                upload = reply.get("upload").getAsLong();
                sendPart();
            } else if (action.equals("part")) {
                if (!status.equals("part")
                        || reply.get("upload").getAsLong() != upload
                        || reply.get("next_offset").getAsInt() != sentPartEnd
                        || !fingerprintsMatch(reply))
                    throw new IllegalArgumentException("Unexpected upload continuation");
                uploadOffset = sentPartEnd;
                sendPart();
            } else if (action.equals("commit")) {
                if (!status.equals("applied")
                        || !epoch.equals(reply.get("epoch").getAsString())
                        || !registry.equals(reply.get("registry").getAsString()))
                    throw new IllegalArgumentException("Unexpected policy commit");
                metadata(reply);
                mutating = false;
                uploadText = "";
                upload = 0;
                needsRefresh = false;
                if (closeAfterReply) {
                    closeAfterReply = false;
                    minecraft.setScreen(parent);
                    return;
                }
                refresh();
            } else throw new IllegalArgumentException("Unknown policy response action");
            updateButtons();
        } catch (RuntimeException invalid) {
            fail(tr("invalid"));
        }
    }

    private void receivePage(JsonObject reply) {
        int count = reply.get("total").getAsInt(), next = reply.get("next").getAsInt();
        if (count < 0
                || total >= 0 && total != count
                || reply.get("offset").getAsInt() != pageOffset)
            throw new IllegalArgumentException("Unstable page");
        total = count;
        if (reply.has("fragment")) {
            var part = reply.getAsJsonObject("fragment");
            int length = part.get("length").getAsInt();
            if (fragment == null) {
                if (textOffset != 0 || length < 1 || length > MAX_POLICY_CHARS)
                    throw new IllegalArgumentException("Invalid row size");
                fragment = new StringBuilder();
                fragmentLength = length;
            }
            String text = part.get("text").getAsString();
            if (part.get("index").getAsInt() != pageOffset
                    || part.get("text_offset").getAsInt() != textOffset
                    || length != fragmentLength
                    || text.isEmpty()
                    || text.length() > 4096
                    || text.length() > length - fragment.length())
                throw new IllegalArgumentException("Invalid row fragment");
            fragment.append(text);
            textOffset += text.length();
            if (textOffset == fragmentLength) {
                appendRow(JsonParser.parseString(fragment.toString()).getAsJsonObject());
                if (next != pageOffset + 1)
                    throw new IllegalArgumentException("Invalid completed row");
                pageOffset = next;
                textOffset = 0;
                fragment = null;
                fragmentLength = -1;
            } else if (next != pageOffset)
                throw new IllegalArgumentException("Invalid partial row");
        } else {
            if (fragment != null || textOffset != 0)
                throw new IllegalArgumentException("Lost row continuation");
            var rows = reply.getAsJsonArray("rows");
            if (rows.size() > 32
                    || next != pageOffset + rows.size()
                    || rows.isEmpty() && pageOffset < total)
                throw new IllegalArgumentException("Invalid page progress");
            for (var row : rows) appendRow(row.getAsJsonObject());
            pageOffset = next;
        }
        if (pageOffset > total) throw new IllegalArgumentException("Page exceeds total");
        if (pageOffset == total && fragment == null) {
            var previous = draft;
            draft = new PatchManagerDraft(receivedRows);
            if (previous != null) {
                for (var id : previous.selected)
                    if (draft.byId.containsKey(id)) draft.selected.add(id);
                draft.anchor =
                        previous.anchor != null && draft.byId.containsKey(previous.anchor)
                                ? previous.anchor
                                : null;
                draft.focused =
                        previous.focused != null && draft.byId.containsKey(previous.focused)
                                ? previous.focused
                                : null;
            }
            receivedRows.clear();
            receivedIds.clear();
            loading = false;
            pendingAction = "";
            error = Component.empty();
            updateVisible();
        } else sendPage();
    }

    private void appendRow(JsonObject value) {
        var row = PatchManagerModel.Row.fromJson(value);
        if (!receivedIds.add(row.id()))
            throw new IllegalArgumentException("Duplicate provider in generation");
        receivedRows.add(row);
    }

    private void apply() {
        cancelGesture();
        if (!canEdit() || !draft.dirty()) return;
        var policy = new JsonObject();
        var disabled = new JsonArray();
        draft.disabled.stream().sorted().forEach(disabled::add);
        var order = new JsonArray();
        draft.order.forEach(order::add);
        policy.add("disabled", disabled);
        policy.add("order", order);
        policy.addProperty("reorder", draft.reordered);
        policy.addProperty("reset_order", draft.resetOrder);
        uploadText = policy.toString();
        if (uploadText.length() > MAX_POLICY_CHARS) {
            error = tr("too_large");
            return;
        }
        mutating = true;
        uploadOffset = 0;
        upload = 0;
        error = Component.empty();
        var begin = fingerprints();
        begin.addProperty("length", uploadText.length());
        send("begin", begin);
    }

    private void sendPart() {
        if (uploadOffset == uploadText.length()) {
            var commit = new JsonObject();
            commit.addProperty("upload", upload);
            send("commit", commit);
            return;
        }
        sentPartEnd = Math.min(uploadText.length(), uploadOffset + PART_CHARS);
        var part = new JsonObject();
        part.addProperty("upload", upload);
        part.addProperty("offset", uploadOffset);
        part.addProperty("text", uploadText.substring(uploadOffset, sentPartEnd));
        send("part", part);
    }

    private void cancelUpload() {
        if (upload == 0
                || minecraft.getConnection() == null
                || connection != minecraft.getConnection()
                || level != minecraft.level) return;
        var json = new JsonObject();
        json.addProperty("upload", upload);
        PacketDistributor.sendToServer(
                new RuntimeSettingsNetworking.PolicyRequest(
                        "cancel", json.toString(), REQUEST_IDS.incrementAndGet(), session));
    }

    private void fail(Component reason) {
        cancelUpload();
        cancelGesture();
        awaitingReply = mutating = loading = false;
        pendingAction = "";
        uploadText = "";
        upload = 0;
        fragment = null;
        receivedRows.clear();
        receivedIds.clear();
        queuedAction = "";
        queuedBody = null;
        requestId = REQUEST_IDS.incrementAndGet();
        needsRefresh = true;
        error = reason;
        // A failed save must remain visible even if Escape requested a deferred close.
        closeAfterReply = false;
        updateButtons();
    }

    private void cancelGesture() {
        scrollBar.release();
        armedId = null;
        dragging = false;
        collapseOnRelease = false;
        setDragging(false);
    }

    private void modifiers(int key, int flags, boolean pressed) {
        modifierMask = flags & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_ALT);
        int bit =
                switch (key) {
                    case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL ->
                            GLFW.GLFW_MOD_CONTROL;
                    case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> GLFW.GLFW_MOD_SHIFT;
                    case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> GLFW.GLFW_MOD_ALT;
                    default -> 0;
                };
        if (pressed) modifierMask |= bit;
        else modifierMask &= ~bit;
    }

    private boolean control() {
        return (modifierMask & GLFW.GLFW_MOD_CONTROL) != 0 || hasControlDown();
    }

    private boolean shift() {
        return (modifierMask & GLFW.GLFW_MOD_SHIFT) != 0 || hasShiftDown();
    }

    @Override
    public boolean keyPressed(int key, int scan, int flags) {
        modifiers(key, flags, true);
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (armedId != null || dragging || scrollBar.dragging()) {
                cancelGesture();
                return true;
            }
            onClose();
            return true;
        }
        if (control() && key == GLFW.GLFW_KEY_F) {
            setFocused(search);
            return true;
        }
        if (getFocused() == search) return super.keyPressed(key, scan, flags);
        if (getFocused() == list && draft != null && !loading && !mutating) {
            if (control() && key == GLFW.GLFW_KEY_A) {
                selectAll();
                return true;
            }
            if (control() && key == GLFW.GLFW_KEY_D) {
                clearSelection();
                return true;
            }
            if (control() && key == GLFW.GLFW_KEY_C) {
                minecraft.keyboardHandler.setClipboard(String.join("\n", draft.selected));
                return true;
            }
            if (control()
                    && (key == GLFW.GLFW_KEY_UP
                            || key == GLFW.GLFW_KEY_DOWN
                            || key == GLFW.GLFW_KEY_HOME
                            || key == GLFW.GLFW_KEY_END)) {
                move(
                        key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_HOME ? -1 : 1,
                        key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END);
                return true;
            }
            if (key == GLFW.GLFW_KEY_SPACE) {
                boolean allEnabled =
                        draft.selected.stream()
                                .filter(visibleSet::contains)
                                .allMatch(id -> !draft.disabled.contains(id));
                enabled(!allEnabled);
                return true;
            }
            int current = Math.max(0, visibleIds.indexOf(draft.focused));
            int target =
                    switch (key) {
                        case GLFW.GLFW_KEY_UP -> Math.max(0, current - 1);
                        case GLFW.GLFW_KEY_DOWN -> current + 1;
                        case GLFW.GLFW_KEY_HOME -> 0;
                        case GLFW.GLFW_KEY_END -> visibleIds.size() - 1;
                        case GLFW.GLFW_KEY_PAGE_UP -> Math.max(0, current - visibleRows());
                        case GLFW.GLFW_KEY_PAGE_DOWN -> current + visibleRows();
                        default -> -1;
                    };
            if (target >= 0 && !visibleIds.isEmpty()) {
                draft.select(
                        visibleIds,
                        visibleIds.get(Math.clamp(target, 0, visibleIds.size() - 1)),
                        false,
                        shift());
                ensureFocusedVisible();
                updateButtons();
                return true;
            }
        }
        return super.keyPressed(key, scan, flags);
    }

    @Override
    public boolean keyReleased(int key, int scan, int flags) {
        modifiers(key, flags, false);
        return super.keyReleased(key, scan, flags);
    }

    private void ensureFocusedVisible() {
        int index = draft == null ? -1 : visibleIds.indexOf(draft.focused);
        if (index < 0) return;
        if (index < scroll) scroll = index;
        else if (index >= scroll + visibleRows()) scroll = index - visibleRows() + 1;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (list == null || !list.isMouseOver(x, y)) cancelGesture();
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (scrollBar.dragging() && button == 0) return scrollBar.drag(y);
        if (armedId != null && button == 0) return list.mouseDragged(x, y, button, dx, dy);
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        boolean track = scrollBar.dragging();
        scrollBar.release();
        boolean handled = track || list != null && list.mouseReleased(x, y, button);
        return super.mouseReleased(x, y, button) || handled;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (list != null && list.isMouseOver(x, y)) {
            scroll =
                    Math.clamp(
                            scroll - (int) Math.signum(vertical) * 3,
                            0,
                            Math.max(0, visibleIds.size() - visibleRows()));
            if (dragging) updateDrop(y);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private void updateDrop(double y) {
        int next =
                Math.clamp(
                        scroll
                                + (int)
                                        Math.floor(
                                                (y - list.getY() + ROW_HEIGHT / 2.0) / ROW_HEIGHT),
                        0,
                        draft.order.size());
        if (next != dropIndex || !dragging) {
            dropIndex = next;
            dropError = draft.validateDrop(dropIndex);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (connection != minecraft.getConnection() || level != minecraft.level) {
            cancelGesture();
            modifierMask = 0;
            awaitingReply = mutating = loading = false;
            upload = 0;
            uploadText = "";
            data = null;
            draft = null;
            queuedAction = "";
            queuedBody = null;
            nextSendAt = 0;
            receivedRows.clear();
            receivedIds.clear();
            fragment = null;
            session = REQUEST_IDS.incrementAndGet();
            requestId = REQUEST_IDS.incrementAndGet();
            connection = minecraft.getConnection();
            level = minecraft.level;
            needsRefresh = false;
            closeAfterReply = false;
            updateVisible();
            if (connection != null) refresh();
        }
        if (!minecraft.isWindowActive() || minecraft.getOverlay() != null) {
            cancelGesture();
            modifierMask = 0;
        }
        if (awaitingReply
                && (connection == null
                        || net.minecraft.Util.getMillis() - requestStarted >= TIMEOUT))
            fail(tr("timeout"));
        if (!queuedAction.isEmpty()
                && !awaitingReply
                && net.minecraft.Util.getMillis() >= nextSendAt) {
            String action = queuedAction;
            var body = queuedBody;
            queuedAction = "";
            queuedBody = null;
            send(action, body);
        }
        if (dragging && draft != null) {
            int step =
                    lastMouseY < list.getY() + 12
                            ? -1
                            : lastMouseY >= list.getY() + list.getHeight() - 12 ? 1 : 0;
            if (lastMouseX >= list.getX()
                    && lastMouseX < list.getX() + list.getWidth()
                    && step != 0) {
                scroll =
                        Math.clamp(
                                scroll + step, 0, Math.max(0, visibleIds.size() - visibleRows()));
                updateDrop(lastMouseY);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return !awaitingReply && !mutating && !loading && queuedAction.isEmpty();
    }

    @Override
    public void onClose() {
        cancelGesture();
        if (mutating) {
            closeAfterReply = true;
            return;
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        cancelGesture();
        modifierMask = 0;
        cancelUpload();
        attached = false;
        closeAfterReply = false;
        if (RuntimeSettingsNetworking.policyReceiver == installedReceiver)
            RuntimeSettingsNetworking.policyReceiver = value -> {};
        awaitingReply = mutating = loading = false;
        session = REQUEST_IDS.incrementAndGet();
        requestId = REQUEST_IDS.incrementAndGet();
        uploadText = "";
        upload = 0;
        receivedRows.clear();
        receivedIds.clear();
        fragment = null;
        data = null;
        draft = null;
        visibleIds = List.of();
        visibleSet = Set.of();
        queuedAction = "";
        queuedBody = null;
        setFocused(null);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        if (!minecraft.isWindowActive() || minecraft.getOverlay() != null) {
            cancelGesture();
            modifierMask = 0;
        }
        super.render(g, mx, my, partial);
        g.drawCenteredString(font, title, width / 2, 9, 0xffffff);
        var hint =
                !editable() && data != null
                        ? tr("read_only")
                        : data != null && data.get("pending_restart").getAsBoolean()
                                ? tr("pending_restart")
                                : Component.empty();
        drawLine(g, hint, 26, 0xffd3dce5, mx, my);
        Component status =
                !error.getString().isEmpty()
                        ? error
                        : mutating || awaitingReply && !loading
                                ? tr("waiting")
                                : loading
                                        ? tr(
                                                "loading_rows",
                                                receivedRows.size(),
                                                Math.max(0, total))
                                        : draft != null && draft.dirty()
                                                ? tr("changed")
                                                : tr("unchanged");
        status =
                status.copy()
                        .append(" | ")
                        .append(
                                !query.isBlank()
                                        ? tr("clear_search_to_reorder")
                                        : tr("selection", visibleSelected));
        drawLine(
                g,
                status,
                height - 40,
                error.getString().isEmpty() ? 0xffd3dce5 : 0xffffba8a,
                mx,
                my);
    }

    private void drawLine(GuiGraphics g, Component text, int y, int color, int mx, int my) {
        g.drawCenteredString(
                font,
                Component.literal(
                        font.plainSubstrByWidth(text.getString(), Math.max(1, span() - 4))),
                width / 2,
                y,
                color);
        if (my >= y - 1 && my < y + font.lineHeight + 1 && mx >= left() && mx < left() + span())
            setTooltipForNextRenderPass(text);
    }

    private final class ProviderList extends AbstractWidget {
        ProviderList(int x, int y, int width, int height) {
            super(x, y, width, height, tr("title"));
        }

        private final java.util.Map<String, Button[]> controls = new java.util.HashMap<>();

        private void track() {
            scrollBar.configure(
                    getX() + width - 7,
                    getY(),
                    height,
                    visibleRows(),
                    visibleIds.size(),
                    scroll,
                    v -> {
                        scroll = v;
                        tooltipId = null;
                    });
        }

        private Button[] rowControls(String id, int y) {
            var buttons =
                    controls.computeIfAbsent(
                            id,
                            key ->
                                    new Button[] {
                                        new Button(
                                                0,
                                                0,
                                                18,
                                                20,
                                                Component.empty(),
                                                v ->
                                                        result(
                                                                draft.enabled(
                                                                        draft.disabled.contains(id),
                                                                        draft.selected.contains(id)
                                                                                ? Set.copyOf(
                                                                                        draft.selected)
                                                                                : Set.of(id))),
                                                message -> message.get()) {
                                            @Override
                                            protected void renderWidget(
                                                    GuiGraphics g, int mx, int my, float partial) {
                                                int color = active ? 0xffeeeeee : 0xffaaaaaa;
                                                int x = getX() + 4, y = getY() + 5;
                                                if (draft.disabled.contains(id))
                                                    g.renderOutline(x, y, 9, 9, color);
                                                else g.fill(x, y, x + 9, y + 9, color);
                                            }
                                        },
                                        Button.builder(Component.literal("↑"), v -> rowMove(id, -1))
                                                .bounds(0, 0, 18, 20)
                                                .build(),
                                        Button.builder(Component.literal("↓"), v -> rowMove(id, 1))
                                                .bounds(0, 0, 18, 20)
                                                .build()
                                    });
            int[] xs = {getX() + 3, getX() + width - 50, getX() + width - 30};
            var row = draft.byId.get(id);
            for (int i = 0; i < 3; i++) {
                buttons[i].setPosition(xs[i], y + 5);
                buttons[i].active = canEdit() && row.present() && (i == 0 || canReorder());
            }
            buttons[0].setMessage(tr(draft.disabled.contains(id) ? "enable" : "disable"));
            buttons[0].setTooltip(
                    Tooltip.create(tr(draft.disabled.contains(id) ? "enable" : "disable")));
            buttons[1].setTooltip(
                    Tooltip.create(tr(query.isBlank() ? "up" : "clear_search_to_reorder")));
            buttons[2].setTooltip(
                    Tooltip.create(tr(query.isBlank() ? "down" : "clear_search_to_reorder")));
            return buttons;
        }

        private void rowMove(String id, int direction) {
            if (!canReorder()) {
                result("npships.patch_manager.clear_search_to_reorder");
                return;
            }
            result(draft.moveOne(id, direction));
        }

        private int rowAt(double y) {
            int index = scroll + (int) Math.floor((y - getY()) / ROW_HEIGHT);
            return y >= getY()
                            && y < getY() + visibleRows() * ROW_HEIGHT
                            && index >= scroll
                            && index < Math.min(visibleIds.size(), scroll + visibleRows())
                    ? index
                    : -1;
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            if (!isMouseOver(x, y) || draft == null || loading || mutating) return false;
            track();
            if (scrollBar.click(x, y, button)) {
                armedId = null;
                dragging = false;
                setFocused(true);
                PatchManagerScreen.this.setFocused(this);
                return true;
            }
            if (x >= getX() + width - 9) return true;
            if (button == 1) return true;
            if (button != 0) return false;
            int index = rowAt(y);
            if (index < 0) return true;
            String id = visibleIds.get(index);
            for (var control : rowControls(id, getY() + (index - scroll) * ROW_HEIGHT))
                if (control.isMouseOver(x, y)) {
                    control.mouseClicked(x, y, button);
                    return true;
                }
            boolean ctrl = control(), range = shift();
            collapseOnRelease =
                    canEdit()
                            && !ctrl
                            && !range
                            && draft.selected.contains(id)
                            && draft.selected.size() > 1;
            if (!collapseOnRelease) draft.select(visibleIds, id, ctrl, range);
            draft.focused = id;
            setFocused(true);
            PatchManagerScreen.this.setFocused(this);
            armedId = canEdit() && draft.selected.contains(id) ? id : null;
            pressX = lastMouseX = x;
            pressY = lastMouseY = y;
            dragging = false;
            dropIndex = -1;
            updateButtons();
            return true;
        }

        @Override
        public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
            if (button != 0 || armedId == null) return false;
            lastMouseX = x;
            lastMouseY = y;
            if (!dragging && Math.hypot(x - pressX, y - pressY) < 4) return true;
            if (!canReorder()) {
                error = tr("clear_search_to_reorder");
                cancelGesture();
                return true;
            }
            dragging = true;
            collapseOnRelease = false;
            updateDrop(y);
            return true;
        }

        @Override
        public boolean mouseReleased(double x, double y, int button) {
            if (button != 0 || armedId == null) return false;
            if (dragging && canReorder()) result(draft.drop(dropIndex));
            else if (collapseOnRelease) draft.select(visibleIds, armedId, false, false);
            cancelGesture();
            updateButtons();
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            g.fill(getX(), getY(), getX() + width, getY() + height, 0x99000000);
            g.enableScissor(getX(), getY(), getX() + width, getY() + height);
            int end = Math.min(visibleIds.size(), scroll + visibleRows());
            for (int i = scroll; i < end; i++) {
                String id = visibleIds.get(i);
                var row = draft.byId.get(id);
                int y = getY() + (i - scroll) * ROW_HEIGHT;
                boolean selected = draft.selected.contains(id),
                        hover =
                                mx >= getX()
                                        && mx < getX() + width
                                        && my >= y
                                        && my < y + ROW_HEIGHT
                                        && isMouseOver(mx, my);
                g.fill(
                        getX() + 1,
                        y + 1,
                        getX() + width - 1,
                        y + ROW_HEIGHT - 1,
                        selected ? 0xff303030 : hover ? 0x503f3f3f : 0x40000000);
                for (var control : rowControls(id, y)) control.render(g, mx, my, partial);
                g.drawString(
                        font,
                        font.plainSubstrByWidth(name(row).getString(), Math.max(1, width - 78)),
                        getX() + 23,
                        y + 4,
                        row.present() ? 0xffffffff : 0xffa0a0a0,
                        false);
                Component source = Component.literal(row.ownerDisplayName());
                g.drawString(
                        font,
                        font.plainSubstrByWidth(source.getString(), Math.max(1, width - 78)),
                        getX() + 23,
                        y + 17,
                        0xffa0a0a0,
                        false);
                // Native settings-list contrast, independent of the editor palette.
                if (selected || isFocused() && id.equals(draft.focused))
                    g.renderOutline(
                            getX() + 1,
                            y + 1,
                            width - 2,
                            ROW_HEIGHT - 2,
                            isFocused() && id.equals(draft.focused) ? 0xffffffff : 0xff808080);
                if (hover && !dragging) {
                    if (mx < getX() + 22)
                        setTooltipForNextRenderPass(
                                tr(draft.disabled.contains(id) ? "enable" : "disable"));
                    else if (mx >= getX() + width - 50 && mx < getX() + width - 10)
                        setTooltipForNextRenderPass(
                                tr(
                                        query.isBlank()
                                                ? (mx < getX() + width - 31 ? "up" : "down")
                                                : "clear_search_to_reorder"));
                    else if (mx < getX() + width - 9) tooltip(row);
                }
            }
            if (dragging && draft != null) {
                int y = getY() + (dropIndex - scroll) * ROW_HEIGHT;
                g.fill(
                        getX() + 1,
                        Math.clamp(y - 1, getY(), getY() + height - 2),
                        getX() + width - 1,
                        Math.clamp(y + 1, getY() + 2, getY() + height),
                        dropError.isEmpty() ? 0xff8ce7bd : 0xffff897e);
            }
            if (visibleIds.isEmpty())
                g.drawCenteredString(
                        font,
                        loading ? tr("loading") : tr("empty"),
                        getX() + width / 2,
                        getY() + 8,
                        0xffc4cfda);
            g.disableScissor();
            track();
            scrollBar.render(g, 0xff101010, 0xffb5b5b5, 0xff777777);
        }

        private void tooltip(PatchManagerModel.Row row) {
            if (!row.id().equals(tooltipId)) {
                var text = name(row).copy().append("\n").append(tr("mod", row.ownerDisplayName()));
                if (row.metadata() != null)
                    text.append("\n")
                            .append(
                                    display(
                                            row.metadata().descriptionKey(),
                                            row.metadata().description()));
                text.append("\n")
                        .append(
                                Component.literal(row.id())
                                        .withStyle(net.minecraft.ChatFormatting.GRAY));
                // Cache only this hovered row. Native tooltip coordinates remain in screen space.
                var lines =
                        font.split(text, Math.clamp(PatchManagerScreen.this.width - 24, 100, 420));
                int maximum = Math.max(1, (PatchManagerScreen.this.height - 24) / font.lineHeight);
                if (lines.size() > maximum) {
                    var shown =
                            new ArrayList<FormattedCharSequence>(
                                    lines.subList(0, Math.max(0, maximum - 1)));
                    shown.add(Component.literal("…").getVisualOrderText());
                    rowTooltip = List.copyOf(shown);
                } else rowTooltip = List.copyOf(lines);
                tooltipId = row.id();
            }
            setTooltipForNextRenderPass(rowTooltip);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE, tr("title"));
            if (draft != null && draft.focused != null)
                out.add(NarratedElementType.POSITION, Component.literal(draft.focused));
            out.add(NarratedElementType.USAGE, tr("drag_hint"));
        }
    }
}
