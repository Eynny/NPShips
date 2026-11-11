package com.eynny.npships.client.editor;

import com.eynny.npships.api.client.editor.EditorExtension;
import com.eynny.npships.authoring.AssemblyEligibility;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.io.AuthoringFiles;
import com.eynny.npships.client.editor.render.PhotoCapture;
import com.eynny.npships.client.editor.render.PreviewRendererScope;
import com.eynny.npships.client.editor.render.SceneOutlines;
import com.eynny.npships.client.editor.render.SchematicPreviewLevel;
import com.eynny.npships.client.editor.render.SchematicPreviewRenderer;
import com.eynny.npships.client.editor.screen.AuthoringFormScreen;
import com.eynny.npships.client.editor.screen.BlockEditorScreen;
import com.eynny.npships.client.editor.screen.ContainerLootScreen;
import com.eynny.npships.client.editor.screen.EditorConfirmScreen;
import com.eynny.npships.client.editor.screen.LocalFilePicker;
import com.eynny.npships.client.editor.screen.SchematicBrowserScreen;
import com.eynny.npships.client.editor.screen.SegmentEditorScreen;
import com.eynny.npships.client.editor.screen.SpawnConfigEditorScreen;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorIconButton;
import com.eynny.npships.client.editor.widget.EditorPopupMenu;
import com.eynny.npships.client.editor.widget.EditorScrollBar;
import com.eynny.npships.client.editor.widget.EditorSlider;
import com.eynny.npships.client.editor.widget.EditorTooltips;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Static preview and local JSON editing; never changes the connected world's blocks. */
public final class SchematicEditorScreen extends Screen implements EditorButtonTheme.PaletteSource {
    // Screen.render invokes this AFTER our scene. Its default blur would blur the editor itself.
    @Override
    public void renderBackground(
            final GuiGraphics graphics,
            final int mouseX,
            final int mouseY,
            final float partialTick) {}

    private final Screen parent;
    private SchematicDocument document;
    private final java.nio.file.Path directory;
    private String filename;
    private JsonObject draft;

    private net.minecraft.nbt.CompoundTag draftNbt() {
        return working.nbtCopy();
    }

    enum EditMode {
        SELECT,
        CREATE,
        DELETE
    }

    private EditMode editMode = EditMode.SELECT;
    private final EditorShortcutDispatcher shortcuts = new EditorShortcutDispatcher(this);
    private final EditorShortcutReference shortcutReference = new EditorShortcutReference();

    boolean shortcutContextAvailable() {
        return sceneShortcutContextAvailable() || inventoryDockAvailable();
    }

    private boolean inventoryDockAvailable() {
        return !previewOnly
                && !importMode
                && !resizeMode
                && tools != null
                && tools.inventoryExpanded
                && tools.workspaceOwner() == EditorToolsPanel.WorkspaceOwner.INVENTORY;
    }

    private boolean sceneShortcutContextAvailable() {
        return tools != null
                && layout != null
                && layout.sceneUsable()
                && (previewOnly
                        || !importMode && !resizeMode && tools != null && layout.inspectorUsable());
    }

    boolean shortcutsAvailable() {
        return !previewOnly
                && !importMode
                && !resizeMode
                && tools != null
                && layout != null
                && layout.sceneUsable()
                && layout.inspectorUsable();
    }

    private java.util.List<net.minecraft.util.FormattedCharSequence> editorTooltip;
    private net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner
            editorTooltipPositioner;

    @Override
    public void setTooltipForNextRenderPass(
            java.util.List<net.minecraft.util.FormattedCharSequence> lines,
            net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
            boolean override) {
        if (editorTooltip == null || override) {
            editorTooltip = EditorTooltips.fit(font, lines, width, height);
            editorTooltipPositioner = EditorTooltips.bounded(positioner);
        }
    }

