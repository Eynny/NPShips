package com.eynny.npships.client.editor;

import com.eynny.npships.api.client.editor.EditorExtension;
import com.eynny.npships.api.client.editor.EditorExtension.Bounds;
import com.eynny.npships.api.client.editor.EditorExtension.Camera;
import com.eynny.npships.api.client.editor.EditorExtension.Change;
import com.eynny.npships.api.client.editor.EditorExtension.Input;
import com.eynny.npships.api.client.editor.EditorExtension.Key;
import com.eynny.npships.api.client.editor.EditorExtension.Mouse;
import com.eynny.npships.api.client.editor.EditorExtension.MouseAction;
import com.eynny.npships.api.client.editor.EditorExtension.Panel;
import com.eynny.npships.api.client.editor.EditorExtension.Preview;
import com.eynny.npships.api.client.editor.EditorExtension.Ray;
import com.eynny.npships.api.client.editor.EditorExtension.Result;
import com.eynny.npships.api.client.editor.EditorExtension.Selection;
import com.eynny.npships.api.client.editor.EditorExtension.Session;
import com.eynny.npships.api.client.editor.EditorExtension.Status;
import com.eynny.npships.api.client.editor.EditorExtension.Text;
import com.eynny.npships.api.client.editor.EditorExtensions;
import com.eynny.npships.api.client.editor.EditorSceneFactory;
import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicExtensions;
import com.eynny.npships.client.editor.render.PreviewRendererScope;
import com.eynny.npships.client.editor.render.PreviewResources;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** One screen activation owns all addon UI, input and native buffers; no history or tick owner. */
final class EditorExtensionHost implements AutoCloseable {
    private SchematicEditorScreen host;
    private final Thread owner = Thread.currentThread();
    private final List<Entry> entries = new ArrayList<>();
    private List<Entry> scenePreviews = List.of();
    private SchematicDocument sceneSource;
    private SchematicDocument sceneDataSource;
    private List<PatchAuthoring.Data> sceneData;
    private final boolean interactive;
    private final com.eynny.npships.api.patches.PatchPolicy policy =
            com.eynny.npships.config.NPShipsRuntimeConfig.patchPolicy();
    private boolean sceneDirty = true, sceneDrawing;
    private final List<Button> choices = new ArrayList<>();
    private UUID revision = UUID.randomUUID();
    private Entry selected;
    private Button selector;
    private boolean open, menu, pointer, busy, closing;
    private int pointerButton = -1;
    private int readOnly, menuScroll, menuIndex;
    private long epoch = PreviewResources.epoch();

    EditorExtensionHost(SchematicEditorScreen host) {
        this(host, true);
    }

    EditorExtensionHost(SchematicEditorScreen host, boolean interactive) {
        this.host = host;
        this.interactive = interactive;
    }

    void init() {
        if (open) {
            relayout();
            return;
        }
        open = true;
        if (interactive)
            for (var definition : EditorExtensions.registered()) entries.add(new Entry(definition));
        for (var definition :
                com.eynny.npships.api.patches.PatchTypes.capabilities(EditorSceneFactory.class)
                        .entrySet())
            if (policy.enabled(definition.getKey()))
                entries.add(
                        new Entry(
                                new EditorExtensions.Definition(
                                        definition.getKey(),
                                        definition.getKey(),
                                        Component.literal(definition.getKey().toString()),
                                        definition.getValue()::create),
                                true));
        if (entries.stream().anyMatch(e -> !e.scene)) {
            selector =
                    Button.builder(Component.literal("+"), b -> toggleMenu())
                            .bounds(0, 0, 20, 20)
                            .build();
            selector.setTooltip(Tooltip.create(Component.translatable("npships.extensions.tabs")));
        }
        relayout();
    }

    private void toggleMenu() {
        if (menu) menu = false;
        else {
            cancelInteraction();
            menu = true;
        }
    }

    boolean available() {
        return open && selector != null;
    }

    boolean panelActive() {
        return open && selected != null;
    }

    boolean toolActive() {
        return panelActive() && selected.tool && !selected.failed;
    }

    boolean modal() {
        return open && (menu || selected != null && selected.modal);
    }

    boolean keyboardFocused() {
        return panelActive() && selected.root.getFocused() != null;
    }

    private boolean active() {
        return open
                && host != null
                && Thread.currentThread() == owner
                && (Minecraft.getInstance().screen == host || sceneDrawing);
    }

    private void requireActive() {
        if (!active())
            throw new IllegalStateException(
                    "Editor extension session is closed or not current on its CLIENT thread");
    }

    private Bounds tab() {
        var b = host.extensionTabBounds();
        return new Bounds(
                b.x() + Math.max(0, b.width() - 20),
                b.y(),
                Math.min(20, b.width()),
                Math.min(20, b.height()));
    }

    void relayout() {
        if (!available()) return;
        var tab = tab();
        selector.setX(tab.x());
        selector.setY(tab.y());
        selector.setWidth(tab.width());
        int retainedMenuScroll = menuScroll;
        menuScroll = 0;
        choices.clear();
        var b = host.extensionPanelBounds();
        int y = b.y();
        choices.add(
                Button.builder(
                                Component.translatable("npships.extensions.native"),
                                button -> deactivate())
                        .bounds(b.x(), y, Math.max(1, b.width()), 20)
                        .build());
        y += 22;
        for (var e : entries) {
            if (e.scene) continue;
            choices.add(
                    Button.builder(e.definition.title(), button -> select(e))
                            .bounds(b.x(), y, Math.max(1, b.width()), 20)
                            .build());
            y += 22;
        }
        setMenuScroll(retainedMenuScroll);
        if (selected != null && !selected.failed) layout(selected);
    }

    private Bounds content() {
        var b = host.extensionPanelBounds();
        return new Bounds(b.x(), b.y() + 24, b.width(), Math.max(0, b.height() - 24));
    }

    private void layout(Entry e) {
        e.callbacks++;
        readOnly++;
        try {
            int retainedScroll = e.scroll;
            setScroll(e, 0);
            var previous = e.root.getFocused();
            var old = new ArrayList<>(e.root.widgets);
            e.root.widgets.clear();
            var window = new PanelWindow(e);
            try {
                observe(e, () -> e.extension.layout(e.session, window));
            } finally {
                window.live = false;
            }
            if (e.retired || !active()) return;
            if (previous != null && e.root.widgets.contains(previous)) e.root.setFocused(previous);
            else {
                if (previous != null) previous.setFocused(false);
                e.root.setFocused(null);
            }
            for (var widget : old) if (!e.root.widgets.contains(widget)) widget.setFocused(false);
            setScroll(e, retainedScroll);

        } finally {
            readOnly--;
            leave(e);
        }
    }

    /** Widgets keep native screen coordinates for hit testing, focus and tooltip placement. */
    private void setScroll(Entry e, long requested) {
        int next =
                (int) Math.clamp(requested, 0, Math.max(0, e.contentHeight - content().height()));
        int delta = e.scroll - next;
        if (delta == 0) return;
        for (var widget : e.root.widgets) widget.setY(Math.addExact(widget.getY(), delta));
        e.scroll = next;
    }

    private void reveal(Entry e, AbstractWidget widget) {
        var b = content();
        long top = widget.getY(), bottom = top + widget.getHeight();
        if (top < b.y()) setScroll(e, (long) e.scroll + top - b.y());
        else if (bottom > (long) b.y() + b.height())
            setScroll(e, (long) e.scroll + bottom - b.y() - b.height());
    }

    private void setMenuScroll(long requested) {
        int next =
                (int)
                        Math.clamp(
                                requested,
                                0,
                                Math.max(
                                        0,
                                        choices.size() * 22
                                                - host.extensionPanelBounds().height()));
        int delta = menuScroll - next;
        if (delta == 0) return;
        for (var button : choices) button.setY(Math.addExact(button.getY(), delta));
        menuScroll = next;
    }

    private void revealMenu(int index) {
        var b = host.extensionPanelBounds();
        var button = choices.get(index);
        long top = button.getY(), bottom = top + button.getHeight();
        if (top < b.y()) setMenuScroll((long) menuScroll + top - b.y());
        else if (bottom > (long) b.y() + b.height())
            setMenuScroll((long) menuScroll + bottom - b.y() - b.height());
    }

    private void select(Entry e) {
        menu = false;
        if (!active() || selected == e) return;
        if (!host.extensionActivate() || !active()) return;
        deactivate();
        if (!active()) return;
        selected = e;
        if (e.extension == null && !e.failed) {
            try {
                create(e);
            } catch (Exception | LinkageError error) {
                failure(e, error);
            }
        }
        if (!e.failed && !e.retired && active()) {
            observe(e, () -> e.extension.selected(e.session, true));
            layout(e);
        }
    }