    private void renderEditorTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        var lines = editorTooltip;
        var positioner = editorTooltipPositioner;
        editorTooltip = null;
        editorTooltipPositioner = null;
        if (lines == null || lines.isEmpty()) return;
        // Native deferred tooltips use z=400, below retained editor overlays at z=500..950.
        // Draw the same native tooltip once, after all editor overlays, in their owning Screen.
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1100);
        try {
            graphics.renderTooltip(font, lines, positioner, mouseX, mouseY);
        } finally {
            graphics.pose().popPose();
        }
    }

    @Override
    protected void clearTooltipForNextRenderPass() {
        super.clearTooltipForNextRenderPass();
        editorTooltip = null;
        editorTooltipPositioner = null;
    }

    private boolean keyboardControl() {
        return extensions != null && extensions.keyboardFocused()
                || tools != null && tools.keyboardControl()
                || getFocused() instanceof AbstractWidget w && w.visible && w.active;
    }

    private boolean presentationKey(int key, int modifiers) {
        boolean control = EditorKeyboardInput.control(modifiers) || hasControlDown();
        if (control
                && (key == GLFW.GLFW_KEY_A
                        || key == GLFW.GLFW_KEY_C
                        || key == GLFW.GLFW_KEY_X
                        || key == GLFW.GLFW_KEY_V
                        || key == GLFW.GLFW_KEY_Z
                        || key == GLFW.GLFW_KEY_Y
                        || key == GLFW.GLFW_KEY_S
                        || key == GLFW.GLFW_KEY_SPACE)) return false;
        if (key == GLFW.GLFW_KEY_SLASH && (modifiers & GLFW.GLFW_MOD_ALT) != 0) return false;
        if (key == GLFW.GLFW_KEY_TAB
                || key == GLFW.GLFW_KEY_ENTER
                || key == GLFW.GLFW_KEY_KP_ENTER
                || key == GLFW.GLFW_KEY_SPACE
                || firstPerson && flightKey(key)) return false;
        // Configured presentation actions cannot replace literal typing/native text navigation.
        return tools == null
                || !tools.editingText()
                || key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25
                || (control || (modifiers & GLFW.GLFW_MOD_ALT) != 0) && key >= 32 && key < 256;
    }

    void openShortcutReference() {
        if (!sceneShortcutContextAvailable()
                || inspectorDivider.captured()
                || tools.captureActive()
                || tools.keyboardPopup()
                || cameraDragButton >= 0
                || heldPaint) return;
        if (extensions != null) extensions.cancelInteraction();
        shortcutReference.open();
        shortcutReference.layout(width, height);
    }

    private Component sliceShortcutHint() {
        return EditorShortcutDispatcher.hint(
                sliceLabel()
                        .copy()
                        .append("\n")
                        .append(Component.translatable("npships.editor.keys.slice_hint")),
                EditorShortcutDispatcher.Action.SLICE_PREVIOUS,
                EditorShortcutDispatcher.Action.SLICE_NEXT);
    }

    private boolean sceneKeyboardCaptured() {
        return sceneKeyboardCaptured(false);
    }

    private boolean sceneKeyboardCaptured(boolean toolNavigation) {
        return cameraDragButton >= 0
                || symmetryDrag >= 0
                || reachDragging
                || selecting
                || choosingCorners
                || heldPaint
                || infoBar.dragging()
                || tools != null
                        && (tools.captureActive() || !tools.ordinaryInspection(toolNavigation));
    }

    boolean shortcutApplicable(EditorShortcutDispatcher.Action action) {
        // A temporary size clamp may hide every inventory cell and its pinned Dock button.
        // Only the existing workspace owner may close that overlay; scene commands stay gated.
        boolean dockingInventory =
                action == EditorShortcutDispatcher.Action.WORKSPACE && inventoryDockAvailable();
        if ((!sceneShortcutContextAvailable() && !dockingInventory)
                || shortcutReference.isOpen()
                || inspectorDivider.captured()
                || viewMenu.isOpen() && !viewMenu.isSuspended()
                // Idle tool modes may block scene edits, but not a validated tab handoff.
                || sceneKeyboardCaptured(shortcutTool(action) != null)
                || saving != null
                || tools.geometryBusy()) return false;
        if (action == EditorShortcutDispatcher.Action.SNBT_REFERENCE)
            return !previewOnly
                    && tools.referenceShortcutAvailable()
                    && (tools.currentReferenceOpen()
                            || !(getFocused() instanceof AbstractWidget w)
                            || !w.active
                            || !w.visible)
                    && (!tools.keyboardPopup() || tools.currentReferenceOpen());
        if (tools.keyboardPopup()) return false;
        if (action == EditorShortcutDispatcher.Action.WORKSPACE)
            return !previewOnly
                    && tools.workspaceOwner() != EditorToolsPanel.WorkspaceOwner.NONE
                    && (!(getFocused() instanceof AbstractWidget w) || !w.active || !w.visible);
        if (keyboardControl() || tools.editingText() || tools.expandedWorkspace()) return false;
        if (action == EditorShortcutDispatcher.Action.CAMERA) return true;
        if (action == EditorShortcutDispatcher.Action.SLICE_PREVIOUS
                || action == EditorShortcutDispatcher.Action.SLICE_NEXT)
            return slider != null && slider.active && slider.visible;
        if (!shortcutsAvailable()) return false;
        if (action == EditorShortcutDispatcher.Action.ROTATE_BRUSH_BACKWARD
                || action == EditorShortcutDispatcher.Action.ROTATE_BRUSH_FORWARD)
            return tools.blocksTool()
                            && (creating()
                                    || editMode == EditMode.SELECT
                                            && tools.pos() != null
                                            && specialBounds() == null)
                    || tools.entityPlacement()
                    || tools.entityTool()
                            && tools.tool != EditorToolsPanel.Tool.GLUE
                            && tools.entityIndex >= 0
                    || tools.attachmentRotationAvailable();
        var target = shortcutTool(action);
        return target == null || tools.toolAvailable(target);
    }

    private EditorToolsPanel.Tool shortcutTool(EditorShortcutDispatcher.Action action) {
        return switch (action) {
            case BLOCKS -> EditorToolsPanel.Tool.BLOCKS;
            case ENTITIES -> EditorToolsPanel.Tool.ENTITIES;
            case SEGMENTS -> EditorToolsPanel.Tool.SEGMENTS;
            case ZONES -> EditorToolsPanel.Tool.ZONES;
            case LOOT -> EditorToolsPanel.Tool.LOOT;
            case PATCHES -> EditorToolsPanel.Tool.PATCHES;
            case GLUE -> EditorToolsPanel.Tool.GLUE;
            case BLOCKS_AREAS ->
                    tools.tool == EditorToolsPanel.Tool.AREA
                            ? EditorToolsPanel.Tool.BLOCKS
                            : EditorToolsPanel.Tool.AREA;
            default -> null;
        };
    }

    void shortcut(EditorShortcutDispatcher.Action action) {
        boolean nativeTool =
                shortcutTool(action) != null
                        || action == EditorShortcutDispatcher.Action.SELECT
                        || action == EditorShortcutDispatcher.Action.CREATE
                        || action == EditorShortcutDispatcher.Action.DELETE_MODE;
        if (nativeTool && extensions != null) extensions.deactivate();
        if (extensionPanelActive()
                && action != EditorShortcutDispatcher.Action.CAMERA
                && action != EditorShortcutDispatcher.Action.SLICE_PREVIOUS
                && action != EditorShortcutDispatcher.Action.SLICE_NEXT) return;
        switch (action) {
            case ROTATE_BRUSH_BACKWARD -> {
                rotateShortcut(-1);
                return;
            }
            case ROTATE_BRUSH_FORWARD -> {
                rotateShortcut(1);
                return;
            }
            case SLICE_PREVIOUS -> {
                setCeiling(ceiling - 1);
                return;
            }
            case SLICE_NEXT -> {
                setCeiling(ceiling + 1);
                return;
            }
            case CAMERA -> {
                toggleFirstPerson();
                return;
            }
            case WORKSPACE -> {
                tools.toggleWorkspace();
                return;
            }
            case SNBT_REFERENCE -> {
                tools.toggleSnbtReference();
                return;
            }
            default -> {}
        }
        var target = shortcutTool(action);
        if (target != null) tools.shortcutTool(target);
        else {
            var mode =
                    switch (action) {
                        case SELECT -> EditMode.SELECT;
                        case CREATE -> EditMode.CREATE;
                        case DELETE_MODE -> EditMode.DELETE;
                        default -> throw new IllegalArgumentException(action.name());
                    };
            if (editMode != mode) editMode(mode);
        }
    }

    private void rotateShortcut(int direction) {
        if (!creating() && (tools.blocksTool() || tools.entityIndex >= 0))
            tools.rotateSelected(direction);
        else tools.rotateBrush(direction);
    }

    boolean creating() {
        return editMode == EditMode.CREATE;
    }

    boolean deleting() {
        return editMode == EditMode.DELETE;
    }

    private void editMode(EditMode next) {
        if (tools != null && !tools.flushEdits()) return;
        if (extensions != null) extensions.deactivate();
        authoringCycle.reset();
        heldPaint = false;
        if (choosingCorners) clearRegion();
        editMode = next;
        if (tools != null && next != EditMode.CREATE) tools.fittedPlane = null;
        pickDirty = true;
    }

    private BlockPos cornerA, cornerB;
    private java.util.Map<BlockPos, String> lootLabels = java.util.Map.of();
    private final boolean previewOnly;
    private SchematicDocument previewSource;
    private boolean previewMobs, heldPaint, repeatingPaint, savingFields, saveAfterResize;
    private int paintTicks;
    private long paintStarted;
    private BlockPos lastPaintEntity;
    private boolean selecting, orbitGesture, choosingCorners;
    private int cameraDragButton = -1;
    private float navigationSpeed = .25f;
    private long navigationSpeedChecked;
    private SchematicPreviewLevel validationLevel;
    private java.util.Iterator<BlockPos> validationQueue;
    private final java.util.Map<BlockPos, Component> supportWarnings = new java.util.HashMap<>();
    private AABB special, boundsPreview, reviewBounds;
    private boolean importMode, resizeMode, zonesHeld, hideWarnings;

    boolean showWarning(BlockPos p) {
        return !hideWarnings;
    }

    boolean allZones() {
        return zonesHeld && !previewOnly && !importMode;
    }

    boolean inspectionReady() {
        return !previewOnly
                && !importMode
                && !resizeMode
                && editMode == EditMode.SELECT
                && !selecting
                && tools != null
                && tools.ordinaryInspection();
    }

    boolean authoringSelectionReady() {
        return allZones() && inspectionReady();
    }

    boolean deviceHints() {
        return zonesHeld && !previewOnly && !importMode;
    }

    private java.util.function.Consumer<BlockPos> importAccept;
    private java.util.function.Consumer<
                    com.eynny.npships.authoring.document.SchematicRegions.Region>
            importAreaAccept;
    private boolean importResizable, importMoving;

    boolean translatingImport() {
        return !importResizable || importMoving;
    }

    private java.util.function.Consumer<SchematicDocument> reviewAccept;
    private Runnable reviewCancel;

    AABB specialBounds() {
        return special;
    }

    boolean importing() {
        return importMode;
    }

    void previewBounds(AABB b) {
        boundsPreview = b;
    }

    double mouseX() {
        return lastMouseX;
    }

    double mouseY() {
        return lastMouseY;
    }

    void previewDraft(SchematicDocument edited) {
        setDraft(edited);
    }

    boolean retainedDocument(SchematicDocument document) {
        return document == working || history.retains(document);
    }

    void finishDraftGesture(SchematicDocument before) {
        if (!before.sameContents(working) || tools.layerStateDiffers(before, working))
            history.record(before);
    }

    private BlockPos pendingWholeResizeOrigin;

    void finishBounds(BlockPos origin, BlockPos size) {
        if (importMode) {
            if (!containsDraft(origin) || !containsDraft(origin.offset(size).offset(-1, -1, -1)))
                throw new IllegalArgumentException(
                        Component.translatable("npships.tools.import_bounds").getString());
            special =
                    new AABB(
                            Vec3.atLowerCornerOf(origin),
                            Vec3.atLowerCornerOf(origin.offset(size)));
            return;
        }
        var proposal =
                com.eynny.npships.authoring.document.SchematicReframe.whole(
                        current(), origin, size);
        pendingWholeResizeOrigin = origin;
        proposeResize(proposal);
    }

    void proposeResize(com.eynny.npships.authoring.document.SchematicReframe.Proposal proposal) {
        if (!proposal.destructive()) acceptResize(proposal.result());
        else {
            saveAfterResize = savingFields;
            ResizeWorkflow.open(this, proposal);
        }
    }

    void cancelResize() {
        pendingWholeResizeOrigin = null;
        saveAfterResize = false;
        minecraft.setScreen(this);
    }

    void acceptResize(SchematicDocument result) {
        boolean saveNow = saveAfterResize;
        saveAfterResize = false;
        boolean whole = pendingWholeResizeOrigin != null;
        if (whole) tools.beginWholeResize(pendingWholeResizeOrigin, result.size());
        try {
            special = null;
            resizeMode = false;
            clearRegion();
            applyDraft(result);
        } finally {
            pendingWholeResizeOrigin = null;
            if (whole) tools.finishWholeResize();
        }
        minecraft.setScreen(this);
        if (saveNow) save();
    }

    void importRegion(
            String name,
            SchematicDocument source,
            BlockPos size,
            java.util.function.Consumer<BlockPos> accept) {
        if (size.getX() > source.size().getX()
                || size.getY() > source.size().getY()
                || size.getZ() > source.size().getZ()) {
            status(Component.translatable("npships.tools.import_bounds").getString());
            minecraft.setScreen(this);
            return;
        }
        var config = source.hasConfig() ? source.configCopy() : new JsonObject();
        config.addProperty("cost", 1);
        var screen =
                new SchematicEditorScreen(
                        this, directory, name, source.withConfig(config), minecraft.level);
        screen.importMode = true;
        screen.importAccept = accept;
        screen.special = new AABB(Vec3.ZERO, Vec3.atLowerCornerOf(size));
        minecraft.setScreen(screen);
    }

    void renameFile() {
        if (!tools.flushEdits()) return;
        if (dirty || saving != null) {
            status(Component.translatable("npships.editor.save_before_rename").getString());
            return;
        }
        minecraft.setScreen(
                new AuthoringFormScreen(
                        this,
                        "npships.editor.rename",
                        List.of("npships.segments.name"),
                        List.of(java.nio.file.Path.of(filename).getFileName().toString()),
                        values -> {
                            try {
                                filename =
                                        AuthoringFiles.rename(
                                                directory, filename, values.getFirst());
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                            com.eynny.npships.network.CatalogRefreshNetworking.saved();
                            minecraft.setScreen(this);
                        }));
    }

    private void selectImportArea(
            String name,
            SchematicDocument source,
            java.util.function.Consumer<
                            com.eynny.npships.authoring.document.SchematicRegions.Region>
                    accept) {
        var config = source.hasConfig() ? source.configCopy() : new JsonObject();
        config.addProperty("cost", 1);
        var screen =
                new SchematicEditorScreen(
                        this, directory, name, source.withConfig(config), minecraft.level);
        screen.importMode = true;
        screen.importResizable = true;
        screen.importAreaAccept = accept;
        screen.special = new AABB(Vec3.ZERO, Vec3.atLowerCornerOf(source.size()));
        minecraft.setScreen(screen);
    }

    void importArea() {
        if (!tools.flushEdits()) return;
        minecraft.setScreen(
                new SchematicBrowserScreen(
                        this,
                        (name, source) ->
                                selectImportArea(
                                        name,
                                        source,
                                        region -> {
                                            var part =
                                                    com.eynny.npships.authoring.document
                                                            .BlockAreaLayers.extract(
                                                            source,
                                                            region,
                                                            new com.eynny.npships.authoring.document
                                                                    .SchematicRegions.Contents(
                                                                    true, true, true));
                                            minecraft.setScreen(this);
                                            tools.beginPaste(part, null);
                                        })));
    }

    private void editConfig() {
        if (tools.flushEdits())
            minecraft.setScreen(
                    new SpawnConfigEditorScreen(
                            this,
                            current(),
                            this::applyDraft,
                            () -> {
                                minecraft.setScreen(this);
                                save();
                            }));
    }

    void reviewResize(
            com.eynny.npships.authoring.document.SchematicReframe.Review review,
            java.util.function.Consumer<SchematicDocument> accept,
            Runnable cancel) {
        var screen =
                new SchematicEditorScreen(
                        this, directory, review.label(), review.before(), minecraft.level);
        screen.reviewBounds =
                new AABB(
                        Vec3.atLowerCornerOf(review.origin()),
                        Vec3.atLowerCornerOf(review.origin().offset(review.size())));
        screen.reviewAccept = accept;
        screen.reviewCancel = cancel;
        screen.resetView();
        minecraft.setScreen(screen);
    }

    private final com.eynny.npships.authoring.document.SchematicHistory history =
            new com.eynny.npships.authoring.document.SchematicHistory();
    private boolean dirty;
    private java.util.concurrent.CompletableFuture<SchematicDocument> saving;
    private Component saveStatus = Component.empty();
    private Button saveButton;
    private Button undoButton, redoButton;
    private List<BlockPos> containers;
    private SchematicPreviewLevel level;
    private SchematicPreviewRenderer renderer;
    private List<BlockPos> objects;
    private int ceiling;
    private SchematicDocument working, diskDocument, savingFlat;
    private com.eynny.npships.authoring.document.BlockAreaLayers.State initialLayers, savingLayers;

    com.eynny.npships.authoring.document.BlockAreaLayers.State initialLayers() {
        return initialLayers;
    }

    /** Automatic initial plane reconstruction is the clean editing baseline, not a user edit. */
    boolean cleanOpeningState() {
        return !dirty && working.sameContents(document);
    }

    void acceptOpeningRefresh() {
        document = working;
        dirty = false;
    }

    void metadataChanged() {
        dirty = true;
    }

    void setSelectMode() {
        editMode = EditMode.SELECT;
        if (tools != null) tools.fittedPlane = null;
        heldPaint = false;
        pickDirty = true;
    }

    boolean showsWarnings() {
        return !previewOnly && !hideWarnings;
    }

    Component assemblyPointWarningAt(double x, double y) {
        if (tools == null
                || !showsWarnings()
                || shortcutReference.isOpen()
                || !inViewport(x, y)
                || viewMenu.contains(x, y)
                || tools.overlayAt(x, y)) return null;
        return tools.assemblyPoints.warningAt(x, y, inverse, previewDepth());
    }

    private EditMode temporaryCreationMode, beforeResizeMode;

    void temporaryCreateMode() {
        if (temporaryCreationMode == null) temporaryCreationMode = editMode;
        setCreateMode();
    }

    void finishCreationMode() {
        var previous = temporaryCreationMode;
        temporaryCreationMode = null;
        editMode(previous == null ? EditMode.SELECT : previous);
    }

    void finishCreationModeForInspection() {
        // Inspection already validated Select; do not restore an old Create/Delete intent.
        temporaryCreationMode = null;
    }

    void setCreateMode() {
        editMode(EditMode.CREATE);
    }

    void translucent(java.util.Set<BlockPos> positions) {
        renderer.translucent(positions);
    }

    private BlockPos draftSize;
    private int sliceMode;
    private EditorToolsPanel tools;
    private EditorExtensionHost extensions;
    private int infoTop;
    private java.util.function.Consumer<SchematicDocument> variantAccept;
    private Runnable previousVariant, nextVariant, variantGallery;
    private net.minecraft.core.Direction hitFace;
    private BlockPos selected;
    private BlockPos hovered;
    private boolean lastPickControl, lastPickShift;
    private float yaw = 135, pitch = 30, zoom = 1;
    private final Vector3f cameraTarget = new Vector3f();
    private Matrix4f inverse;
    private int left, right, top, bottom, sideWidth;
    private java.util.function.Consumer<byte[]> iconCapture;
    private boolean captureRequested;
    private EditorButton photoCaptureButton, photoSizeArrow;
    private EditorButtonTheme.Palette snapshotPalette;

    @Override
    public EditorButtonTheme.Palette editorPalette() {
        return snapshotPalette;
    }

    public static SchematicEditorScreen iconPreview(
            Screen parent, SchematicDocument document, java.util.function.Consumer<byte[]> accept) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var tag = document.nbtCopy();
        com.eynny.npships.authoring.patches.SchematicPatches.embed(tag, document.configCopy());
        var preview =
                new SchematicEditorScreen(
                        parent,
                        mc.gameDirectory.toPath(),
                        "Pack icon",
                        new SchematicDocument(tag, document.configCopy()),
                        mc.level,
                        true);
        preview.iconCapture = accept;
        preview.snapshotPalette = EditorButtonTheme.current();
        preview.gridMode = GridMode.BACKGROUND;
        preview.blackGrid = false;
        preview.gridFade = 3;
        preview.pinSchematicBounds = false;
        preview.graphPaper = true;
        return preview;
    }

    private java.nio.file.Path photoOutput;
    private int photoSize = 256;

    private void requestPhoto(java.nio.file.Path path, int size) {
        if (path == null) return;
        if (size != 512 && size != 1024)
            throw new IllegalArgumentException("Choose 512 or 1024 pixels.");
        photoOutput = path;
        photoSize = size;
        captureRequested = true;
    }

    private List<EditorPopupMenu.Entry> brightnessEntries() {
        var entries = new ArrayList<EditorPopupMenu.Entry>();
        for (float value : new float[] {.5f, .75f, 1, 1.25f, 1.5f})
            entries.add(
                    new EditorPopupMenu.Entry(
                            Component.literal(Math.round(value * 100) + "%"),
                            () -> brightness == value,
                            () -> brightness = value));
        return entries;
    }

    private void photoMenu(int x) {
        var entries = new ArrayList<EditorPopupMenu.Entry>();
        for (int size : new int[] {512, 1024})
            entries.add(
                    new EditorPopupMenu.Entry(
                            Component.translatable("npships.addons.logo_save", size),
                            () -> false,
                            () -> {
                                try {
                                    var path =
                                            LocalFilePicker.save(
                                                    LocalFilePicker.downloadsDirectory()
                                                            .resolve(
                                                                    "npships-project-"
                                                                            + size
                                                                            + ".png"),
                                                    Component.translatable(
                                                                    "npships.addons.logo_save",
                                                                    size)
                                                            .getString());
                                    requestPhoto(path, size);
                                } catch (Exception e) {
                                    error(EditorErrorText.message(e));
                                }
                            }));
        openHeaderMenu(photoCaptureButton, entries, false);
    }

    private void captureIcon(GuiGraphics graphics) {
        captureRequested = false;
        graphics.flush();
        var destination = photoOutput;
        photoOutput = null;
        try {
            int resolution = destination == null ? 256 : photoSize;
            byte[] png =
                    PhotoCapture.render(
                            destination == null ? 256 : photoSize,
                            EditorButtonTheme.scene(),
                            g -> renderPreview(g, -100000, -100000, resolution));
            if (destination == null) {
                saveStatus = Component.empty();
                iconCapture.accept(png);
            } else {
                PhotoCapture.saveNew(destination, png);
                status(
                        FeedbackOwner.PHOTO,
                        Component.translatable("npships.addons.logo_saved", destination.toString())
                                .getString(),
                        true);
            }
        } catch (Exception e) {
            error(
                    FeedbackOwner.PHOTO,
                    Component.translatable(
                                    "npships.addons.capture_failed", EditorErrorText.message(e))
                            .getString());
        }
    }

    private int interactionRange = 129;
    private AbstractSliderButton reachSlider;
    private AbstractWidget reachAnchor;
    private boolean reachOpen;

    private void layoutReachSlider() {
        if (reachSlider == null || reachAnchor == null) return;
        int popupWidth = Math.min(180, Math.max(0, width - 16));
        reachSlider.setWidth(popupWidth);
        reachSlider.setX(
                Math.clamp(
                        reachAnchor.getX() - 130,
                        Math.min(8, width),
                        Math.max(Math.min(8, width), width - popupWidth - 8)));
        reachSlider.setY(layout.documentToolbar().bottom() + 4);
        reachSlider.visible =
                reachOpen
                        && popupWidth >= 40
                        && reachAnchor.visible
                        && reachSlider.getY() + reachSlider.getHeight() <= layout.status().top();
    }

    private boolean reachDragging;

    private void loadViewPreferences() {
        var spec = com.eynny.npships.config.NPShipsEditorConfig.SPEC;
        if (!spec.isLoaded()) return;
        var saved =
                com.eynny.npships.config.NPShipsEditorConfig.withNativeOwner(
                        () -> com.eynny.npships.config.NPShipsEditorConfig.VIEW.get());
        try {
            var o = com.google.gson.JsonParser.parseString(saved).getAsJsonObject();
            gridMode =
                    o.has("grid") && !o.get("grid").getAsBoolean() ? GridMode.OFF : GridMode.FLOOR;
            if (o.has("gridMode")) {
                try {
                    gridMode =
                            GridMode.valueOf(
                                    o.get("gridMode")
                                            .getAsString()
                                            .toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    /* Fall back to the legacy visibility. */
                }
            }
            savedGridMode = gridMode == GridMode.OFF ? GridMode.FLOOR : gridMode;
            if (o.has("gridDisplayMode")) {
                try {
                    var display =
                            GridMode.valueOf(
                                    o.get("gridDisplayMode")
                                            .getAsString()
                                            .toUpperCase(java.util.Locale.ROOT));
                    if (display != GridMode.OFF) savedGridMode = display;
                } catch (IllegalArgumentException ignored) {
                    /* Retain the accepted current/legacy mode. */
                }
            }
            if (o.has("paper")) graphPaper = o.get("paper").getAsBoolean();
            // Legacy bold is deliberately ignored; all floor strokes are thin.
            if (o.has("black")) blackGrid = o.get("black").getAsBoolean();
            if (o.has("fade")) {
                double value = o.get("fade").getAsDouble();
                gridFade = Double.isFinite(value) ? Mth.clamp(value, 0, 8) : 3;
            }
            if (o.has("fps")) firstPerson = o.get("fps").getAsBoolean();
            if (o.has("fpsFreeLook")) freeFirstPersonLook = o.get("fpsFreeLook").getAsBoolean();
            if (o.has("preset")) cameraPreset = Mth.clamp(o.get("preset").getAsInt(), 0, 3);
            if (o.has("cycle")) cycleCamera = o.get("cycle").getAsBoolean();
            if (o.has("gridBounds")) pinGridBounds = o.get("gridBounds").getAsBoolean();
            if (o.has("bounds")) pinSchematicBounds = o.get("bounds").getAsBoolean();
            if (o.has("objectHints")) objectHoverHints = o.get("objectHints").getAsBoolean();
            if (o.has("hideOverriddenEntityFields"))
                hideOverriddenEntityFields = o.get("hideOverriddenEntityFields").getAsBoolean();
            if (o.has("reach")) interactionRange = Mth.clamp(o.get("reach").getAsInt(), 1, 129);
            loadMirrorPreferences(o);
        } catch (RuntimeException ignored) {
            /* Malformed preferences must not prevent opening a document. */
        }
    }

    private com.google.gson.JsonObject viewPreferencesBaseline;
    private com.google.gson.JsonObject mirrorPreferencesBaseline;
    private String documentViewBaselineKey;

    private String documentViewKey() {
        if (directory == null || filename == null || filename.isBlank()) return null;
        try {
            String path = directory.resolve(filename).toAbsolutePath().normalize().toString();
            if (java.io.File.separatorChar == '\\') path = path.toLowerCase(java.util.Locale.ROOT);
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            path.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.nio.file.InvalidPathException
                | java.security.NoSuchAlgorithmException invalid) {
            return null;
        }
    }

    private com.google.gson.JsonObject mirrorPreferenceFields() {
        var o = new com.google.gson.JsonObject();
        var span = mirrorSpan();
        var coordinates = new com.google.gson.JsonArray();
        coordinates.add(span.getX());
        coordinates.add(span.getY());
        coordinates.add(span.getZ());
        o.add("mirrorSpan", coordinates);
        o.addProperty("mirrorAxes", selectedSymmetryAxes());
        return o;
    }

    private void loadMirrorPreferences(com.google.gson.JsonObject view) {
        String key = documentViewKey();
        if (key == null || !view.has("documents")) return;
        try {
            var documents = view.getAsJsonObject("documents");
            if (!documents.has(key)) return;
            var entry = documents.getAsJsonObject(key);
            var span = entry.getAsJsonArray("mirrorSpan");
            if (span == null || span.size() != 3) return;
            int[] size = {draftSize.getX(), draftSize.getY(), draftSize.getZ()};
            double[] centers = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                // Integer half-block coordinates are independent of camera projection and layers.
                int value = span.get(axis).getAsBigDecimal().intValueExact();
                centers[axis] = Mth.clamp(value / 2d, 0, size[axis]);
            }
            int axes = entry.get("mirrorAxes").getAsBigDecimal().intValueExact();
            if (axes < 0 || axes > 7) return;
            symmetryCenters = centers;
            savedSymmetryAxes = axes;
        } catch (RuntimeException invalid) {
            // A malformed per-file preference must not prevent editing the schematic.
        }
    }

    /** Only the screen's own view values; unrelated persisted fields stay with the native owner. */
    private com.google.gson.JsonObject viewPreferenceFields() {
        var o = new com.google.gson.JsonObject();
        o.addProperty("grid", gridMode != GridMode.OFF);
        o.addProperty("gridMode", gridMode.name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("gridDisplayMode", savedGridMode.name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("paper", graphPaper);
        o.addProperty("black", blackGrid);
        o.addProperty("fade", gridFade);
        o.addProperty("fps", firstPerson);
        o.addProperty("fpsFreeLook", freeFirstPersonLook);
        o.addProperty("preset", cameraPreset);
        o.addProperty("cycle", cycleCamera);
        o.addProperty("gridBounds", pinGridBounds);
        o.addProperty("bounds", pinSchematicBounds);
        o.addProperty("reach", interactionRange);
        o.addProperty("objectHints", objectHoverHints);
        o.addProperty("hideOverriddenEntityFields", hideOverriddenEntityFields);
        return o;
    }

    private void saveViewPreferences() {
        if (!com.eynny.npships.config.NPShipsEditorConfig.SPEC.isLoaded()
                || iconCapture != null
                || previewOnly) return;
        var next = viewPreferenceFields();
        var changed = new java.util.HashSet<String>();
        for (var entry : next.entrySet())
            if (viewPreferencesBaseline == null
                    || !java.util.Objects.equals(
                            entry.getValue(), viewPreferencesBaseline.get(entry.getKey())))
                changed.add(entry.getKey());
        // A native reload may have replaced the active value while a newer file is already saved.
        // Closing an unchanged view is not a new user write and must not amplify that stale image.
        var mirror = mirrorPreferenceFields();
        String documentKey = documentViewKey();
        boolean mirrorChanged =
                documentKey != null
                        && (!mirror.equals(mirrorPreferencesBaseline)
                                || !documentKey.equals(documentViewBaselineKey));
        if (changed.isEmpty() && !mirrorChanged) return;
        if (changed.contains("grid")
                || changed.contains("gridMode")
                || changed.contains("gridDisplayMode")) {
            changed.add("grid");
            changed.add("gridMode");
            changed.add("gridDisplayMode");
        }
        com.eynny.npships.config.NPShipsEditorConfig.withNativeOwner(
                () -> {
                    var o = new com.google.gson.JsonObject();
                    try {
                        o =
                                com.google.gson.JsonParser.parseString(
                                                com.eynny.npships.config.NPShipsEditorConfig.VIEW
                                                        .get())
                                        .getAsJsonObject();
                    } catch (RuntimeException ignored) {
                        /* Recover only malformed view preferences. */
                    }
                    for (var key : changed) o.add(key, next.get(key));
                    if (mirrorChanged) {
                        var documents =
                                o.get("documents") instanceof com.google.gson.JsonObject existing
                                        ? existing
                                        : new com.google.gson.JsonObject();
                        var entry =
                                documents.get(documentKey)
                                                instanceof com.google.gson.JsonObject existing
                                        ? existing
                                        : new com.google.gson.JsonObject();
                        for (var field : mirror.entrySet())
                            entry.add(field.getKey(), field.getValue());
                        documents.remove(documentKey);
                        documents.add(documentKey, entry);
                        // Persisted UI data is not a route/model cache: opening another file
                        // must not evict the author's saved mirror coordinates.
                        o.add("documents", documents);
                    }
                    o.remove("bold");
                    com.eynny.npships.config.NPShipsEditorConfig.VIEW.set(o.toString());
                    com.eynny.npships.config.NPShipsEditorConfig.SPEC.save();
                });
        viewPreferencesBaseline = next;
        mirrorPreferencesBaseline = mirror;
        documentViewBaselineKey = documentKey;
    }

    boolean interactionAllowed(Vec3 point) {
        return !firstPerson
                || interactionRange == 129
                || point.distanceToSqr(new Vec3(cameraTarget))
                        <= (double) interactionRange * interactionRange;
    }

    Vec3[] interactionRay(Vec3 from, Vec3 to) {
        if (!firstPerson || interactionRange == 129) return new Vec3[] {from, to};
        var eye = new Vec3(cameraTarget);
        var direction = to.subtract(from).normalize();
        double remaining = Math.max(0, interactionRange - eye.distanceTo(from));
        return new Vec3[] {
            from, from.add(direction.scale(Math.min(remaining, from.distanceTo(to))))
        };
    }

    private void constrainCamera() {
        if (!firstPerson) return;
        var center =
                new Vector3f(draftSize.getX() / 2f, draftSize.getY() / 2f, draftSize.getZ() / 2f);
        float radius =
                new Vector3f(draftSize.getX(), draftSize.getY(), draftSize.getZ()).length() * 5;
        var offset = new Vector3f(cameraTarget).sub(center);
        if (offset.lengthSquared() > radius * radius) {
            cameraTarget.set(center.add(offset.normalize(radius)));
            pickDirty = true;
        }
    }

    private void createReachSlider(int x) {
        reachSlider =
                new EditorSlider(
                        Math.max(left, x - 130),
                        31,
                        180,
                        20,
                        Component.empty(),
                        (interactionRange - 1) / 128d) {
                    {
                        updateMessage();
                    }

                    protected void updateMessage() {
                        setMessage(
                                Component.translatable(
                                        "npships.editor.reach_value",
                                        interactionRange == 129
                                                ? Component.translatable("npships.editor.unlimited")
                                                : Component.literal("" + interactionRange)));
                    }

                    protected void applyValue() {
                        interactionRange = 1 + (int) Math.round(value * 128);
                        pickDirty = true;
                    }
                };
        reachOpen = false;
        reachSlider.visible = false;
        addWidget(reachSlider);
    }

    public enum GridMode {
        FLOOR,
        BACKGROUND,
        OFF
    }

    private GridMode gridMode = GridMode.FLOOR;

    private GridMode nextPhotoGridMode() {
        return switch (gridMode) {
            case BACKGROUND -> GridMode.FLOOR;
            case FLOOR -> GridMode.OFF;
            case OFF -> GridMode.BACKGROUND;
        };
    }

    private void gridMode(GridMode value) {
        gridMode = value;
        if (value != GridMode.OFF) savedGridMode = value;
        snapshotGrid.end();
    }

    private boolean graphPaper = true;
    private final SnapshotGridHandles snapshotGrid = new SnapshotGridHandles();

    private final EditorPopupMenu viewMenu = new EditorPopupMenu();
    private boolean pinGridBounds;
    private double gridFade = 3;
    private boolean blackGrid, pinSchematicBounds;
    boolean symmetricResize;
    int symmetryAxes;
    private int savedSymmetryAxes = 1;
    private double[] symmetryCenters;
    private boolean symmetryVisible, symmetryFlashes = true;
    private int symmetryFlashAxes;
    private long symmetryFlashUntil;
    private int symmetryDrag = -1;
    private double symmetryDragStart, symmetryMouseX, symmetryMouseY;
    private org.joml.Vector2f symmetryDirection;

    BlockPos mirrorSpan() {
        if (symmetryCenters == null)
            symmetryCenters =
                    new double[] {
                        draftSize.getX() / 2d, draftSize.getY() / 2d, draftSize.getZ() / 2d
                    };
        return new BlockPos(
                (int) Math.round(symmetryCenters[0] * 2),
                (int) Math.round(symmetryCenters[1] * 2),
                (int) Math.round(symmetryCenters[2] * 2));
    }

    private boolean pickSymmetry(double x, double y) {
        if (tools == null
                || tools.captureActive()
                || !symmetryVisible
                || selectedSymmetryAxes() == 0
                || editMode != EditMode.SELECT) return false;
        mirrorSpan();
        var matrix = cameraMatrix();
        var ray = tools.ray(x, y, new Matrix4f(matrix).invert(), previewDepth());
        if (ray == null) return false;
        var delta = ray[1].subtract(ray[0]);
        double nearest = Double.POSITIVE_INFINITY;
        int chosen = -1;
        org.joml.Vector2f chosenDirection = null;
        int[] size = {draftSize.getX(), draftSize.getY(), draftSize.getZ()};
        double[] from = {ray[0].x, ray[0].y, ray[0].z};
        double[] direction = {delta.x, delta.y, delta.z};
        for (int axis = 0; axis < 3; axis++) {
            if ((selectedSymmetryAxes() & (1 << axis)) == 0) continue;
            if (Math.abs(direction[axis]) < 1e-9) continue;
            double distance = (symmetryCenters[axis] - from[axis]) / direction[axis];
            if (distance < 0 || distance > 1 || distance >= nearest) continue;
            var hit = ray[0].add(delta.scale(distance));
            double[] point = {hit.x, hit.y, hit.z};
            boolean inside = true;
            for (int along = 0; along < 3; along++)
                if (along != axis && (point[along] < 0 || point[along] > size[along]))
                    inside = false;
            if (!inside) continue;
            // Use the grabbed face point, not coincident plane-centre handles. The nearest
            // visible plane owns overlapping hits; the existing normal-axis drag stays intact.
            var normal = new Vector3f();
            normal.setComponent(axis, 1);
            var p =
                    com.eynny.npships.authoring.geometry.SchematicViewProjection.projectHandle(
                            matrix, hit.toVector3f());
            var q =
                    com.eynny.npships.authoring.geometry.SchematicViewProjection.projectHandle(
                            matrix, hit.toVector3f().add(normal));
            if (!p.isFinite() || !q.isFinite()) continue;
            var projected = new org.joml.Vector2f(q.x - p.x, q.y - p.y);
            if (projected.lengthSquared() < .01f) continue;
            nearest = distance;
            chosen = axis;
            chosenDirection = projected;
        }
        if (chosen < 0) return false;
        symmetryDrag = chosen;
        symmetryDragStart = symmetryCenters[chosen];
        symmetryMouseX = x;
        symmetryMouseY = y;
        symmetryDirection = chosenDirection;
        return true;
    }

    final boolean[] massPlanes = new boolean[3];
    private float brightness = 1;

    private EditorPopupMenu.Entry menuEntry(
            String key, java.util.function.BooleanSupplier on, Runnable action) {
        return new EditorPopupMenu.Entry(Component.translatable(key), on, action);
    }

    void toolMenu(int x, int y, java.util.List<EditorPopupMenu.Entry> entries) {
        if (tools.popupTrigger != null) {
            x = tools.popupTrigger.getX();
            y = tools.popupTrigger.getY();
        }
        inspectorPopupSource = tools.popupTrigger;
        if (extensions != null) extensions.cancelInteraction();
        viewMenu.open(x, width, height, entries);
        viewMenu.within(tools.x, tools.x + tools.w, 9);
        viewMenu.at(y, height);
        viewMenu.triggerWidth(20);
    }

    void toolMenu(int x, int y, int triggerWidth, java.util.List<EditorPopupMenu.Entry> entries) {
        if (triggerWidth <= 32) {
            toolMenu(x, y, entries);
            viewMenu.triggerWidth(triggerWidth);
            return;
        }
        if (tools.popupTrigger != null) {
            x = tools.popupTrigger.getX();
            y = tools.popupTrigger.getY();
            triggerWidth = tools.popupTrigger.getWidth();
        }
        inspectorPopupSource = tools.popupTrigger;
        if (extensions != null) extensions.cancelInteraction();
        viewMenu.openText(x, y, triggerWidth, width, height, entries);
    }

    private GridMode savedGridMode = GridMode.FLOOR;

    private void toggleGrid() {
        if (gridMode == GridMode.OFF) gridMode(savedGridMode);
        else {
            savedGridMode = gridMode;
            gridMode(GridMode.OFF);
        }
    }

    private List<EditorPopupMenu.Entry> gridEntries() {
        var entries = new ArrayList<EditorPopupMenu.Entry>();
        entries.add(
                fileEntry("npships.editor.grid_display", () -> {})
                        .submenu(
                                () -> {
                                    var modes = new ArrayList<EditorPopupMenu.Entry>();
                                    for (var mode : List.of(GridMode.FLOOR, GridMode.BACKGROUND))
                                        modes.add(
                                                menuEntry(
                                                        "npships.editor.grid_mode_"
                                                                + mode.name()
                                                                        .toLowerCase(
                                                                                java.util.Locale
                                                                                        .ROOT),
                                                        () ->
                                                                (gridMode == GridMode.OFF
                                                                                ? savedGridMode
                                                                                : gridMode)
                                                                        == mode,
                                                        () -> {
                                                            savedGridMode = mode;
                                                            if (gridMode != GridMode.OFF)
                                                                gridMode(mode);
                                                        }));
                                    return modes;
                                }));
        entries.add(
                menuEntry(
                        "npships.editor.grid_black",
                        () -> blackGrid,
                        () -> blackGrid = !blackGrid));
        entries.add(
                menuEntry(
                                "npships.editor.grid_paper",
                                () -> graphPaper,
                                () -> graphPaper = !graphPaper)
                        .enabledWhen(() -> gridMode == GridMode.FLOOR));
        if (iconCapture != null)
            entries.add(
                    menuEntry(
                                    "npships.editor.grid_pin",
                                    () -> pinGridBounds,
                                    () -> pinGridBounds = !pinGridBounds)
                            .enabledWhen(() -> gridMode == GridMode.FLOOR));
        entries.add(
                menuEntry("npships.editor.grid_fade", () -> false, () -> {})
                        .submenu(
                                () -> {
                                    var values = new ArrayList<EditorPopupMenu.Entry>();
                                    for (int amount : new int[] {0, 1, 2, 3, 5, 8})
                                        values.add(
                                                new EditorPopupMenu.Entry(
                                                        Component.translatable(
                                                                "npships.editor.grid_fade_cells",
                                                                amount),
                                                        () -> gridFade == amount,
                                                        () -> gridFade = amount));
                                    return values;
                                })
                        .enabledWhen(() -> gridMode == GridMode.FLOOR));
        return entries;
    }

    private void gridMenu(int x) {
        openHeaderMenu(toolbarArrows.get("grid"), gridEntries(), true);
    }

    private void gridButton(int x) {
        var button =
                toolbarIcon(
                                "grid",
                                Component.translatable("npships.editor.grid_menu"),
                                x,
                                8,
                                () -> gridMode(nextPhotoGridMode()))
                        .selected(() -> gridMode != GridMode.OFF);
        button.hint(
                () ->
                        Component.translatable("npships.editor.grid_menu")
                                .append(": ")
                                .append(
                                        Component.translatable(
                                                "npships.editor.grid_mode_"
                                                        + gridMode.name()
                                                                .toLowerCase(
                                                                        java.util.Locale.ROOT))));
        addRenderableWidget(button);
        addRenderableWidget(
                toolbarArrow(
                        "grid",
                        EditorButton.flat(Component.empty(), b -> gridMenu(x + 20))
                                .bounds(x + 20, 8, 7, 20)
                                .build()));
    }

    private List<EditorPopupMenu.Entry> cameraEntries() {
        var entries = new java.util.ArrayList<EditorPopupMenu.Entry>();
        for (int i = 0; i < 4; i++) {
            int preset = i;
            entries.add(
                    menuEntry(
                            "npships.editor.camera_"
                                    + new String[] {"default", "front", "side", "top"}[i],
                            () -> !cycleCamera && cameraPreset == preset,
                            () -> {
                                cycleCamera = false;
                                cameraPreset = preset;
                                resetView();
                            }));
        }
        entries.add(
                new EditorPopupMenu.Entry(
                        Component.translatable("npships.editor.camera_cycle"),
                        () -> cycleCamera,
                        () -> cycleCamera = !cycleCamera,
                        () -> Component.translatable("npships.editor.camera_cycle_hint")));
        entries.add(
                new EditorPopupMenu.Entry(
                        Component.translatable("npships.editor.camera_first_person"),
                        () -> firstPerson,
                        this::toggleFirstPerson,
                        () ->
                                EditorShortcutDispatcher.hint(
                                        Component.translatable(
                                                "npships.editor.camera_first_person_hint"),
                                        EditorShortcutDispatcher.Action.CAMERA)));
        entries.add(
                new EditorPopupMenu.Entry(
                        Component.translatable("npships.editor.camera_look_free"),
                        () -> freeFirstPersonLook,
                        () -> firstPersonLook(true),
                        () -> Component.translatable("npships.editor.camera_look_free_hint")));
        entries.add(
                new EditorPopupMenu.Entry(
                        Component.translatable("npships.editor.camera_look_drag"),
                        () -> !freeFirstPersonLook,
                        () -> firstPersonLook(false),
                        () -> Component.translatable("npships.editor.camera_look_drag_hint")));
        return entries;
    }

    private void cameraMenu(int x) {
        openHeaderMenu(toolbarArrows.get("reset_view"), cameraEntries(), true);
    }

    private void firstPersonLook(boolean free) {
        releaseFirstPersonMouse();
        freeFirstPersonLook = free;
        firstPersonEntryPending = false;
        flightKeys.clear();
        cameraDragButton = -1;
        orbitGesture = false;
        setDragging(false);
        pickDirty = true;
        if (firstPerson && free) captureFirstPersonMouse();
    }

    private int cameraPreset;
    private boolean cycleCamera;
    private boolean firstPerson = false;
    private boolean objectHoverHints;
    private boolean hideOverriddenEntityFields = true;

    boolean hideOverriddenEntityFields() {
        return hideOverriddenEntityFields;
    }

    private void toggleOverriddenEntityFields() {
        if (tools != null && !tools.flushEdits()) return;
        hideOverriddenEntityFields = !hideOverriddenEntityFields;
        if (tools != null) tools.rebuild = true;
        saveViewPreferences();
    }

    private boolean selectionLookSuspended;
    private boolean freeFirstPersonLook = true;
    private boolean firstPersonMouse;
    private boolean firstPersonEntryPending = true;

    /** Only consulted by the native mouse hook during an explicit editor-owned capture. */
    public boolean retainsFirstPersonMouse() {
        return firstPersonMouse
                && freeFirstPersonLook
                && firstPerson
                && minecraft != null
                && minecraft.screen == this;
    }

    private void releaseFirstPersonMouse() {
        if (!firstPersonMouse) return;
        firstPersonMouse = false;
        flightKeys.clear();
        flightFrameNanos = 0;
        heldPaint = false;
        lastPaintEntity = null;
        selecting = false;
        deferredSelectionClick = false;
        deferredInspectionValidated = false;
        deferredAuthoringGesture = false;
        orbitGesture = false;
        if (tools != null && tools.dragging()) tools.cancelDrag();
        cameraDragButton = -1;
        setDragging(false);
        minecraft.mouseHandler.releaseMouse();
        minecraft.mouseHandler.cursorEntered();
    }

    private boolean selectedObject() {
        return selected != null
                || tools != null && (tools.hasSelection() || !tools.inspectedArea.isEmpty());
    }

    private boolean dragFirstPersonLook() {
        return !freeFirstPersonLook || selectedObject();
    }

    private void updateSelectionLook() {
        if (!firstPerson
                || !freeFirstPersonLook
                || minecraft == null
                || minecraft.screen != this
                || !minecraft.isWindowActive()) {
            selectionLookSuspended = false;
            return;
        }
        if (selectedObject()) {
            if (firstPersonMouse) {
                selectionLookSuspended = true;
                releaseFirstPersonMouse();
            }
        } else if (selectionLookSuspended) {
            captureFirstPersonMouse();
            if (firstPersonMouse) selectionLookSuspended = false;
        }
    }

    private void captureFirstPersonMouse() {
        if (!firstPerson
                || !freeFirstPersonLook
                || selectedObject()
                || !layout.sceneUsable()
                || minecraft.screen != this
                || !minecraft.isWindowActive()
                || minecraft.getOverlay() != null
                || shortcutReference.isOpen()
                || extensions != null && (extensions.keyboardFocused() || extensions.modal())
                || viewMenu.isOpen() && !viewMenu.isSuspended()
                || tools != null
                        && (tools.editingText()
                                || tools.keyboardPopup()
                                || tools.expandedWorkspace())) return;
        setFocused((GuiEventListener) null);
        firstPersonMouse = true;
        minecraft.mouseHandler.grabMouse();
        // Native grab polls world keys because it normally closes the Screen. Keeping this
        // editor must retain a Screen's neutral player input even if a flight key is held.
        net.minecraft.client.KeyMapping.releaseAll();
        if (!minecraft.mouseHandler.isMouseGrabbed()) firstPersonMouse = false;
        pickDirty = true;
    }

    /**
     * Native accumulated raw deltas, once per normal mouse update; no GLFW callback replacement.
     */
    public boolean consumeFirstPersonMouse(double dx, double dy) {
        if (minecraft != null && !minecraft.isWindowActive() && tools != null)
            tools.patchRegionTool.cancelGesture();
        if (firstPerson && minecraft != null && !minecraft.isWindowActive() && !firstPersonMouse) {
            selectionLookSuspended = false;
            cameraDragButton = -1;
            orbitGesture = false;
            flightKeys.clear();
            setDragging(false);
            return false;
        }
        boolean captured = retainsFirstPersonMouse();
        updateSelectionLook();
        // Own the transition delta too; neither player look nor the preview may consume it twice.
        if (!retainsFirstPersonMouse()) return captured;
        if (!captured) return true;
        if (!minecraft.isWindowActive()
                || minecraft.getOverlay() != null
                || !minecraft.mouseHandler.isMouseGrabbed()) {
            releaseFirstPersonMouse();
            return true;
        }
        float sensitivity = com.eynny.npships.config.NPShipsEditorConfig.orbit() * .35f;
        yaw += (float) dx * sensitivity;
        pitch = Mth.clamp(pitch + (float) dy * sensitivity, -89, 89);
        pickDirty |= dx != 0 || dy != 0;
        return true;
    }

    private boolean initialViewPending;
    private final java.util.Set<Integer> flightKeys = new java.util.LinkedHashSet<>();

    private boolean flightKey(int key) {
        return key == GLFW.GLFW_KEY_W
                || key == GLFW.GLFW_KEY_A
                || key == GLFW.GLFW_KEY_S
                || key == GLFW.GLFW_KEY_D
                || key == GLFW.GLFW_KEY_SPACE
                || key == GLFW.GLFW_KEY_LEFT_SHIFT
                || key == GLFW.GLFW_KEY_RIGHT_SHIFT;
    }

    private void toggleFirstPerson() {
        if (extensions != null) extensions.cancelInteraction();
        firstPersonEntryPending = false;
        releaseFirstPersonMouse();
        if (!firstPerson) {
            float distance =
                    Math.max(80, layout.viewport().height() * .9f) / (fittedScale() * zoom);
            var offset =
                    new org.joml.Matrix4f()
                            .rotateX((float) Math.toRadians(pitch))
                            .rotateY((float) Math.toRadians(yaw))
                            .invert()
                            .transformDirection(new Vector3f(0, 0, distance));
            cameraTarget.add(offset);
        }
        firstPerson = !firstPerson;
        zonesHeld = false;
        flightFrameNanos = 0;
        flightKeys.clear();
        if (!firstPerson || document.blocks().isEmpty()) resetView();
        pickDirty = true;
        if (firstPerson) captureFirstPersonMouse();
    }

    private long flightFrameNanos;

    private void moveFirstPerson() {
        long now = System.nanoTime();
        float seconds =
                flightFrameNanos == 0
                        ? 0
                        : Math.min(.1f, (now - flightFrameNanos) / 1_000_000_000f);
        flightFrameNanos = now;
        if (inspectorDivider.captured()) return;
        if (shortcutReference.isOpen()) {
            flightKeys.clear();
            return;
        }
        if (!layout.sceneUsable()
                || tools != null && tools.expandedWorkspace()
                || !firstPerson
                || !(firstPersonMouse || dragFirstPersonLook() && cameraDragButton == 1)
                || minecraft == null
                || !minecraft.isWindowActive()
                || keyboardControl()
                || tools != null && (tools.editingText() || tools.keyboardPopup())
                || viewMenu.isOpen() && !viewMenu.isSuspended()) {
            flightKeys.clear();
            return;
        }
        flightKeys.removeIf(
                key ->
                        GLFW.glfwGetKey(minecraft.getWindow().getWindow(), key)
                                == GLFW.GLFW_RELEASE);
        if (hasControlDown()) {
            for (int shortcut :
                    new int[] {
                        GLFW.GLFW_KEY_Z,
                        GLFW.GLFW_KEY_Y,
                        GLFW.GLFW_KEY_C,
                        GLFW.GLFW_KEY_X,
                        GLFW.GLFW_KEY_V
                    })
                if (GLFW.glfwGetKey(minecraft.getWindow().getWindow(), shortcut) == GLFW.GLFW_PRESS)
                    return;
        }
        var motion = new Vector3f();
        // Insertion order records real presses; key repeat does not reorder a held key.
        for (int key : flightKeys) {
            switch (key) {
                case GLFW.GLFW_KEY_A -> motion.x = -1;
                case GLFW.GLFW_KEY_D -> motion.x = 1;
                case GLFW.GLFW_KEY_W -> motion.z = -1;
                case GLFW.GLFW_KEY_S -> motion.z = 1;
                case GLFW.GLFW_KEY_SPACE -> motion.y = 1;
                case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> motion.y = -1;
            }
        }
        if (motion.lengthSquared() > 0) {
            motion.normalize().rotateY((float) Math.toRadians(-yaw));
            cameraTarget.add(
                    motion.mul(10 * nearbyPanScale() * (hasControlDown() ? 3 : 1) * seconds));
            pickDirty = true;
        }
    }

    private void cameraButtons(int x) {
        var reset =
                toolbarIcon(
                        "reset_view",
                        Component.translatable("npships.editor.reset_view"),
                        x,
                        8,
                        this::resetView);
        addRenderableWidget(reset);
        var menu =
                EditorButton.flat(Component.empty(), b -> cameraMenu(x))
                        .bounds(x + 20, 8, 7, 20)
                        .build();
        menu.tooltip(
                () ->
                        EditorShortcutDispatcher.hint(
                                Component.translatable("npships.editor.camera_views"),
                                EditorShortcutDispatcher.Action.CAMERA));
        addRenderableWidget(toolbarArrow("reset_view", menu));
    }

    private AABB gridBounds() {
        return iconCapture == null
                ? new AABB(Vec3.ZERO, Vec3.atLowerCornerOf(draftSize))
                : snapshotGrid.bounds(draftSize);
    }

    private int gridSpacing() {
        double pixels = fittedScale() * zoom;
        if (iconCapture != null)
            pixels *=
                    256d
                            / Math.max(
                                    1,
                                    Math.min(
                                            layout.viewport().width(), layout.viewport().height()));
        return com.eynny.npships.authoring.geometry.SchematicGrid.spacing(
                pixels, graphPaper ? 1 : 2);
    }

    private static final int TITLE_BUTTON_GAP = 10;
    private int titleLeft = 10, titleRight;
    private boolean titleShowsPending;
    private final java.util.Map<Integer, BlockPos> topMenuAnchors = new java.util.HashMap<>();
    private final Map<String, EditorIconButton> toolbarIcons = new LinkedHashMap<>();
    private final Map<String, AbstractWidget> toolbarArrows = new LinkedHashMap<>();
    private final List<EditorLayout.Rect> toolbarSeparators = new ArrayList<>();
    private final List<EditorLayout.Rect> toolbarBackgrounds = new ArrayList<>();

    private static final List<String> PINNED_TOOLBAR_ACTIONS = List.of("save", "back");
    static final List<List<String>> TOOLBAR_GROUPS =
            List.of(
                    List.of("file", "edit", "view"),
                    List.of("undo", "redo"),
                    List.of("mode_select", "mode_create", "mode_delete"),
                    List.of("symmetry", "reset_view", "physics"),
                    PINNED_TOOLBAR_ACTIONS);

    private EditorIconButton toolbarIcon(
            String id, Component label, int x, int y, Runnable action) {
        var button = new EditorIconButton(id, label, x, y, action);
        toolbarIcons.put(id, button);
        return button;
    }

    private EditorButton toolbarArrow(String id, EditorButton button) {
        toolbarArrows.put(id, button);
        button.splitArrow();
        if (id.equals("grid") || id.equals("symmetry"))
            button.tooltip(() -> Component.translatable("npships.editor." + id + "_settings"));
        return button;
    }

    private record ToolbarItem(AbstractWidget main, AbstractWidget arrow) {
        int width() {
            return main.getWidth() + (arrow == null ? 0 : arrow.getWidth());
        }

        void place(int x, int y) {
            main.setX(x);
            main.setY(y);
            if (arrow != null) {
                arrow.setX(x + main.getWidth());
                arrow.setY(y);
            }
        }
    }

    private int layoutTopButtons() {
        topMenuAnchors.clear();
        toolbarSeparators.clear();
        for (var id : List.of("file", "edit", "view")) {
            var button = toolbarIcons.get(id);
            if (button != null)
                button.setWidth(
                        Math.min(font.width(button.getMessage()) + 22, Math.max(20, width - 16)));
        }
        var groups = new ArrayList<List<ToolbarItem>>();
        var pinned = new ArrayList<ToolbarItem>();
        int order = -100;
        for (var ids : TOOLBAR_GROUPS) {
            var group = new ArrayList<ToolbarItem>();
            for (var id : ids) {
                var main = toolbarIcons.get(id);
                if (main == null) continue;
                var arrow = toolbarArrows.get(id);
                main.setTabOrderGroup(order++);
                if (arrow != null) arrow.setTabOrderGroup(order++);
                group.add(new ToolbarItem(main, arrow));
            }
            if (ids.equals(PINNED_TOOLBAR_ACTIONS)) pinned.addAll(group);
            else if (!group.isEmpty()) groups.add(group);
        }
        // Native Init additions keep their callbacks and participate in the same wrapping.
        var extra = new ArrayList<ToolbarItem>();
        for (var widget : headerAnchors.keySet())
            if (!toolbarIcons.containsValue(widget) && !toolbarArrows.containsValue(widget))
                extra.add(new ToolbarItem(widget, null));
        if (!extra.isEmpty()) groups.add(extra);
        int pinnedWidth =
                pinned.stream().mapToInt(ToolbarItem::width).sum()
                        + Math.max(0, pinned.size() - 1) * 4;
        int pinnedLeft = width - 8 - pinnedWidth;
        int pinnedX = pinnedLeft;
        for (var item : pinned) {
            item.place(pinnedX, 8);
            pinnedX += item.width() + 4;
        }
        int firstRight = pinned.isEmpty() ? width - 8 : pinnedLeft - 8;
        var right =
                groups.stream()
                        .flatMap(List::stream)
                        .filter(
                                item ->
                                        List.of("symmetry", "reset_view", "physics").stream()
                                                .anyMatch(
                                                        id -> toolbarIcons.get(id) == item.main()))
                        .toList();
        var leftGroups = new ArrayList<List<ToolbarItem>>();
        for (var group : groups) {
            var items = group.stream().filter(item -> !right.contains(item)).toList();
            if (!items.isEmpty()) leftGroups.add(items);
        }
        int rightWidth =
                right.stream().mapToInt(ToolbarItem::width).sum()
                        + Math.max(0, right.size() - 1) * 4;
        int rightRow = rightWidth <= firstRight - 8 ? 0 : 1;
        int rightLeft = (rightRow == 0 ? firstRight : width - 8) - rightWidth;
        int rx = rightLeft;
        for (var item : right) {
            item.place(rx, 8 + rightRow * 24);
            rx += item.width() + 4;
        }
        int leftWidth =
                leftGroups.stream()
                        .mapToInt(
                                group ->
                                        group.stream().mapToInt(ToolbarItem::width).sum()
                                                + group.size() * 4
                                                + 4)
                        .sum();
        int titleEdge = rightRow == 0 ? rightLeft - 8 : firstRight;
        int titleCapacity = Math.max(0, titleEdge - 8 - leftWidth - TITLE_BUTTON_GAP);
        // A short name takes only its actual width; narrow rows retain an ellipsis hit target.
        int ellipsisRoom =
                Math.min(font.width("\u2026"), Math.max(0, titleEdge - 8 - TITLE_BUTTON_GAP));
        titleShowsPending = toolbarTitlePending();
        int titleRoom =
                Math.min(font.width(toolbarTitleText()), Math.max(ellipsisRoom, titleCapacity));
        titleLeft = 8;
        titleRight = 8 + titleRoom;
        int row = 0, x = titleRoom > 0 ? titleRight + TITLE_BUTTON_GAP : 8;
        int leftGroupIndex = 0;
        for (var group : leftGroups) {
            int groupWidth =
                    group.stream().mapToInt(ToolbarItem::width).sum() + (group.size() - 1) * 4;
            int edge = row == rightRow ? rightLeft - 8 : row == 0 ? firstRight : width - 8;
            if (x + groupWidth > edge && groupWidth <= width - 16) {
                row++;
                x = 8;
            } else if (x > 8 && leftGroupIndex > 0) {
                toolbarSeparators.add(
                        new EditorLayout.Rect(x, 12 + row * 24, x + 1, 24 + row * 24));
                x += 4;
            }
            leftGroupIndex++;
            for (var item : group) {
                edge = row == rightRow ? rightLeft - 8 : row == 0 ? firstRight : width - 8;
                if (x + item.width() > edge && (row == rightRow || row == 0 || x != 8)) {
                    row++;
                    x = 8;
                }
                item.place(x, 8 + row * 24);
                x += item.width() + 4;
            }
        }
        var deleteMode = toolbarIcons.get("mode_delete");
        var mirrorMode = toolbarIcons.get("symmetry");
        if (deleteMode != null
                && mirrorMode != null
                && deleteMode.getY() == mirrorMode.getY()
                && mirrorMode.getX() - deleteMode.getRight() <= 12
                && mirrorMode.getX() - deleteMode.getRight() >= 4) {
            int separatorX = (deleteMode.getRight() + mirrorMode.getX()) / 2;
            toolbarSeparators.add(
                    new EditorLayout.Rect(
                            separatorX,
                            deleteMode.getY() + 4,
                            separatorX + 1,
                            deleteMode.getY() + 16));
        }
        if (!pinned.isEmpty() && pinnedLeft > 12)
            toolbarSeparators.add(new EditorLayout.Rect(pinnedLeft - 5, 12, pinnedLeft - 4, 24));
        row = Math.max(row, rightRow);
        reachAnchor = toolbarIcons.get("edit");
        for (var entry : headerAnchors.entrySet())
            topMenuAnchors.put(
                    entry.getValue(),
                    new BlockPos(entry.getKey().getX(), entry.getKey().getY(), 0));
        return row * 24;
    }

    private int layoutPhotoButtons() {
        var back =
                headerAnchors.keySet().stream()
                        .filter(w -> w.getMessage().equals(Component.translatable("gui.back")))
                        .findFirst()
                        .orElse(null);
        var widgets =
                headerAnchors.keySet().stream()
                        .filter(w -> w != back)
                        .sorted(java.util.Comparator.comparingInt(headerAnchors::get))
                        .toList();
        // Bind widget owners, never translated labels or a glyph's encoding.
        var splits = new java.util.IdentityHashMap<AbstractWidget, AbstractWidget>();
        toolbarArrows.forEach(
                (id, arrow) -> {
                    var main = toolbarIcons.get(id);
                    if (main != null) splits.put(main, arrow);
                });
        if (photoCaptureButton != null && photoSizeArrow != null)
            splits.put(photoCaptureButton, photoSizeArrow);
        var items = new ArrayList<ToolbarItem>();
        for (var main : widgets)
            if (!splits.containsValue(main)) items.add(new ToolbarItem(main, splits.get(main)));
        int total = items.stream().mapToInt(item -> item.width() + 4).sum() + 24;
        int x = Math.max(8, width - total - 8), row = 0;
        titleRight = x - 6;
        for (var item : items) {
            int edge = width - (row == 0 ? 36 : 8);
            if (x + item.width() > edge) {
                x = 8;
                row++;
            }
            item.place(x, 8 + row * 24);
            x += item.width() + 4;
        }
        if (back != null) back.setPosition(width - 28, 8);
        topMenuAnchors.clear();
        for (var entry : headerAnchors.entrySet())
            topMenuAnchors.put(
                    entry.getValue(),
                    new BlockPos(entry.getKey().getX(), entry.getKey().getY(), 0));
        return row * 24;
    }

    private void layoutToolbarBackgrounds() {
        toolbarBackgrounds.clear();
        if (!previewOnly) {
            var rows = new java.util.TreeMap<Integer, EditorLayout.Rect>();
            for (var widget : headerAnchors.keySet()) {
                if (!widget.visible) continue;
                int y = widget.getY();
                var before = rows.get(y);
                rows.put(
                        y,
                        new EditorLayout.Rect(
                                before == null
                                        ? widget.getX() - 2
                                        : Math.min(before.left(), widget.getX() - 2),
                                y - 2,
                                before == null
                                        ? widget.getX() + widget.getWidth() + 2
                                        : Math.max(
                                                before.right(),
                                                widget.getX() + widget.getWidth() + 2),
                                y + 22));
            }
            for (var row : rows.values())
                toolbarBackgrounds.add(row.intersect(layout.documentToolbar()));
        }
    }

    private void renderToolbar(GuiGraphics graphics) {
        if (previewOnly) return;
        if (EditorIconButton.currentStyle() == EditorIconButton.Style.TOOLBAR)
            for (var row : toolbarBackgrounds)
                row.fill(graphics, EditorButtonTheme.surface(0xff202e3c));
        int color =
                EditorButtonTheme.mix(
                        EditorButtonTheme.background(), EditorButtonTheme.edge(), .55f);
        for (var separator : toolbarSeparators)
            separator.intersect(layout.documentToolbar()).fill(graphics, color);
    }

    private boolean toolbarTitlePending() {
        return dirty || tools != null && tools.hasPendingEdits();
    }

    private String toolbarTitleText() {
        return (toolbarTitlePending() ? "* " : "") + title.getString();
    }

    private Component toolbarTitleHint(double mouseX, double mouseY) {
        String visible = toolbarTitle();
        return !visible.equals(toolbarTitleText())
                        && mouseX >= titleLeft
                        && mouseX < titleLeft + font.width(visible)
                        && mouseY >= 12
                        && mouseY < 12 + font.lineHeight
                ? title
                : null;
    }

    private String toolbarTitle() {
        String text = toolbarTitleText();
        int space =
                Math.max(
                        0,
                        previewOnly && iconCapture == null ? width - 154 : titleRight - titleLeft);
        if (font.width(text) <= space) return text;
        String ellipsis = "\u2026";
        return space < font.width(ellipsis)
                ? ""
                : font.plainSubstrByWidth(text, space - font.width(ellipsis)) + ellipsis;
    }

    private void topMenu(int anchor, java.util.List<EditorPopupMenu.Entry> entries) {
        var p = topMenuAnchors.getOrDefault(anchor, new BlockPos(anchor, 8, 0));
        if (extensions != null) extensions.cancelInteraction();
        viewMenu.open(p.getX(), width, height, entries);
        var source =
                headerAnchors.entrySet().stream()
                        .filter(e -> e.getValue() == anchor)
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
        viewMenu.triggerWidth(source == null ? 20 : source.getWidth());
        viewMenu.at(p.getY(), height);
    }

    private HeightSlider slider;
    private Component renderError;
    private long previewResourceEpoch =
            com.eynny.npships.client.editor.render.PreviewResources.epoch();
    private double lastMouseX = Double.NaN, lastMouseY = Double.NaN;
    private boolean pickDirty = true;
    private int infoScroll, infoHeight, preferredInfoScroll;
    private final EditorScrollBar infoBar = new EditorScrollBar();
    private BlockPos infoPosition;
    private int infoEntity = -1;

    public SchematicEditorScreen(
            final Screen parent,
            final java.nio.file.Path directory,
            final String name,
            final SchematicDocument document,
            final Level world) {
        this(parent, directory, name, document, world, !document.hasConfig());
    }

    private SchematicEditorScreen(
            final Screen parent,
            final java.nio.file.Path directory,
            final String name,
            final SchematicDocument document,
            final Level world,
            final boolean previewOnly) {
        super(Component.literal(name));
        this.parent = parent;
        this.diskDocument = document;
        initialLayers = com.eynny.npships.authoring.document.BlockAreaLayers.load(document);
        this.previewOnly = previewOnly;
        this.directory = directory;
        filename = name;
        working =
                com.eynny.npships.authoring.patches.PatchDocuments.normalize(
                                initialLayers.compose(), world)
                        .inheritPatchValidation(document);
        // Reject unsafe contracts before constructing preview/editor owners or replacing a Screen.
        com.eynny.npships.authoring.patches.PatchDocuments.admitEditor(working);
        draft = working.configCopy();
        draftSize = working.size();
        this.document = working;
        dirty = false;
        cacheLootLabels();
        ceiling = draftSize.getY() - 1;
        rebuildScene(world);
        loadViewPreferences();
        // Capture before resetView advances an intentionally persisted cycle preset.
        viewPreferencesBaseline = viewPreferenceFields();
        mirrorPreferencesBaseline = mirrorPreferenceFields();
        documentViewBaselineKey = documentViewKey();
        resetView();
    }

    private void rebuildScene(Level world) {
        if (renderer != null) renderer.close();
        var document = working;
        level = new SchematicPreviewLevel(document, world);
        validationLevel = new SchematicPreviewLevel(document, world);
        supportWarnings.clear();
        validationQueue = document.blocks().keySet().iterator();
        renderer = new SchematicPreviewRenderer(level);
        if (tools != null) tools.rendererReset();
        updateObjectLists();
    }

    private void updateObjectLists() {
        updateObjectLists(null);
    }

    private void updateObjectLists(java.util.Set<BlockPos> changed) {
        java.util.Set<BlockPos> positions;
        if (changed == null) positions = new java.util.HashSet<>(level.getAllPositions());
        else {
            boolean affected = false;
            for (var p : changed)
                if (objects.contains(p) || level.originalState(p).hasBlockEntity()) {
                    affected = true;
                    break;
                }
            if (!affected) return;
            positions = new java.util.HashSet<>(objects);
            positions.addAll(changed);
        }
        objects =
                positions.stream()
                        .filter(pos -> level.originalState(pos).hasBlockEntity())
                        .sorted(
                                java.util.Comparator.<BlockPos>comparingInt(BlockPos::getY)
                                        .thenComparingInt(BlockPos::getX)
                                        .thenComparingInt(BlockPos::getZ))
                        .toList();
        // Native resource availability also depends on each block entity's saved contents.
        containers =
                objects.stream()
                        .filter(
                                pos -> {
                                    try {
                                        return com.eynny.npships.authoring.patches
                                                        .SchematicResources.open(
                                                                current(), pos, level)
                                                        .size()
                                                > 0;
                                    } catch (Exception e) {
                                        com.mojang.logging.LogUtils.getLogger()
                                                .warn(
                                                        "[NPShips editor] Cannot inspect loot support for {} at {}",
                                                        level.originalState(pos),
                                                        pos,
                                                        e);
                                        return false;
                                    }
                                })
                        .toList();
    }

    private EditorLayout layout;
    int preferredInspectorWidth = com.eynny.npships.config.NPShipsEditorConfig.inspectorWidth();
    private int extraToolbarHeight;
    private final EditorInspectorDivider inspectorDivider = new EditorInspectorDivider(this);
    private AbstractWidget inspectorPopupSource;

    /** Re-anchor inspector popups without screen/camera reconstruction or dismissal of choices. */
    void inspectorPresentation(Runnable arrange) {
        if (!viewMenu.isOpen()) {
            inspectorPopupSource = null;
            arrange.run();
            return;
        }
        var source = inspectorPopupSource;
        if (source != null && tools != null && !tools.widgets.contains(source)) {
            viewMenu.close();
            inspectorPopupSource = null;
            arrange.run();
            return;
        }
        if (source != null
                && !viewMenu.isSuspended()
                && (source.getX() != viewMenu.anchorX() || source.getY() != viewMenu.anchorY()))
            source = null;
        if (source == null || tools == null) source = popupSource();
        arrange.run();
        if (source != null && tools != null && tools.widgets.contains(source)) {
            inspectorPopupSource = source;
            viewMenu.suspend(!source.visible);
            var anchor = tools.sections.scrollAnchor(source);
            viewMenu.updateTextTriggerWidth(source.getWidth());
            viewMenu.reanchor(anchor.getX(), anchor.getY(), width, height);
            viewMenu.within(tools.x, tools.x + tools.w, 9);
            viewMenu.at(anchor.getY(), height);
        } else {
            inspectorPopupSource = null;
            viewMenu.suspend(false);
        }
    }

    private AbstractWidget popupSource() {
        var nativeWidgets =
                children().stream()
                        .filter(AbstractWidget.class::isInstance)
                        .map(AbstractWidget.class::cast);
        var panelWidgets =
                tools == null
                        ? java.util.stream.Stream.<AbstractWidget>empty()
                        : java.util.stream.Stream.concat(
                                tools.toolbar.stream(), tools.widgets.stream());
        return java.util.stream.Stream.concat(nativeWidgets, panelWidgets)
                .filter(
                        widget ->
                                widget.getX() == viewMenu.anchorX()
                                        && widget.getY() == viewMenu.anchorY())
                .findFirst()
                .orElse(null);
    }

    private void applyRetainedLayout(AbstractWidget popupSource) {
        authoringCycle.reset();
        placeScreenControls();
        if (tools != null) {
            if (previewOnly) tools.previewLayout(layout);
            else tools.layoutOnly(layout);
        }
        if (popupSource != null) {
            viewMenu.updateTextTriggerWidth(popupSource.getWidth());
            viewMenu.reanchor(popupSource.getX(), popupSource.getY(), width, height);
            if (headerAnchors.containsKey(popupSource)) viewMenu.suspend(!popupSource.visible);
            if (tools != null
                    && (tools.widgets.contains(popupSource)
                            || tools.toolbar.contains(popupSource))) {
                viewMenu.within(tools.x, tools.x + tools.w, 9);
                viewMenu.at(popupSource.getY(), height);
            }
        } else viewMenu.reposition(width, height);
        inverse = cameraMatrix().invert();
        pickDirty = true;
        if (extensions != null) extensions.relayout();
    }

    /** Presentation only: native Init hooks and editing/geometry callbacks are not resize work. */
    void reflowInspector(int preferredWidth) {
        if (tools != null) tools.patchRegionTool.cancelGesture();
        preferredInspectorWidth = Math.max(0, preferredWidth);
        if (layout == null) return;
        var previous = layout;
        var anchor = popupSource();
        updateLayout(extraToolbarHeight);
        if (!layout.equals(previous)) applyRetainedLayout(anchor);
    }

    private boolean dividerAvailable(double x, double y) {
        return !previewOnly
                && !shortcutReference.isOpen()
                && minecraft.isWindowActive()
                && !viewMenu.contains(x, y)
                && (tools == null || !tools.overlayAt(x, y))
                && !(reachSlider != null && reachSlider.visible && reachSlider.isMouseOver(x, y))
                && cameraDragButton < 0
                && symmetryDrag < 0
                && !reachDragging
                && (!selecting || choosingCorners)
                && !heldPaint
                && !infoBar.dragging()
                && (tools == null || !tools.captureActive())
                && layout.divider().contains(x, y);
    }

    private void dividerFocus() {
        shortcuts.focus();
        if (!minecraft.isWindowActive()) {
            flightKeys.clear();
            zonesHeld = false;
            authoringCycle.reset();
        }
        if (minecraft != null && !minecraft.isWindowActive()) inspectorDivider.cancel();
    }

    @Override
    public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
        if (tools != null) tools.patchRegionTool.cancelGesture();
        inspectorDivider.cancel();
        super.resize(minecraft, width, height);
    }

    EditorLayout layout() {
        return layout;
    }

    private void updateLayout(int extraToolbar) {
        extraToolbarHeight = extraToolbar;
        var next =
                EditorLayout.calculate(
                        width,
                        height,
                        previewOnly,
                        previewSource != null,
                        isVariant() || reviewAccept != null,
                        extraToolbar,
                        preferredInspectorWidth,
                        font == null ? 9 : font.lineHeight);
        // Equal geometry skips arrangement; keep Screen and Panel on the same snapshot.
        if (!next.equals(layout)) layout = next;
        left = layout.viewport().left();
        right = layout.viewport().right();
        top = layout.viewport().top();
        bottom = layout.viewport().bottom();
        sideWidth = layout.inspector().width();
        infoTop = layout.information().top();
    }

    private final java.util.Set<GuiEventListener> ownedScreenChildren =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private void rememberScreenChildren(java.util.List<GuiEventListener> before) {
        ownedScreenChildren.clear();
        ownedScreenChildren.addAll(children());
        ownedScreenChildren.removeAll(before);
        headerAnchors.keySet().retainAll(ownedScreenChildren);
        screenPlacements.clear();
        for (var child : ownedScreenChildren)
            if (child instanceof AbstractWidget widget
                    && !headerAnchors.containsKey(widget)
                    && widget != slider
                    && widget != sliceButton
                    && widget != reachSlider)
                screenPlacements.put(
                        widget,
                        EditorWidgetArrangement.Binding.capture(
                                widget, importMode ? layout.inspector() : layout.viewport(), 0));
        placeScreenControls();
    }

    private final Map<AbstractWidget, Integer> headerAnchors = new LinkedHashMap<>();
    private int headerWidth;
    private AbstractWidget sliceButton;
    private final Map<AbstractWidget, EditorWidgetArrangement.Binding> screenPlacements =
            new java.util.IdentityHashMap<>();

    private void placeScreenControls() {
        for (var entry : screenPlacements.entrySet()) {
            var widget = entry.getKey();
            entry.getValue()
                    .apply(importMode ? layout.inspector() : layout.viewport(), 0, importMode);
            widget.visible =
                    containsWidget(layout.documentToolbar(), widget)
                            || containsWidget(layout.viewport(), widget)
                            || importMode && containsWidget(layout.inspector(), widget);
        }
        if (slider != null) {
            int modeWidth = Math.min(108, layout.modeControls().width() / 2);
            slider.setX(layout.modeControls().left());
            slider.setY(layout.viewportFooter().top());
            slider.setWidth(Math.max(0, layout.modeControls().width() - modeWidth - 4));
            slider.visible = containsWidget(layout.modeControls(), slider) && layout.sceneUsable();
            if (sliceButton != null) {
                if (sliceButton instanceof EditorButton b) b.tooltip(this::sliceShortcutHint);
                sliceButton.setX(layout.modeControls().right() - modeWidth);
                sliceButton.setY(slider.getY());
                sliceButton.setWidth(modeWidth);
                sliceButton.visible =
                        containsWidget(layout.modeControls(), sliceButton) && layout.sceneUsable();
            }
        }
        for (var widget : headerAnchors.keySet())
            widget.visible = containsWidget(layout.documentToolbar(), widget);
        if (!previewOnly && !importMode)
            for (var entry : toolbarArrows.entrySet()) {
                var main = toolbarIcons.get(entry.getKey());
                boolean visible = main.visible && entry.getValue().visible;
                main.visible = entry.getValue().visible = visible;
            }
        layoutToolbarBackgrounds();
        layoutReachSlider();
    }

    private static boolean containsWidget(EditorLayout.Rect rect, AbstractWidget widget) {
        return widget.getWidth() > 0
                && widget.getHeight() > 0
                && widget.getX() >= rect.left()
                && widget.getY() >= rect.top()
                && widget.getX() + widget.getWidth() <= rect.right()
                && widget.getY() + widget.getHeight() <= rect.bottom();
    }

    private void rememberHeaderAnchors() {
        headerAnchors.clear();
        headerWidth = width;
        for (var child : children())
            if (child instanceof AbstractWidget widget && widget.getY() == 8)
                headerAnchors.put(widget, widget.getX());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void repositionElements() {
        if (layout == null) {
            super.repositionElements();
            return;
        }
        // Hook-added widgets keep native Init lifecycle; editor-owned controls retain identity.
        for (var child : java.util.List.copyOf(children()))
            if (!ownedScreenChildren.contains(child)) {
                if (getFocused() == child) setFocused(null);
                removeWidget(child);
            }
        var popupSource = popupSource();
        var added = new ArrayList<GuiEventListener>();
        Consumer<GuiEventListener> add =
                widget -> {
                    if (children().contains(widget)) return;
                    if (widget instanceof Renderable renderable) addRenderableOnly(renderable);
                    if (widget instanceof NarratableEntry narration)
                        addWidget(
                                (GuiEventListener
                                                & net.minecraft.client.gui.narration
                                                        .NarratableEntry)
                                        widget);
                    else
                        ((List<net.minecraft.client.gui.components.events.GuiEventListener>)
                                        children())
                                .add(widget);
                    added.add(widget);
                };
        if (!NeoForge.EVENT_BUS
                .post(
                        new ScreenEvent.Init.Pre(
                                this,
                                (List<net.minecraft.client.gui.components.events.GuiEventListener>)
                                        children(),
                                add,
                                this::removeWidget))
                .isCanceled()) {
            for (var entry : headerAnchors.entrySet()) {
                entry.getKey().setX(entry.getValue() + width - headerWidth);
                entry.getKey().setY(8);
            }
            titleLeft = 10;
            int extra = previewOnly ? layoutPhotoButtons() : importMode ? 0 : layoutTopButtons();
            for (var entry : headerAnchors.entrySet())
                topMenuAnchors.put(
                        entry.getValue(),
                        new BlockPos(entry.getKey().getX(), entry.getKey().getY(), 0));
            if ((previewOnly || importMode) && extra == 0 && !headerAnchors.isEmpty())
                titleRight =
                        headerAnchors.keySet().stream().mapToInt(v -> v.getX()).min().orElse(102)
                                - 6;
            updateLayout(extra);
            // Native Screen.init reuses repositionElements after a child Screen returns.
            // Retain native controls/drafts, but never revive the retired addon session.
            if (extensions == null) {
                extensions = new EditorExtensionHost(this, !previewOnly && !importMode);
                extensions.init();
            }
            applyRetainedLayout(popupSource);
        }
        NeoForge.EVENT_BUS.post(
                new ScreenEvent.Init.Post(
                        this,
                        (List<net.minecraft.client.gui.components.events.GuiEventListener>)
                                children(),
                        add,
                        this::removeWidget));
    }

    @Override
    protected void init() {
        shortcuts.focus();
        inspectorDivider.cancel();
        var hookChildren = new java.util.ArrayList<GuiEventListener>(children());
        viewMenu.close();
        topMenuAnchors.clear();
        toolbarIcons.clear();
        toolbarArrows.clear();
        photoCaptureButton = photoSizeArrow = null;
        toolbarSeparators.clear();
        updateLayout(0);
        if (previewOnly) {
            initPreview();
            if (extensions == null) extensions = new EditorExtensionHost(this, false);
            extensions.init();
            rememberHeaderAnchors();
            updateLayout(layoutPhotoButtons());
            if (initialViewPending) resetView();
            rememberScreenChildren(hookChildren);
            return;
        }
        int sx = layout.inspector().left();
        if (tools == null) tools = new EditorToolsPanel(this);
        tools.patchEditors.activate();
        if (extensions == null) extensions = new EditorExtensionHost(this, !importMode);
        int bx = width - 28;
        addRenderableWidget(
                toolbarIcon("back", Component.translatable("gui.back"), bx, 8, this::onClose));
        bx -= 24;
        saveButton =
                addRenderableWidget(
                        toolbarIcon(
                                "save",
                                Component.translatable("npships.loot.save"),
                                bx,
                                8,
                                this::save));
        bx -= 24;
        redoButton =
                addRenderableWidget(
                        toolbarIcon(
                                "redo",
                                Component.translatable("npships.editor.redo"),
                                bx,
                                8,
                                () -> tools.toolbarAction(() -> restoreHistory(true))));
        bx -= 24;
        undoButton =
                addRenderableWidget(
                        toolbarIcon(
                                "undo",
                                Component.translatable("npships.editor.undo"),
                                bx,
                                8,
                                () -> tools.toolbarAction(() -> restoreHistory(false))));
        bx -= 24;
        final int symmetryMenuX = bx;
        addRenderableWidget(
                toolbarIcon(
                                "symmetry",
                                Component.translatable("npships.editor.symmetry"),
                                bx,
                                8,
                                () -> {
                                    if (symmetryAxes != 0) {
                                        savedSymmetryAxes = symmetryAxes;
                                        symmetryAxes = 0;
                                    } else symmetryAxes = savedSymmetryAxes;
                                })
                        .selected(() -> symmetryAxes != 0));
        addRenderableWidget(
                toolbarArrow(
                        "symmetry",
                        EditorButton.flat(
                                        Component.empty(),
                                        button -> {
                                            var entries =
                                                    new java.util.ArrayList<
                                                            EditorPopupMenu.Entry>();
                                            for (int i = 0; i < 3; i++) {
                                                int mask = 1 << i;
                                                entries.add(
                                                        new EditorPopupMenu.Entry(
                                                                Component.literal(
                                                                        "XYZ".substring(i, i + 1)),
                                                                () ->
                                                                        (selectedSymmetryAxes()
                                                                                        & mask)
                                                                                != 0,
                                                                () -> toggleSymmetryAxis(mask)));
                                            }
                                            entries.add(
                                                    new EditorPopupMenu.Entry(
                                                            Component.translatable(
                                                                    "npships.editor.symmetry_planes"),
                                                            () -> symmetryVisible,
                                                            () ->
                                                                    symmetryVisible =
                                                                            !symmetryVisible));
                                            entries.add(
                                                    new EditorPopupMenu.Entry(
                                                            Component.translatable(
                                                                    "npships.editor.symmetry_flash"),
                                                            () -> symmetryFlashes,
                                                            () ->
                                                                    symmetryFlashes =
                                                                            !symmetryFlashes));
                                            topMenu(symmetryMenuX + 20, entries);
                                        })
                                .bounds(bx + 20, 8, 7, 20)
                                .build()));
        bx -= 24;

        bx -= 24;
        cameraButtons(bx);
        bx -= 32;
        addRenderableWidget(
                toolbarIcon(
                                "physics",
                                Component.translatable("npships.tools.physics"),
                                bx,
                                8,
                                () -> tools.toggleMass())
                        .selected(() -> tools.showingMass()));
        addRenderableWidget(
                toolbarArrow(
                        "physics",
                        EditorButton.flat(Component.empty(), v -> massMenu())
                                .bounds(bx + 20, 8, 7, 20)
                                .build()
                                .tooltip(
                                        () ->
                                                Component.translatable(
                                                        "npships.editor.mass_settings"))));
        bx -= 32;
        createReachSlider(bx);
        bx -= 24;
        for (var mode : EditMode.values()) {
            bx -= 24;
            var target = mode;
            var modeButton =
                    addRenderableWidget(
                            toolbarIcon(
                                            "mode_"
                                                    + mode.name()
                                                            .toLowerCase(java.util.Locale.ROOT),
                                            Component.translatable(
                                                    "npships.editor.mode_"
                                                            + mode.name()
                                                                    .toLowerCase(
                                                                            java.util.Locale.ROOT)),
                                            bx,
                                            8,
                                            () -> editMode(target))
                                    .selected(() -> editMode == target));
            EditorShortcutDispatcher.hint(
                    modeButton,
                    switch (mode) {
                        case SELECT -> EditorShortcutDispatcher.Action.SELECT;
                        case CREATE -> EditorShortcutDispatcher.Action.CREATE;
                        case DELETE -> EditorShortcutDispatcher.Action.DELETE_MODE;
                    });
            if (mode == EditMode.CREATE)
                modeButton.hint(
                        () -> {
                            var hint =
                                    EditorShortcutDispatcher.hint(
                                            modeButton.baseHint(),
                                            EditorShortcutDispatcher.Action.CREATE);
                            return tools != null && tools.blocksTool()
                                    ? hint.copy().append("\n").append(brushRotationHelp())
                                    : hint;
                        });
        }

        for (String menu : List.of("file", "edit", "view")) {
            bx -= 60;
            String id = menu;
            addRenderableWidget(
                    toolbarIcon(
                                    id,
                                    Component.translatable("npships.editor." + id),
                                    bx,
                                    8,
                                    () -> {
                                        switch (id) {
                                            case "file" -> fileMenu();
                                            case "edit" -> editMenu();
                                            case "view" -> viewOptionsMenu();
                                        }
                                    })
                            .dropdownTextButton(60));
        }
        titleRight = bx - 6;
        rememberHeaderAnchors();
        int extraToolbar = importMode ? 0 : layoutTopButtons();
        updateLayout(extraToolbar);
        if (initialCamera) {
            resetView();
            initialCamera = false;
        }
        if (extensions != null) extensions.init();
        tools.init(layout);
        if (isVariant()) {
            addRenderableWidget(
                                    new EditorIconButton(
                                            "turn_left",
                                            Component.translatable("npships.variants.previous"),
                                            8,
                                            34 + extraToolbar,
                                            () -> navigateVariant(previousVariant)))
                            .active =
                    previousVariant != null;
            addRenderableWidget(
                    new EditorIconButton(
                            "segments",
                            Component.translatable("npships.variants.gallery"),
                            32,
                            34 + extraToolbar,
                            () -> navigateVariant(variantGallery)));
            addRenderableWidget(
                                    new EditorIconButton(
                                            "turn_right",
                                            Component.translatable("npships.variants.next"),
                                            56,
                                            34 + extraToolbar,
                                            () -> navigateVariant(nextVariant)))
                            .active =
                    nextVariant != null;
        }
        int modeWidth = Math.min(108, (right - left) / 2);
        slider =
                addRenderableWidget(
                        new HeightSlider(
                                left, layout.viewportFooter().top(), right - left - modeWidth - 4));
        sliceButton =
                addRenderableWidget(
                        EditorButton.flat(sliceLabel(), this::sliceMenu)
                                .bounds(
                                        right - modeWidth,
                                        layout.viewportFooter().top(),
                                        modeWidth,
                                        20)
                                .build());
        if (importMode) {
            clearWidgets();
            slider =
                    addRenderableWidget(
                            new HeightSlider(
                                    left,
                                    layout.viewportFooter().top(),
                                    right - left - modeWidth - 4));
            sliceButton =
                    addRenderableWidget(
                            EditorButton.flat(sliceLabel(), this::sliceMenu)
                                    .bounds(
                                            right - modeWidth,
                                            layout.viewportFooter().top(),
                                            modeWidth,
                                            20)
                                    .build());
            addRenderableWidget(
                    EditorButton.flat(
                                    Component.translatable("npships.tools.accept"),
                                    b -> {
                                        try {
                                            minecraft.setScreen(parent);
                                            if (importAreaAccept != null)
                                                importAreaAccept.accept(
                                                        new com.eynny.npships.authoring.document
                                                                .SchematicRegions.Region(
                                                                BlockPos.containing(
                                                                        special.minX,
                                                                        special.minY,
                                                                        special.minZ),
                                                                BlockPos.containing(
                                                                        special.getXsize(),
                                                                        special.getYsize(),
                                                                        special.getZsize())));
                                            else
                                                importAccept.accept(
                                                        BlockPos.containing(
                                                                special.minX,
                                                                special.minY,
                                                                special.minZ));
                                        } catch (Exception e) {
                                            minecraft.setScreen(this);
                                            error(EditorErrorText.message(e));
                                        }
                                    })
                            .bounds(sx, top + 4, sideWidth, 20)
                            .build());
            addRenderableWidget(
                    EditorButton.flat(
                                    Component.translatable("gui.cancel"),
                                    b -> minecraft.setScreen(parent))
                            .bounds(sx, top + 28, sideWidth, 20)
                            .build());
        }
        if (importMode && importResizable) {
            addRenderableWidget(
                    EditorButton.flat(
                                    Component.translatable("npships.tools.area_move"),
                                    b -> importMoving = !importMoving)
                            .bounds(sx, top + 52, sideWidth, 20)
                            .build()
                            .selected(() -> importMoving));
            addRenderableWidget(
                    new EditorIconButton(
                                    "mode_select",
                                    Component.translatable("npships.editor.mode_select"),
                                    8,
                                    8,
                                    () -> editMode(EditMode.SELECT))
                            .selected(() -> editMode == EditMode.SELECT));
            addRenderableWidget(
                    new EditorIconButton(
                                    "mode_create",
                                    Component.translatable("npships.editor.mode_create"),
                                    32,
                                    8,
                                    () -> editMode(EditMode.CREATE))
                            .selected(() -> editMode == EditMode.CREATE));
        }
        if (reviewAccept != null) {
            addRenderableWidget(
                    EditorButton.flat(Component.translatable("npships.tools.accept"), b -> save())
                            .bounds(8, 34 + extraToolbar, 140, 20)
                            .build());
            addRenderableWidget(
                    EditorButton.flat(Component.translatable("gui.cancel"), b -> reviewCancel.run())
                            .bounds(152, 34 + extraToolbar, 140, 20)
                            .build());
        }
        updateSlice();
        pickDirty = true;
        refreshActions();
        var importConfig = working.configCopy();
        String reportKey = com.eynny.npships.authoring.io.ContraptionImport.REPORT;
        if (importConfig != null && importConfig.has(reportKey)) {
            var details = new java.util.ArrayList<String>();
            for (var value : importConfig.getAsJsonArray(reportKey)) {
                var report = value.getAsJsonObject();
                details.add(com.eynny.npships.authoring.io.ContraptionImport.diagnostic(report));
            }
            status(
                    Component.translatable(
                                    "npships.import.disassembled", String.join("; ", details))
                            .getString());
        }
        if (initialViewPending) resetView();
        rememberScreenChildren(hookChildren);
    }

    boolean containsDraft(BlockPos p) {
        return p.getX() >= 0
                && p.getY() >= 0
                && p.getZ() >= 0
                && p.getX() < draftSize.getX()
                && p.getY() < draftSize.getY()
                && p.getZ() < draftSize.getZ();
    }

    private Component sliceLabel() {
        return Component.translatable(
                "npships.editor.slice_mode."
                        + new String[] {"below", "single", "above", "left", "vertical", "right"}
                                [sliceMode]);
    }

    private int sliceSize() {
        return sliceMode < 3 ? draftSize.getY() : draftSize.getX();
    }

    private EditorPopupMenu.Entry fileEntry(String key, Runnable action) {
        return new EditorPopupMenu.Entry(
                Component.translatable(key),
                () -> false,
                action,
                () ->
                        Component.translatable(
                                net.minecraft.client.resources.language.I18n.exists(key + "_hint")
                                        ? key + "_hint"
                                        : key));
    }

    private void fileMenu() {
        var button = toolbarIcons.get("file");
        if (button == null) return;
        var rename =
                fileEntry(
                        "npships.editor.rename",
                        () -> {
                            if (!isPart()) renameFile();
                        });
        if (extensions != null) extensions.cancelInteraction();
        viewMenu.openText(
                button.getX(),
                button.getY(),
                button.getWidth(),
                width,
                height,
                List.of(
                        fileEntry("npships.editor.import_area", this::importArea),
                        fileEntry("npships.editor.config", this::editConfig),
                        rename));
        viewMenu.availableWhen(entry -> entry != rename || !isPart());
        // Short text triggers need readable action labels; long text fields retain exact width.
        viewMenu.fitTextContents(width, height);
    }

    private void massMenu() {
        var button = toolbarArrows.get("physics");
        if (button == null) return;
        var entries = new ArrayList<EditorPopupMenu.Entry>();
        entries.add(
                new EditorPopupMenu.Entry(
                        Component.translatable("npships.tools.physics_region"),
                        tools::choosingMassRegion,
                        tools::chooseMassRegion));
        for (int i = 0; i < 3; i++) {
            int axis = i;
            entries.add(
                    new EditorPopupMenu.Entry(
                            Component.literal(new String[] {"YZ", "XZ", "XY"}[i]),
                            () -> massPlanes[axis],
                            () -> {
                                massPlanes[axis] = !massPlanes[axis];
                                if (massPlanes[axis]
                                        && !tools.showingMass()
                                        && !tools.choosingMassRegion()) tools.toggleMass();
                            }));
        }
        if (extensions != null) extensions.cancelInteraction();
        viewMenu.openText(button.getX(), button.getY(), button.getWidth(), width, height, entries);
        viewMenu.iconTrigger(width, height);
    }

    private List<EditorPopupMenu.Entry> paletteEntries() {
        var entries = new ArrayList<EditorPopupMenu.Entry>();
        for (var palette : EditorButtonTheme.Palette.values())
            entries.add(
                    new EditorPopupMenu.Entry(
                            Component.translatable(
                                    "npships.tools.palette_"
                                            + palette.name().toLowerCase(java.util.Locale.ROOT)),
                            () -> EditorButtonTheme.current() == palette,
                            () -> {
                                if (iconCapture != null) snapshotPalette = palette;
                                else EditorButtonTheme.use(palette);
                            }));
        return entries;
    }

    private List<EditorPopupMenu.Entry> helpEntries() {
        return List.of(
                new EditorPopupMenu.Entry(
                        Component.translatable("npships.tools.code_auto"),
                        LuaEditBox::automaticSuggestions,
                        () -> tools.toggleCodeSuggestions(),
                        () -> Component.translatable("npships.tools.text_settings")),
                new EditorPopupMenu.Entry(
                        Component.translatable("npships.editor.object_hints"),
                        () -> objectHoverHints,
                        () -> {
                            objectHoverHints = !objectHoverHints;
                            saveViewPreferences();
                        },
                        () -> Component.translatable("npships.editor.object_hints_hint")),
                new EditorPopupMenu.Entry(
                        Component.translatable("npships.editor.hide_overridden_data"),
                        () -> hideOverriddenEntityFields,
                        this::toggleOverriddenEntityFields,
                        () -> Component.translatable("npships.editor.hide_overridden_data_hint")),
                fileEntry("npships.editor.keys.reference_title", this::openShortcutReference));
    }

    private void openHeaderMenu(
            AbstractWidget owner, List<EditorPopupMenu.Entry> entries, boolean icon) {
        if (owner == null) return;
        if (extensions != null) extensions.cancelInteraction();
        viewMenu.openText(owner.getX(), owner.getY(), owner.getWidth(), width, height, entries);
        viewMenu.fitTextContents(width, height);
        if (icon) viewMenu.iconTrigger(width, height);
    }

    private void editMenu() {
        openHeaderMenu(
                toolbarIcons.get("edit"),
                List.of(
                        menuEntry("npships.editor.resize", () -> resizeMode, this::resizeDocument)
                                .enabledWhen(() -> !isPart()),
                        fileEntry(
                                        "npships.tools.turn_left",
                                        () -> tools.toolbarAction(() -> tools.turnDocument(false)))
                                .enabledWhen(() -> !isPart()),
                        fileEntry(
                                        "npships.tools.turn_right",
                                        () -> tools.toolbarAction(() -> tools.turnDocument(true)))
                                .enabledWhen(() -> !isPart()),
                        menuEntry(
                                "npships.editor.reach",
                                () -> reachOpen,
                                () -> {
                                    reachOpen = !reachOpen;
                                    layoutReachSlider();
                                })),
                false);
    }

    private void viewOptionsMenu() {
        var grid =
                new EditorPopupMenu.Entry(
                                Component.translatable("npships.editor.grid_menu"),
                                () -> gridMode != GridMode.OFF,
                                this::toggleGrid,
                                () ->
                                        Component.translatable(
                                                "npships.editor.grid_mode_"
                                                        + gridMode.name()
                                                                .toLowerCase(
                                                                        java.util.Locale.ROOT)))
                        .splitMenu(this::gridEntries);
        openHeaderMenu(
                toolbarIcons.get("view"),
                List.of(
                        fileEntry("npships.segments.random_preview", this::randomPreview),
                        grid,
                        menuEntry(
                                "npships.editor.bounds_pin",
                                () -> pinSchematicBounds,
                                () -> pinSchematicBounds = !pinSchematicBounds),
                        fileEntry("npships.editor.brightness", () -> {})
                                .submenu(this::brightnessEntries),
                        new EditorPopupMenu.Entry(
                                Component.translatable("npships.editor.support_warnings"),
                                () -> !hideWarnings,
                                () -> hideWarnings = !hideWarnings,
                                () -> Component.translatable("npships.tools.warnings_hint")),
                        fileEntry("npships.tools.styles", () -> {}).submenu(this::paletteEntries),
                        fileEntry("npships.editor.settings_help", () -> {})
                                .submenu(this::helpEntries)),
                false);
    }

    private void sliceMenu(net.minecraft.client.gui.components.Button button) {
        var entries = new java.util.ArrayList<EditorPopupMenu.Entry>();
        String[] names = {"below", "single", "above", "left", "vertical", "right"};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            entries.add(
                    new EditorPopupMenu.Entry(
                            Component.translatable("npships.editor.slice_mode." + names[i]),
                            () -> sliceMode == mode,
                            () -> {
                                boolean changeAxis = (sliceMode < 3) != (mode < 3);
                                sliceMode = mode;
                                if (changeAxis) ceiling = mode % 3 == 0 ? sliceSize() - 1 : 0;
                                ceiling = Mth.clamp(ceiling, 0, sliceSize() - 1);
                                button.setMessage(sliceLabel());
                                updateSlice();
                            }));
        }
        if (extensions != null) extensions.cancelInteraction();
        viewMenu.openText(button.getX(), button.getY(), button.getWidth(), width, height, entries);
    }

    private AABB sliceBounds() {
        return com.eynny.npships.authoring.geometry.PreviewSlice.bounds(
                draftSize, ceiling, sliceMode);
    }

    boolean visible(BlockPos p) {
        return level.visible(p);
    }

    boolean isVariant() {
        return variantAccept != null;
    }

    boolean isPart() {
        return isVariant() || reviewAccept != null;
    }

    EditorExtensionHost extensionHost() {
        return extensions;
    }

    boolean extensionPanelActive() {
        return extensions != null && extensions.panelActive();
    }

    boolean extensionToolActive() {
        return extensions != null && extensions.toolActive();
    }

    SchematicDocument extensionDocument() {
        return working;
    }

    void extensionAccept(SchematicDocument accepted) {
        applyDraft(accepted);
    }

    EditorExtension.Selection extensionSelection() {
        var roots = List.<java.util.UUID>of();
        if (tools != null
                && tools.entityIndex >= 0
                && tools.entityPreview.entitySource == working
                && tools.entityIndex < tools.entityPreview.entityEntries.size()) {
            var identity =
                    com.eynny.npships.authoring.patches.EntityIdentity.read(
                            tools.entityPreview
                                    .entityEntries
                                    .getCompound(tools.entityIndex)
                                    .getCompound("nbt"));
            if (identity != null) roots = List.of(identity);
        }
        return new EditorExtension.Selection(selected, cornerA, cornerB, roots);
    }

    private EditorExtension.Bounds extensionBounds(EditorLayout.Rect bounds) {
        return new EditorExtension.Bounds(
                bounds.left(), bounds.top(), bounds.width(), bounds.height());
    }

    EditorExtension.Bounds extensionPanelBounds() {
        return extensionBounds(layout.inspectorContent());
    }

    EditorExtension.Bounds extensionTabBounds() {
        return extensionBounds(layout.toolTabs());
    }

    EditorExtension.Bounds extensionViewportBounds() {
        return extensionBounds(layout.viewport());
    }

    Matrix4f extensionCamera() {
        return cameraMatrix();
    }

    EditorExtension.Ray extensionRay(double x, double y) {
        if (tools == null || inverse == null || !inViewport(x, y) || !layout.sceneUsable())
            return null;
        var ray = tools.ray(x, y, inverse, previewDepth());
        return new EditorExtension.Ray(ray[0], ray[1]);
    }

    void extensionStatus(Component message, boolean failure) {
        if (failure) error(message.getString());
        else status(message.getString());
    }

    boolean extensionReady() {
        return !previewOnly
                && !importMode
                && !resizeMode
                && saving == null
                && tools != null
                && tools.flushEdits();
    }

    boolean extensionActivate() {
        if (!extensionReady() || !tools.blur()) return false;
        releaseFirstPersonMouse();
        if (tools.dragging()) tools.cancelDrag();
        tools.cancelPaste();
        tools.extensionActivated();
        heldPaint = false;
        selecting = false;
        choosingCorners = false;
        symmetryDrag = -1;
        cameraDragButton = -1;
        orbitGesture = false;
        setDragging(false);
        setFocused((GuiEventListener) null);
        setSelectMode();
        return true;
    }

    SchematicDocument current() {
        return working;
    }

    BlockPos selection() {
        return selected;
    }

    java.nio.file.Path directory() {
        return directory;
    }

    boolean supportsLootTable(BlockPos pos) {
        return pos != null
                && com.eynny.npships.authoring.patches.SchematicResources.detached(
                                current(), pos, minecraft.level.registryAccess())
                        instanceof net.minecraft.world.RandomizableContainer;
    }

    List<BlockPos> containers() {
        return containers;
    }

    net.minecraft.world.level.BlockGetter physicsLevel() {
        return validationLevel;
    }

    boolean selectingRegion() {
        return selecting;
    }

    BlockPos firstCorner() {
        return cornerA;
    }

    BlockPos lastCorner() {
        return cornerB;
    }

    void commit(SchematicDocument value) {
        if (effectiveSymmetry() != 0)
            value =
                    com.eynny.npships.authoring.document.SchematicSymmetry.delta(
                            current(), value, effectiveSymmetry(), mirrorSpan(), minecraft.level);
        if (effectiveSymmetry() != 0) {
            symmetryFlashAxes = effectiveSymmetry();
            symmetryFlashUntil = net.minecraft.Util.getMillis() + 500;
        }
        applyDraft(value);
    }

    private int selectedSymmetryAxes() {
        return symmetryAxes != 0 ? symmetryAxes : savedSymmetryAxes;
    }

    private void toggleSymmetryAxis(int mask) {
        int before = selectedSymmetryAxes();
        int after = before ^ mask;
        if (symmetryAxes != 0) symmetryAxes = after;
        savedSymmetryAxes = after;
        if (symmetryFlashes) {
            symmetryFlashAxes = mask;
            symmetryFlashUntil = net.minecraft.Util.getMillis() + 500;
        }
    }

    private int visibleSymmetryAxes() {
        return (symmetryVisible ? selectedSymmetryAxes() : 0)
                | (symmetryFlashes && net.minecraft.Util.getMillis() < symmetryFlashUntil
                        ? symmetryFlashAxes
                        : 0);
    }

    void flashSymmetry() {
        if (effectiveSymmetry() != 0) {
            symmetryFlashAxes = effectiveSymmetry();
            symmetryFlashUntil = net.minecraft.Util.getMillis() + 500;
        }
    }

    int effectiveSymmetry() {
        return symmetryAxes;
    }

    private int statusColor = 0xffff55;
    boolean fieldApplication;
    final EditorValidationFeedback validation = new EditorValidationFeedback();

    private record FeedbackContext(
            long documentRevision,
            EditorToolsPanel.Tool tool,
            EditMode mode,
            BlockPos block,
            int entity,
            String segment,
            String zone,
            String area,
            BlockPos firstCorner,
            BlockPos secondCorner,
            com.eynny.npships.authoring.geometry.FittedPlane.Orientation fittedPlane) {}

    private long feedbackDocumentRevision;
    private FeedbackContext statusContext;
    private long statusUntil;

    private FeedbackContext feedbackContext() {
        return new FeedbackContext(
                feedbackDocumentRevision,
                tools == null ? null : tools.tool,
                editMode,
                selected,
                tools == null ? -1 : tools.entityIndex,
                tools == null ? "" : tools.segment,
                tools == null ? "" : tools.zone,
                tools == null ? "" : tools.selectedAreaId(),
                cornerA,
                cornerB,
                tools == null ? null : tools.fittedPlane);
    }

    private java.util.List<Component> warningBlocks, warningEntities, warningPaste;
    private int warningRendererFailures = -1;
    private Component cachedPreviewWarnings = Component.empty();

    /** Cached from existing preview-owned diagnostic lists, never from a document/world scan. */
    private Component previewWarnings() {
        if (level == null || renderer == null) return Component.empty();
        var blocks = level.diagnostics.lines();
        var entities =
                tools == null
                        ? java.util.List.<Component>of()
                        : tools.entityPreview.diagnostics.lines();
        var paste = tools == null ? java.util.List.<Component>of() : tools.pasteEntityDiagnostics();
        int failures = renderer.failedRenderers();
        if (warningBlocks == blocks
                && warningEntities == entities
                && warningPaste == paste
                && warningRendererFailures == failures) return cachedPreviewWarnings;
        warningBlocks = blocks;
        warningEntities = entities;
        warningPaste = paste;
        warningRendererFailures = failures;
        var result = Component.empty();
        if (failures > 0)
            result.append(Component.translatable("npships.editor.renderer_failures", failures));
        for (var category : java.util.List.of(blocks, entities, paste))
            for (var detail : category) {
                if (!result.getSiblings().isEmpty()) result.append("\n");
                result.append(detail);
            }
        return cachedPreviewWarnings = result;
    }

    private Component feedback() {
        if (validation.error() != null) return Component.literal(validation.error());
        boolean sameContext = statusContext == null || statusContext.equals(feedbackContext());
        if (statusContext != null
                && (feedbackOwner == FeedbackOwner.CONTEXT && !sameContext
                        || statusColor != 0xff5555
                                && net.minecraft.Util.getMillis() >= statusUntil)) {
            saveStatus = Component.empty();
            statusContext = null;
        }
        if (!saveStatus.getString().isEmpty()) return saveStatus;
        if (!previewOnly
                && tools != null
                && tools.tool == EditorToolsPanel.Tool.AREA
                && tools.layers.layers().isEmpty()
                && creating()) return Component.translatable("npships.tools.empty_area_create");
        return previewWarnings();
    }

    private int feedbackColor() {
        return validation.error() != null
                ? 0xff5555
                : saveStatus.getString().isEmpty()
                        ? previewWarnings().getString().isEmpty() ? 0xffff55 : 0xffaa55
                        : statusColor;
    }

    private enum FeedbackOwner {
        CONTEXT,
        SAVE,
        PHOTO
    }

    private FeedbackOwner feedbackOwner = FeedbackOwner.CONTEXT;

    void status(String value) {
        status(FeedbackOwner.CONTEXT, value, false);
    }

    private void status(FeedbackOwner owner, String value, boolean completed) {
        feedback(); // Expire the previous context before arbitrating the next operation.
        if (fieldApplication && !saveStatus.getString().isEmpty()) return;
        if (statusColor == 0xff5555
                && !saveStatus.getString().isEmpty()
                && (!completed || owner != feedbackOwner)) return;
        feedbackOwner = owner;
        statusColor = 0xffff55;
        saveStatus = Component.literal(value == null ? "" : value);
        statusContext = feedbackContext();
        statusUntil = net.minecraft.Util.getMillis() + 8000;
    }

    void error(String value) {
        error(FeedbackOwner.CONTEXT, value);
    }

    private void error(FeedbackOwner owner, String value) {
        feedbackOwner = owner;
        statusColor = 0xff5555;
        saveStatus = Component.literal(value == null ? "Unknown error" : value);
        statusContext = feedbackContext();
    }

    void commitGesture(SchematicDocument before, SchematicDocument after) {
        if (before == null) before = working;
        boolean changed =
                !before.sameContents(after)
                        || (tools != null && tools.layerStateDiffers(before, after));
        setDraft(after);
        if (changed) history.record(before);
    }

    void select(BlockPos value) {
        selected = value;
        pickDirty = true;
        if (extensions != null) extensions.selectionChanged();
    }

    void nextContainer() {
        if (containers.isEmpty()) return;
        selected = containers.get((containers.indexOf(selected) + 1) % containers.size());
        // Container navigation must not hide unrelated blocks or entities by changing the slice.
        tools.selectionChanged();
    }

    void advancedBlock() {
        editBlock();
    }

    void advancedLoot() {
        editLoot();
    }

    void advancedSegments(boolean create) {
        openSegments(create);
    }

    void clearObjectSelection() {
        if (tools != null && !tools.flushEdits()) return;
        clearObjectSelectionValidated();
    }

    void clearObjectSelectionValidated() {
        if (tools != null && tools.dragging()) tools.endDrag();
        special = null;
        resizeMode = false;
        boundsPreview = null;
        clearRegion();
        selected = null;
        if (tools != null) tools.clearSelectionValidated();
        if (extensions != null) extensions.selectionChanged();
    }

    void clearRegion() {
        cornerA = null;
        cornerB = null;
        selecting = false;
        choosingCorners = false;
    }

    void selectRegion(BlockPos origin, BlockPos size) {
        selected = null;
        cornerA = origin;
        cornerB = origin.offset(size).offset(-1, -1, -1);
        selecting = false;
        choosingCorners = false;
    }

    void resizeDocument() {
        if (isPart() || !tools.flushEdits()) return;
        clearRegion();
        tools.clearSelection();
        resizeMode = !resizeMode;
        if (resizeMode) {
            beforeResizeMode = editMode;
            setSelectMode();
        } else {
            if (beforeResizeMode != null) editMode(beforeResizeMode);
            beforeResizeMode = null;
        }
        special = resizeMode ? new AABB(Vec3.ZERO, Vec3.atLowerCornerOf(draftSize)) : null;
        status(Component.translatable("npships.tools.resize_drag").getString());
    }

    void editVariant(
            String id,
            String name,
            JsonObject config,
            net.minecraft.nbt.CompoundTag tag,
            java.util.function.UnaryOperator<SchematicDocument> validation,
            java.util.function.Consumer<SchematicDocument> accept,
            Runnable previous,
            Runnable next,
            Runnable gallery) {
        var screen =
                new SchematicEditorScreen(
                        this,
                        directory,
                        name,
                        com.eynny.npships.authoring.AssemblyPoints.segmentDocument(tag, config)
                                .withPatchValidation(validation),
                        minecraft.level);
        screen.variantAccept = accept;
        screen.previousVariant = previous;
        screen.nextVariant = next;
        screen.variantGallery = gallery;
        minecraft.setScreen(screen);
    }

    private void navigateVariant(Runnable action) {
        if (action == null || saving != null || !tools.flushEdits()) return;
        if (dirty) save();
        if (!dirty) action.run();
    }

    private void cacheLootLabels() {
        var map = new java.util.HashMap<BlockPos, String>();
        for (var entry : LootAssignments.read(draft))
            map.put(entry.pos(), entry.table().toString());
        lootLabels = java.util.Map.copyOf(map);
    }

    private void randomPreview() {
        if (!tools.flushEdits()) return;
        try {
            var segments = com.eynny.npships.authoring.StructureSegments.read(draft);
            var composition =
                    com.eynny.npships.authoring.StructureSegments.compose(
                            draftNbt(),
                            segments,
                            LootAssignments.read(draft),
                            com.eynny.npships.authoring.StructureSegments.choose(
                                    segments, net.minecraft.util.RandomSource.create()));
            var tag = composition.nbt();
            if (isVariant())
                com.eynny.npships.authoring.StructureCrew.apply(
                        tag,
                        draft,
                        new SchematicPreviewLevel(
                                new SchematicDocument(tag, null), minecraft.level),
                        net.minecraft.util.RandomSource.create());
            var screen =
                    new SchematicEditorScreen(
                            this,
                            directory,
                            Component.translatable("npships.editor.preview_title").getString(),
                            new SchematicDocument(tag, null),
                            minecraft.level);
            screen.firstPerson = false;
            screen.resetView();
            screen.previewSource = current();
            screen.previewMobs = isVariant();
            minecraft.setScreen(screen);
        } catch (Exception e) {
            error(EditorErrorText.message(e));
        }
    }

    public SchematicEditorScreen rerollable(SchematicDocument source) {
        previewSource = source;
        firstPerson = false;
        resetView();
        return this;
    }

    private void rerollPreview() {
        if (previewSource == null) return;
        try {
            var source = previewSource;
            var config = source.configCopy();
            var segments = com.eynny.npships.authoring.StructureSegments.read(config);
            var result =
                    com.eynny.npships.authoring.StructureSegments.compose(
                            source.nbtCopy(),
                            segments,
                            LootAssignments.read(config),
                            com.eynny.npships.authoring.StructureSegments.choose(
                                    segments, net.minecraft.util.RandomSource.create()));
            var tag = result.nbt();
            if (previewMobs)
                com.eynny.npships.authoring.StructureCrew.apply(
                        tag,
                        config,
                        new SchematicPreviewLevel(
                                new SchematicDocument(tag, null), minecraft.level),
                        net.minecraft.util.RandomSource.create());
            var next = new SchematicDocument(tag, null);
            document = next;
            setDraft(next);
            dirty = false;
            tools.preparePreview();
        } catch (Exception e) {
            error(EditorErrorText.message(e));
        }
    }

    private void initPreview() {
        cameraButtons(width - 136);
        gridButton(width - 104);
        if (previewSource != null) {
            addRenderableWidget(
                    new EditorIconButton(
                                    "entities",
                                    Component.translatable(
                                            "npships.tools.preview_mobs",
                                            Component.translatable(
                                                    previewMobs ? "options.on" : "options.off")),
                                    width - 76,
                                    8,
                                    () -> {
                                        previewMobs = !previewMobs;
                                        rerollPreview();
                                        rebuildWidgets();
                                    })
                            .selected(() -> previewMobs));
        }

        if (iconCapture != null) {
            var theme =
                    toolbarIcon(
                            "styles",
                            Component.translatable("npships.addons.snapshot_theme"),
                            width - 336,
                            8,
                            () ->
                                    openHeaderMenu(
                                            toolbarIcons.get("styles"), paletteEntries(), true));
            addRenderableWidget(theme);
            var light =
                    toolbarIcon(
                            "brightness",
                            Component.translatable("npships.editor.brightness"),
                            width - 312,
                            8,
                            () ->
                                    openHeaderMenu(
                                            toolbarIcons.get("brightness"),
                                            brightnessEntries(),
                                            false));
            addRenderableWidget(light);
            addRenderableWidget(
                    toolbarArrow(
                            "brightness",
                            EditorButton.flat(
                                            Component.empty(),
                                            b -> openHeaderMenu(light, brightnessEntries(), false))
                                    .bounds(width - 292, 8, 7, 20)
                                    .build()));
            photoCaptureButton =
                    addRenderableWidget(
                            EditorButton.flat(
                                            Component.translatable("npships.addons.capture"),
                                            b -> {
                                                photoOutput = null;
                                                captureRequested = true;
                                            })
                                    .bounds(width - 280, 8, 116, 20)
                                    .build());
            int photoAnchor = width - 164;
            var export =
                    EditorButton.flat(Component.empty(), b -> photoMenu(photoAnchor))
                            .bounds(photoAnchor, 8, 7, 20)
                            .build();
            export.tooltip(() -> Component.translatable("npships.addons.logo_menu"));
            photoSizeArrow = addRenderableWidget(export.splitArrow());
        }
        if (previewSource != null)
            addRenderableWidget(
                    new EditorIconButton(
                            "preview",
                            Component.translatable("npships.segments.random_preview"),
                            width - 52,
                            8,
                            this::rerollPreview));
        if (tools == null) tools = new EditorToolsPanel(this);
        tools.patchEditors.activate();
        tools.preparePreview();
        addRenderableWidget(
                new EditorIconButton(
                        "back", Component.translatable("gui.back"), width - 28, 8, this::onClose));
        sliceButton =
                addRenderableWidget(
                        EditorButton.flat(sliceLabel(), this::sliceMenu)
                                .bounds(right - 108, layout.viewportFooter().top(), 108, 20)
                                .build());
        slider = new HeightSlider(left, layout.viewportFooter().top(), right - left - 112);
        addRenderableWidget(slider);
        updateSlice();
        pickDirty = true;
    }

    private float previewDepth() {
        return com.eynny.npships.authoring.geometry.SchematicViewProjection.depth(
                cameraTransform().last().pose(), draftSize);
    }

    private void openSegments(boolean create) {
        try {
            minecraft.setScreen(
                    new SegmentEditorScreen(
                            this,
                            directory,
                            current(),
                            cornerA,
                            cornerB,
                            this::applyDraft,
                            create));
        } catch (Exception e) {
            error(EditorErrorText.message(e));
        }
    }

    private void editBlock() {
        minecraft.setScreen(
                new BlockEditorScreen(
                        this,
                        current(),
                        selected == null ? BlockPos.ZERO : selected,
                        this::applyDraft));
    }

    private void applyDraft(SchematicDocument edited) {
        if (edited.sameContents(working)) return;
        var normalized = normalizeDraft(edited);
        history.record(working);
        setNormalizedDraft(normalized);
    }

    private SchematicDocument normalizeDraft(SchematicDocument edited) {
        edited = edited.inheritPatchValidation(working);
        if (tools != null) tools.validateDraft(edited);
        var beforeNormalization = edited;
        edited =
                com.eynny.npships.authoring.document.NeighborConnections.normalize(edited, working);
        edited =
                com.eynny.npships.authoring.patches.PatchDocuments.normalize(
                        edited, minecraft.level);
        edited = edited.inheritPatchValidation(working);
        if (tools != null) tools.normalizedDraft(beforeNormalization, edited);
        return edited;
    }

    private void setDraft(SchematicDocument edited) {
        setNormalizedDraft(normalizeDraft(edited));
    }

    private void setNormalizedDraft(SchematicDocument edited) {
        authoringCycle.reset();
        boolean atTop = ceiling == sliceSize() - 1;
        var changed = edited.changedBlocks(working, 256);
        boolean geometryChanged = changed == null;
        draft = edited.configCopy();
        draftSize = edited.size();
        if (working != edited) feedbackDocumentRevision++;
        working = edited;
        cacheLootLabels();
        dirty = !edited.sameContents(document) || (tools != null && tools.layersDirty());
        if (changed != null) {
            changed = level.update(edited, changed);
            changed = validationLevel.update(edited, changed);
            renderer.update(changed);
            var check = new java.util.HashSet<BlockPos>();
            for (var p : changed)
                for (int y = -1; y <= 1; y++)
                    for (int z = -1; z <= 1; z++)
                        for (int x = -1; x <= 1; x++) check.add(p.offset(x, y, z));
            check.forEach(supportWarnings::remove);
            if (validationQueue != null && validationQueue.hasNext())
                validationQueue =
                        java.util.stream.Stream.concat(
                                        check.stream(),
                                        java.util.stream.StreamSupport.stream(
                                                java.util.Spliterators.spliteratorUnknownSize(
                                                        validationQueue, 0),
                                                false))
                                .iterator();
            else validationQueue = check.iterator();
            if (!changed.isEmpty()) updateObjectLists(changed);
        } else if (geometryChanged) {
            ceiling = atTop ? sliceSize() - 1 : Math.min(ceiling, sliceSize() - 1);
            rebuildScene(minecraft.level);
            updateSlice();
        }
        if (!fieldApplication) saveStatus = Component.empty();
        pickDirty = true;
        if (tools != null) tools.documentChanged();
        if (extensions != null) extensions.documentChanged();
        dirty = !working.sameContents(document) || (tools != null && tools.layersDirty());
        refreshActions();
    }

    private boolean deferredSelectionClick, replayingSelectionClick, saveAfterShape;
    private boolean deferredInspectionValidated, replayingInspectionValidated;
    private boolean deferredAuthoringGesture;
    private final EditorAuthoringCycle authoringCycle = new EditorAuthoringCycle();
    private EditorInspection.Candidate inspectionHover;
    private EditorAuthoringCycle.Plan authoringHoverPlan;
    private boolean universalHover;

    private record HoverKey(
            EditorAuthoringCycle.Context context,
            double x,
            double y,
            boolean authoring,
            boolean part,
            SchematicDocument entitySource,
            List<net.minecraft.world.entity.Entity> failedEntities,
            EditorToolsPanel.Tool tool) {}

    private HoverKey hoverKey;
    private List<EditorInspection.Candidate> hoverCandidates = List.of();

    void cancelDeferredSave() {
        saveAfterShape = false;
    }

    private double selectionClickX, selectionClickY;

    @Override
    public boolean mouseReleased(double rawX, double rawY, int button) {
        try {
            return releaseSceneMouse(rawX, rawY, button);
        } finally {
            // Accept the complete click/drag first, then hand native cursor ownership to selection.
            updateSelectionLook();
        }
    }

    private boolean releaseSceneMouse(double rawX, double rawY, int button) {
        final double x = retainsFirstPersonMouse() ? layout.viewport().centerX() : rawX;
        final double y = retainsFirstPersonMouse() ? layout.viewport().centerY() : rawY;
        dividerFocus();
        if (shortcutReference.isOpen()) {
            shortcutReference.release();
            return true;
        }
        if (inspectorDivider.release(button)) return true;
        if (button == cameraDragButton) cameraDragButton = -1;
        if (button == 1) orbitGesture = false;
        if (extensions != null
                && extensions.mouse(EditorExtension.MouseAction.RELEASE, x, y, button, 0, 0, 0))
            return true;
        if (reachSlider != null && reachSlider.visible) reachSlider.mouseReleased(x, y, button);
        if (button == 0) {
            reachDragging = false;
            symmetryDrag = -1;
        }
        if (button == 0 && iconCapture != null && snapshotGrid.end()) return true;
        if (!previewOnly && button == 0 && tools != null && tools.patchRegionTool.end())
            return true;
        infoBar.release();
        if (tools != null) tools.inventoryBar.release();
        if (button == cameraDragButton) cameraDragButton = -1;
        if (button == 1) orbitGesture = false;
        boolean completedRegion = button == 0 && selecting && !choosingCorners;
        if (button == 0) {
            if (!choosingCorners) selecting = false;
            heldPaint = false;
            lastPaintEntity = null;
        }
        if (tools != null) {
            if (button == 0) {
                boolean click =
                        deferredSelectionClick
                                && !tools.dragChanged()
                                && Math.hypot(x - selectionClickX, y - selectionClickY) < 3;
                deferredSelectionClick = false;
                if (click) {
                    tools.cancelDrag();
                    if (!deferredAuthoringGesture) {
                        replayingSelectionClick = true;
                        replayingInspectionValidated = deferredInspectionValidated;
                        try {
                            mouseClicked(x, y, 0);
                        } finally {
                            replayingSelectionClick = false;
                            replayingInspectionValidated = false;
                        }
                    }
                } else tools.endDrag();
                deferredInspectionValidated = false;
                deferredAuthoringGesture = false;
            }
            tools.mouseReleased(x, y, button);
        }
        if (completedRegion && cornerA != null && cornerB != null && importResizable) {
            special =
                    new AABB(
                            Math.min(cornerA.getX(), cornerB.getX()),
                            Math.min(cornerA.getY(), cornerB.getY()),
                            Math.min(cornerA.getZ(), cornerB.getZ()),
                            Math.max(cornerA.getX(), cornerB.getX()) + 1,
                            Math.max(cornerA.getY(), cornerB.getY()) + 1,
                            Math.max(cornerA.getZ(), cornerB.getZ()) + 1);
            clearRegion();
        } else if (completedRegion
                && cornerA != null
                && cornerB != null
                && tools != null
                && tools.regionTool()) tools.finishRegion();
        return super.mouseReleased(x, y, button);
    }

    private void refreshActions() {
        if (previewOnly || saveButton == null) return;
        saveButton.active = !importMode && saving == null && document.hasConfig();
        undoButton.active =
                !importMode && saving == null && (history.canUndo() || tools.settingsDirty);
        redoButton.active =
                !importMode && saving == null && history.canRedo() && !tools.settingsDirty;
    }

    private void editLoot() {
        if (!containers.contains(selected)) return;
        final BlockPos pos = selected;
        var current =
                LootAssignments.read(draft).stream()
                        .filter(entry -> entry.pos().equals(pos))
                        .map(LootAssignments.Entry::table)
                        .findFirst()
                        .orElse(null);
        var nbt = current().blockEntityData(pos);
        Component original =
                nbt.contains("LootTable")
                        ? Component.translatable(
                                "npships.loot.original_table", nbt.getString("LootTable"))
                        : Component.translatable("npships.loot.original_items");
        minecraft.setScreen(
                new ContainerLootScreen(
                        this,
                        pos,
                        current,
                        original,
                        draft,
                        config -> applyDraft(new SchematicDocument(draftNbt(), config)),
                        table -> {
                            applyDraft(
                                    new SchematicDocument(
                                            draftNbt(), LootAssignments.assign(draft, pos, table)));
                        }));
    }

    private void save() {
        if (importMode) return;
        boolean ready;
        savingFields = true;
        try {
            ready = tools == null || tools.flushEdits();
        } finally {
            savingFields = false;
        }
        if (!ready) {
            if (tools != null && tools.geometryBusy()) saveAfterShape = true;
            return;
        }
        saveAfterShape = false;
        if (!saveButton.active) return;
        if (reviewAccept != null) {
            try {
                reviewAccept.accept(current());
            } catch (Exception e) {
                error(FeedbackOwner.SAVE, EditorErrorText.message(e));
            }
            return;
        }
        if (variantAccept != null) {
            try {
                variantAccept.accept(tools.layersForSave().save());
                document = current();
                tools.layersSaved(tools.layersForSave());
                dirty = false;
                status(
                        FeedbackOwner.SAVE,
                        Component.translatable("npships.loot.saved").getString(),
                        true);
                refreshActions();
            } catch (Exception e) {
                error(FeedbackOwner.SAVE, EditorErrorText.message(e));
            }
            return;
        }
        var expected = diskDocument;
        var updated = draft.deepCopy();
        savingFlat = working;
        savingLayers = tools.layersForSave();
        var layerSnapshot = savingLayers;
        status(
                FeedbackOwner.SAVE,
                Component.translatable("npships.loot.saving").getString(),
                false);
        saving =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                var edited = layerSnapshot.save().withConfig(updated);
                                AuthoringFiles.saveDocument(directory, filename, expected, edited);
                                return edited;
                            } catch (Exception e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                        },
                        net.minecraft.Util.ioPool());
        refreshActions();
    }

    @Override
    public void tick() {
        dividerFocus();
        if (tools != null) tools.tick();
        refreshActions();
        if (saveAfterShape && !tools.geometryBusy()) {
            saveAfterShape = false;
            save();
        }
        if (heldPaint) {
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                                    minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
                            != GLFW.GLFW_PRESS
                    || editMode == EditMode.SELECT) heldPaint = false;
            else if (net.minecraft.Util.getMillis() - paintStarted >= 500
                    && ++paintTicks % 2 == 0
                    && inViewport(lastMouseX, lastMouseY)) {
                repeatingPaint = true;
                try {
                    mouseClicked(lastMouseX, lastMouseY, 0);
                } finally {
                    repeatingPaint = false;
                }
            }
        }
        for (int i = 0; i < 512 && validationQueue != null && validationQueue.hasNext(); i++) {
            var p = validationQueue.next();
            var state = validationLevel.originalState(p);
            try {
                if (AssemblyEligibility.excluded(state, validationLevel, p))
                    supportWarnings.put(
                            p, Component.translatable("npships.tools.assembly_excluded"));
                else if (!state.isAir() && !state.canSurvive(validationLevel, p))
                    supportWarnings.put(p, Component.translatable("npships.tools.invalid_support"));
            } catch (Exception ignored) {
            }
        }
        if (saving != null && saving.isDone()) {
            try {
                diskDocument = saving.join();
                document = savingFlat;
                tools.layersSaved(savingLayers);
                com.eynny.npships.network.CatalogRefreshNetworking.saved();
                dirty = !working.sameContents(document) || tools.layersDirty();
                status(
                        FeedbackOwner.SAVE,
                        Component.translatable("npships.loot.saved").getString(),
                        true);
            } catch (java.util.concurrent.CompletionException e) {
                error(FeedbackOwner.SAVE, EditorErrorText.message(e.getCause()));
            }
            saving = null;
            refreshActions();
        }
    }

    private boolean initialCamera = true;

    private void resetView() {
        if (firstPerson && layout == null) {
            initialViewPending = true;
            return;
        }
        initialViewPending = false;
        authoringCycle.reset();
        orbitGesture = false;
        flightKeys.clear();
        if (cycleCamera) cameraPreset = (cameraPreset + 1) % 4;
        yaw = new float[] {135, 180, 90, 180}[cameraPreset];
        pitch = new float[] {30, 0, 0, 90}[cameraPreset];
        zoom = 1;
        var center = viewBounds().getCenter();
        cameraTarget.set((float) center.x, (float) center.y, (float) center.z);
        if (firstPerson) {
            var offset =
                    new Matrix4f()
                            .rotateX((float) Math.toRadians(pitch))
                            .rotateY((float) Math.toRadians(yaw))
                            .invert()
                            .transformDirection(
                                    new Vector3f(
                                            0,
                                            0,
                                            Math.max(80, layout.viewport().height() * .9f)
                                                    / fittedScale()));
            cameraTarget.add(offset);
        }
        pickDirty = true;
    }

    private void setCeiling(final int value) {
        int next = Mth.clamp(value, 0, sliceSize() - 1);
        if (ceiling == next) return;
        ceiling = next;
        if (slider != null) slider.sync();
        updateSlice();
    }

    private void updateSlice() {
        authoringCycle.reset();
        if (slider != null) slider.sync();
        level.slice(ceiling, sliceMode);
        renderer.invalidate();
        renderError = null;
        pickDirty = true;
    }

    @Override
    public void render(
            final GuiGraphics graphics,
            final int rawMouseX,
            final int rawMouseY,
            final float partialTick) {
        final var extensionActivation = extensions;
        if (extensions != null && (!minecraft.isWindowActive() || minecraft.getOverlay() != null))
            extensions.cancelInteraction();
        if (extensions != extensionActivation) return;
        if (firstPersonEntryPending && minecraft.screen == this) {
            firstPersonEntryPending = false;
            if (firstPerson) captureFirstPersonMouse();
        }
        if (firstPersonMouse
                && (extensions != null && (extensions.keyboardFocused() || extensions.modal())
                        || !minecraft.isWindowActive()
                        || minecraft.getOverlay() != null
                        || shortcutReference.isOpen()
                        || viewMenu.isOpen() && !viewMenu.isSuspended()
                        || tools != null
                                && (tools.editingText()
                                        || tools.keyboardPopup()
                                        || tools.expandedWorkspace()))) releaseFirstPersonMouse();
        updateSelectionLook();
        final int mouseX =
                retainsFirstPersonMouse() ? (int) layout.viewport().centerX() : rawMouseX;
        final int mouseY =
                retainsFirstPersonMouse() ? (int) layout.viewport().centerY() : rawMouseY;
        long resourceEpoch = com.eynny.npships.client.editor.render.PreviewResources.epoch();
        if (resourceEpoch != previewResourceEpoch) {
            previewResourceEpoch = resourceEpoch;
            renderError = null;
            validationLevel.resetVisualInstances();
            if (tools != null) tools.refreshPreviewResources();
            if (extensions != null) extensions.resourcesChanged(resourceEpoch);
            if (extensions != extensionActivation) return;
        }
        dividerFocus();
        if (inspectorDivider.captured()
                && GLFW.glfwGetMouseButton(
                                minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
                        == GLFW.GLFW_RELEASE) inspectorDivider.lostRelease();
        if (cameraDragButton >= 0
                && (!minecraft.isWindowActive()
                        || GLFW.glfwGetMouseButton(
                                        minecraft.getWindow().getWindow(), cameraDragButton)
                                == GLFW.GLFW_RELEASE)) {
            cameraDragButton = -1;
            orbitGesture = false;
            setDragging(false);
        }
        moveFirstPerson();
        constrainCamera();
        if (!previewOnly && !importMode && titleShowsPending != toolbarTitlePending()) {
            var anchor = popupSource();
            updateLayout(layoutTopButtons());
            applyRetainedLayout(anchor);
        }
        graphics.fill(0, 0, width, height, EditorButtonTheme.background());
        renderToolbar(graphics);
        graphics.drawString(font, toolbarTitle(), titleLeft, 12, EditorButtonTheme.text());
        layout.viewport().fill(graphics, EditorButtonTheme.scene());
        if (layout.sceneUsable()
                && renderError == null
                && (tools == null || !tools.expandedWorkspace())) {
            try {
                renderer.buildNextBatch();
                renderPreview(graphics, mouseX, mouseY);
                if (extensions != extensionActivation) return;
                if (captureRequested
                        && minecraft.getOverlay() == null
                        && renderer.built() >= renderer.total()) {
                    captureIcon(graphics);
                    return;
                }
                if (iconCapture != null) {
                    int side = Math.min(layout.viewport().width(), layout.viewport().height());
                    int ix = (left + right - side) / 2, iy = (top + bottom - side) / 2;
                    graphics.fill(ix, iy, ix + side, iy + 1, 0x90ffffff);
                    graphics.fill(ix, iy + side - 1, ix + side, iy + side, 0x90ffffff);
                    graphics.fill(ix, iy, ix + 1, iy + side, 0x90ffffff);
                    graphics.fill(ix + side - 1, iy, ix + side, iy + side, 0x90ffffff);
                }
            } catch (Exception exception) {
                renderError =
                        Component.translatable("npships.editor.error", exception.getMessage());
                if (exception instanceof PreviewRendererScope.RecoveryFailure)
                    com.mojang.logging.LogUtils.getLogger()
                            .warn("[NPShips editor] {}", exception.getMessage());
                else
                    com.mojang.logging.LogUtils.getLogger()
                            .warn(
                                    "[NPShips editor] Preview failed for {}",
                                    title.getString(),
                                    exception);
            }
        }
        int sx = layout.inspector().left();
        if (!previewOnly) {
            layout.informationSurface().fill(graphics, EditorButtonTheme.panel());
            if (!importMode) tools.render(graphics, mouseX, mouseY, partialTick);
            if (extensions != null) extensions.renderPanel(graphics, mouseX, mouseY, partialTick);
            if (extensions != extensionActivation) return;
        }
        if (!previewOnly) renderInformation(graphics, mouseX, mouseY);
        if (!previewOnly && !tools.expandedWorkspace() && tools.massProgress() != null)
            graphics.drawString(font, tools.massProgress(), left + 6, top + 19, 0xffff55);
        Component status =
                renderError != null
                        ? renderError
                        : Component.translatable(
                                "npships.editor.progress", renderer.built(), renderer.total());
        if ((tools == null || !tools.expandedWorkspace())
                && (!previewOnly || renderer.built() < renderer.total() || renderError != null))
            graphics.drawString(
                    font,
                    font.plainSubstrByWidth(status.getString(), Math.max(20, right - left - 12)),
                    left + 6,
                    top + 6,
                    renderError == null ? 0xffff55 : 0xff5555);
        if ((tools == null || !tools.expandedWorkspace()) && document.hasMultiplePalettes())
            graphics.drawString(
                    font,
                    Component.translatable("npships.editor.first_palette"),
                    left + 6,
                    bottom - 14,
                    0xffff55);
        Component controls = cameraHelp();
        graphics.drawString(
                font,
                font.plainSubstrByWidth(controls.getString(), width - 16),
                8,
                layout.controlsHint().top(),
                0x9caabd);
        boolean constrained =
                !layout.sceneUsable() && (tools == null || !tools.expandedWorkspace())
                        || !previewOnly && !layout.inspectorUsable()
                        || tools != null
                                && tools.expandedCode()
                                && layout.expandedCodeField().width() == 0;
        Component footer =
                !feedback().getString().isEmpty()
                        ? feedback()
                        : constrained
                                ? Component.translatable("npships.editor.window_small")
                                : iconCapture != null
                                        ? Component.translatable("npships.addons.grid_drag")
                                        : previewOnly
                                                ? previewHelp()
                                                : Component.translatable(
                                                        "npships.segments.selection_hint");
        graphics.drawString(
                font,
                font.plainSubstrByWidth(footer.getString().replace('\n', ' '), width - 16),
                8,
                layout.status().top() + 4,
                feedback().getString().isEmpty() ? 0x9caabd : feedbackColor());
        if (slider != null)
            slider.visible =
                    layout.sceneUsable()
                            && (tools == null || !tools.expandedWorkspace())
                            && containsWidget(layout.modeControls(), slider);
        if (sliceButton != null)
            sliceButton.visible =
                    layout.sceneUsable()
                            && (tools == null || !tools.expandedWorkspace())
                            && containsWidget(layout.modeControls(), sliceButton);
        EditorButtonTheme.widgets(this);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (retainsFirstPersonMouse()) {
            renderFirstPersonCrosshair(graphics, mouseX, mouseY);
        }
        if (tools != null && !extensionPanelActive())
            tools.renderCodeWorkspace(graphics, mouseX, mouseY, partialTick);
        if (iconCapture != null && gridMode == GridMode.FLOOR) {
            snapshotGrid.bounds(draftSize);
            layout.viewport().scissor(graphics);
            snapshotGrid.render(graphics, cameraMatrix(), mouseX, mouseY, pinGridBounds);
            graphics.disableScissor();
        }
        if (tools != null && !extensionToolActive())
            tools.renderRotationAngles(graphics, left, top, right);
        boolean dividerHover = dividerAvailable(mouseX, mouseY);
        if (!previewOnly) inspectorDivider.render(graphics, font, dividerHover, mouseX, mouseY);
        inspectorDivider.cursor(
                minecraft.getWindow().getWindow(),
                minecraft.isWindowActive() && (dividerHover || inspectorDivider.dragging()));
        viewMenu.render(graphics, mouseX, mouseY);
        if (reachSlider != null && reachSlider.visible) {
            // Preview text has a font depth bias; the popup owns a separate GUI plane.
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 800);
            reachSlider.render(graphics, mouseX, mouseY, partialTick);
            graphics.pose().popPose();
        }
        shortcutReference.render(graphics, width, height, mouseX, mouseY);
        var assemblyWarning = assemblyPointWarningAt(mouseX, mouseY);
        if (assemblyWarning != null) EditorTooltips.show(font, assemblyWarning, mouseX, mouseY);
        else if (!previewOnly && !viewMenu.contains(mouseX, mouseY) && inViewport(mouseX, mouseY)) {
            var p =
                    authoringHoverPlan == null
                            ? pickExisting(mouseX, mouseY)
                            : authoringHoverPlan.candidate().target()
                                            instanceof EditorInspection.BlockTarget block
                                    ? block.position()
                                    : null;
            var warning = blockWarning(p);
            if (warning != null && editMode == EditMode.SELECT)
                EditorTooltips.show(
                        font, font.split(warning, Math.min(260, width - 24)), mouseX, mouseY);
        }
        if (!shortcutReference.isOpen() && layout.controlsHint().contains(mouseX, mouseY))
            EditorTooltips.show(font, controls, mouseX, mouseY);
        if (!shortcutReference.isOpen() && mouseY >= height - 20)
            EditorTooltips.show(
                    font,
                    font.split(
                            feedback().getString().isEmpty()
                                    ? footer
                                    : footer.copy()
                                            .append("\n")
                                            .append(
                                                    Component.translatable(
                                                            "npships.editor.copy_message")),
                            width - 24),
                    mouseX,
                    mouseY);
        if (assemblyWarning == null
                && objectHoverHints
                && authoringHoverPlan != null
                && universalHover
                && inViewport(mouseX, mouseY))
            EditorTooltips.show(font, authoringHoverLabel(authoringHoverPlan), mouseX, mouseY);
        if (tools != null) {
            tools.renderPinnedHelp(graphics, mouseX, mouseY);
            if (shortcutReference.isOpen()
                    || tools.textPopupContains(mouseX, mouseY)
                    || tools.controlTextHelpAt(mouseX, mouseY))
                setTooltipForNextRenderPass(
                        java.util.List.of(),
                        net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner
                                .INSTANCE,
                        true);
        }
        if (!viewMenu.isOpen() && !shortcutReference.isOpen()) {
            var titleHint = toolbarTitleHint(mouseX, mouseY);
            if (titleHint != null) EditorTooltips.show(font, titleHint, mouseX, mouseY);
        }
        renderEditorTooltip(graphics, mouseX, mouseY);
    }

    private void renderFirstPersonCrosshair(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 3, y, x + 4, y + 1, 0xffe6e6e6);
        graphics.fill(x, y - 3, x + 1, y + 4, 0xffe6e6e6);
    }

    private Component cameraHelp() {
        if (previewOnly) return previewHelp();
        var hint =
                (firstPerson
                                ? firstPersonHelp(false)
                                : Component.translatable("npships.editor.controls"))
                        .copy();
        if (tools != null) {
            if (creating() && (tools.blocksTool() || tools.entityPlacement()))
                hint.append(" \u00b7 ").append(brushRotationHelp());
            else if (!creating() && !tools.regionTool()) {
                if (tools.blockSelectionTool() && tools.pos() != null)
                    hint.append(" \u00b7 ")
                            .append(
                                    Component.translatable(
                                            "npships.editor.rotate_selected_block_hint",
                                            EditorShortcutDispatcher.binding(
                                                    EditorShortcutDispatcher.Action
                                                            .ROTATE_BRUSH_BACKWARD),
                                            EditorShortcutDispatcher.binding(
                                                    EditorShortcutDispatcher.Action
                                                            .ROTATE_BRUSH_FORWARD)));
                else if (tools.entityTool()
                        && tools.tool != EditorToolsPanel.Tool.GLUE
                        && tools.entityIndex >= 0)
                    hint.append(" \u00b7 ")
                            .append(
                                    Component.translatable(
                                            "npships.editor.rotate_selected_entity_hint",
                                            EditorShortcutDispatcher.binding(
                                                    EditorShortcutDispatcher.Action
                                                            .ROTATE_BRUSH_BACKWARD),
                                            EditorShortcutDispatcher.binding(
                                                    EditorShortcutDispatcher.Action
                                                            .ROTATE_BRUSH_FORWARD)));
            }
        }
        return hint;
    }

    private Component brushRotationHelp() {
        return Component.translatable(
                "npships.editor.rotate_brush_gestures",
                EditorShortcutDispatcher.binding(
                        EditorShortcutDispatcher.Action.ROTATE_BRUSH_BACKWARD),
                EditorShortcutDispatcher.binding(
                        EditorShortcutDispatcher.Action.ROTATE_BRUSH_FORWARD));
    }

    private Component previewHelp() {
        return firstPerson
                ? firstPersonHelp(true)
                : Component.translatable("npships.editor.preview_hint");
    }

    private Component firstPersonHelp(boolean photo) {
        return Component.translatable(
                (photo ? "npships.editor.fps_photo" : "npships.editor.fps")
                        + (freeFirstPersonLook
                                ? retainsFirstPersonMouse() ? "_captured" : "_cursor"
                                : "_drag"));
    }

    private record InformationText(Component text, int gap, int color) {}

    /** Existing information sources only; transient hover never changes the reserved geometry. */
    private java.util.List<InformationText> informationText(int mouseX, int mouseY) {
        var rows = new java.util.ArrayList<InformationText>();
        rows.add(
                new InformationText(
                        Component.translatable(
                                "npships.editor.schematic_size",
                                draftSize.getX(),
                                draftSize.getY(),
                                draftSize.getZ()),
                        0,
                        EditorButtonTheme.text()));
        if (inViewport(mouseX, mouseY) && tools != null) {
            var region = tools.hoveredRegion(mouseX, mouseY, inverse, previewDepth());
            if (region != null)
                rows.add(new InformationText(region, EditorLayout.CONTROL_GAP, 0x73dfcc));
        }
        var entity = tools == null ? null : tools.entityInfo();
        BlockPos info = selected != null ? selected : hovered;
        if (entity != null)
            rows.add(new InformationText(entity, EditorLayout.CONTROL_GAP, 0x73dfcc));
        else if (tools != null && !tools.entityTool() && info != null) {
            rows.add(
                    new InformationText(
                            Component.translatable(
                                    "npships.editor.object_position",
                                    info.getX(),
                                    info.getY(),
                                    info.getZ()),
                            EditorLayout.CONTROL_GAP,
                            0x73dfcc));
            rows.add(new InformationText(level.describe(info), 0, EditorButtonTheme.text()));
            var loot = lootLabels.get(info);
            if (loot == null) loot = level.savedLootTable(info);
            if (!loot.isEmpty())
                rows.add(
                        new InformationText(
                                Component.translatable("npships.loot.assigned", loot),
                                0,
                                0xffff55));
        }
        return rows;
    }

    private void renderInformation(GuiGraphics graphics, int mouseX, int mouseY) {
        BlockPos info = selected != null ? selected : hovered;
        int entity = tools != null && tools.entityTool() ? tools.entityIndex : -1;
        if (!java.util.Objects.equals(infoPosition, info) || entity != infoEntity) {
            infoEntity = entity;
            infoScroll = preferredInfoScroll = 0;
            infoPosition = info;
        }
        var rows = informationText(mouseX, mouseY);
        var lines =
                rows.stream()
                        .map(row -> font.split(row.text(), Math.max(1, layout.contentWidth())))
                        .toList();
        int pitch = font.lineHeight + EditorLayout.INFORMATION_LINE_GAP;
        infoHeight = 0;
        for (int i = 0; i < rows.size(); i++)
            infoHeight += rows.get(i).gap() + lines.get(i).size() * pitch;
        // Clamp against this frame's wrapped content, retaining preferred scroll through width
        // clamps.
        infoScroll =
                Math.clamp(
                        preferredInfoScroll,
                        0,
                        Math.max(0, infoHeight - layout.information().height()));
        layout.information().scissor(graphics);
        int y = infoTop - infoScroll;
        for (int i = 0; i < rows.size(); i++) {
            y += rows.get(i).gap();
            for (var line : lines.get(i)) {
                graphics.drawString(
                        font, line, layout.information().left(), y, rows.get(i).color());
                y += pitch;
            }
        }
        graphics.disableScissor();
        int available = layout.information().height();
        infoBar.configure(
                layout.information().right() - 6,
                layout.information().top(),
                available,
                available,
                available > 0 ? infoHeight : 0,
                infoScroll,
                value -> infoScroll = preferredInfoScroll = value);
        infoBar.render(graphics);
    }

    private void renderPreview(
            final GuiGraphics graphics, final double mouseX, final double mouseY) {
        renderPreview(graphics, mouseX, mouseY, 0);
    }

    private void renderPreview(
            final GuiGraphics graphics, final double mouseX, final double mouseY, int resolution) {
        boolean capture = resolution > 0;
        graphics.flush();
        float[] callerColor = RenderSystem.getShaderColor().clone();
        if (!capture) layout.viewport().scissor(graphics);
        RenderSystem.enableDepthTest();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.getModelViewStack().pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.depthMask(true);
        RenderSystem.clear(
                org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.Minecraft.ON_OSX);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            // Shift the GUI near plane for a 3D scene; restore it before drawing controls.
            // A dedicated orthographic depth range, sized for every transformed corner.
            float depth = previewDepth();
            int side = Math.min(layout.viewport().width(), layout.viewport().height());
            int cropX = (left + right - side) / 2, cropY = (top + bottom - side) / 2;
            var projection =
                    capture
                            ? new Matrix4f()
                                    .setOrtho(
                                            cropX, cropX + side, cropY + side, cropY, -depth, depth)
                            : com.eynny.npships.authoring.geometry.SchematicViewProjection
                                    .projection(width, height, depth);
            RenderSystem.setProjectionMatrix(
                    projection.mul(cameraLens()),
                    firstPerson
                            ? VertexSorting.byDistance(
                                    layout.viewport().centerX(),
                                    layout.viewport().centerY(),
                                    200 + Math.max(80, layout.viewport().height() * .9f))
                            : VertexSorting.ORTHOGRAPHIC_Z);
            PoseStack camera = cameraTransform();
            inverse = cameraMatrix().invert();
            SceneOutlines.projection(cameraLens(), depth);
            if (gridMode == GridMode.BACKGROUND) {
                SceneOutlines.backdrop(
                        graphics,
                        left,
                        top,
                        right,
                        bottom,
                        blackGrid,
                        capture
                                ? (float) resolution / side
                                : (float) minecraft.getWindow().getGuiScale(),
                        iconCapture != null);
                graphics.flush();
            }
            pose.mulPose(camera.last().pose());
            RenderSystem.setupLevelDiffuseLighting(
                    new Vector3f(.2f, 1, -.7f).normalize(), new Vector3f(-.2f, 1, .7f).normalize());
            renderer.mask(null);
            if (tools != null) tools.previewAreaMovement(renderer);
            renderer.brightness(brightness);
            RenderSystem.setShaderColor(brightness, brightness, brightness, 1);
            renderer.render(
                    pose,
                    graphics.bufferSource(),
                    () -> {
                        if (tools != null) tools.renderEntities(graphics, pose);
                    });
            if (extensions != null)
                extensions.scene(pose, graphics.bufferSource(), cameraMatrix(), false);
            graphics.flush();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            if (capture) {
                if (gridMode == GridMode.FLOOR)
                    SceneOutlines.grid(
                            graphics, pose, gridBounds(), gridSpacing(), gridFade, blackGrid);
                graphics.flush();
                return;
            }
            if (extensionToolActive()) {
                inspectionHover = null;
                authoringHoverPlan = null;
                universalHover = false;
                tools.hoveredEntity = -1;
            }
            if (tools != null && !extensionToolActive()) {
                inspectionHover = inspectionHoverAt(mouseX, mouseY);
                if (universalHover) tools.hoveredEntity = -1;
                else
                    tools.hoverEntity(
                            inViewport(mouseX, mouseY) ? mouseX : -100000,
                            mouseY,
                            inverse,
                            previewDepth());
                tools.renderSceneOverlays(graphics, pose);
                if (tools.pasting())
                    tools.renderPaste(
                            graphics,
                            pose,
                            inViewport(mouseX, mouseY) ? pick(mouseX, mouseY) : null);
            }
            if (!previewOnly
                    && !extensionToolActive()
                    && (pickDirty
                            || mouseX != lastMouseX
                            || mouseY != lastMouseY
                            || lastPickControl != creating()
                            || lastPickShift != deleting())) {
                lastPickControl = creating();
                lastPickShift = deleting();
                hovered = inViewport(mouseX, mouseY) ? pick(mouseX, mouseY) : null;
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                pickDirty = false;
            }
            if (!previewOnly
                    && !importMode
                    && !extensionToolActive()
                    && !allZones()
                    && !tools.entityTool()
                    && !tools.regionTool()
                    && !tools.inspectTool()
                    && (tools.tool != EditorToolsPanel.Tool.ASSEMBLY
                            || editMode != EditMode.SELECT)) {
                if (tools.blockPlacement()) tools.renderGhost(graphics, pose, hovered);
                else {
                    if (!tools.dragging() && !universalHover) {
                        if (tools.blocksTool()
                                && deleting()
                                && hovered != null
                                && !level.originalState(hovered).isAir())
                            SceneOutlines.deletion(graphics, pose, new AABB(hovered), 0xff3030);
                        else outline(graphics, pose, hovered, .9f, .9f, .9f);
                    }
                    outline(graphics, pose, selected, .25f, 1, .8f);
                }
            }
            if (!previewOnly && !importMode && !extensionToolActive() && !allZones())
                tools.renderEntityGhost(graphics, pose, hovered);
            if (inspectionHover != null && inspectionHover.bounds() != null)
                SceneOutlines.box(graphics, pose, inspectionHover.bounds(), 0xe6e6e6, false);
            int displayedAxes = visibleSymmetryAxes();
            if (displayedAxes != 0) {
                mirrorSpan();
                SceneOutlines.massPlanes(
                        graphics,
                        pose,
                        new Vec3(symmetryCenters[0], symmetryCenters[1], symmetryCenters[2]),
                        draftSize,
                        new boolean[] {
                            (displayedAxes & 1) != 0,
                            (displayedAxes & 2) != 0,
                            (displayedAxes & 4) != 0
                        },
                        symmetryVisible
                                ? 1
                                : (float)
                                        Math.sin(
                                                Math.PI
                                                        * Mth.clamp(
                                                                (symmetryFlashUntil
                                                                                - net.minecraft.Util
                                                                                        .getMillis())
                                                                        / 500d,
                                                                0,
                                                                1)),
                        0x60dfff);
            }
            if (zonesHeld) SceneOutlines.front(graphics, pose, draftSize);
            if (gridMode == GridMode.FLOOR)
                SceneOutlines.grid(
                        graphics, pose, gridBounds(), gridSpacing(), gridFade, blackGrid);
            float alpha = boundsAlpha();
            if (alpha > 0)
                SceneOutlines.dashed(
                        graphics,
                        pose,
                        new AABB(Vec3.ZERO, Vec3.atLowerCornerOf(draftSize)),
                        0xffffff,
                        alpha);
            if (special != null) SceneOutlines.zone(graphics, pose, special, 0xffffff, true);
            if (boundsPreview != null)
                SceneOutlines.dashed(graphics, pose, boundsPreview, 0xffca60);
            if (reviewBounds != null) SceneOutlines.dashed(graphics, pose, reviewBounds, 0xffca60);
            if (!previewOnly)
                for (var p : supportWarnings.keySet())
                    if (level.visible(p) && !hideWarnings)
                        SceneOutlines.patch(graphics, pose, new AABB(p), 0xff3030);
            if (selected != null
                    && !tools.inspectTool()
                    && supportWarnings.containsKey(selected)
                    && !hideWarnings)
                SceneOutlines.corners(graphics, pose, new AABB(selected), 0x40ffcc);
            if (cornerA != null && cornerB != null) {
                var region =
                        new AABB(
                                Math.min(cornerA.getX(), cornerB.getX()),
                                Math.min(cornerA.getY(), cornerB.getY()),
                                Math.min(cornerA.getZ(), cornerB.getZ()),
                                Math.max(cornerA.getX(), cornerB.getX()) + 1,
                                Math.max(cornerA.getY(), cornerB.getY()) + 1,
                                Math.max(cornerA.getZ(), cornerB.getZ()) + 1);
                SceneOutlines.zone(graphics, pose, region, tools.regionColor(), true);
            }
            graphics.bufferSource().endBatch();
            if (tools != null && !extensionToolActive()) tools.renderControls(graphics, pose);
            if (extensions != null)
                extensions.preview(pose, graphics.bufferSource(), cameraMatrix(), false);
            graphics.bufferSource().endBatch();
        } finally {
            // Brightness belongs to this scene, including a failed native render or buffer flush.
            RenderSystem.setShaderColor(
                    callerColor[0], callerColor[1], callerColor[2], callerColor[3]);
            pose.popPose();
            SceneOutlines.projection(new Matrix4f(), 1024);
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.clear(
                    org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
                    net.minecraft.client.Minecraft.ON_OSX);
            com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
            RenderSystem.disableDepthTest();
            if (!capture) graphics.disableScissor();
        }
    }

    private AABB viewBounds() {
        var base = new AABB(Vec3.ZERO, Vec3.atLowerCornerOf(draftSize));
        return reviewBounds == null ? base : base.minmax(reviewBounds);
    }

    private float fittedScale() {
        var extent = viewBounds();
        double diagonal =
                Math.sqrt(
                        extent.getXsize() * extent.getXsize()
                                + extent.getYsize() * extent.getYsize()
                                + extent.getZsize() * extent.getZsize());
        return (float)
                (Math.max(1, Math.min(layout.viewport().width(), layout.viewport().height()))
                        * .85
                        / diagonal);
    }

    private Matrix4f cameraLens() {
        return !firstPerson
                ? new Matrix4f()
                : com.eynny.npships.authoring.geometry.SchematicViewProjection.perspective(
                        layout.viewport().centerX(),
                        layout.viewport().centerY(),
                        Math.max(80, layout.viewport().height() * .9f),
                        firstPerson ? 32 : fittedScale() * zoom,
                        previewDepth());
    }

    private Matrix4f cameraMatrix() {
        return cameraLens().mul(cameraTransform().last().pose());
    }

    private PoseStack cameraTransform() {
        float scale = firstPerson ? 32 : fittedScale() * zoom;
        PoseStack camera = new PoseStack();
        camera.translate(
                layout.viewport().centerX(),
                layout.viewport().centerY(),
                firstPerson ? 200 + Math.max(80, layout.viewport().height() * .9f) : 200);
        camera.scale(scale, -scale, scale);
        camera.mulPose(Axis.XP.rotationDegrees(pitch));
        camera.mulPose(Axis.YP.rotationDegrees(yaw));
        camera.translate(-cameraTarget.x, -cameraTarget.y, -cameraTarget.z);
        return camera;
    }

    /** Bounded local search, only during navigation; cost is independent of schematic size. */
    private float navigationSpeed() {
        if (!firstPerson) return 1;
        long now = net.minecraft.Util.getMillis();
        if (now - navigationSpeedChecked < 100) return navigationSpeed;
        navigationSpeedChecked = now;
        double radius = 12, nearestSquared = radius * radius;
        int minX = Math.max(0, Mth.floor(cameraTarget.x - radius));
        int minY = Math.max(0, Mth.floor(cameraTarget.y - radius));
        int minZ = Math.max(0, Mth.floor(cameraTarget.z - radius));
        int maxX = Math.min(draftSize.getX() - 1, Mth.floor(cameraTarget.x + radius));
        int maxY = Math.min(draftSize.getY() - 1, Mth.floor(cameraTarget.y + radius));
        int maxZ = Math.min(draftSize.getZ() - 1, Mth.floor(cameraTarget.z + radius));
        var pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            double dx = Math.max(0, Math.max(x - cameraTarget.x, cameraTarget.x - x - 1));
            for (int y = minY; y <= maxY; y++) {
                double dy = Math.max(0, Math.max(y - cameraTarget.y, cameraTarget.y - y - 1));
                if (dx * dx + dy * dy >= nearestSquared) continue;
                for (int z = minZ; z <= maxZ; z++) {
                    double dz = Math.max(0, Math.max(z - cameraTarget.z, cameraTarget.z - z - 1));
                    double squared = dx * dx + dy * dy + dz * dz;
                    if (squared >= nearestSquared) continue;
                    pos.set(x, y, z);
                    if (!level.originalState(pos).isAir()) {
                        nearestSquared = squared;
                        if (squared <= 4) return navigationSpeed = .25f;
                    }
                }
            }
        }
        double distance = Math.sqrt(nearestSquared);
        if (document.blocks().isEmpty()) {
            distance =
                    cameraTarget.distance(
                            new Vector3f(draftSize.getX() / 2f, 0, draftSize.getZ() / 2f));
            return navigationSpeed = .25f + (float) Math.max(0, distance - 2) / 32;
        }
        if (nearestSquared == radius * radius)
            distance =
                    Math.max(
                            distance,
                            level.occupiedSectionDistance(
                                    cameraTarget.x, cameraTarget.y, cameraTarget.z));
        return navigationSpeed = .25f + (float) Math.max(0, distance - 2) / 32;
    }

    /** Approximate grab-scale panning from nearby geometry, independent of cursor position. */
    private float nearbyPanScale() {
        if (!firstPerson) return 1;
        float distance = 2 + Math.max(0, navigationSpeed() - .25f) * 32;
        float focal = Math.max(80, layout.viewport().height() * .9f);
        return Math.max(.375f, distance * 32 / focal);
    }

    /** Move the orbit depth along the centre ray, without changing the visible framing. */
    private void focusOrbit() {
        focusOrbit(layout.viewport().centerX(), layout.viewport().centerY());
    }

    private void focusOrbit(float x, float y) {
        var inv = new Matrix4f(cameraTransform().last().pose()).invert();
        Vec3 from = new Vec3(inv.transformPosition(new Vector3f(x, y, previewDepth())));
        Vec3 to = new Vec3(inv.transformPosition(new Vector3f(x, y, -previewDepth())));
        var bounds = sliceBounds().inflate(.001);
        var entry = bounds.clip(from, to);
        var exit = bounds.clip(to, from);
        Vec3 nearest = null;
        if (entry.isPresent() && exit.isPresent()) {
            var hit =
                    level.clip(
                            new ClipContext(
                                    entry.get(),
                                    exit.get(),
                                    ClipContext.Block.OUTLINE,
                                    ClipContext.Fluid.ANY,
                                    CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.BLOCK) {
                nearest = hit.getLocation();
            }
        }
        if (tools != null) {
            var entityHit = tools.orbitHit(from, to);
            if (entityHit != null
                    && (nearest == null
                            || from.distanceToSqr(entityHit) < from.distanceToSqr(nearest)))
                nearest = entityHit;
        }
        if (nearest != null) {
            // Change only orbit depth; retain the current screen position of the work site.
            var centerRay =
                    new Vec3(
                            inv.transformPosition(
                                    new Vector3f(
                                            layout.viewport().centerX(),
                                            layout.viewport().centerY(),
                                            previewDepth())));
            cameraTarget.set(nearest.add(centerRay.subtract(from)).toVector3f());
            return;
        }
        // Only empty scenes need a construction plane for orbit focus.
        if (!objects.isEmpty() || tools != null && tools.hasOrbitEntities()) return;
        var plane =
                com.eynny.npships.authoring.geometry.PreviewSlice.intersect(
                        from, to, ceiling, sliceMode);
        if (plane != null
                && new AABB(0, 0, 0, draftSize.getX(), draftSize.getY(), draftSize.getZ())
                        .inflate(.001)
                        .contains(plane)) cameraTarget.set(plane.toVector3f());
    }

    private void outline(
            final GuiGraphics graphics,
            final PoseStack pose,
            final BlockPos pos,
            final float r,
            final float g,
            final float b) {
        if (pos == null || !level.visible(pos) || level.originalState(pos).isAir()) return;
        SceneOutlines.box(
                graphics,
                pose,
                new AABB(pos),
                ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255),
                false);
    }

    private long boundsUntil, lastBoundsFrame;
    private float boundsAlpha;
    private boolean attemptedOutside;

    void flashBounds() {
        boundsUntil = net.minecraft.Util.getMillis() + 3000;
    }

    boolean checkBounds(AABB b) {
        boolean valid =
                b.minX >= 0
                        && b.minY >= 0
                        && b.minZ >= 0
                        && b.maxX <= draftSize.getX()
                        && b.maxY <= draftSize.getY()
                        && b.maxZ <= draftSize.getZ();
        if (!valid) flashBounds();
        return valid;
    }

    private float boundsAlpha() {
        long now = net.minecraft.Util.getMillis();
        float dt = lastBoundsFrame == 0 ? 0 : Math.min(.1f, (now - lastBoundsFrame) / 1000f);
        lastBoundsFrame = now;
        boolean show = pinSchematicBounds || zonesHeld || now < boundsUntil - 300;
        boundsAlpha = Mth.clamp(boundsAlpha + (show ? 1 : -1) * dt / .3f, 0, 1);
        return boundsAlpha * boundsAlpha * (3 - 2 * boundsAlpha);
    }

    private boolean pickingExisting;

    BlockPos blockUnderCursor() {
        return pickExisting(lastMouseX, lastMouseY);
    }

    private BlockPos pickExisting(double x, double y) {
        pickingExisting = true;
        try {
            return pick(x, y);
        } finally {
            pickingExisting = false;
        }
    }

    List<EditorInspection.Candidate> inspectionCandidates(double x, double y) {
        if (inverse == null || !inViewport(x, y) || tools == null) return List.of();
        var ray = tools.ray(x, y, inverse, previewDepth());
        return EditorInspection.collect(
                current(), level, tools.entityPreview, sliceBounds(), ray[0], ray[1]);
    }

    List<EditorInspection.Candidate> authoringCandidates(double x, double y) {
        if (inverse == null || !inViewport(x, y) || tools == null) return List.of();
        var ray = tools.ray(x, y, inverse, previewDepth());
        var segments = tools.segments;
        var zones = tools.zones;
        if (tools.cached != current()) {
            var config = current().configCopy();
            segments = com.eynny.npships.authoring.StructureSegments.read(config);
            zones = com.eynny.npships.authoring.StructureCrew.read(config);
        }
        return EditorInspection.collectAuthoring(
                current(),
                tools.layers,
                tools.entityPreview,
                level,
                isPart() ? List.of() : segments,
                isPart() ? List.of() : zones,
                ray[0],
                ray[1]);
    }

    private List<EditorInspection.Candidate> selectionCandidates(double x, double y) {
        // A deferred ordinary press retains its input domain even if Alt changes before release.
        if (allZones() && !replayingInspectionValidated) return authoringCandidates(x, y);
        if (tools.regionTool()) {
            var preferred =
                    authoringCandidates(x, y).stream()
                            .filter(
                                    candidate ->
                                            switch (tools.tool) {
                                                case AREA ->
                                                        !EditorInspection.areaId(candidate.target())
                                                                .isEmpty();
                                                case ZONES ->
                                                        candidate.target()
                                                                instanceof
                                                                EditorInspection.MobZoneTarget;
                                                case SEGMENTS ->
                                                        candidate.target()
                                                                instanceof
                                                                EditorInspection.SegmentTarget;
                                                case GLUE ->
                                                        candidate.target()
                                                                instanceof
                                                                EditorInspection.GlueTarget;
                                                default -> false;
                                            })
                            .toList();
            if (!preferred.isEmpty()) return preferred;
        }
        var hits = inspectionCandidates(x, y);
        if (tools.entityTool()) {
            var entities =
                    hits.stream()
                            .filter(
                                    candidate ->
                                            candidate.target()
                                                    instanceof EditorInspection.EntityTarget)
                            .toList();
            if (!entities.isEmpty()) return entities;
        }
        return hits;
    }

    private EditorAuthoringCycle.Context authoringContext() {
        return new EditorAuthoringCycle.Context(
                current(),
                tools.layers,
                new Matrix4f(inverse).invert(),
                ceiling,
                sliceMode,
                interactionRange,
                firstPerson);
    }

    EditorInspection.Candidate inspectionHoverAt(double x, double y) {
        authoringHoverPlan = null;
        universalHover =
                inverse != null
                        && inspectionReady()
                        && inViewport(x, y)
                        && !viewMenu.contains(x, y)
                        && !tools.overlayAt(x, y)
                        && !(reachSlider != null
                                && reachSlider.visible
                                && reachSlider.isMouseOver(x, y))
                        && !tools.authoringGestureAt(x, y, inverse, previewDepth())
                        && tools.assemblyPoints.hit(x, y, inverse, previewDepth()) == null;
        if (!universalHover) {
            authoringCycle.reset();
            return null;
        }
        var context = authoringContext();
        var key =
                new HoverKey(
                        context,
                        x,
                        y,
                        allZones(),
                        isPart(),
                        tools.entityPreview.entitySource,
                        List.copyOf(tools.entityPreview.failedEntities),
                        tools.tool);
        if (!key.equals(hoverKey)) {
            hoverCandidates = selectionCandidates(x, y);
            hoverKey = key;
        }
        if (inspectionReady()) {
            var plan = authoringCycle.propose(context, hoverCandidates, x, y);
            authoringHoverPlan = plan;
            return plan == null ? null : plan.candidate();
        }
        return hoverCandidates.isEmpty() ? null : hoverCandidates.getFirst();
    }

    private Component blockWarning(BlockPos position) {
        if (!showsWarnings() || editMode != EditMode.SELECT || position == null) return null;
        var warning = tools.warning(position);
        return warning == null ? supportWarnings.get(position) : warning;
    }

    private Component authoringHoverLabel(EditorAuthoringCycle.Plan plan) {
        var target = plan.candidate().target();
        Component kind =
                tools.tr(
                        switch (target) {
                            case EditorInspection.BlockTarget block -> "blocks";
                            case EditorInspection.EntityTarget entity -> "entities";
                            case EditorInspection.AreaTarget area -> "area_custom_label";
                            case EditorInspection.PlaneTarget plane -> "fitted_plane";
                            case EditorInspection.SegmentTarget segment -> "segments";
                            case EditorInspection.MobZoneTarget zone -> "zones";
                            case EditorInspection.GlueTarget glue -> "glue";
                        });
        var area = tools.layers.find(EditorInspection.areaId(target));
        // Hover labels never resolve native entities or mutate the accepted document. A stale
        // layer reference can still be identified by its authored ID without an unsafe cast.
        String name =
                switch (target) {
                    case EditorInspection.BlockTarget block -> block.position().toShortString();
                    case EditorInspection.EntityTarget entity -> "#" + entity.index();
                    case EditorInspection.AreaTarget layer ->
                            area == null ? layer.id() : area.name();
                    case EditorInspection.PlaneTarget plane ->
                            area == null ? plane.id() : area.name();
                    case EditorInspection.SegmentTarget segment -> segment.id();
                    case EditorInspection.MobZoneTarget zone -> zone.id();
                    case EditorInspection.GlueTarget glue -> "#" + glue.index();
                };
        var label =
                Component.translatable(
                        "npships.tools.authoring_select_hover",
                        kind,
                        name,
                        plan.index() + 1,
                        plan.targets().size());
        if (target instanceof EditorInspection.BlockTarget block) {
            var warning = blockWarning(block.position());
            if (warning != null) label.append("\n").append(warning);
        }
        return label;
    }

    BlockPos pick(final double x, final double y) {
        attemptedOutside = false;
        if (inverse == null) return null;
        Vector3f start =
                inverse.transformProject(new Vector3f((float) x, (float) y, previewDepth()));
        Vector3f end =
                inverse.transformProject(new Vector3f((float) x, (float) y, -previewDepth()));
        var limited = interactionRay(new Vec3(start), new Vec3(end));
        Vec3 from = limited[0], to = limited[1];
        var size = draftSize;
        var bounds = sliceBounds().inflate(.001);
        if (tools != null && tools.filteredBlockTool()) {
            net.minecraft.world.phys.BlockHitResult nearest = null;
            double distance = Double.POSITIVE_INFINITY;
            for (var position : tools.pickableBlocks()) {
                if (!level.visible(position)) continue;
                var shape = net.minecraft.world.phys.shapes.Shapes.block();
                var candidate = shape.clip(from, to, position);
                if (candidate != null && candidate.getLocation().distanceToSqr(from) < distance) {
                    nearest = candidate;
                    distance = candidate.getLocation().distanceToSqr(from);
                }
            }
            if (nearest == null) return null;
            hitFace = nearest.getDirection();
            return nearest.getBlockPos().immutable();
        }
        var clipped =
                com.eynny.npships.authoring.geometry.SchematicViewProjection.clip(bounds, from, to);
        var hit =
                clipped.isPresent()
                        ? level.clip(
                                new ClipContext(
                                        clipped.get().start(),
                                        clipped.get().end(),
                                        ClipContext.Block.OUTLINE,
                                        ClipContext.Fluid.ANY,
                                        CollisionContext.empty()))
                        : null;
        boolean adjacent =
                !pickingExisting
                        && tools != null
                        && (tools.fittedPlane != null
                                || !tools.regionTool() && tools.wantsAdjacent());
        if (hit != null
                && hit.getType() == HitResult.Type.BLOCK
                && containsDraft(hit.getBlockPos())) {
            if (adjacent && tools.canCombineBlock(level.originalState(hit.getBlockPos()))) {
                hitFace = hit.getDirection();
                return hit.getBlockPos().immutable();
            }
            hitFace = hit.getDirection();
            var p = adjacent ? hit.getBlockPos().relative(hitFace) : hit.getBlockPos();
            if (!adjacent && tools != null && !tools.acceptsBlock(p)) return null;
            attemptedOutside = !containsDraft(p);
            return attemptedOutside || !adjacent || level.originalState(p).isAir()
                    ? p.immutable()
                    : null;
        }
        if (!adjacent && (tools == null || !tools.createRegion())) return null;
        // Empty cells remain editable on either orientation of the construction plane.
        var point =
                com.eynny.npships.authoring.geometry.PreviewSlice.intersect(
                        from, to, ceiling, sliceMode);
        if (point != null) {
            var pos = BlockPos.containing(point);
            attemptedOutside = !containsDraft(pos);
            return pos;
        }
        return null;
    }

    private void chooseRegionCorner(BlockPos hit) {
        if (!choosingCorners) {
            tools.beginRegion();
            cornerA = hit;
            cornerB = hit;
            selecting = true;
            choosingCorners = true;
            status(Component.translatable("npships.editor.second_corner").getString());
        } else {
            cornerB = hit;
            choosingCorners = false;
            selecting = false;
            tools.finishRegion();
        }
        tools.selectionChanged();
        pickDirty = true;
    }

    @Override
    public void mouseMoved(double x, double y) {
        authoringCycle.pointer(x, y);
        dividerFocus();
        inspectorDivider.cursor(
                minecraft.getWindow().getWindow(),
                minecraft.isWindowActive()
                        && (inspectorDivider.dragging() || dividerAvailable(x, y)));
        if (inspectorDivider.captured()) return;
        super.mouseMoved(x, y);
        if (cameraDragButton >= 0 && !inViewport(x, y)) {
            cameraDragButton = -1;
            orbitGesture = false;
            setDragging(false);
        }
        if (choosingCorners && inViewport(x, y)) {
            var hit = pick(x, y);
            if (hit != null && !attemptedOutside) {
                cornerB = hit;
                pickDirty = true;
            }
        }
    }

    private boolean inViewport(final double x, final double y) {
        return !shortcutReference.isOpen()
                && !inspectorDivider.captured()
                && layout.sceneUsable()
                && (tools == null || !tools.expandedWorkspace())
                && layout.viewport().contains(x, y);
    }

    @Override
    public boolean mouseClicked(final double rawX, final double rawY, final int button) {
        if (retainsFirstPersonMouse()) {
            if (!minecraft.isWindowActive() || minecraft.getOverlay() != null) {
                releaseFirstPersonMouse();
                return true;
            }
        }
        final double x = retainsFirstPersonMouse() ? layout.viewport().centerX() : rawX;
        final double y = retainsFirstPersonMouse() ? layout.viewport().centerY() : rawY;
        dividerFocus();
        if (shortcutReference.isOpen()) {
            shortcutReference.layout(width, height);
            shortcutReference.click(x, y, button);
            return true;
        }
        if (inspectorDivider.dragging()
                || inspectorDivider.captured() && (button != 0 || !minecraft.isWindowActive()))
            return true;
        inspectorDivider.newPress();
        if (extensions != null
                && extensions.modal()
                && !(viewMenu.isOpen() && !viewMenu.isSuspended())
                && extensions.mouse(EditorExtension.MouseAction.PRESS, x, y, button, 0, 0, 0))
            return true;
        if (!extensionPanelActive()
                && tools != null
                && tools.overlayAt(x, y)
                && tools.clickOverlay(x, y, button)) return true;
        if (viewMenu.contains(x, y) && viewMenu.click(x, y, button)) {
            setFocused((GuiEventListener) null);
            return true;
        }
        if (!extensionPanelActive() && tools != null && tools.dismissValuePickerOutside(x, y))
            return true;
        if (dividerAvailable(x, y) && inspectorDivider.press(x, y, button)) return true;
        if (!extensionPanelActive() && tools != null && tools.clickTextPopup(x, y, button))
            return true;
        if (!extensionPanelActive() && tools != null && tools.sections.click(x, y, button))
            return true;
        if (com.eynny.npships.client.editor.widget.EditorMessageCopy.click(
                minecraft,
                feedback().getString(),
                x,
                y,
                button,
                layout.status().left(),
                layout.status().top(),
                layout.status().width(),
                layout.status().height())) {
            if (feedback() == cachedPreviewWarnings)
                status(Component.translatable("npships.editor.details_copied").getString());
            return true;
        }
        if ((tools == null || !tools.expandedWorkspace())
                && renderError != null
                && com.eynny.npships.client.editor.widget.EditorMessageCopy.click(
                        minecraft,
                        renderError.getString(),
                        x,
                        y,
                        button,
                        left,
                        top,
                        right - left,
                        18)) return true;
        if ((tools == null || !tools.expandedWorkspace())
                && document.hasMultiplePalettes()
                && com.eynny.npships.client.editor.widget.EditorMessageCopy.click(
                        minecraft,
                        Component.translatable("npships.editor.first_palette").getString(),
                        x,
                        y,
                        button,
                        left,
                        bottom - 18,
                        right - left,
                        18)) return true;

        if (reachSlider != null && reachSlider.visible) {
            if (reachSlider.isMouseOver(x, y)) {
                reachDragging = button == 0;
                return reachSlider.mouseClicked(x, y, button);
            }
            if (y >= 30) {
                reachOpen = false;
                reachSlider.visible = false;
            }
        }
        if (viewMenu.click(x, y, button)) return true;
        if (extensions != null
                && extensions.mouse(EditorExtension.MouseAction.PRESS, x, y, button, 0, 0, 0))
            return true;
        if (extensionToolActive() && button == 0 && inViewport(x, y)) return true;
        if (button == 0 && inViewport(x, y) && tools != null && tools.mergeChoosing) {
            tools.pickRegion(x, y, inverse, previewDepth());
            return true;
        }
        if (button == 0 && inViewport(x, y) && pickSymmetry(x, y)) return true;
        if (tools != null && tools.pasting() && inViewport(x, y) && button == 0) {
            tools.acceptPaste();
            return true;
        }
        if (iconCapture != null
                && gridMode == GridMode.FLOOR
                && button == 0
                && inViewport(x, y)
                && snapshotGrid.begin(x, y, cameraMatrix())) return true;
        if (tools != null && tools.codeAt(x, y)) return tools.mouseClicked(x, y, button);
        if (!previewOnly && layout.information().contains(x, y) && infoBar.click(x, y, button))
            return true;
        if (!previewOnly
                && !importMode
                && button == 0
                && inViewport(x, y)
                && tools != null
                && tools.patchRegionTool.choosing()) {
            tools.patchRegionTool.begin(x, y, inverse, previewDepth());
            return true;
        }
        if (!previewOnly
                && !importMode
                && button == 0
                && inViewport(x, y)
                && tools != null
                && (tools.tool == EditorToolsPanel.Tool.ASSEMBLY && editMode != EditMode.SELECT
                        || (tools.tool == EditorToolsPanel.Tool.ASSEMBLY || allZones())
                                && tools.assemblyPoints.hit(x, y, inverse, previewDepth())
                                        != null)) {
            if (!tools.blur()) return true;
            setFocused((GuiEventListener) null);
            if (editMode == EditMode.SELECT
                    && !replayingSelectionClick
                    && tools.tool == EditorToolsPanel.Tool.ASSEMBLY
                    && tools.beginDrag(x, y, inverse, previewDepth())) {
                deferredSelectionClick = true;
                deferredInspectionValidated = false;
                // A press/release without movement must replay the point's deselect callback.
                deferredAuthoringGesture = false;
                selectionClickX = x;
                selectionClickY = y;
            } else tools.assemblyPoints.click(x, y, inverse, previewDepth());
            return true;
        }
        boolean authoringGesture =
                allZones()
                        && !replayingInspectionValidated
                        && tools != null
                        && tools.authoringGestureAt(x, y, inverse, previewDepth());
        boolean inspection =
                inverse != null
                        && button == 0
                        && inViewport(x, y)
                        && inspectionReady()
                        && !authoringGesture;
        boolean authoring = inspection;
        if (!authoring) authoringCycle.reset();
        var inspectionSource = current();
        var inspectionLayers = tools == null ? null : tools.layers;
        var candidates =
                !inspection ? List.<EditorInspection.Candidate>of() : selectionCandidates(x, y);
        var cyclePlan =
                authoring ? authoringCycle.propose(authoringContext(), candidates, x, y) : null;
        var inspectionTarget =
                cyclePlan != null
                        ? cyclePlan.candidate()
                        : candidates.isEmpty() ? null : candidates.getFirst();
        // Alt inspects authored regions only. Empty Alt input retains its existing selection
        // and must not accept a draft; ordinary empty clicks still clear inspection below.
        if (authoring && allZones() && !replayingInspectionValidated && inspectionTarget == null)
            return true;
        // Nonunique Alt hits may revisit the current target after a cycle reset. Keep that
        // no-op draft-neutral; only the unique repeat below changes selection to deselected.
        if (authoring
                && allZones()
                && !replayingInspectionValidated
                && candidates.size() > 1
                && tools.inspectionMatches(inspectionTarget)) {
            if (cyclePlan != null) authoringCycle.accepted(cyclePlan);
            return true;
        }
        if (inspection && tools != null) {
            if (tools.changingLocalLootTarget(inspectionTarget)) return true;
            tools.discardPatchDraftForInspection(inspectionTarget);
        }
        if (!previewOnly
                && button == 0
                && inViewport(x, y)
                && tools != null
                && (!replayingInspectionValidated || tools.hasPendingEdits())
                && !tools.blur()) return true;
        if (!previewOnly && button == 0 && inViewport(x, y)) setFocused((GuiEventListener) null);
        if (!previewOnly
                && inViewport(x, y)
                && button == 0
                && !replayingSelectionClick
                && editMode == EditMode.SELECT
                && (!allZones() || authoringGesture)
                && tools.beginDrag(x, y, inverse, previewDepth())) {
            deferredSelectionClick = true;
            deferredInspectionValidated = inspection;
            deferredAuthoringGesture = authoringGesture;
            selectionClickX = x;
            selectionClickY = y;
            return true;
        }
        if (inViewport(x, y) && button == 2) {
            setFocused((GuiEventListener) null);
            cameraDragButton = button;
            navigationSpeed();
            return true;
        }
        if (inViewport(x, y) && button == 1) {
            setFocused((GuiEventListener) null);
            cameraDragButton = button;
            if (!firstPerson) focusOrbit();
            orbitGesture = true;
            return true;
        }
        if (!previewOnly && inViewport(x, y) && button == 0) {
            if (importMode) {
                if (importResizable && creating()) {
                    var p = pick(x, y);
                    if (p != null && !attemptedOutside) {
                        cornerA = p;
                        cornerB = p;
                        selecting = true;
                    }
                }
                return true;
            }
            if (resizeMode) {
                var ray =
                        interactionRay(
                                new Vec3(
                                        inverse.transformProject(
                                                new Vector3f(
                                                        (float) x, (float) y, previewDepth()))),
                                new Vec3(
                                        inverse.transformProject(
                                                new Vector3f(
                                                        (float) x, (float) y, -previewDepth()))));
                if (com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                                new AABB(Vec3.ZERO, Vec3.atLowerCornerOf(draftSize)),
                                ray[0],
                                ray[1])
                        .isEmpty()) resizeDocument();
                return true;
            }
            if (inspection) {
                if (current() != inspectionSource || tools.layers != inspectionLayers) {
                    // A validated field may move/delete/reorder objects. Recollect current hits
                    // instead of activating an index from the pre-flush document version.
                    tools.preparePreview();
                    candidates = selectionCandidates(x, y);
                    cyclePlan =
                            authoring
                                    ? authoringCycle.propose(authoringContext(), candidates, x, y)
                                    : null;
                    inspectionTarget =
                            cyclePlan != null
                                    ? cyclePlan.candidate()
                                    : candidates.isEmpty() ? null : candidates.getFirst();
                }
                if (inspectionTarget == null
                        || candidates.size() == 1 && tools.inspectionMatches(inspectionTarget)) {
                    if (inspectionTarget == null) tools.deselectInspectionValidated();
                    else clearObjectSelectionValidated();
                    authoringCycle.reset();
                } else if (tools.inspectValidated(inspectionTarget) && cyclePlan != null)
                    authoringCycle.accepted(cyclePlan);
                refreshActions();
                return true;
            }
            if (creating()) {
                var controlHit = pick(x, y);
                if (attemptedOutside || controlHit == null) {
                    clearRegion();
                    selected = null;
                    tools.clearSelection();
                    flashBounds();
                    return true;
                }
            }
            if (!repeatingPaint && tools.repeatTool() && editMode != EditMode.SELECT) {
                heldPaint = true;
                paintStarted = net.minecraft.Util.getMillis();
                paintTicks = 0;
                lastPaintEntity = null;
            }
            if (tools.membershipAt(pickExisting(x, y))) return true;
            if (tools.inspectTool() && !allZones()) return true;
            if (tools.pickDeviceLink(pickExisting(x, y))) return true;
            if (tools.createRegion()) {
                var hit = pick(x, y);
                if (tools.fittedPlane != null) {
                    if (!attemptedOutside) tools.run(() -> tools.placeFittedPlane(hit));
                    return true;
                }
                if (hit != null && !attemptedOutside) chooseRegionCorner(hit);
                return true;
            }
            if (tools.entityTool()) {
                if (tools.entityPlacement()) {
                    var target = pick(x, y);
                    if (!repeatingPaint || !java.util.Objects.equals(target, lastPaintEntity)) {
                        tools.placeEntityAt(target);
                        lastPaintEntity = target;
                    }
                } else tools.pickEntity(x, y, inverse, previewDepth());
                return true;
            }
            if (tools.pickRegion(x, y, inverse, previewDepth())) return true;
            var hit = pick(x, y);

            if (tools.blocksTool() && editMode != EditMode.SELECT) {
                clearRegion();
                tools.placeBlockAt(hit, deleting());
                refreshActions();
                return true;
            }
            clearRegion();
            selected =
                    java.util.Objects.equals(selected, hit) && editMode == EditMode.SELECT
                            ? null
                            : hit;
            tools.clickedBlock();
            if (tools.blocksTool() && creating()) selected = null;
            tools.selectionChanged();
            refreshActions();
            return true;
        }
        if (!previewOnly && !importMode && tools != null && tools.mouseClicked(x, y, button))
            return true;
        boolean consumed = super.mouseClicked(x, y, button);
        // Mouse actions need no retained keyboard capture. Tab/Enter focus remains native.
        if (consumed && getFocused() instanceof EditorIconButton)
            setFocused((GuiEventListener) null);
        return consumed;
    }

    @Override
    public boolean mouseDragged(
            final double x, final double y, final int button, final double dx, final double dy) {
        dividerFocus();
        if (shortcutReference.isOpen()) {
            shortcutReference.drag(y);
            return true;
        }
        if (inspectorDivider.captured()) {
            if (button == 0) inspectorDivider.drag(x);
            return true;
        }
        if (extensions != null
                && cameraDragButton != button
                && extensions.mouse(EditorExtension.MouseAction.DRAG, x, y, button, dx, dy, 0))
            return true;
        if (button == 0 && tools != null && tools.inventoryExpanded && tools.inventoryBar.drag(y))
            return true;
        if (button == 0 && symmetryDrag >= 0 && layout.sceneUsable()) {
            double delta =
                    ((x - symmetryMouseX) * symmetryDirection.x
                                    + (y - symmetryMouseY) * symmetryDirection.y)
                            / symmetryDirection.lengthSquared();
            int limit =
                    symmetryDrag == 0
                            ? draftSize.getX()
                            : symmetryDrag == 1 ? draftSize.getY() : draftSize.getZ();
            symmetryCenters[symmetryDrag] =
                    Mth.clamp(Math.round((symmetryDragStart + delta) * 2) / 2d, 0, limit);
            return true;
        }
        if (button == 0 && reachSlider != null && reachSlider.visible && reachDragging)
            return reachSlider.mouseDragged(x, y, button, dx, dy);
        if (button == 0 && iconCapture != null && snapshotGrid.drag(x, y)) return true;
        if (!previewOnly && button == 0 && tools != null && tools.patchRegionTool.drag(x, y))
            return true;
        if (button == 0 && infoBar.drag(y)) return true;
        if (!previewOnly && button == 0 && layout.sceneUsable() && tools.dragging())
            return tools.drag(x, y, cameraMatrix());
        if (cameraDragButton != button
                && !previewOnly
                && tools != null
                && tools.mouseDragged(x, y, button, dx, dy)) return true;
        if (button == 0 && selecting && layout.sceneUsable()) {
            var hit = pick(x, y);
            if (attemptedOutside) flashBounds();
            if (hit != null && !attemptedOutside) cornerB = hit;
            if (tools != null) tools.selectionChanged();
            refreshActions();
            return true;
        }
        if (cameraDragButton == button && layout.sceneUsable()) {
            if (button == 1) {
                if (!orbitGesture) {
                    if (!firstPerson) focusOrbit();
                    orbitGesture = true;
                }
                float sensitivity =
                        com.eynny.npships.config.NPShipsEditorConfig.orbit()
                                * (firstPerson ? .35f : 1);
                yaw += (float) dx * sensitivity;
                pitch = Mth.clamp(pitch + (float) dy * sensitivity, -89, 89);
                pickDirty = true;
                return true;
            }
            if (button == 2) {
                var delta =
                        new Matrix4f(cameraTransform().last().pose())
                                .invert()
                                .transformDirection(
                                        new Vector3f((float) dx, (float) dy, 0)
                                                .mul(nearbyPanScale()));
                cameraTarget.sub(delta);
                if (!firstPerson) focusOrbit();
                pickDirty = true;
                return true;
            }
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(
            final double rawX, final double rawY, final double horizontal, final double vertical) {
        final double x = retainsFirstPersonMouse() ? layout.viewport().centerX() : rawX;
        final double y = retainsFirstPersonMouse() ? layout.viewport().centerY() : rawY;
        dividerFocus();
        if (shortcutReference.isOpen()) {
            shortcutReference.layout(width, height);
            shortcutReference.wheel(vertical);
            return true;
        }
        if (inspectorDivider.captured()) return true;
        if (viewMenu.contains(x, y)) return true;
        if (extensions != null
                && extensions.mouse(EditorExtension.MouseAction.SCROLL, x, y, -1, 0, 0, vertical))
            return true;
        if (tools != null && tools.scrollTextPopup(x, y, vertical)) return true;
        if (tools != null && tools.codeAt(x, y)) return tools.mouseScrolled(x, y, vertical);
        if (slider != null && slider.isMouseOver(x, y)) {
            setCeiling(ceiling + (int) Math.signum(vertical));
            return true;
        }
        if (!previewOnly && layout.information().contains(x, y)) {
            preferredInfoScroll =
                    infoScroll =
                            Mth.clamp(
                                    infoScroll - (int) (vertical * 16),
                                    0,
                                    Math.max(0, infoHeight - layout.information().height()));
            return true;
        }
        if (!previewOnly && tools != null && tools.mouseScrolled(x, y, vertical)) return true;
        if (!previewOnly
                && inViewport(x, y)
                && creating()
                && hasControlDown()
                && (tools.blocksTool() || tools.entityPlacement())) {
            tools.rotateBrush((int) Math.signum(vertical));
            pickDirty = true;
            return true;
        }
        if (!previewOnly
                && inViewport(x, y)
                && tools != null
                && (tools.dragging() || hasControlDown() && !creating())) {
            tools.rotateSelected((int) Math.signum(vertical));
            return true;
        }
        if (inViewport(x, y)) {
            authoringCycle.reset();
            if (firstPerson) {
                float travel = (float) vertical * 2.2f * navigationSpeed();
                var forward =
                        new Matrix4f()
                                .rotateX((float) Math.toRadians(pitch))
                                .rotateY((float) Math.toRadians(yaw))
                                .invert()
                                .transformDirection(new Vector3f(0, 0, -travel));
                cameraTarget.add(forward);
                pickDirty = true;
                return true;
            }
            if (hasShiftDown()) setCeiling(ceiling + (int) Math.signum(vertical));
            else {
                zoom =
                        Mth.clamp(
                                zoom
                                        * (float)
                                                Math.pow(
                                                        com.eynny.npships.config.NPShipsEditorConfig
                                                                .zoom(),
                                                        vertical),
                                .2f,
                                Math.max(4f, 96f / fittedScale()));
            }
            pickDirty = true;
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    public void restoreHistory(boolean redo) {
        if (importMode) return;
        if (saving != null || tools.deferHistory(redo)) return;
        tools.prepareUndo();
        try {
            var restored = redo ? history.redo(working) : history.undo(working);
            if (restored != working) {
                special = null;
                resizeMode = false;
                boundsPreview = null;
                setDraft(restored);
                if (!tools.restoreSelection(restored)) {
                    clearRegion();
                    tools.clearSelection();
                }
            }
        } finally {
            tools.finishUndo();
            refreshActions();
        }
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        shortcuts.released(key, scan);
        boolean releasedFlight = flightKeys.remove(key);
        boolean releasedZones = key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT;
        if (releasedZones) zonesHeld = false;
        // A modal must not strand native held-key state or leak its release to addon tools.
        if (shortcutReference.isOpen()
                || inspectorDivider.captured()
                || viewMenu.isOpen() && !viewMenu.isSuspended()) return true;
        if (extensions != null && extensions.key(key, scan, modifiers, false)) return true;
        return releasedFlight || releasedZones || super.keyReleased(key, scan, modifiers);
    }

    @Override
    public boolean keyPressed(final int key, final int scanCode, final int modifiers) {
        if (retainsFirstPersonMouse()
                && (key == GLFW.GLFW_KEY_ESCAPE
                        || key == GLFW.GLFW_KEY_TAB
                        || key == GLFW.GLFW_KEY_F1)) {
            if (!shortcuts.edge(key, scanCode, modifiers)) return true;
            releaseFirstPersonMouse();
            return true;
        }
        if (firstPerson
                && freeFirstPersonLook
                && key == GLFW.GLFW_KEY_TAB
                && !hasShiftDown()
                && inViewport(lastMouseX, lastMouseY)
                && !keyboardControl()
                && !extensionToolActive()
                && (extensions == null || !extensions.modal())
                && (tools == null
                        || !tools.editingText()
                                && !tools.keyboardPopup()
                                && !tools.expandedWorkspace())) {
            if (!shortcuts.edge(key, scanCode, modifiers)) return true;
            captureFirstPersonMouse();
            if (retainsFirstPersonMouse()) return true;
        }
        dividerFocus();
        if (shortcuts.repeatedOwner(key, scanCode, modifiers)) return true;
        boolean escape = key == GLFW.GLFW_KEY_ESCAPE;
        if (escape && !shortcuts.edge(key, scanCode, modifiers)) return true;
        if (inspectorDivider.captured()) {
            if (escape) inspectorDivider.cancel();
            return true;
        }
        // The popup owns navigation and activation; no key leaks into tools or camera motion.
        if (shortcutReference.isOpen()) {
            shortcutReference.key(key);
            return true;
        }
        if (viewMenu.isOpen() && !viewMenu.isSuspended()) {
            if (escape) viewMenu.keyPressed(key, modifiers);
            else {
                boolean activate =
                        key == GLFW.GLFW_KEY_SPACE
                                || key == GLFW.GLFW_KEY_ENTER
                                || key == GLFW.GLFW_KEY_KP_ENTER;
                if (!activate || shortcuts.edge(key, scanCode, modifiers))
                    viewMenu.keyPressed(key, modifiers);
            }
            return true;
        }
        if (extensions != null && extensions.key(key, scanCode, modifiers, true)) return true;
        if (escape && tools != null && tools.dismissKeyboardPopup(key, scanCode, modifiers))
            return true;
        if (escape && tools != null && tools.patchRegionTool.choosing()) {
            tools.patchRegionTool.cancel();
            return true;
        }
        if (presentationKey(key, modifiers)
                && shortcuts.dispatchPresentation(key, scanCode, modifiers)) return true;
        boolean activation =
                key == GLFW.GLFW_KEY_SPACE
                        || key == GLFW.GLFW_KEY_ENTER
                        || key == GLFW.GLFW_KEY_KP_ENTER;
        if (tools != null && tools.keyboardPopup()) {
            if (tools.keyboardPopupActivation(key, modifiers)
                    && !shortcuts.edge(key, scanCode, modifiers)) return true;
            if (tools.referenceKeyPressed(key, scanCode, modifiers)) return true;
            // An open reference owns Save even when its selectable text has no focus.
            if (key == GLFW.GLFW_KEY_S
                    && (EditorKeyboardInput.control(modifiers) || hasControlDown())) return true;
        }
        if (!previewOnly
                && !firstPerson
                && key == GLFW.GLFW_KEY_S
                && (EditorKeyboardInput.control(modifiers) || hasControlDown())) {
            if (!shortcuts.edge(key, scanCode, modifiers)) return true;
            flightKeys.remove(key);
            save();
            return true;
        }
        if (tools != null && tools.referenceKeyPressed(key, scanCode, modifiers)) return true;
        if (tools != null && tools.completeCode(key, modifiers)) return true;
        boolean text =
                extensions != null && extensions.keyboardFocused()
                        || tools != null && tools.editingText();
        if (!text
                && (EditorKeyboardInput.control(modifiers) || hasControlDown())
                && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_X || key == GLFW.GLFW_KEY_V)
                && !shortcuts.edge(key, scanCode, modifiers)) return true;
        if (!text && activation && keyboardControl()) {
            if (!shortcuts.edge(key, scanCode, modifiers)) return true;
            if (tools != null && tools.sectionKeyPressed(key, scanCode, modifiers)) return true;
            if (tools != null && tools.focusedKeyPressed(key, scanCode, modifiers)) return true;
            return super.keyPressed(key, scanCode, modifiers);
        }
        if (!extensionPanelActive()
                && tools != null
                && tools.sectionKeyPressed(key, scanCode, modifiers)) return true;
        if (escape && tools != null && !tools.captureActive() && tools.cancelPaste()) return true;
        if (escape && sceneKeyboardCaptured()) {
            status(Component.translatable("npships.editor.keys.finish_operation").getString());
            return true;
        }
        if (firstPerson
                && flightKey(key)
                && !text
                && !keyboardControl()
                && (tools == null || !tools.keyboardPopup() && !tools.expandedWorkspace())) {
            flightKeys.add(key);
            return true;
        }
        if ((key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT) && !text) {
            zonesHeld = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE && !text) return true;
        if (!text && !keyboardControl() && (tools == null || !tools.expandedWorkspace())) {
            boolean nativeChord =
                    (EditorKeyboardInput.control(modifiers) || hasControlDown())
                            && (key == GLFW.GLFW_KEY_C
                                    || key == GLFW.GLFW_KEY_X
                                    || key == GLFW.GLFW_KEY_V
                                    || key == GLFW.GLFW_KEY_Z
                                    || key == GLFW.GLFW_KEY_Y
                                    || key == GLFW.GLFW_KEY_S);
            if (!nativeChord && shortcuts.dispatch(key, scanCode, modifiers)) return true;
        }
        if (!previewOnly
                && hasControlDown()
                && (key == GLFW.GLFW_KEY_Z || key == GLFW.GLFW_KEY_Y)
                && !text) {
            if (importMode) return true;
            if (tools.dragging()) tools.endDrag();
            restoreHistory(key == GLFW.GLFW_KEY_Y || hasShiftDown());
            return true;
        }
        if (escape && !previewOnly && !importMode && editMode != EditMode.SELECT) {
            if (tools != null && !tools.flushEdits()) return true;
            temporaryCreationMode = null;
            setSelectMode();
            if (tools != null) tools.rebuild = true;
            return true;
        }
        if (escape && !previewOnly && tools != null && (tools.hasSelection() || special != null)) {
            if (!tools.flushEdits()) return true;
            clearObjectSelection();
            return true;
        }
        if (!text && key == GLFW.GLFW_KEY_DELETE && !shortcuts.edge(key, scanCode, modifiers))
            return true;
        if (!layout.sceneUsable()
                && !text
                && (key == GLFW.GLFW_KEY_DELETE
                        || hasControlDown() && (key == GLFW.GLFW_KEY_X || key == GLFW.GLFW_KEY_V)))
            return true;
        if (!previewOnly
                && !importMode
                && !extensionPanelActive()
                && !text
                && saving == null
                && tools != null
                && tools.tool == EditorToolsPanel.Tool.ASSEMBLY
                && tools.assemblyPoints.keyPressed(key, modifiers)) return true;
        if (!extensionPanelActive() && tools != null && tools.keyPressed(key, scanCode, modifiers))
            return true;
        if (!previewOnly
                && !extensionPanelActive()
                && key == GLFW.GLFW_KEY_DELETE
                && !tools.entityTool()
                && selected != null
                && saving == null) {
            tools.deleteSelectedBlock();
            return true;
        }
        if (key == GLFW.GLFW_KEY_R) {
            resetView();
            return true;
        }
        if (getFocused() instanceof AbstractWidget widget
                && !widget.visible
                && key != GLFW.GLFW_KEY_TAB) return true;
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (shortcuts.character()) return true;
        if (shortcutReference.isOpen()) return true;
        if (inspectorDivider.captured()) return true;
        if (viewMenu.isOpen() && !viewMenu.isSuspended()) return true;
        if (extensions != null && extensions.text(c, modifiers)) return true;
        return !extensionPanelActive() && tools != null && tools.charTyped(c, modifiers)
                || super.charTyped(c, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        selectionLookSuspended = false;
        if (extensions != null) {
            extensions.close();
            extensions = null;
        }
        releaseFirstPersonMouse();
        captureRequested = false;
        photoOutput = null;
        if (tools != null) tools.externalPatchControls.clear();
        shortcutReference.close();
        shortcuts.reset();
        authoringCycle.reset();
        hoverKey = null;
        hoverCandidates = List.of();
        inspectionHover = null;
        authoringHoverPlan = null;
        inspectorDivider.close();
        try {
            saveViewPreferences();
        } finally {
            zonesHeld = false;
            heldPaint = false;
            renderer.close();
            if (tools != null) {
                tools.patchLinks.close();
                tools.patchEditors.close();
                tools.patchRegionTool.close();
                tools.cancelPaste();
                tools.entityPreview.close();
                tools.ghostRenderScope.close();
                tools.ghostCameraContext.release();
            }
        }
    }

    @Override
    public void onClose() {
        releaseFirstPersonMouse();
        inspectorDivider.cancel();
        com.eynny.npships.diagnostics.EditorWorkMetrics.report();
        if (!previewOnly && tools != null && !tools.flushEdits()) return;
        if (importMode) {
            minecraft.setScreen(parent);
            return;
        }
        if (reviewCancel != null) {
            reviewCancel.run();
            return;
        }
        if (saving != null) return;
        if (!dirty) {
            confirmAssemblyPointExit();
            return;
        }
        minecraft.setScreen(
                new EditorConfirmScreen(
                        discard -> {
                            if (discard) confirmAssemblyPointExit();
                            else minecraft.setScreen(this);
                        },
                        Component.translatable("npships.loot.unsaved"),
                        Component.translatable("npships.loot.discard_question")));
    }

    private void confirmAssemblyPointExit() {
        if (!previewOnly
                && iconCapture == null
                && !isPart()
                && !hideWarnings
                && !com.eynny.npships.authoring.AssemblyEligibility.hasAssemblyPoints(current())) {
            minecraft.setScreen(
                    new EditorConfirmScreen(
                            leave -> minecraft.setScreen(leave ? parent : this),
                            Component.translatable("npships.editor.no_assembler"),
                            Component.translatable("npships.editor.no_assembler_exit_hint"),
                            Component.translatable("npships.editor.exit_anyway"),
                            Component.translatable("gui.back")));
        } else minecraft.setScreen(parent);
    }

    private final class HeightSlider extends AbstractSliderButton {
        HeightSlider(final int x, final int y, final int width) {
            super(x, y, width, 20, Component.empty(), 0);
            sync();
        }

        private boolean sliding;
        private Component shortcutTooltip;

        void sync() {
            if (sliding) {
                updateMessage();
                return;
            }
            value = sliceSize() <= 1 ? 0 : (double) ceiling / (sliceSize() - 1);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(
                    Component.translatable(
                            "npships.editor."
                                    + new String[] {
                                                "height", "height_single", "height_above",
                                                "slice_x", "slice_x_single", "slice_x_above"
                                            }
                                            [sliceMode],
                            ceiling,
                            sliceSize() - 1));
        }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float tick) {
            var hint = sliceShortcutHint();
            if (!hint.equals(shortcutTooltip)) {
                shortcutTooltip = hint;
                setTooltip(net.minecraft.client.gui.components.Tooltip.create(hint));
            }
            g.fill(
                    getX(),
                    getY(),
                    getX() + width,
                    getY() + height,
                    EditorButtonTheme.surface(0xff18232f));
            g.renderOutline(getX(), getY(), width, height, EditorButtonTheme.surface(0xff596a7c));
            int at = getX() + 2 + (int) Math.round(value * Math.max(1, width - 12));
            g.fill(
                    at,
                    getY() + 2,
                    at + 8,
                    getY() + height - 2,
                    EditorButtonTheme.mix(
                            EditorButtonTheme.panel(), EditorButtonTheme.accent(), .45f));
            g.renderOutline(at, getY() + 2, 8, height - 4, EditorButtonTheme.accent());
            g.drawCenteredString(
                    font, getMessage(), getX() + width / 2, getY() + 6, EditorButtonTheme.text());
        }

        private void moveThumb(double mx) {
            value = Math.max(0, Math.min(1, (mx - getX() - 6) / Math.max(1, width - 12)));
            applyValue();
            updateMessage();
        }

        @Override
        public void onClick(double mx, double my) {
            sliding = true;
            moveThumb(mx);
        }

        @Override
        protected void onDrag(double mx, double my, double dx, double dy) {
            moveThumb(mx);
        }

        @Override
        public void onRelease(double mx, double my) {
            sliding = false;
            super.onRelease(mx, my);
            sync();
        }

        @Override
        protected void applyValue() {
            setCeiling((int) Math.round(value * (sliceSize() - 1)));
        }
    }
}