    /** One policy, fresh-owner check and reentrant lifetime for tabs and authored scene owners. */
    private void create(Entry e) {
        e.callbacks++;
        readOnly++;
        try {
            com.eynny.npships.authoring.patches.PatchCode.validate(
                    policy, Set.of(e.definition.provider()));
            if (!policy.enabled(e.definition.provider()))
                throw new IllegalArgumentException(
                        "Editor provider is disabled: " + e.definition.provider());
            e.extension =
                    Objects.requireNonNull(
                            e.definition.factory().get(), "Null editor contribution");
            if (e.retired || !active()) return;
            for (var other : entries)
                if (other != e && other.extension == e.extension) {
                    e.extension = null;
                    throw new IllegalArgumentException("Editor factories must return fresh owners");
                }
            if (!e.scene) e.previewEnabled = e.extension.hasPreview();
            if (!e.failed && !e.retired && active())
                observe(e, () -> e.extension.opened(e.session));
        } finally {
            readOnly--;
            leave(e);
        }
    }

    void deactivate() {
        menu = false;
        clearPointer();
        var e = selected;
        if (e == null) return;
        e.tool = false;
        e.modal = false;
        e.root.setFocused(null);
        if (!e.failed && e.extension != null) {
            observe(e, () -> e.extension.cancel(e.session));
            observe(e, () -> e.extension.selected(e.session, false));
        }
        selected = null;
    }

    private void clearPointer() {
        pointer = false;
        pointerButton = -1;
        if (selected != null) selected.root.setDragging(false);
    }

    void documentChanged() {
        if (!open) return;
        revision = UUID.randomUUID();
        sceneDirty = true;
        for (var e : List.copyOf(entries))
            if (!e.scene && open && !e.retired && e.extension != null && !e.failed)
                observe(e, () -> e.extension.changed(e.session, Change.DOCUMENT));
    }

    void cancelInteraction() {
        var e = selected;
        if (e == null) return;
        boolean owned = e.tool || e.modal || pointer || e.root.getFocused() != null;
        e.tool = false;
        e.modal = false;
        clearPointer();
        e.root.setFocused(null);
        if (owned && !e.failed) observe(e, () -> e.extension.cancel(e.session));
    }

    void selectionChanged() {
        if (selected != null && !selected.failed)
            observe(selected, () -> selected.extension.changed(selected.session, Change.SELECTION));
    }

    void resourcesChanged(long generation) {
        if (!open || epoch == generation) return;
        epoch = generation;
        sceneDirty = true;
        for (var e : List.copyOf(entries))
            if (open && !e.retired && e.extension != null) {
                try {
                    e.scope.close();
                } catch (RuntimeException failure) {
                    failure(e, failure);
                }
                if (!e.failed && !e.scene)
                    observe(e, () -> e.extension.resourcesChanged(e.session, generation));
            }
    }

    void renderPanel(GuiGraphics graphics, int mx, int my, float partial) {
        if (!available()) return;
        selector.render(graphics, mx, my, partial);
        var bounds = host.extensionPanelBounds();
        if (selected != null) {
            graphics.fill(
                    bounds.x(),
                    bounds.y(),
                    bounds.x() + bounds.width(),
                    bounds.y() + bounds.height(),
                    EditorButtonTheme.panel());
            var font = Minecraft.getInstance().font;
            if (bounds.height() >= font.lineHeight + 5)
                graphics.drawString(
                        font,
                        font.plainSubstrByWidth(
                                selected.definition.title().getString(),
                                Math.max(0, bounds.width() - 4)),
                        bounds.x() + 2,
                        bounds.y() + 5,
                        EditorButtonTheme.text());
            if (!selected.failed && content().height() > 0)
                renderWidgets(selected, graphics, mx, my, partial);
        }
        if (menu) {
            graphics.enableScissor(
                    bounds.x(),
                    bounds.y(),
                    bounds.x() + bounds.width(),
                    bounds.y() + bounds.height());
            graphics.fill(
                    bounds.x(),
                    bounds.y(),
                    bounds.x() + bounds.width(),
                    bounds.y() + bounds.height(),
                    EditorButtonTheme.panel());
            for (int i = 0; i < choices.size(); i++) {
                var button = choices.get(i);
                button.setFocused(i == menuIndex);
                button.render(graphics, mx, my, partial);
            }
            graphics.disableScissor();
        }
    }

    private void renderWidgets(Entry e, GuiGraphics destination, int mx, int my, float partial) {
        e.callbacks++;
        try {
            var b = content();
            try {
                e.scope.renderIsolated(
                        destination.pose(),
                        destination.bufferSource(),
                        (pose, buffers) -> {
                            var g =
                                    new GuiGraphics(
                                            Minecraft.getInstance(),
                                            (MultiBufferSource.BufferSource) buffers);
                            g.pose().last().pose().set(pose.last().pose());
                            g.pose().last().normal().set(pose.last().normal());
                            g.enableScissor(b.x(), b.y(), b.x() + b.width(), b.y() + b.height());
                            readOnly++;
                            try {
                                for (var widget : List.copyOf(e.root.widgets))
                                    if (widget.visible)
                                        widget.render(
                                                g, mx, b.contains(mx, my) ? my : -100000, partial);
                                g.flush();
                            } finally {
                                readOnly--;
                            }
                        });
            } catch (Exception | LinkageError error) {
                if (error instanceof PreviewRendererScope.RecoveryFailure recovery) throw recovery;
                failure(e, error);
            }

        } finally {
            leave(e);
        }
    }

    boolean mouse(
            MouseAction action,
            double x,
            double y,
            int button,
            double dx,
            double dy,
            double scroll) {
        var eventOwner = selected;
        if (eventOwner != null) eventOwner.callbacks++;
        try {
            try {
                try {
                    return mouseInput(action, x, y, button, dx, dy, scroll);
                } finally {
                    refreshAfterInput();
                }
            } catch (Exception | LinkageError error) {
                if (selected != null) failure(selected, error);
                return true;
            }

        } finally {
            if (eventOwner != null) leave(eventOwner);
        }
    }

    private boolean mouseInput(
            MouseAction action,
            double x,
            double y,
            int button,
            double dx,
            double dy,
            double scroll) {
        if (!available()) return false;
        if (action == MouseAction.PRESS && selector.isMouseOver(x, y))
            return selector.mouseClicked(x, y, button);
        if (menu) {
            if (action == MouseAction.SCROLL) {
                setMenuScroll(menuScroll - Math.round(scroll * 22));
                return true;
            }
            if (action == MouseAction.PRESS) {
                var b = host.extensionPanelBounds();
                if (b.contains(x, y))
                    for (var choice : List.copyOf(choices))
                        if (choice.mouseClicked(x, y, button)) return true;
                menu = false;
            }
            return true;
        }
        var e = selected;
        if (e == null || e.failed) return false;
        if (action == MouseAction.RELEASE && pointer && button == pointerButton) {
            owned(e, () -> e.root.mouseReleased(x, y, button));
            deliver(e, new Mouse(action, x, y, button, modifiers(), dx, dy, scroll, ray(x, y)));
            clearPointer();
            return true;
        }
        var b = content();
        if (action == MouseAction.DRAG && pointer) {
            if (!owned(e, () -> e.root.mouseDragged(x, y, button, dx, dy)))
                deliver(e, new Mouse(action, x, y, button, modifiers(), dx, dy, scroll, ray(x, y)));
            return true;
        }
        if (b.contains(x, y)) {
            boolean handled =
                    owned(
                            e,
                            () ->
                                    switch (action) {
                                        case PRESS -> e.root.mouseClicked(x, y, button);
                                        case DRAG -> e.root.mouseDragged(x, y, button, dx, dy);
                                        case RELEASE ->
                                                owned(e, () -> e.root.mouseReleased(x, y, button));
                                        case SCROLL -> e.root.mouseScrolled(x, y, 0, scroll);
                                    });
            if (!handled && action == MouseAction.SCROLL)
                setScroll(e, e.scroll - Math.round(scroll * 18));
            if (!handled)
                deliver(e, new Mouse(action, x, y, button, modifiers(), dx, dy, scroll, null));
            if (action == MouseAction.PRESS) {
                pointer = true;
                pointerButton = button;
            }
            return true;
        }
        if (action == MouseAction.PRESS) e.root.setFocused(null);
        if (e.modal) {
            deliver(e, new Mouse(action, x, y, button, modifiers(), dx, dy, scroll, null));
            return true;
        }
        if (host.extensionViewportBounds().contains(x, y)) {
            boolean handled =
                    deliver(
                            e,
                            new Mouse(
                                    action, x, y, button, modifiers(), dx, dy, scroll, ray(x, y)));
            if (action == MouseAction.PRESS && (handled || e.tool && button == 0)) {
                pointer = true;
                pointerButton = button;
            }
            return handled || e.tool && button == 0;
        }
        return false;
    }

    private void refreshAfterInput() {
        var e = selected;
        if (e != null && e.refresh && !e.failed && active()) {
            e.refresh = false;
            layout(e);
        }
    }

    private Ray ray(double x, double y) {
        return host.extensionViewportBounds().contains(x, y) ? host.extensionRay(x, y) : null;
    }

    private static int modifiers() {
        return (Screen.hasControlDown() ? GLFW.GLFW_MOD_CONTROL : 0)
                | (Screen.hasShiftDown() ? GLFW.GLFW_MOD_SHIFT : 0)
                | (Screen.hasAltDown() ? GLFW.GLFW_MOD_ALT : 0);
    }

    boolean key(int key, int scan, int modifiers, boolean pressed) {
        var eventOwner = selected;
        if (eventOwner != null) eventOwner.callbacks++;
        try {
            try {
                try {
                    return keyInput(key, scan, modifiers, pressed);
                } finally {
                    refreshAfterInput();
                }
            } catch (Exception | LinkageError error) {
                if (selected != null) failure(selected, error);
                return true;
            }

        } finally {
            if (eventOwner != null) leave(eventOwner);
        }
    }

    private boolean keyInput(int key, int scan, int modifiers, boolean pressed) {
        if (!available()) return false;
        if (pressed
                && key == GLFW.GLFW_KEY_E
                && (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT))
                        == (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) {
            toggleMenu();
            return true;
        }
        if (menu) {
            if (pressed && key == GLFW.GLFW_KEY_ESCAPE) menu = false;
            else if (pressed && (key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END)) {
                menuIndex = key == GLFW.GLFW_KEY_HOME ? 0 : choices.size() - 1;
                setMenuScroll(
                        menuIndex == 0
                                ? 0
                                : Math.max(
                                        0,
                                        choices.size() * 22
                                                - host.extensionPanelBounds().height()));
            } else if (pressed
                    && (key == GLFW.GLFW_KEY_DOWN
                            || key == GLFW.GLFW_KEY_UP
                            || key == GLFW.GLFW_KEY_TAB)) {
                int step =
                        key == GLFW.GLFW_KEY_UP
                                        || key == GLFW.GLFW_KEY_TAB
                                                && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0
                                ? -1
                                : 1;
                menuIndex = Math.floorMod(menuIndex + step, choices.size());
                revealMenu(menuIndex);
            } else if (pressed && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE))
                choices.get(menuIndex).onPress();
            return true;
        }
        var e = selected;
        if (e == null || e.failed) return false;
        if (pressed && key == GLFW.GLFW_KEY_ESCAPE) {
            boolean owned = e.tool || e.modal || pointer || e.root.getFocused() != null;
            e.tool = false;
            e.modal = false;
            clearPointer();
            e.root.setFocused(null);
            observe(e, () -> e.extension.cancel(e.session));
            return owned;
        }
        if (pressed && key == GLFW.GLFW_KEY_TAB) {
            var widgets = e.root.widgets.stream().filter(w -> w.visible && w.active).toList();
            if (!widgets.isEmpty()) {
                int at = widgets.indexOf(e.root.getFocused());
                int next =
                        Math.floorMod(
                                at + ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1),
                                widgets.size());
                var focused = widgets.get(next);
                e.root.setFocused(focused);
                reveal(e, focused);
                return true;
            }
        }
        if (e.root.getFocused() != null) {
            boolean handled =
                    owned(
                            e,
                            () ->
                                    pressed
                                            ? e.root.keyPressed(key, scan, modifiers)
                                            : e.root.keyReleased(key, scan, modifiers));
            if (pressed
                    && !handled
                    && key == GLFW.GLFW_KEY_S
                    && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) return false;
            return true;
        }
        return deliver(e, new Key(key, scan, modifiers, pressed)) || e.modal;
    }

    boolean text(char character, int modifiers) {
        var eventOwner = selected;
        if (eventOwner != null) eventOwner.callbacks++;
        try {
            try {
                try {
                    return textInput(character, modifiers);
                } finally {
                    refreshAfterInput();
                }
            } catch (Exception | LinkageError error) {
                if (selected != null) failure(selected, error);
                return true;
            }

        } finally {
            if (eventOwner != null) leave(eventOwner);
        }
    }

    private boolean textInput(char character, int modifiers) {
        if (menu) return true;
        var e = selected;
        if (e == null || e.failed) return false;
        if (e.root.getFocused() != null) {
            owned(e, () -> e.root.charTyped(character, modifiers));
            return true;
        }
        return deliver(e, new Text(character, modifiers)) || e.modal;
    }

    private boolean deliver(Entry e, Input input) {
        if (e.failed || e.retired || e.extension == null || !open) return false;
        boolean[] result = {false};
        invoke(e, () -> result[0] = e.extension.input(e.session, input));
        return result[0];
    }

    void preview(
            PoseStack pose,
            MultiBufferSource.BufferSource destination,
            Matrix4f camera,
            boolean ghost) {
        var e = selected;
        if (e == null || e.failed || !e.previewEnabled || !active()) return;
        preview(e, pose, destination, camera, ghost);
    }

    private void preview(
            Entry e,
            PoseStack pose,
            MultiBufferSource.BufferSource destination,
            Matrix4f camera,
            boolean ghost) {
        e.callbacks++;
        try {
            e.scope.renderIsolated(
                    pose,
                    destination,
                    (detached, buffers) -> {
                        var window =
                                new PreviewWindow(
                                        detached,
                                        buffers,
                                        new Camera(camera, host.extensionViewportBounds()),
                                        ghost);
                        try {
                            readOnly++;
                            e.extension.preview(e.session, window);
                        } finally {
                            readOnly--;
                            window.live = false;
                        }
                    });
        } catch (Exception | LinkageError error) {
            if (error instanceof PreviewRendererScope.RecoveryFailure recovery) throw recovery;
            failure(e, error);
        } finally {
            leave(e);
        }
    }

    /**
     * Authored scene visuals share this owner and render in ordinary/photo views, without input.
     */
    void scene(
            PoseStack pose,
            MultiBufferSource.BufferSource destination,
            Matrix4f camera,
            boolean ghost) {
        if (!open || host == null || Thread.currentThread() != owner) return;
        if (sceneDrawing) throw new IllegalStateException("Reentrant editor scene drawing");
        sceneDrawing = true;
        try {
            if (sceneDirty || sceneSource != host.extensionDocument()) refreshScenes();
            for (var e : scenePreviews) {
                if (!open || host == null) break;
                if (!e.retired && !e.failed && e.extension != null)
                    preview(e, pose, destination, camera, ghost);
            }
        } finally {
            sceneDrawing = false;
        }
    }

    private void refreshScenes() {
        var source = host.extensionDocument();
        boolean changed = source != sceneSource;
        var active = new LinkedHashMap<net.minecraft.resources.ResourceLocation, Entry>();
        for (var e : List.copyOf(entries)) {
            if (!open || host == null) break;
            if (!e.scene || e.failed || e.retired) continue;
            try {
                boolean fresh = e.extension == null;
                if (fresh) {
                    create(e);
                    e.resourceEpoch = epoch;
                } else {
                    if (e.resourceEpoch != epoch) {
                        observe(e, () -> e.extension.resourcesChanged(e.session, epoch));
                        e.resourceEpoch = epoch;
                    }
                    if (changed && !e.failed && !e.retired)
                        observe(e, () -> e.extension.changed(e.session, Change.DOCUMENT));
                }
                if (!open || e.failed || e.retired || e.extension == null) continue;
                e.previewEnabled = owned(e, () -> e.extension.hasPreview());
                if (e.previewEnabled) active.put(e.definition.provider(), e);
            } catch (Exception | LinkageError error) {
                failure(e, error);
            }
        }
        if (!open || host == null) {
            scenePreviews = List.of();
            return;
        }
        try {
            com.eynny.npships.authoring.patches.PatchCode.validate(policy, active.keySet());
            scenePreviews =
                    com.eynny.npships.api.patches.PatchOrdering.discoveryOrder(
                                    com.eynny.npships.api.patches.PatchStage.AUTHOR_PREPARATION,
                                    active.keySet(),
                                    policy)
                            .stream()
                            .map(active::get)
                            .toList();
        } catch (IllegalArgumentException refused) {
            scenePreviews = List.of();
            for (var e : active.values()) failure(e, refused);
        }
        sceneSource = source;
        sceneDirty = host.extensionDocument() != source;
    }

    private void observe(Entry e, Runnable callback) {
        readOnly++;
        try {
            invoke(e, callback);
        } finally {
            readOnly--;
        }
    }

    private void leave(Entry e) {
        e.callbacks--;
        if (e.callbacks == 0) e.callbackEpoch++;
        if (e.callbacks == 0 && e.retired) dispose(e);
    }

    private <T> T owned(Entry e, Supplier<T> action) {
        e.callbacks++;
        try {
            return action.get();
        } finally {
            leave(e);
        }
    }

    private void invoke(Entry e, Runnable callback) {
        if (e.failed || e.retired || e.extension == null) return;
        e.callbacks++;
        try {
            callback.run();
        } catch (Exception | LinkageError error) {
            failure(e, error);
        } finally {
            leave(e);
        }
    }

    private void retire(Entry e) {
        if (e.retired) return;
        e.retired = true;
        e.tool = false;
        e.modal = false;
        if (e.callbacks == 0) dispose(e);
    }

    private void dispose(Entry e) {
        if (e.disposed) return;
        e.disposed = true;
        var extension = e.extension;
        e.extension = null;
        e.root.widgets.clear();
        e.root.setFocused(null);
        try {
            e.scope.close();
        } catch (Exception | LinkageError error) {
            failure(e, error);
        }
        if (extension != null)
            try {
                extension.close();
            } catch (Exception | LinkageError error) {
                failure(e, error);
            }
    }

    private void failure(Entry e, Throwable error) {
        if (e.failed) return;
        e.failed = true;
        e.tool = false;
        e.modal = false;
        clearPointer();
        e.root.setFocused(null);
        String detail = EditorErrorText.message(error);
        if (detail.length() > 384) detail = detail.substring(0, 384);
        var text =
                Component.translatable(
                        "npships.extensions.failed", e.definition.id().toString(), detail);
        if (host != null) host.extensionStatus(text, true);
        LogUtils.getLogger().warn("[NPShips editor extension] {}: {}", e.definition.id(), detail);
        retire(e);
    }

    @Override
    public void close() {
        if (!open || closing) return;
        closing = true;
        if (selected != null && selected.callbacks == 0) deactivate();
        else {
            menu = false;
            clearPointer();
            if (selected != null) {
                selected.tool = false;
                selected.modal = false;
            }
            selected = null;
        }
        open = false;
        revision = UUID.randomUUID();
        for (var e : entries) retire(e);
        entries.clear();
        scenePreviews = List.of();
        sceneSource = null;
        sceneDataSource = null;
        sceneData = null;
        choices.clear();
        selector = null;
        host = null;
    }

    private final class Entry {
        final EditorExtensions.Definition definition;
        final boolean scene;
        long callbackEpoch, resourceEpoch = -1;
        final Session session = new SessionImpl(this);
        final Root root = new Root();
        final PreviewRendererScope scope = new PreviewRendererScope();
        EditorExtension extension;
        boolean failed, tool, previewEnabled, modal, refresh, retired, disposed;
        int callbacks;
        int scroll, contentHeight;

        Entry(EditorExtensions.Definition definition) {
            this(definition, false);
        }

        Entry(EditorExtensions.Definition definition, boolean scene) {
            this.definition = definition;
            this.scene = scene;
        }
    }

    private static final class Root extends AbstractContainerEventHandler {
        final List<AbstractWidget> widgets = new ArrayList<>();

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }
    }

    private final class SessionImpl implements Session {
        private final Entry entry;

        SessionImpl(Entry entry) {
            this.entry = entry;
        }

        private void check() {
            requireActive();
            if (entry.failed) throw new IllegalStateException("Editor contribution is quarantined");
        }

        public <T> Optional<T> findCapability(Class<T> contract) {
            check();
            Objects.requireNonNull(contract);
            if (entry.callbacks <= 0 || entry.retired)
                throw new IllegalStateException("Host capability requires a current callback");
            if (contract == NativeEditorDocument.class
                    && entry.definition.provider().getNamespace().equals("npships")) {
                long generation = entry.callbackEpoch;
                NativeEditorDocument input =
                        () -> {
                            check();
                            if (entry.callbacks <= 0
                                    || entry.retired
                                    || entry.callbackEpoch != generation)
                                throw new IllegalStateException("Native editor view expired");
                            return host.extensionDocument();
                        };
                return Optional.of(contract.cast(input));
            }
            return Optional.empty();
        }

        public UUID revision() {
            check();
            return revision;
        }

        public <T> T inspect(Function<PatchAuthoring.Context, T> read) {
            check();
            return owned(
                    entry,
                    () -> {
                        var document = host.extensionDocument();
                        if (!entry.scene)
                            return SchematicExtensions.inspect(
                                    entry.definition.provider(), document, read);
                        if (sceneDataSource != document) {
                            sceneData = List.copyOf(SchematicExtensions.read(document));
                            sceneDataSource = document;
                        }
                        return SchematicExtensions.inspect(
                                entry.definition.provider(), document, sceneData, read);
                    });
        }

        public Result submit(
                UUID expected, Function<PatchAuthoring.Context, PatchAuthoring.Proposal> edit) {
            if (entry.scene || !interactive)
                return new Result(
                        Status.REFUSED,
                        revision,
                        Component.translatable("npships.extensions.scene_read_only"));
            if (!active())
                return new Result(
                        Status.CLOSED,
                        revision,
                        Component.translatable("npships.extensions.closed"));
            if (!revision.equals(expected))
                return new Result(
                        Status.STALE, revision, Component.translatable("npships.extensions.stale"));
            if (entry.failed || busy || closing || readOnly != 0)
                return new Result(
                        Status.REFUSED,
                        revision,
                        Component.translatable("npships.extensions.busy"));
            busy = true;
            entry.callbacks++;
            try {
                if (!host.extensionReady())
                    return new Result(
                            Status.REFUSED,
                            revision,
                            Component.translatable("npships.extensions.busy"));
                if (!revision.equals(expected))
                    return new Result(
                            Status.STALE,
                            revision,
                            Component.translatable("npships.extensions.stale"));
                var before = host.extensionDocument();
                var candidate = SchematicExtensions.edit(entry.definition.provider(), before, edit);
                if (!active() || !revision.equals(expected) || host.extensionDocument() != before)
                    return new Result(
                            Status.STALE,
                            revision,
                            Component.translatable("npships.extensions.stale"));
                if (candidate.sameContents(before))
                    return new Result(Status.UNCHANGED, revision, Component.empty());
                host.extensionAccept(candidate);
                return new Result(Status.ACCEPTED, revision, Component.empty());
            } catch (IllegalArgumentException | IllegalStateException refused) {
                return new Result(
                        Status.REFUSED,
                        revision,
                        Component.literal(EditorErrorText.message(refused)));
            } catch (Exception | LinkageError error) {
                failure(entry, error);
                return new Result(
                        Status.FAILED, revision, Component.literal(EditorErrorText.message(error)));
            } finally {
                busy = false;
                leave(entry);
            }
        }

        public Selection selection() {
            check();
            return host.extensionSelection();
        }

        public void toolActive(boolean value) {
            check();
            if (selected != entry) throw new IllegalStateException("Select this tab first");
            if (value && entry.modal)
                throw new IllegalStateException(
                        "Close the panel popup before enabling a scene tool");
            entry.tool = value;
            if (!value) clearPointer();
        }

        public boolean toolActive() {
            check();
            return selected == entry && entry.tool;
        }

        public void modal(boolean value) {
            check();
            if (selected != entry || readOnly != 0 || busy)
                throw new IllegalStateException("Popup ownership requires selected input callback");
            if (value && entry.tool)
                throw new IllegalStateException(
                        "Cancel the scene tool before opening a panel popup");
            entry.modal = value;
            clearPointer();
        }

        public boolean modal() {
            check();
            return selected == entry && entry.modal;
        }

        public void refresh() {
            check();
            if (selected != entry || readOnly != 0 || busy)
                throw new IllegalStateException("Layout refresh requires selected input callback");
            entry.refresh = true;
        }

        public void feedback(Component message, boolean error) {
            check();
            host.extensionStatus(Objects.requireNonNull(message), error);
        }
    }

    private final class PanelWindow implements Panel {
        private final Entry entry;
        boolean live = true;

        PanelWindow(Entry entry) {
            this.entry = entry;
        }

        private void check() {
            requireActive();
            if (!live) throw new IllegalStateException("Layout callback expired");
        }

        public Bounds bounds() {
            check();
            return content();
        }

        public <T extends AbstractWidget> T add(T widget) {
            check();
            Objects.requireNonNull(widget);
            if (entry.root.widgets.contains(widget))
                throw new IllegalArgumentException("Duplicate widget");
            entry.root.widgets.add(widget);
            return widget;
        }

        public void contentHeight(int height) {
            check();
            if (height < 0) throw new IllegalArgumentException("Negative panel extent");
            entry.contentHeight = height;
        }
    }

    private final class PreviewWindow implements Preview {
        final PoseStack pose;
        final MultiBufferSource buffers;
        final Camera camera;
        final boolean ghost;
        boolean live = true;

        PreviewWindow(PoseStack pose, MultiBufferSource buffers, Camera camera, boolean ghost) {
            this.pose = pose;
            this.buffers = buffers;
            this.camera = camera;
            this.ghost = ghost;
        }

        private void check() {
            requireActive();
            if (!live) throw new IllegalStateException("Preview callback expired");
        }

        public PoseStack pose() {
            check();
            return pose;
        }

        public MultiBufferSource buffers() {
            check();
            return buffers;
        }

        public boolean visible(net.minecraft.core.BlockPos position) {
            check();
            return host.visible(Objects.requireNonNull(position));
        }

        public Camera camera() {
            check();
            return camera;
        }

        public boolean ghost() {
            check();
            return ghost;
        }

        public long resourceGeneration() {
            check();
            return epoch;
        }
    }
}
