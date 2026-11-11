package com.eynny.npships.client.editor;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.SchematicMass;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.concurrent.LatestTask;
import com.eynny.npships.authoring.document.AreaDeformationSession;
import com.eynny.npships.authoring.document.AreaEditOperation;
import com.eynny.npships.authoring.document.BlockAreaLayers;
import com.eynny.npships.authoring.document.FittedPlaneLayers;
import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicEdits;
import com.eynny.npships.authoring.document.SchematicGlue;
import com.eynny.npships.authoring.document.SchematicPlacement;
import com.eynny.npships.authoring.document.SchematicReframe;
import com.eynny.npships.authoring.document.SchematicRegions;
import com.eynny.npships.authoring.document.SchematicRotations;
import com.eynny.npships.authoring.document.SchematicTurn;
import com.eynny.npships.authoring.geometry.AreaGeometry;
import com.eynny.npships.authoring.geometry.DirectDeformation;
import com.eynny.npships.authoring.geometry.FittedPlane;
import com.eynny.npships.authoring.geometry.SchematicShape;
import com.eynny.npships.authoring.geometry.ShapeDeformation;
import com.eynny.npships.authoring.io.AuthoringCreation;
import com.eynny.npships.authoring.io.ExportProfile;
import com.eynny.npships.authoring.patches.EntityIdentity;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.PatchInstances;
import com.eynny.npships.authoring.patches.SchematicFluids;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.authoring.patches.SchematicResources;
import com.eynny.npships.client.editor.render.GhostRenderType;
import com.eynny.npships.client.editor.render.PreviewRendererScope;
import com.eynny.npships.client.editor.render.SceneOutlines;
import com.eynny.npships.client.editor.render.SchematicPreviewLevel;
import com.eynny.npships.client.editor.screen.BlockEditorScreen;
import com.eynny.npships.client.editor.screen.VariantGalleryScreen;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorIconButton;
import com.eynny.npships.client.editor.widget.EditorScrollBar;
import com.eynny.npships.client.editor.widget.RegistryEditBox;
import com.eynny.npships.network.LootTableNetworking;
import com.eynny.npships.ship.EntityHeadPose;
import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

/** Tools operate on detached documents. The panel clips and routes its own scrollable controls. */
final class EditorToolsPanel {
    enum Tool {
        BLOCKS,
        ENTITIES,
        SEGMENTS,
        ZONES,
        LOOT,
        PATCHES,
        GLUE,
        ASSEMBLY,
        AREA,
        STYLES
    }

    final AssemblyPointControls assemblyPoints = new AssemblyPointControls(this);
    final ParameterControls parameters = new ParameterControls(this);
    final BannerControls banners = new BannerControls(this);
    private final BlockToolControls blockToolControls = new BlockToolControls(this);
    private final com.eynny.npships.authoring.patches.PatchBlockPlacements.Matcher blockPlacements =
            new com.eynny.npships.authoring.patches.PatchBlockPlacements.Matcher(
                    com.eynny.npships.config.NPShipsRuntimeConfig.patchPolicy());
    private final EntityToolControls entityToolControls = new EntityToolControls(this);
    private final RegionToolControls regionToolControls = new RegionToolControls(this);
    private final AreaToolControls areaToolControls = new AreaToolControls(this);
    private final LootToolControls lootToolControls = new LootToolControls(this);
    private final PatchToolControls patchToolControls = new PatchToolControls(this);
    final PatchEditorParticipants patchEditors = new PatchEditorParticipants(this);
    final PatchLinks patchLinks = new PatchLinks(this);
    final com.eynny.npships.authoring.patches.PatchItemPresentations.Plan blockPresentations =
            com.eynny.npships.authoring.patches.PatchItemPresentations.capture();
    final PatchSpatialTools patchRegionTool = new PatchSpatialTools(this);
    final ExternalPatchControls externalPatchControls = new ExternalPatchControls(this);
    private final EditorGestures editorGestures = new EditorGestures(this);
    static final int TOOLBAR_HEIGHT = 28;
    final SchematicEditorScreen host;
    final Minecraft mc = Minecraft.getInstance();
    final EntityPreview entityPreview = new EntityPreview();
    final List<AbstractWidget> widgets = new ArrayList<>(), toolbar = new ArrayList<>();
    static final List<String> recent = new ArrayList<>(),
            recentEntities = new ArrayList<>(),
            pinnedBlocks = new ArrayList<>(),
            pinnedEntities = new ArrayList<>();
    String glueType =
            SchematicGlue.formats().stream()
                    .findFirst()
                    .map(com.eynny.npships.authoring.patches.PatchVolumeFormat::type)
                    .orElse("");
    Tool tool = Tool.BLOCKS;
    boolean massRegionEditing;
    int x, y, w, h, cursor, scroll, contentHeight, page, entityIndex = -1;
    String query = "", state = "minecraft:stone", entityType = "minecraft:pig", segment = "";
    boolean rebuild;
    private AbstractWidget focused;
    long focusSequence;
    int validationNavigation;
    AbstractWidget popupTrigger;
    List<StructureSegments.Segment> segments = List.of();
    private SchematicDocument pendingImport;
    private String pendingName;
    SchematicDocument cached;
    CompoundTag blockBrushData, entityBrush;
    List<JsonObject> entityBrushPatches = List.of();
    String zone = "";
    List<StructureCrew.Rule> zones = List.of();
    SchematicDocument dragBase, dragOriginal;
    BlockPos dragBlock;
    int dragEntity = -1;
    net.minecraft.core.Direction dragFace;
    AABB dragBounds, proposedBounds;
    double dragX, dragY;
    int dragStep;
    BlockPos blocked;
    long blockedUntil;
    boolean dragChanged, dragOccupied;
    SchematicDocument lastFreeDrag;
    BlockPos lastFreeBlock;
    Runnable pendingSettings;
    boolean settingsDirty;
    final List<Runnable> fieldSavers = new ArrayList<>();
    final EditorInspectorSections sections = new EditorInspectorSections(this);
    AbstractWidget lastField;

    boolean geometryBusy() {
        return shapeJobs.busy();
    }

    boolean hasPendingEdits() {
        return settingsDirty || shapeJobs.busy();
    }

    void defer(Runnable save) {
        defer(sections.currentId().isEmpty() ? null : lastField, save);
    }

    void defer(AbstractWidget owner, Runnable save) {
        if (owner == null || !sections.enabled()) {
            fieldSavers.add(save);
            return;
        }
        host.validation.register(owner, sections.currentId());
        fieldSavers.add(() -> validateField(owner, save));
    }

    /** Wrap an existing acceptance step without moving it out of its atomic saver. */
    void validateField(AbstractWidget owner, Runnable save) {
        if (owner == null || !sections.enabled()) {
            save.run();
            return;
        }
        long generation = host.validation.generation(owner);
        try {
            save.run();
            host.validation.result(owner, generation, null);
        } catch (RuntimeException error) {
            if (error instanceof EditorValidationFeedback.Failure
                    || error instanceof DeferredResize) throw error;
            host.validation.result(owner, generation, EditorErrorText.message(error));
            throw new EditorValidationFeedback.Failure(error, owner);
        }
    }

    static final class DeferredResize extends RuntimeException {}

    boolean flushEdits() {
        if (patchRegionTool.pending()) {
            host.status(patchRegionTool.pendingHint().getString());
            return false;
        }
        if (externalPatchControls.pending()) {
            host.status(tr("patch_external_finish").getString());
            return false;
        }
        if (shapeJobs.busy()) {
            host.status(tr("shape_busy").getString());
            return false;
        }
        try {
            flushSettings();
            if (shapeJobs.busy()) {
                host.status(tr("shape_busy").getString());
                return false;
            }
            return true;
        } catch (DeferredResize e) {
            return false;
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger()
                    .warn("[NPShips editor] Cannot apply settings", e);
            if (!(e instanceof EditorValidationFeedback.Failure))
                host.error(EditorErrorText.message(e));
            if (e instanceof EditorValidationFeedback.Failure failure)
                sections.reveal(
                        failure.owner == null ? host.validation.errorOwner() : failure.owner);
            return false;
        }
    }

    private void flushSettings() {
        if (settingsDirty) {
            settingsDirty = false;
            try {
                var saves = List.copyOf(fieldSavers);
                if (pendingSettings != null) pendingSettings.run();
                for (var save : saves) save.run();
            } catch (DeferredResize e) {
                throw e;
            } catch (RuntimeException e) {
                settingsDirty = true;
                throw e;
            }
        }
    }

    String mode() {
        return host.creating() ? "place" : host.deleting() ? "delete" : "select";
    }

    boolean regionTool() {
        return tool == Tool.SEGMENTS
                || tool == Tool.ZONES
                || tool == Tool.GLUE
                || tool == Tool.AREA
                || massRegionEditing;
    }

    boolean inspectTool() {
        return tool == Tool.STYLES && !massRegionEditing;
    }

    boolean glueTool() {
        return !massRegionEditing && tool == Tool.GLUE;
    }

    boolean createRegion() {
        return regionTool() && host.creating();
    }

    boolean blocksTool() {
        return !massRegionEditing && tool == Tool.BLOCKS;
    }

    boolean blockSelectionTool() {
        return !massRegionEditing
                && (tool == Tool.BLOCKS || tool == Tool.LOOT || tool == Tool.PATCHES);
    }

    int lootRequest, lootPage, hoveredEntity = -1;
    private boolean lootReceived;
    List<String> serverTables = List.of();
    String lootQuery = "", ghostStateText;
    private net.minecraft.nbt.CompoundTag ghostBrushData;
    private net.minecraft.world.level.block.state.BlockState ghostState;
    private SchematicPreviewLevel ghostLevel;
    final List<AbstractWidget> lootRows = new ArrayList<>();
    int lootRowsY;

    EditorToolsPanel(SchematicEditorScreen host) {
        this.host = host;
        layers = host.initialLayers();
        if (layers.compose() != host.current())
            layers = layers.synchronize(layers.compose(), host.current());
        lastLayerDocument = host.current();
        layerHistory.put(lastLayerDocument, layers);
        savedLayers = layers;
        if (layers.hasLivePlanes()) {
            var snapshot = layers;
            refreshingPlanes = true;
            submitArea(() -> new AreaResult(snapshot.compose(), snapshot));
            if (host.cleanOpeningState()) {
                openingLayers = snapshot;
                openingDocument = host.current();
            }
        }
    }

    Component tr(String key) {
        String id = "npships.tools." + key;
        return net.minecraft.client.resources.language.I18n.exists(id)
                ? Component.translatable(id)
                : Component.literal(key);
    }

    EditorLayout layout;

    EditorLayout layout() {
        return layout;
    }

    int contentWidth() {
        return layout.contentWidth();
    }

    void init(EditorLayout layout) {
        this.layout = layout;
        this.x = layout.inspector().left();
        this.y = layout.inspector().top();
        this.w = layout.inspector().width();
        this.h = layout.inspector().height();
        for (var list : List.of(recent, recentEntities, pinnedBlocks, pinnedEntities))
            trimQuickRow(list);
        build();
    }

    void resetTextWorkspace() {
        codeWorkspaceAction = snbtReferenceAction = null;
        inventoryWorkspaceAction = null;
        lootToolControls.resetLayout();
        host.validation.context(null);
        sections.reset();
        codeEditor = null;
        codeContext = null;
        codeComparedBase = codeComparedCurrent = null;
        liveData = null;
        expandedCode = false;
        flows.clear();
        horizontalRoles.clear();
        notes.clear();
        wrappedText.clear();
    }

    void previewLayout(EditorLayout next) {
        layout = next;
    }

    private final EditorInspectorArrangement arrangement = new EditorInspectorArrangement();
    final java.util.List<Flow> flows = new java.util.ArrayList<>();
    final List<EditorInspectorArrangement.Note> notes = new ArrayList<>();
    final Map<MultiLineTextWidget, Integer> wrappedText = new IdentityHashMap<>();

    void wrappedNote(String key) {
        wrappedNote(tr(key));
    }

    void wrappedNote(Component message) {
        var note = new EditorInspectorArrangement.Note(message, mc.font, cursor, contentWidth());
        notes.add(note);
        note.place(this, x, cursor, contentWidth());
        cursor += note.height() + 4;
    }

    record Flow(
            List<AbstractWidget> cells,
            int pitch,
            boolean eight,
            List<Integer> desiredWidths,
            boolean measured) {
        Flow(List<AbstractWidget> cells, int pitch, boolean eight) {
            this(
                    List.copyOf(cells),
                    pitch,
                    eight,
                    cells.stream().map(AbstractWidget::getWidth).toList(),
                    false);
        }

        int columns(int width) {
            return eight
                    ? Math.min(8, Math.max(1, (width - EditorLayout.CONTENT_GUTTER) / 20))
                    : Math.max(
                            1,
                            (pitch == 18
                                            ? EditorLayout.listWidth(width)
                                            : EditorLayout.contentWidth(width))
                                    / pitch);
        }

        int step(int width) {
            return eight
                    ? Math.min(
                            24,
                            Math.max(20, (width - EditorLayout.CONTENT_GUTTER) / columns(width)))
                    : pitch;
        }

        /** Desired cell widths stay independent of temporarily constrained widget bounds. */
        List<EditorLayout.Rect> bounds(int width) {
            int available =
                    pitch == 18 ? EditorLayout.listWidth(width) : EditorLayout.contentWidth(width);
            var result = new ArrayList<EditorLayout.Rect>();
            int left = 0, top = 0;
            for (int i = 0; i < cells.size(); i++) {
                int cellWidth = Math.min(desiredWidths.get(i), available);
                if (measured) {
                    if (left > 0 && left + cellWidth > available) {
                        left = 0;
                        top += pitch;
                    }
                } else {
                    left = i % columns(width) * step(width);
                    top = i / columns(width) * pitch;
                    cellWidth = Math.min(cellWidth, Math.max(0, available - left));
                }
                result.add(
                        new EditorLayout.Rect(
                                left, top, left + cellWidth, top + cells.get(i).getHeight()));
                if (measured) left += cellWidth + EditorLayout.CONTROL_GAP;
            }
            return result;
        }

        int height(int width) {
            if (cells.isEmpty()) return 0;
            if (!measured) return ((cells.size() + columns(width) - 1) / columns(width)) * pitch;
            int available = EditorLayout.contentWidth(width), left = 0, rows = 1;
            for (int desired : desiredWidths) {
                int cellWidth = Math.min(desired, available);
                if (left > 0 && left + cellWidth > available) {
                    left = 0;
                    rows++;
                }
                left += cellWidth + EditorLayout.CONTROL_GAP;
            }
            return rows * pitch;
        }

        void place(int x, int y, int width) {
            var positions = bounds(width);
            for (int i = 0; i < cells.size(); i++) {
                var cell = cells.get(i);
                var at = positions.get(i);
                cell.setPosition(x + at.left(), y + at.top());
                cell.setWidth(at.width());
            }
        }
    }

    final Map<AbstractWidget, EditorWidgetArrangement.Horizontal> horizontalRoles =
            new IdentityHashMap<>();

    void stretch(AbstractWidget widget) {
        horizontalRoles.put(widget, EditorWidgetArrangement.Horizontal.FILL);
    }

    void anchorRight(AbstractWidget widget) {
        horizontalRoles.put(widget, EditorWidgetArrangement.Horizontal.RIGHT);
    }

    int toolbarPitch() {
        int extensionWidth =
                host.extensionHost() != null && host.extensionHost().available() ? 24 : 0;
        int toolCount = host.isPart() ? 7 : 8;
        return Math.max(20, Math.min(24, (w - 20 - extensionWidth) / toolCount));
    }

    void captureArrangement() {
        arrangement.capture(this);
    }

    void layoutInventory(EditorLayout next) {
        lootToolControls.layoutInventory(next);
    }

    void layoutOnly(EditorLayout next) {
        host.inspectorPresentation(() -> arrangement.layout(this, next));
        syncTextPopup();
    }

    void preferInspectorScroll() {
        arrangement.preferScroll(this);
    }

    AbstractWidget focusedWidget() {
        return focused;
    }

    void focusWidget(AbstractWidget widget) {
        if (focused != widget) focusSequence++;
        if (focused != null && focused != widget) focused.setFocused(false);
        focused = widget;
        if (widget != null) widget.setFocused(true);
        host.setFocused(null);
    }

    void revealWidget(AbstractWidget widget) {
        if (widget == codeEditor && expandedCode()) return;
        int top = widget == codeEditor ? codeDockY - scroll : widget.getY();
        int bottom = top + (widget == codeEditor ? codeDockHeight : widget.getHeight());
        int next = scroll;
        if (top < layout.inspectorContent().top()) next += top - layout.inspectorContent().top();
        else if (bottom > layout.inspectorContent().bottom())
            next +=
                    Math.min(
                            top - layout.inspectorContent().top(),
                            bottom - layout.inspectorContent().bottom());
        setScroll(
                Math.clamp(
                        next, 0, Math.max(0, contentHeight - layout.inspectorContent().height())));
    }

    int registryRemainder, itemRemainder;

    private boolean automaticCreationMode;
    String inspectedArea = "";

    String selectedAreaId() {
        return inspectedArea.isEmpty() ? activeArea : inspectedArea;
    }

    void extensionActivated() {
        assemblyPoints.selected = null;
        expandedCode = false;
        inventoryExpanded = false;
    }

    void change(Tool value) {
        if (value == Tool.ASSEMBLY && host.isPart()) return;
        if (host.extensionHost() != null) host.extensionHost().deactivate();
        // Handoff only a current block target, not an old entity index or authoring selection.
        boolean handoff = (value == Tool.LOOT || value == Tool.PATCHES) && currentBlockTarget();
        if (!flushEdits()) return;
        if (value == Tool.AREA && tool == Tool.AREA) {
            build();
            return;
        }
        var target = handoff ? pos() : null;
        if (target != null
                && (!doc().blocks().containsKey(target)
                        || value == Tool.LOOT && !host.containers().contains(target)))
            target = null;
        replaceToolContext(value, false);
        if (target != null) host.select(target);
        build();
        if (value == Tool.ZONES && zones.isEmpty()
                || value == Tool.ASSEMBLY && doc().assemblyPoints().isEmpty()
                || value == Tool.AREA && layers.layers().isEmpty()) {
            automaticCreationMode = true;
            host.temporaryCreateMode();
        }
    }

    boolean toolAvailable(Tool value) {
        return !(host.isPart()
                && (value == Tool.SEGMENTS || value == Tool.ZONES || value == Tool.ASSEMBLY));
    }

    void shortcutTool(Tool value) {
        if (toolAvailable(value) && (value != tool || massRegionEditing)) change(value);
    }

    private boolean currentBlockTarget() {
        if (!blockSelectionTool() || pos() == null || cached == null) return false;
        var before = cached.blocks().get(pos());
        var now = doc().blocks().get(pos());
        return before != null
                && now != null
                && cached.paletteCopy()
                        .getCompound(before)
                        .equals(doc().paletteCopy().getCompound(now))
                && cached.blockEntityData(pos()).equals(doc().blockEntityData(pos()));
    }

    private void replaceToolContext(Tool value, boolean inspection) {
        assemblyPoints.selected = null;
        if (automaticCreationMode || massRegionEditing) {
            if (inspection) host.finishCreationModeForInspection();
            else host.finishCreationMode();
            automaticCreationMode = false;
        }
        massRegionEditing = false;
        inspectedArea = "";
        activeArea = "";
        deforming = false;
        pendingSettings = null;
        linkRole = null;
        patchLinks.close();
        linkPatch = null;
        firstLink = null;
        linkTargets = List.of();
        host.select(null);
        host.clearRegion();
        segment = "";
        zone = "";
        fittedPlane = null;
        tool = value;
        entityIndex = -1;
        hoveredEntity = -1;
        scroll = page = 0;
        query = "";
        focused = null;
    }

    boolean ordinaryInspection() {
        return ordinaryInspection(false);
    }

    boolean ordinaryInspection(boolean toolNavigation) {
        return !captureActive()
                && !massRegionEditing
                && !mergeChoosing
                && !pasting()
                && linkRole == null
                && fittedPlane == null
                && (tool != Tool.AREA
                        || !inspectedArea.isEmpty()
                        || membershipMode == 0
                                && (toolNavigation || !deforming)
                                && !areaGizmo.pickingCenter);
    }

    boolean inspectionMatches(EditorInspection.Candidate candidate) {
        if (candidate == null) return false;
        return switch (candidate.target()) {
            case EditorInspection.BlockTarget block ->
                    (tool == Tool.BLOCKS || tool == Tool.PATCHES || tool == Tool.LOOT)
                            && block.position().equals(pos())
                            && entityIndex < 0;
            case EditorInspection.EntityTarget entity ->
                    (tool == Tool.ENTITIES || tool == Tool.PATCHES)
                            && entityIndex == entity.index();
            case EditorInspection.AreaTarget area ->
                    tool == Tool.AREA && area.id().equals(selectedAreaId());
            case EditorInspection.PlaneTarget plane ->
                    tool == Tool.AREA && plane.id().equals(selectedAreaId());
            case EditorInspection.SegmentTarget target ->
                    tool == Tool.SEGMENTS && segment.equals(target.id());
            case EditorInspection.MobZoneTarget target ->
                    tool == Tool.ZONES && zone.equals(target.id());
            case EditorInspection.GlueTarget glue ->
                    tool == Tool.GLUE && entityIndex == glue.index();
        };
    }

    /** Caller has successfully flushed the previous context once, before any selection mutation. */
    boolean inspectValidated(EditorInspection.Candidate candidate) {
        if (candidate == null
                || candidate.source() != doc()
                || candidate.layerSource() != null && candidate.layerSource() != layers)
            return false;
        if (inspectionMatches(candidate)) return true;
        if (changingLocalLootTarget(candidate)) return false;
        discardPatchDraftForInspection(candidate);
        boolean patches = tool == Tool.PATCHES;
        boolean loot = tool == Tool.LOOT;
        if (candidate.target() instanceof EditorInspection.BlockTarget block) {
            replaceToolContext(patches ? Tool.PATCHES : loot ? Tool.LOOT : Tool.BLOCKS, true);
            host.select(block.position());
        } else if (candidate.target() instanceof EditorInspection.EntityTarget entity) {
            if (entity.index() < 0 || entity.index() >= doc().entityCount()) return false;
            replaceToolContext(patches ? Tool.PATCHES : Tool.ENTITIES, true);
            entityIndex = entity.index();
        } else if (!EditorInspection.areaId(candidate.target()).isEmpty()) {
            var id = EditorInspection.areaId(candidate.target());
            var layer = layers.find(id);
            if (layer == null) return false;
            replaceToolContext(Tool.AREA, true);
            // Selection opens controls without promoting/reordering the retained layer.
            loadArea(layer);
            // Opening controls does not arm a deformation gesture or steal Alt selection.
            deforming = false;
            host.setSelectMode();
            host.selectRegion(layer.region().origin(), layer.region().size());
        } else if (candidate.target() instanceof EditorInspection.SegmentTarget target) {
            if (host.isPart() || !hasSegment(target.id())) return false;
            replaceToolContext(Tool.SEGMENTS, true);
            segment = target.id();
        } else if (candidate.target() instanceof EditorInspection.MobZoneTarget target) {
            if (host.isPart()
                    || StructureCrew.read(doc().configCopy()).stream()
                            .noneMatch(value -> value.id().equals(target.id()))) return false;
            replaceToolContext(Tool.ZONES, true);
            zone = target.id();
        } else if (candidate.target() instanceof EditorInspection.GlueTarget glue) {
            if (glue.index() < 0 || glue.index() >= doc().entityCount()) return false;
            replaceToolContext(Tool.GLUE, true);
            entityIndex = glue.index();
        }
        build();
        return true;
    }

    /** Selection may discard a detached provider draft, never accept it for a different target. */
    void discardPatchDraftForInspection(EditorInspection.Candidate candidate) {
        if (candidate == null || candidate.source() != doc() || inspectionMatches(candidate))
            return;
        if (!(candidate.target() instanceof EditorInspection.BlockTarget
                || candidate.target() instanceof EditorInspection.EntityTarget)) return;
        if (externalPatchControls.pending()) {
            externalPatchControls.clear();
            host.status(tr("patch_external_stale").getString());
        }
    }

    boolean entityPatchTarget() {
        return entityIndex >= 0 && (tool == Tool.ENTITIES || tool == Tool.PATCHES);
    }

    EditorButton button(String key, Runnable action) {
        var b = buttonAt(Component.translatable(key), x, cursor, w, action);
        cursor += EditorLayout.ROW_PITCH;
        return b;
    }

    void toggle(String key, java.util.function.BooleanSupplier selected, Runnable action) {
        buttonAt(tr(key), x, cursor, w, action).selected(selected);
        cursor += EditorLayout.ROW_PITCH;
    }

    void action(String key, Runnable action) {
        button("npships.tools." + key, action);
    }

    EditorButton buttonAt(Component label, int bx, int by, int bw, Runnable action) {
        var b =
                EditorButton.flat(
                                label,
                                pressed -> {
                                    popupTrigger = pressed;
                                    try {
                                        run(action);
                                    } finally {
                                        popupTrigger = null;
                                    }
                                })
                        .bounds(bx, by, Math.max(20, Math.min(bw, x + contentWidth() - bx)), 20)
                        .build();
        widgets.add(b);
        return b;
    }

    void run(Runnable action) {
        try {
            flushSettings();
            action.run();
        } catch (DeferredResize ignored) {
        } catch (Exception e) {
            if (!(e instanceof EditorValidationFeedback.Failure))
                host.error(EditorErrorText.message(e));
            if (e instanceof EditorValidationFeedback.Failure failure)
                sections.reveal(
                        failure.owner == null ? host.validation.errorOwner() : failure.owner);
        }
    }

    /** A retained native control may follow accepted raw edits, but not a different target/type. */
    BlockState currentBlock(BlockPos expected, Block block) {
        if (!Objects.equals(pos(), expected))
            throw new IllegalArgumentException(tr("action_target_changed").getString());
        var current = selectedBlockState();
        if (current.getBlock() != block)
            throw new IllegalArgumentException(tr("action_target_changed").getString());
        return current;
    }

    /** Resolve the accepted provider instance after pending text has been flushed. */
    JsonObject currentPatch(JsonObject expected) {
        String key = PatchInstances.key(expected, false);
        var matches =
                ExternalPatches.authoringEntries(doc()).stream()
                        .filter(p -> PatchInstances.key(p, false).equals(key))
                        .toList();
        if (matches.size() != 1
                || !Objects.equals(matches.getFirst().get("pos"), expected.get("pos"))
                || !Objects.equals(matches.getFirst().get("block"), expected.get("block")))
            throw new IllegalArgumentException(tr("action_target_changed").getString());
        return matches.getFirst().deepCopy();
    }

    int currentRow(Iterable<?> current, Object expected, int originalIndex, Iterable<?> original) {
        if (Objects.equals(current, original)) return originalIndex;
        return currentRow(current, expected);
    }

    /** Collection indexes are presentation state, not the identity of an authored row. */
    int currentRow(Iterable<?> current, Object expected) {
        int found = -1, index = 0;
        for (var value : current) {
            if (Objects.equals(value, expected)) {
                if (found >= 0)
                    throw new IllegalArgumentException(tr("action_target_changed").getString());
                found = index;
            }
            index++;
        }
        if (found < 0) throw new IllegalArgumentException(tr("action_target_changed").getString());
        return found;
    }

    void toolbarAction(Runnable action) {
        run(action);
    }

    Component fieldHint(String label) {
        String key = "npships.tools." + label + "_hint";
        if (net.minecraft.client.resources.language.I18n.exists(key))
            return Component.translatable(key);
        return Component.translatable(
                "npships.tools." + (label.contains(":") ? "attribute_hint" : "field_hint"),
                tr(label));
    }

    RegistryEditBox field(
            String label, String initial, java.util.function.Consumer<String> changed) {
        return field(label, initial, changed, true);
    }

    RegistryEditBox field(
            String label,
            String initial,
            java.util.function.Consumer<String> changed,
            boolean captionVisible) {
        var caption =
                new StringWidget(x, cursor, contentWidth(), 12, tr(label), mc.font).alignLeft();
        caption.setTooltip(Tooltip.create(fieldHint(label)));
        if (captionVisible) {
            widgets.add(caption);
            cursor += 13;
        }
        String[] typed = {initial};
        var box =
                new RegistryEditBox(
                        x,
                        cursor,
                        contentWidth(),
                        tr(label),
                        () -> {
                            if (label.equals("loot_table")) {
                                var names = new TreeSet<>(serverTables);
                                var config = doc().configCopy();
                                if (config != null && config.has("loot_tables"))
                                    names.addAll(config.getAsJsonObject("loot_tables").keySet());
                                return List.copyOf(names);
                            }
                            return Set.of("state", "material").contains(label)
                                    ? RegistryEditBox.states(typed[0])
                                    : RegistryEditBox.names(label);
                        });
        box.setHint(tr(label));
        box.setMaxLength(32767);
        box.setValue(initial);
        box.setResponder(
                v -> {
                    if (!label.equals("search")) host.validation.changed(box);
                    typed[0] = v;
                    changed.accept(v);
                    if (!Set.of(
                                    "search",
                                    "state",
                                    "entity_type",
                                    "item",
                                    "count",
                                    "loot_weight",
                                    "reference_name",
                                    "new_pool_type",
                                    "area_origin",
                                    "area_size")
                            .contains(label)) settingsDirty = true;
                });
        box.setTooltip(Tooltip.create(fieldHint(label)));
        widgets.add(box);
        lastField = box;
        if (!sections.currentId().isEmpty()) host.validation.register(box, sections.currentId());
        cursor += EditorLayout.ROW_PITCH;
        return box;
    }

    MultiLineEditBox multiline(String initial, java.util.function.Consumer<String> changed) {
        var box =
                new EditorMultilineEditBox(
                        mc.font,
                        x,
                        cursor,
                        contentWidth(),
                        100,
                        Component.empty(),
                        Component.literal("NBT / JSON"));
        box.setCharacterLimit(2 * 1024 * 1024);
        box.setValue(initial);
        box.setValueListener(
                v -> {
                    host.validation.changed(box);
                    changed.accept(v);
                    settingsDirty = true;
                });
        widgets.add(box);
        if (!sections.currentId().isEmpty()) host.validation.register(box, sections.currentId());
        // The native character-limit decoration ends thirteen units below the fixed surface.
        cursor += box.getHeight() + 16;
        return box;
    }

    private LuaEditBox pinnedHelp;
    private Runnable liveData;
    private long liveDataAt;

    void dataEditor(
            String initial,
            java.util.function.UnaryOperator<com.eynny.npships.authoring.document.DataSuggestions>
                    suggestions,
            java.util.function.Consumer<String> changed,
            java.util.function.Consumer<String> apply) {
        var dock = layout.codeDock(cursor, 0, layout.codeDockHeight());
        var registryHints =
                new com.eynny.npships.authoring.document.DataSuggestions[] {
                    suggestions.apply(null)
                };
        long[] hintEpoch = {com.eynny.npships.client.editor.render.PreviewResources.epoch()};
        var box = new LuaEditBox(mc.font, dock.left(), dock.top(), dock.width(), registryHints[0]);
        box.valueSource(
                (value, offset) ->
                        com.eynny.npships.authoring.document.SnbtPropertyValues.at(
                                value, offset, mc.level));
        box.setValue(initial);
        box.setHeight(box.compactHeight(layout.contentWidth(), layout.codeDockHeight()));
        String[] lastApplied = {initial};
        long[] generation = {0};
        var editingContext = codeContext();
        SchematicDocument[] acceptedOwnerDocument = {doc()};
        host.validation.context(box);
        host.validation.register(box, sections.currentId());
        java.util.function.BiConsumer<String, Long> save =
                (value, revision) -> {
                    if (revision != generation[0] || !host.validation.owns(box)) return;
                    if (!Objects.equals(editingContext, codeContext())
                            || !sameCodePayload(acceptedOwnerDocument[0])) {
                        host.validation.forget(box);
                        rebuild = true;
                        return;
                    }
                    try {
                        if (!value.equals(lastApplied[0])) {
                            host.fieldApplication = true;
                            try {
                                apply.accept(value);
                            } finally {
                                host.fieldApplication = false;
                            }
                            acceptedOwnerDocument[0] = doc();
                            lastApplied[0] = value;
                            long epoch =
                                    com.eynny.npships.client.editor.render.PreviewResources.epoch();
                            registryHints[0] =
                                    suggestions.apply(
                                            epoch == hintEpoch[0] ? registryHints[0] : null);
                            hintEpoch[0] = epoch;
                            box.dataSuggestions(registryHints[0]);
                        }
                        host.validation.result(box, revision, null);
                    } catch (RuntimeException e) {
                        host.validation.result(box, revision, EditorErrorText.message(e));
                        throw new EditorValidationFeedback.Failure(e, box);
                    }
                };
        box.setValueListener(
                value -> {
                    changed.accept(value);
                    long revision = ++generation[0];
                    host.validation.draft(box, revision);
                    settingsDirty = true;
                    liveDataAt = net.minecraft.Util.getMillis() + 400;
                    liveData = () -> save.accept(value, revision);
                });
        widgets.add(box);
        cursor += box.getHeight() + 4;
        codeControls(box);
        codeWorkspaceAction.tooltip(
                () ->
                        EditorShortcutDispatcher.hint(
                                tr("data_hint"), EditorShortcutDispatcher.Action.WORKSPACE));
        snbtReferenceAction =
                EditorButton.flat(tr("data_structure"), ignored -> toggleSnbtReference())
                        .bounds(x, cursor, contentWidth(), 20)
                        .build()
                        .tooltip(
                                () ->
                                        EditorShortcutDispatcher.hint(
                                                tr("data_structure_hint"),
                                                EditorShortcutDispatcher.Action.SNBT_REFERENCE));
        widgets.add(snbtReferenceAction);
        cursor += 24;
        fieldSavers.add(() -> save.accept(box.getValue(), generation[0]));
    }

    /** Shares the code workspace while the enclosing form owns its atomic Apply/Cancel draft. */
    LuaEditBox jsonDraftEditor(String initial, java.util.function.Consumer<String> change) {
        com.google.gson.JsonObject known = new com.google.gson.JsonObject();
        try {
            known =
                    com.eynny.npships.authoring.validation.JsonStructure.parse(initial)
                            .getAsJsonObject();
        } catch (RuntimeException unfinishedDraft) {
            // Keep unfinished text intact; native validation belongs to the enclosing Apply.
        }
        var suggestions =
                com.eynny.npships.authoring.document.LootDataSuggestions.create(
                        known, mc.level.registryAccess());
        var dock = layout.codeDock(cursor, 0, layout.codeDockHeight());
        var box = new LuaEditBox(mc.font, dock.left(), dock.top(), dock.width(), suggestions);
        box.setValue(initial);
        box.setHeight(box.compactHeight(layout.contentWidth(), layout.codeDockHeight()));
        host.validation.register(box, sections.currentId());
        box.setValueListener(
                value -> {
                    host.validation.changed(box);
                    change.accept(value);
                    settingsDirty = true;
                });
        widgets.add(box);
        cursor += box.getHeight() + 4;
        codeControls(box);
        codeDocumentBound = true;
        snbtReferenceAction =
                EditorButton.flat(
                                Component.translatable("npships.loot.reference"),
                                ignored -> toggleSnbtReference())
                        .bounds(x, cursor, contentWidth(), 20)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable("npships.loot.reference_hint")))
                        .build();
        widgets.add(snbtReferenceAction);
        cursor += 24;
        return box;
    }

    void liveCommit(SchematicDocument value) {
        boolean pendingRebuild = rebuild;
        rememberArea();
        host.commit(value);
        cached = doc();
        // Preserve an earlier refresh; this commit's own notification must not replace its field.
        rebuild = pendingRebuild;
        parameters.acceptedDataChanged();
        host.status(tr("data_applied").getString());
    }

    SchematicDocument doc() {
        return host.current();
    }

    BlockPos pos() {
        return host.selection();
    }

    void commit(SchematicDocument value) {
        rememberArea();
        host.commit(value);
        cached = null;
        rebuild = true;
    }

    void selectionChanged() {
        entityIndex = -1;
        if (tool == Tool.AREA) readCorners();
        rebuild = true;
        if (host.extensionHost() != null) host.extensionHost().selectionChanged();
    }

    boolean blur() {
        if (!flushEdits()) return false;
        if (focused != null) focused.setFocused(false);
        focused = null;
        return true;
    }

    boolean hasSelection() {
        return assemblyPoints.point() != null
                || pos() != null
                || fittedPlane != null
                || entityIndex >= 0
                || !segment.isEmpty()
                || !zone.isEmpty()
                || host.firstCorner() != null;
    }

    int regionColor() {
        return massRegionEditing
                ? 0xffd040
                : tool == Tool.AREA && FittedPlaneLayers.plane(layers.find(selectedAreaId()))
                        ? 0xff9f60
                        : tool == Tool.GLUE
                                ? SchematicGlue.color(glueType)
                                : tool == Tool.ZONES
                                        ? 0x55dd88
                                        : tool == Tool.SEGMENTS ? 0xc899ff : 0x70c8ff;
    }

    void clearSelection() {
        if (!flushEdits()) return;
        clearSelectionValidated();
    }

    void clearSelectionValidated() {
        if (massRegionEditing) {
            massRegionEditing = false;
            showRegionMass = false;
            massRegion = null;
            massAnalysis = null;
            massParts = List.of();
            massPending = false;
            host.finishCreationMode();
        }
        assemblyPoints.selected = null;
        patchRegionTool.clear();
        inspectedArea = "";
        fittedPlane = null;
        settingsDirty = false;
        pendingSettings = null;
        linkRole = null;
        patchLinks.close();
        host.select(null);
        entityIndex = -1;
        segment = "";
        zone = "";
        if (tool == Tool.AREA) activeArea = "";
        rebuild = true;
    }

    /** Empty ordinary clicks clear inspection; active area editing keeps its explicit exit. */
    void deselectInspectionValidated() {
        if (!hasSelection()
                || tool == Tool.AREA && inspectedArea.isEmpty() && !activeArea.isEmpty()) return;
        host.clearObjectSelectionValidated();
        build();
    }

    void rendererReset() {
        visibleLayers = null;
    }

    void validateDraft(SchematicDocument candidate) {
        var known = layerHistory.get(candidate);
        var state = known != null ? known : layers;
        if (state != null) state.requireConfiguration(candidate);
    }

    void normalizedDraft(SchematicDocument original, SchematicDocument normalized) {
        if (original == normalized) return;
        var state = layerHistory.get(original);
        if (state != null) {
            layers = state.synchronize(original, normalized);
            layerHistory.put(normalized, layers);
            areaSelections.put(normalized, areaSelections.getOrDefault(original, activeArea));
        }
    }

    void documentChanged() {
        parameters.contextChanged();
        var localChanges = doc().changedBlocks(lastLayerDocument, 256);
        boolean shapeChanged = localChanges == null;
        if (!shapeChanged && !localChanges.isEmpty()) {
            var beforePalette = lastLayerDocument.paletteCopy();
            var afterPalette = doc().paletteCopy();
            for (var p : localChanges) {
                var before = lastLayerDocument.blocks().get(p);
                var after = doc().blocks().get(p);
                if (before == null
                        || after == null
                        || !beforePalette
                                .getCompound(before)
                                .equals(afterPalette.getCompound(after))) {
                    shapeChanged = true;
                    break;
                }
            }
        }
        if (layers != null) {
            var known = layerHistory.get(doc());
            if (known != null) layers = known;
            else if (!applyingShape && lastLayerDocument != null) {
                layers = layers.synchronize(lastLayerDocument, doc());
                layerHistory.put(doc(), layers);
            }
            lastLayerDocument = doc();
        }
        if (!inspectedArea.isEmpty() && layers.find(inspectedArea) == null) {
            inspectedArea = "";
            host.clearRegion();
        }
        if (showMass) calculateMass();
        if (!applyingShape) {
            shapeJobs.discard();
            shapeCommitBase = null;
            shapeBase = null;
        }
        rebuild = true;
        if (entityIndex >= doc().entityCount()) entityIndex = -1;
        if (shapeChanged && !applyingShape && layers.hasLivePlanes() && !shapeJobs.busy()) {
            var snapshot = layers;
            refreshingPlanes = true;
            submitArea(() -> new AreaResult(snapshot.compose(), snapshot));
            host.status(tr("shape_busy").getString());
        }
        if (!hasSegment(segment)) segment = "";
        if (StructureCrew.read(doc().configCopy()).stream().noneMatch(r -> r.id().equals(zone)))
            zone = "";
    }

    boolean hasOrbitEntities() {
        return entityPreview.entities.stream()
                .anyMatch(
                        entity ->
                                !glue(entity)
                                        && host.visible(BlockPos.containing(entity.position())));
    }

    Vec3 orbitHit(Vec3 from, Vec3 to) {
        Vec3 nearest = null;
        for (var entity : entityPreview.entities) {
            if (glue(entity) || !host.visible(BlockPos.containing(entity.position()))) continue;
            var hit =
                    com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                            entity.getBoundingBox(), from, to);
            if (hit.isPresent()
                    && (nearest == null
                            || from.distanceToSqr(hit.get()) < from.distanceToSqr(nearest)))
                nearest = hit.get();
        }
        return nearest;
    }

    void preparePreview() {
        layout = host.layout();
        refreshEntities();
    }

    void tick() {
        refreshCodeLayout();
        if (liveData != null && net.minecraft.Util.getMillis() >= liveDataAt) {
            var pending = liveData;
            liveData = null;
            try {
                pending.run();
            } catch (RuntimeException e) {
                if (!(e instanceof EditorValidationFeedback.Failure))
                    host.error(EditorErrorText.message(e));
            }
        }
        updateAreaTransparency();
        patchEditors.poll();
        shapeJobs.poll(
                result -> {
                    boolean opening = openingLayers == layers && openingDocument == doc();
                    openingLayers = null;
                    openingDocument = null;
                    var next = result.document();
                    layers =
                            result.layers()
                                    .mirrorCreatedRemoved(
                                            layers,
                                            host.effectiveSymmetry(),
                                            host.mirrorSpan(),
                                            mc.level);
                    if (layers != result.layers()) next = layers.compose();
                    if (result.selection() != null) {
                        activeArea = result.selection();
                        var selectedLayer = layers.find(activeArea);
                        if (selectedLayer != null) {
                            loadArea(selectedLayer);
                            host.selectRegion(
                                    selectedLayer.region().origin(), selectedLayer.region().size());
                        } else host.clearRegion();
                        rebuild = true;
                    }
                    layerHistory.put(next, layers);
                    areaSelections.put(next, activeArea);
                    applyingShape = true;
                    try {
                        if (dragging() || refreshingPlanes) host.previewDraft(next);
                        else {
                            host.commitGesture(
                                    shapeCommitBase == null ? doc() : shapeCommitBase, next);
                            shapeCommitBase = null;
                        }
                        if (opening) {
                            savedLayers = layers;
                            host.acceptOpeningRefresh();
                        }
                        if (result.acceptedMassRegion() != null)
                            massRegion = result.acceptedMassRegion();
                        rememberArea();
                    } finally {
                        applyingShape = false;
                        refreshingPlanes = false;
                    }
                    for (var plane : layers.layers())
                        if (plane.settings().has("plane_error")) {
                            host.status(
                                    Component.translatable(
                                                    "npships.tools.fitted_plane_wait",
                                                    plane.settings()
                                                            .get("plane_error")
                                                            .getAsString())
                                            .getString());
                            break;
                        }
                },
                error -> {
                    pendingDeformationAction = null;
                    refreshingPlanes = false;
                    applyingShape = true;
                    try {
                        if (shapeCommitBase != null) host.previewDraft(shapeCommitBase);
                        shapeCommitBase = null;
                    } finally {
                        applyingShape = false;
                    }
                    if (layers.find(activeArea) != null) {
                        loadArea(layers.get(activeArea));
                        host.selectRegion(
                                layers.get(activeArea).region().origin(),
                                layers.get(activeArea).region().size());
                        rebuild = true;
                    }
                    host.cancelDeferredSave();
                    host.error(EditorErrorText.message(error));
                });
        if (pendingDeformationAction != null && !shapeJobs.busy()) {
            var action = pendingDeformationAction;
            pendingDeformationAction = null;
            run(action);
        }
        if (deferredHistory != null && !shapeJobs.busy()) {
            boolean redo = deferredHistory;
            deferredHistory = null;
            host.restoreHistory(redo);
        }
        var reply = LootTableNetworking.clientReply();
        if (reply != null && serverTables != reply.tables()) {
            lootReceived = true;
            serverTables = reply.tables();
            if (tool == Tool.LOOT && sections.find("table_editor") == null) rebuild = true;
        }
        if (inventorySliding
                && org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), 0)
                        != org.lwjgl.glfw.GLFW.GLFW_PRESS) inventorySliding = false;
        if (rebuild && !inventorySliding && !retainFocusedCode()) {
            if (!flushEdits()) {
                rebuild = false;
                return;
            }
            rebuild = false;
            build();
        }
    }

    boolean building;

    void build() {
        codeWorkspaceAction = snbtReferenceAction = null;
        inventoryWorkspaceAction = null;
        sections.reset();
        lastField = null;
        lootToolControls.resetLayout();
        registryRemainder = itemRemainder = 0;
        horizontalRoles.clear();
        notes.clear();
        wrappedText.clear();
        flows.clear();
        parameters.resetHandled();
        loadPins();
        host.validation.context(null);
        codeEditor = null;
        codeContext = null;
        codeComparedBase = codeComparedCurrent = null;
        liveData = null;
        expandedCode = false;
        building = true;
        itemCells.clear();
        inventoryCells.clear();
        compactInventoryCells.clear();
        fieldSavers.clear();
        pendingSettings = null;
        rebuild = false;
        widgets.clear();
        searchStart = searchEnd = -1;
        toolbar.clear();
        focused = null;
        cached = doc();
        segments = StructureSegments.read(cached.configCopy());
        zones = StructureCrew.read(cached.configCopy());
        int index = 0;
        for (var t : Tool.values()) {
            if (t == Tool.AREA || t == Tool.STYLES || t == Tool.ASSEMBLY && host.isPart()) continue;
            var b =
                    new EditorIconButton(
                            t.name().toLowerCase(Locale.ROOT),
                            tr(t.name().toLowerCase(Locale.ROOT)),
                            x + index++ * toolbarPitch(),
                            y,
                            () -> {
                                if (t != tool || massRegionEditing || host.extensionPanelActive())
                                    change(t);
                            });
            b.selected(
                    () ->
                            !massRegionEditing
                                    && (t == tool || t == Tool.BLOCKS && tool == Tool.AREA));
            b.active = toolAvailable(t);
            if (t != Tool.ASSEMBLY)
                EditorShortcutDispatcher.hint(b, EditorShortcutDispatcher.Action.valueOf(t.name()));
            toolbar.add(b);
        }
        cursor = layout.inspectorContent().top();
        if (hasSelection() && regionTool() && tool != Tool.AREA)
            areaIcons(
                    new String[] {"symmetric_resize"},
                    new Runnable[] {
                        () -> {
                            host.symmetricResize = !host.symmetricResize;
                            build();
                        }
                    },
                    new java.util.function.BooleanSupplier[] {() -> host.symmetricResize});
        if (tool == Tool.BLOCKS || tool == Tool.AREA) {
            var single = icon("single_blocks", x, cursor, () -> change(Tool.BLOCKS));
            single.selected(() -> tool == Tool.BLOCKS && !massRegionEditing);
            EditorShortcutDispatcher.hint(single, EditorShortcutDispatcher.Action.BLOCKS);
            var bulk = icon("area", x + 24, cursor, () -> change(Tool.AREA));
            bulk.selected(() -> tool == Tool.AREA && !massRegionEditing);
            EditorShortcutDispatcher.hint(bulk, EditorShortcutDispatcher.Action.BLOCKS_AREAS);
            cursor += 24;
        }
        try {
            switch (tool) {
                case STYLES -> buttonStyles();
                case BLOCKS -> blocks();
                case ENTITIES -> entityTools();
                case GLUE -> glueTools();
                case ASSEMBLY -> assemblyPoints.controls();
                case AREA -> areaTools();
                case SEGMENTS -> segmentTools();
                case ZONES -> zoneTools();
                case LOOT -> lootTools();
                case PATCHES -> patchTools();
            }
        } catch (Exception e) {
            if (!(e instanceof EditorValidationFeedback.Failure))
                host.error(EditorErrorText.message(e));
        }
        contentHeight = cursor - layout.inspectorContent().top();
        scroll =
                Math.min(scroll, Math.max(0, contentHeight - (layout.inspectorContent().height())));
        for (var widget : widgets) {
            if (widget.getTooltip() == null
                    && !(widget instanceof LuaEditBox)
                    && (widget instanceof EditBox || widget instanceof MultiLineEditBox))
                widget.setTooltip(
                        Tooltip.create(
                                Component.translatable(
                                        "npships.tools.field_hint", widget.getMessage())));
            com.eynny.npships.client.editor.widget.EditorButtonTheme.meaningfulTooltip(widget);
            widget.setY(widget.getY() - scroll);
            if (widget.getX() + widget.getWidth() > x + contentWidth())
                widget.setWidth(Math.max(1, x + contentWidth() - widget.getX()));
        }
        refreshEntities();
        building = false;
        captureArrangement();
        if (sections.any()) layoutOnly(layout);
    }

    private List<com.eynny.npships.authoring.SchematicMass.Part> massParts = List.of();
    private com.eynny.npships.authoring.SchematicMass.Analysis massAnalysis;
    private boolean showMass, massPending;

    private void buttonStyles() {
        // Kept only as a retained-session compatibility route; ordinary UI uses the dropdown.
        for (var palette : EditorButtonTheme.Palette.values()) {
            buttonAt(
                    tr("palette_" + palette.name().toLowerCase(Locale.ROOT)),
                    x,
                    cursor,
                    contentWidth(),
                    () -> EditorButtonTheme.use(palette));
            cursor += 24;
        }
    }

    private AABB massRegion;
    private boolean showRegionMass;

    void toggleMass() {
        showMass = !showMass;
        if (showMass) {
            if (massRegionEditing) host.finishCreationMode();
            showRegionMass = false;
            massRegionEditing = false;
            massRegion = null;
            calculateMass();
        } else {
            massAnalysis = null;
            massParts = List.of();
        }
    }

    boolean showingMass() {
        return showMass;
    }

    void chooseMassRegion() {
        if (!flushEdits()) return;
        massRegionEditing = !massRegionEditing;
        showRegionMass = massRegionEditing;
        showMass = false;
        if (massRegionEditing) host.temporaryCreateMode();
        else host.finishCreationMode();
        if (!massRegionEditing) {
            massRegion = null;
            showMass = false;
            massAnalysis = null;
            massParts = List.of();
            massPending = false;
        }
        host.select(null);
        host.clearRegion();
        entityIndex = -1;
        segment = "";
        zone = "";
        linkRole = null;
        patchLinks.close();
        linkPatch = null;
        firstLink = null;
        linkTargets = List.of();
        rebuild = true;
    }

    boolean choosingMassRegion() {
        return massRegionEditing;
    }

    EditorIconButton icon(String key, int bx, int by, Runnable action) {
        var button = new EditorIconButton(key, tr(key), bx, by, () -> run(action));
        widgets.add(button);
        return button;
    }

    private void calculateMass() {
        massParts = List.of();
        massAnalysis = null;
        massPending = true;
    }

    private void advanceMass() {
        if (!(showMass || showRegionMass) || shapeJobs.busy() || dragging()) return;
        try {
            if (massPending) {
                massAnalysis = new SchematicMass.Analysis(doc(), host.physicsLevel(), massRegion);
                massPending = false;
            }
            if (massAnalysis != null && massAnalysis.stepForNanos(3_000_000L)) {
                massParts = massAnalysis.result();
                massAnalysis = null;
            }
        } catch (RuntimeException e) {
            massAnalysis = null;
            massPending = false;
            host.error(EditorErrorText.message(e));
        }
    }

    Component massProgress() {
        return (showMass || showRegionMass) && (massPending || massAnalysis != null)
                ? tr("mass_progress")
                        .copy()
                        .append(
                                " "
                                        + (massAnalysis == null
                                                ? 0
                                                : massAnalysis.progressPercent())
                                        + "%")
                : null;
    }

    Component hoveredRegion(double mx, double my, Matrix4f inverse, float depth) {
        if (dragging()
                && !gizmoGesture
                && dragBounds != null
                && proposedBounds != null
                && (regionTool() || host.specialBounds() != null)) {
            return Component.translatable(
                    "npships.tools.region_resize_delta",
                    (int) Math.round(proposedBounds.getXsize()),
                    signedSizeDelta(proposedBounds.getXsize() - dragBounds.getXsize()),
                    (int) Math.round(proposedBounds.getYsize()),
                    signedSizeDelta(proposedBounds.getYsize() - dragBounds.getYsize()),
                    (int) Math.round(proposedBounds.getZsize()),
                    signedSizeDelta(proposedBounds.getZsize() - dragBounds.getZsize()));
        }
        if (inverse == null) return null;
        var ray = ray(mx, my, inverse, depth);
        var boxes = new ArrayList<AABB>();
        if (host.specialBounds() != null) boxes.add(host.specialBounds());
        if (host.firstCorner() != null && host.lastCorner() != null) {
            var a = host.firstCorner();
            var b = host.lastCorner();
            boxes.add(
                    new AABB(
                            Math.min(a.getX(), b.getX()),
                            Math.min(a.getY(), b.getY()),
                            Math.min(a.getZ(), b.getZ()),
                            Math.max(a.getX(), b.getX()) + 1,
                            Math.max(a.getY(), b.getY()) + 1,
                            Math.max(a.getZ(), b.getZ()) + 1));
        }
        if (tool == Tool.SEGMENTS || host.allZones())
            for (var s : segments) boxes.add(bounds(s.origin(), s.size()));
        if (tool == Tool.ZONES || host.allZones())
            for (var z : zones) boxes.add(bounds(z.origin(), z.size()));
        if (tool == Tool.GLUE || host.allZones())
            for (var e : entityPreview.entities) if (glue(e)) boxes.add(e.getBoundingBox());
        AABB nearest = null;
        double distance = Double.MAX_VALUE;
        for (var b : boxes) {
            var hit =
                    com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                            b, ray[0], ray[1]);
            if (hit.isPresent() && hit.get().distanceToSqr(ray[0]) < distance) {
                nearest = b;
                distance = hit.get().distanceToSqr(ray[0]);
            }
        }
        return nearest == null
                ? null
                : Component.translatable(
                        "npships.tools.region_dimensions",
                        (int) Math.round(nearest.getXsize()),
                        (int) Math.round(nearest.getYsize()),
                        (int) Math.round(nearest.getZsize()));
    }

    private static String signedSizeDelta(double value) {
        long blocks = Math.round(value);
        return (blocks >= 0 ? "+" : "") + blocks;
    }

    void calculateRegionMass() {
        var a = host.firstCorner();
        var b = host.lastCorner();
        massRegion =
                a == null || b == null
                        ? null
                        : new AABB(
                                Math.min(a.getX(), b.getX()),
                                Math.min(a.getY(), b.getY()),
                                Math.min(a.getZ(), b.getZ()),
                                Math.max(a.getX(), b.getX()) + 1,
                                Math.max(a.getY(), b.getY()) + 1,
                                Math.max(a.getZ(), b.getZ()) + 1);
        showRegionMass = true;
        showMass = false;
        calculateMass();
    }

    void turnDocument(boolean right) {
        if (!flushEdits()) return;
        var before = doc();
        rememberArea();
        boolean selectedArea = host.firstCorner() != null && host.lastCorner() != null;
        if (selectedArea) readCorners();
        var region =
                SchematicTurn.region(
                        new SchematicRegions.Region(
                                BlockEditorScreen.position(areaOrigin),
                                BlockEditorScreen.position(areaSize)),
                        before.size(),
                        right);
        var background = shapeBase == null ? null : shapeBase.turn(right, mc.level);
        String selectedSegment = segment, selectedZone = zone;
        int selectedEntity = entityIndex;
        String rotatedState =
                BlockStateParser.serialize(
                        parse(state)
                                .rotate(
                                        right
                                                ? Rotation.CLOCKWISE_90
                                                : Rotation.COUNTERCLOCKWISE_90));
        var turnedMass = massRegion;
        if (massRegion != null) {
            var r =
                    SchematicTurn.region(
                            new SchematicRegions.Region(
                                    BlockPos.containing(
                                            massRegion.minX, massRegion.minY, massRegion.minZ),
                                    BlockPos.containing(
                                            massRegion.getXsize(),
                                            massRegion.getYsize(),
                                            massRegion.getZsize())),
                            before.size(),
                            right);
            turnedMass = r.bounds();
        }
        var turned = layers.turn(right, mc.level);
        for (var l : turned.layers()) {
            var options = l.settings();
            options.addProperty(
                    "generated_turns",
                    Math.floorMod(
                            (options.has("generated_turns")
                                            ? options.get("generated_turns").getAsInt()
                                            : 0)
                                    + (right ? 1 : -1),
                            4));
            options.add(
                    "deformation",
                    ShapeDeformation.decode(
                                    options.has("deformation")
                                            ? options.getAsJsonObject("deformation")
                                            : null)
                            .turned(right)
                            .encode());
            if (options.has("brush"))
                options.addProperty(
                        "brush",
                        BlockStateParser.serialize(
                                parse(options.get("brush").getAsString())
                                        .rotate(
                                                right
                                                        ? Rotation.CLOCKWISE_90
                                                        : Rotation.COUNTERCLOCKWISE_90)));
            if (options.has("random_pool"))
                for (var item : options.getAsJsonArray("random_pool")) {
                    var entry = item.getAsJsonObject();
                    entry.addProperty(
                            "block",
                            BlockStateParser.serialize(
                                    parse(entry.get("block").getAsString())
                                            .rotate(
                                                    right
                                                            ? Rotation.CLOCKWISE_90
                                                            : Rotation.COUNTERCLOCKWISE_90)));
                }
            turned = turned.replace(l.id(), l.region(), l.content(), l.mask(), options);
        }
        layerCommit(turned, turnedMass);
        if (layers.find(activeArea) != null) loadArea(layers.get(activeArea));
        shapeBase = background;
        areaOrigin = xyz(region.origin());
        areaSize = xyz(region.size());
        state = rotatedState;
        segment = selectedSegment;
        zone = selectedZone;
        entityIndex = selectedEntity;
        host.select(null);
        if (selectedArea) host.selectRegion(region.origin(), region.size());
        rememberArea();
        build();
    }

    String areaOrigin = "0 0 0", areaSize = "1 1 1";
    boolean areaBlocks = true, areaEntities = true, areaZones = true, areaHollow;
    SchematicRegions.Shape areaShape = SchematicRegions.Shape.NONE;
    private SchematicDocument areaClipboard;
    private BitSet clipboardMask;
    private SchematicRegions.Region clipboardRegion;
    private SchematicRegions.Contents clipboardContents;
    private SchematicShape shapeBase;
    boolean applyingShape, areaGenerated = true, shapeListOpen;
    int areaTurns;
    BlockAreaLayers.State layers, savedLayers;
    private SchematicDocument lastLayerDocument;
    String activeArea = "";
    private final Map<SchematicDocument, BlockAreaLayers.State> layerHistory =
            new IdentityHashMap<>();
    private final Map<SchematicDocument, String> areaSelections = new IdentityHashMap<>();

    record AreaResult(
            SchematicDocument document,
            BlockAreaLayers.State layers,
            String selection,
            AABB acceptedMassRegion) {
        AreaResult(SchematicDocument document, BlockAreaLayers.State layers) {
            this(document, layers, null, null);
        }

        AreaResult(SchematicDocument document, BlockAreaLayers.State layers, String selection) {
            this(document, layers, selection, null);
        }
    }

    double originalBlockWeight = 1;
    boolean boundaryDrag, deferredShape;
    boolean randomPanelOpen;
    boolean areaSticky, areaTransparent, randomBlocks, mergeChoosing, mergeSubtract;
    long randomSeed = 1;
    final List<String> randomStates = new ArrayList<>();
    final List<Double> randomWeights = new ArrayList<>();
    ShapeDeformation deformation = ShapeDeformation.IDENTITY;
    boolean deforming;
    int dragCorner = -1;
    final AreaGizmo areaGizmo = new AreaGizmo();
    boolean gizmoGesture;
    private ShapeDeformation dragDeformation;
    private Matrix4f deformationInverse;
    final LatestTask<AreaResult> shapeJobs =
            new LatestTask<>(net.minecraft.Util.backgroundExecutor());
    private boolean refreshingPlanes;
    private BlockAreaLayers.State openingLayers;
    private SchematicDocument openingDocument;

    private void submitArea(java.util.function.Supplier<AreaResult> work) {
        openingLayers = null;
        openingDocument = null;
        String selectedPlane =
                host.firstCorner() != null && FittedPlaneLayers.plane(layers.find(activeArea))
                        ? activeArea
                        : null;
        shapeJobs.submit(
                () -> {
                    var result = work.get();
                    var refreshed = result.layers().refreshPlanes(null);
                    String selection =
                            result.selection() != null ? result.selection() : selectedPlane;
                    if (selection != null && refreshed.find(selection) == null) selection = null;
                    return new AreaResult(
                            refreshed.compose(), refreshed, selection, result.acceptedMassRegion());
                });
    }

    SchematicDocument shapeCommitBase;
    boolean movingArea;

    record AreaState(
            String origin,
            String size,
            SchematicRegions.Shape shape,
            boolean hollow,
            boolean generated,
            int turns,
            String block,
            SchematicShape background,
            ShapeDeformation deformation) {}

    private final Map<SchematicDocument, AreaState> areaHistory = new IdentityHashMap<>();

    record SelectionState(
            Tool tool,
            BlockPos block,
            int entity,
            String segment,
            String zone,
            BlockPos first,
            BlockPos last,
            String inspectedArea) {}

    private final Map<SchematicDocument, SelectionState> selections = new IdentityHashMap<>();

    private SelectionState selectionState() {
        return new SelectionState(
                tool,
                pos(),
                entityIndex,
                segment,
                zone,
                host.firstCorner(),
                host.lastCorner(),
                inspectedArea);
    }

    private void rememberSelection() {
        selections.keySet().removeIf(d -> !host.retainedDocument(d) && d != dragOriginal);
        selections.put(doc(), selectionState());
    }

    private SelectionState undoSelection;

    boolean restoreSelection(SchematicDocument restored) {
        var saved = selections.getOrDefault(restored, undoSelection);
        if (saved == null) return false;
        tool = saved.tool();
        inspectedArea = saved.inspectedArea();
        host.select(
                saved.block() != null && restored.blocks().containsKey(saved.block())
                        ? saved.block()
                        : null);
        entityIndex = saved.entity() < restored.entityCount() ? saved.entity() : -1;
        segment =
                StructureSegments.read(restored.configCopy()).stream()
                                .anyMatch(s -> s.id().equals(saved.segment()))
                        ? saved.segment()
                        : "";
        zone =
                StructureCrew.read(restored.configCopy()).stream()
                                .anyMatch(s -> s.id().equals(saved.zone()))
                        ? saved.zone()
                        : "";
        if (tool == Tool.AREA && !inspectedArea.isEmpty()) {
            if (layerHistory.containsKey(restored)) layers = layerHistory.get(restored);
            var layer = layers.find(inspectedArea);
            if (layer != null) host.selectRegion(layer.region().origin(), layer.region().size());
            else {
                inspectedArea = "";
                host.clearRegion();
            }
            rebuild = true;
            return true;
        }
        if (tool == Tool.AREA) return restoreArea(restored);
        if (saved.first() != null
                && saved.last() != null
                && restored.contains(saved.first())
                && restored.contains(saved.last()))
            host.selectRegion(saved.first(), saved.last().subtract(saved.first()).offset(1, 1, 1));
        rebuild = true;
        return true;
    }

    void rememberArea() {
        rememberSelection();
        layerHistory
                .keySet()
                .removeIf(
                        d ->
                                !host.retainedDocument(d)
                                        && d != shapeCommitBase
                                        && d != dragOriginal);
        areaSelections.keySet().retainAll(layerHistory.keySet());
        if (layers != null) {
            layerHistory.putIfAbsent(doc(), layers);
            areaSelections.putIfAbsent(doc(), activeArea);
        }
        if (doc() == shapeCommitBase) return;
        areaHistory
                .keySet()
                .removeIf(
                        d ->
                                !host.retainedDocument(d)
                                        && d != shapeCommitBase
                                        && d != dragOriginal);
        if (tool == Tool.AREA && inspectedArea.isEmpty() && host.firstCorner() != null) {
            readCorners();
            areaHistory.put(
                    doc(),
                    new AreaState(
                            areaOrigin,
                            areaSize,
                            areaShape,
                            areaHollow,
                            areaGenerated,
                            areaTurns,
                            state,
                            shapeBase,
                            deformation));
        }
    }

    boolean restoreArea(SchematicDocument restored) {
        if (tool != Tool.AREA) return false;
        if (layerHistory.containsKey(restored)) {
            layers = layerHistory.get(restored);
            activeArea = areaSelections.getOrDefault(restored, activeArea);
            if (layers.find(activeArea) != null) {
                loadArea(layers.find(activeArea));
                host.selectRegion(
                        layers.find(activeArea).region().origin(),
                        layers.find(activeArea).region().size());
            } else {
                activeArea = "";
                host.clearRegion();
            }
            rebuild = true;
            return true;
        }
        var saved = areaHistory.get(restored);
        if (saved != null) {
            areaOrigin = saved.origin();
            areaSize = saved.size();
            areaShape = saved.shape();
            areaHollow = saved.hollow();
            areaGenerated = saved.generated();
            areaTurns = saved.turns();
            state = saved.block();
            shapeBase = saved.background();
            deformation = saved.deformation();
        }
        var origin = BlockEditorScreen.position(areaOrigin);
        var size = BlockEditorScreen.position(areaSize);
        if (origin.getX() + size.getX() > restored.size().getX()
                || origin.getY() + size.getY() > restored.size().getY()
                || origin.getZ() + size.getZ() > restored.size().getZ()) return false;
        host.selectRegion(origin, size);
        rebuild = true;
        return true;
    }

    private Boolean deferredHistory;
    LuaEditBox codeEditor;
    private boolean expandedCode;
    private CodeContext codeContext;
    private boolean codeDocumentBound;
    private SchematicDocument codeComparedBase, codeComparedCurrent;
    private boolean codeComparedEqual;

    private record CodeContext(
            Tool tool,
            BlockPos position,
            int entity,
            String segment,
            String zone,
            String area,
            boolean compactEntity) {}

    private CodeContext codeContext() {
        return new CodeContext(
                tool,
                pos(),
                entityIndex,
                segment,
                zone,
                selectedAreaId(),
                host.hideOverriddenEntityFields());
    }

    /** Automatic refreshes wait for blur while the same accepted text owner still has focus. */
    private boolean retainFocusedCode() {
        if (codeEditor == null
                || focused != codeEditor
                || !codeEditor.isFocused()
                || !host.validation.owns(codeEditor)
                || !Objects.equals(codeContext, codeContext())
                || cached == null) return false;
        if (cached == doc()) return true;
        // The nested JSON form owns Apply/Cancel against its exact opening document.
        if (codeDocumentBound) return false;
        if (codeComparedBase != cached || codeComparedCurrent != doc()) {
            codeComparedBase = cached;
            codeComparedCurrent = doc();
            codeComparedEqual = sameCodePayload(cached);
        }
        return codeComparedEqual;
    }

    private boolean sameCodePayload(SchematicDocument accepted) {
        if (accepted == doc()) return true;
        return pos() != null
                ? accepted.sameBlockAt(doc(), pos())
                : entityIndex >= 0
                        ? accepted.sameEntityAt(doc(), entityIndex)
                        : Objects.equals(accepted.configCopy(), doc().configCopy());
    }

    private EditorButton codeWorkspaceAction, snbtReferenceAction;
    AbstractWidget inventoryWorkspaceAction;

    enum WorkspaceOwner {
        NONE,
        CODE,
        INVENTORY
    }

    private boolean workspaceButton(AbstractWidget button) {
        return button != null
                && button.active
                && sections.inputAvailable(button)
                && button.getWidth() >= 20
                && button.getHeight() >= 20;
    }

    WorkspaceOwner workspaceOwner() {
        boolean code =
                codeEditor != null
                        && textSurfaceAvailable(codeEditor)
                        && workspaceButton(codeWorkspaceAction);
        boolean inventory =
                (!inventoryCells.isEmpty() || !compactInventoryCells.isEmpty())
                        && workspaceButton(inventoryWorkspaceAction);
        if (expandedCode()) return code ? WorkspaceOwner.CODE : WorkspaceOwner.NONE;
        if (inventoryExpanded)
            return inventoryWorkspaceAction != null
                            && resourceInventory != null
                            && java.util.Objects.equals(resourceOwner, pos())
                    ? WorkspaceOwner.INVENTORY
                    : WorkspaceOwner.NONE;
        if ((focused == codeEditor || focused == codeWorkspaceAction) && code)
            return WorkspaceOwner.CODE;
        if (focused != null && sections.sameSection(focused, inventoryWorkspaceAction) && inventory)
            return WorkspaceOwner.INVENTORY;
        if (focused != null
                && focused != codeWorkspaceAction
                && focused != inventoryWorkspaceAction) return WorkspaceOwner.NONE;
        if (code == inventory) return WorkspaceOwner.NONE;
        return code ? WorkspaceOwner.CODE : WorkspaceOwner.INVENTORY;
    }

    void toggleWorkspace() {
        switch (workspaceOwner()) {
            case CODE -> toggleCodeWorkspace();
            case INVENTORY -> run(this::acceptInventoryWorkspaceToggle);
            case NONE -> {}
        }
    }

    void acceptInventoryWorkspaceToggle() {
        inventoryExpanded = !inventoryExpanded;
        if (!inventoryExpanded) sections.expansion("contents", true);
        build();
    }

    boolean referenceShortcutAvailable() {
        if (currentReferenceOpen()) return true;
        return codeEditor != null
                && codeEditor.dataMode()
                && textSurfaceAvailable(codeEditor)
                && snbtReferenceAction != null
                && snbtReferenceAction.active
                && (expandedCode() || workspaceButton(snbtReferenceAction))
                && (focused == null
                        || focused == codeEditor
                        || focused == snbtReferenceAction
                        || pinnedHelp == codeEditor && pinnedHelp.referenceOpen());
    }

    boolean currentReferenceOpen() {
        return pinnedHelp == codeEditor && pinnedHelp != null && pinnedHelp.referenceOpen();
    }

    void toggleSnbtReference() {
        if (codeEditor == null || !codeEditor.dataMode()) return;
        if (textSurfaceAvailable(codeEditor)) focusCode();
        if (pinnedHelp != null && pinnedHelp != codeEditor && pinnedHelp.referenceOpen())
            pinnedHelp.toggleStructureHelp();
        pinnedHelp = codeEditor;
        codeEditor.toggleStructureHelp();
    }

    final EditorScrollBar inventoryBar = new EditorScrollBar();
    boolean inventoryExpanded, inventorySliding;
    int inventoryOffsetX, inventoryOffsetY;
    int inventoryRowsY;
    final List<AbstractWidget> inventoryCells = new ArrayList<>();
    final List<AbstractWidget> compactInventoryCells = new ArrayList<>();

    private boolean inventoryAt(double mx, double my) {
        return inventoryExpanded
                && !inventoryCells.isEmpty()
                && !expandedCode()
                && layout.expandedInventory().contains(mx, my);
    }

    boolean expandedWorkspace() {
        return expandedCode() || inventoryExpanded && !inventoryCells.isEmpty();
    }

    int codeDockY;
    int codeDockHeight = 140;

    /** Native cached visual lines drive only retained geometry, never acceptance or parsing. */
    void refreshCodeLayout() {
        if (building || codeEditor == null || expandedCode() || captureActive()) return;
        int height = codeEditor.compactHeight(layout.contentWidth(), layout.codeDockHeight());
        if (height != codeDockHeight) {
            preferInspectorScroll();
            layoutOnly(layout);
        }
    }

    boolean expandedCode() {
        return expandedCode && codeEditor != null;
    }

    boolean codeAt(double mx, double my) {
        return expandedCode() && layout.expandedCode().contains(mx, my);
    }

    private void toggleCode() {
        if (codeEditor == null) return;
        if (sections.any()) {
            preferInspectorScroll();
            expandedCode = !expandedCode;
            if (!expandedCode) sections.revealCodeSection();
            layoutOnly(layout);
            focusCode();
            return;
        }
        expandedCode = !expandedCode;
        int delta = (expandedCode ? -1 : 1) * (codeDockHeight + 4);
        boolean below = false;
        for (var widget : widgets) {
            if (widget == codeEditor) {
                below = true;
                continue;
            }
            if (below) widget.setY(widget.getY() + delta);
        }
        contentHeight += delta;
        setScroll(
                Math.min(
                        scroll, Math.max(0, contentHeight - (layout.inspectorContent().height()))));
        if (!expandedCode) layout.codeDock(codeDockY, scroll, codeDockHeight).apply(codeEditor);
        focusCode();
        captureArrangement();
    }

    private void toggleCodeWorkspace() {
        toggleCode();
        if (codeWorkspaceAction != null)
            codeWorkspaceAction.setMessage(tr(expandedCode() ? "data_dock" : "data_expand"));
    }

    private void focusCode() {
        focusWidget(codeEditor);
    }

    void codeControls(LuaEditBox editor) {
        codeEditor = editor;
        codeContext = codeContext();
        codeDocumentBound = false;
        sections.code(editor);
        editor.clipboard(mc.keyboardHandler::setClipboard);
        editor.valueFocus(() -> focusWidget(editor));
        expandedCode = false;
        codeDockY = editor.getY();
        codeDockHeight = editor.getHeight();
        codeWorkspaceAction =
                EditorButton.flat(tr("data_expand"), button -> toggleCodeWorkspace())
                        .bounds(x, cursor, contentWidth(), 20)
                        .tooltip(Tooltip.create(tr("data_expand_hint")))
                        .build()
                        .selected(this::expandedCode)
                        .tooltip(
                                () ->
                                        EditorShortcutDispatcher.hint(
                                                tr("data_expand_hint"),
                                                EditorShortcutDispatcher.Action.WORKSPACE));
        widgets.add(codeWorkspaceAction);
        sections.workspaceAction(widgets.getLast());
        cursor += 24;
        syncTextPopup();
    }

    void toggleCodeSuggestions() {
        if (codeEditor != null) codeEditor.toggleSuggestions();
        else LuaEditBox.toggleAutomaticSuggestions();
    }

    private void syncTextPopup() {
        if (codeEditor == null) return;
        codeEditor.popupSurface(
                layout.screenBounds().width(),
                expandedCode() ? layout.expandedCodeField().top() : layout.inspectorContent().top(),
                expandedCode() ? layout.expandedCode().bottom() - 4 : layout.inspector().bottom());
    }

    boolean dismissValuePickerOutside(double mx, double my) {
        syncTextPopup();
        if (codeEditor == null
                || !textSurfaceAvailable(codeEditor)
                || !codeEditor.valuePickerOpen()
                || codeEditor.popupContains(mx, my)) return false;
        codeEditor.dismissCompletion();
        return true;
    }

    void renderCodeWorkspace(GuiGraphics g, int mx, int my, float tick) {
        if (inventoryExpanded && !expandedCode() && !inventoryCells.isEmpty()) {
            layout.expandedInventory().fill(g, EditorButtonTheme.panel());
            layout.expandedInventory().scissor(g);
            for (var cell : inventoryCells) cell.render(g, mx, my, tick);
            inventoryBar.render(g);
            g.disableScissor();
        }
        if (!expandedCode()
                || layout.expandedCodeField().width() < 24
                || layout.expandedCodeField().height() < 20) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        layout.expandedCode().fill(g, EditorButtonTheme.panel());
        layout.expandedCodeField().apply(codeEditor);
        codeEditor.render(g, mx, my, tick);
        codeEditor.suggestions(
                g, layout.expandedCodeField().top(), layout.expandedCode().bottom() - 4, mx, my);
        g.pose().popPose();
    }

    boolean deferHistory(boolean redo) {
        if (!shapeJobs.busy()) return false;
        deferredHistory = redo;
        host.status(tr("shape_busy").getString());
        return true;
    }

    void prepareUndo() {
        externalPatchControls.clear();
        patchRegionTool.clear();
        undoSelection = selectionState();
        rememberSelection();
        if (!areaHistory.containsKey(doc())) rememberArea();
        shapeJobs.discard();
        shapeCommitBase = null;
        applyingShape = true;
    }

    void finishUndo() {
        applyingShape = false;
    }

    void updateShape() {
        var currentLayer = layers.find(activeArea);
        if (currentLayer != null) {
            var previousAngles =
                    ShapeDeformation.decode(currentLayer.settings().getAsJsonObject("deformation"))
                            .parameters();
            var angles = deformation.parameters();
            for (int i = 0; i < 3; i++)
                if (Math.abs(angles[i] - previousAngles[i]) > 1e-8) areaSticky = false;
        }
        if (dragging()
                && (gizmoGesture || movingArea || boundaryDrag || doc().blocks().size() > 65536)) {
            deferredShape = true;
            host.status(tr("area_deferred").getString());
            return;
        }
        deferredShape = false;
        if (host.firstCorner() == null || host.lastCorner() == null) return;
        ensureArea();
        if (shapeCommitBase == null) {
            rememberArea();
            shapeCommitBase = doc();
        }
        var region = area();
        var layer = layers.find(activeArea);
        if (layer == null) return;
        var snapshot = layers;
        var id = activeArea;
        var options = areaOptions();
        var shape = areaShape;
        var block = parse(state);
        var warp = deformation;
        boolean generated = areaGenerated && areaBlocks && areaShape != SchematicRegions.Shape.NONE,
                hollow = areaHollow,
                sticky = areaSticky;
        var contents = areaContents();
        long seed = randomSeed;
        int turns = areaTurns;
        double retainedWeight = originalBlockWeight;
        boolean expand = boundaryDrag;
        boolean allowLoss = allowConfiguredLoss;
        if (expand && !deforming && warp.direct().mode() == DirectDeformation.Mode.SCALE)
            warp = warp.direct(DirectDeformation.NONE);
        var weights = new ArrayList<AreaGeometry.Weighted>();
        if (randomBlocks)
            for (int i = 0; i < randomStates.size(); i++)
                weights.add(
                        new AreaGeometry.Weighted(
                                parse(randomStates.get(i)), randomWeights.get(i)));
        var request =
                new AreaEditOperation.Request(
                        snapshot,
                        id,
                        region,
                        options,
                        contents,
                        shape,
                        block,
                        warp,
                        generated,
                        hollow,
                        sticky,
                        expand,
                        weights,
                        seed,
                        turns,
                        retainedWeight);
        submitArea(
                () -> {
                    var result = AreaEditOperation.apply(request, allowLoss);
                    return new AreaResult(result.document(), result.layers(), result.selection());
                });
        if (!dragging()) boundaryDrag = false;
        host.status(tr("shape_busy").getString());
    }

    boolean fittedPlaneMenu;
    FittedPlane.Orientation fittedPlane;

    void beginFittedPlane(FittedPlane.Orientation orientation) {
        if (!flushEdits()) return;
        clearSelection();
        host.clearRegion();
        fittedPlane = orientation;
        fittedPlaneMenu = false;
        host.setCreateMode();
        host.status(tr("fitted_plane_pick").getString());
        rebuild = true;
    }

    boolean placeFittedPlane(BlockPos point) {
        if (tool != Tool.AREA || fittedPlane == null) return false;
        if (point == null) return true;
        var orientation = fittedPlane;
        var brush = parse(state);
        var snapshot = layers;
        var document = doc();
        var brushText = state;
        shapeCommitBase = document;
        fittedPlane = null;
        host.setSelectMode();
        submitArea(
                () -> {
                    var plane = FittedPlane.build(document, point, orientation, brush);
                    var next =
                            snapshot.create(
                                    plane.region(),
                                    new SchematicRegions.Contents(true, false, false),
                                    false);
                    var layer = next.layers().getLast();
                    var settings = new JsonObject();
                    settings.addProperty("blocks", true);
                    settings.addProperty("entities", false);
                    settings.addProperty("zones", false);
                    settings.addProperty("sticky", false);
                    settings.addProperty("transparent", true);
                    settings.addProperty("generated", false);
                    settings.addProperty("shape", "NONE");
                    settings.addProperty("brush", brushText);
                    settings.add(
                            FittedPlaneLayers.KEY,
                            FittedPlaneLayers.definition(
                                    point, orientation, plane.region(), brush));
                    next =
                            next.withSource(
                                            layer.id(),
                                            plane.geometry().content(),
                                            plane.geometry().mask())
                                    .replace(
                                            layer.id(),
                                            plane.region(),
                                            plane.geometry().content(),
                                            plane.geometry().mask(),
                                            settings);
                    return new AreaResult(next.compose(), next, layer.id());
                });
        host.status(tr("shape_busy").getString());
        return true;
    }

    SchematicRegions.Region gizmoRegion() {
        if (deforming
                && areaGizmo.tool == 1
                && deformation.direct().mode() == DirectDeformation.Mode.SCALE) return area();
        var layer = layers.find(activeArea);
        return FittedPlaneLayers.plane(layer) && !FittedPlaneLayers.locked(layer)
                ? FittedPlaneLayers.frame(layer.settings().getAsJsonObject(FittedPlaneLayers.KEY))
                : area();
    }

    void togglePlaneLock() {
        if (!flushEdits()) return;
        layerCommit(FittedPlaneLayers.toggleLock(layers, activeArea));
        if (!shapeJobs.busy()) loadArea(layers.get(activeArea));
        rebuild = true;
    }

    BlockPos rotationBasisSize() {
        var layer = layers.find(activeArea);
        areaGizmo.displaySize = FittedPlaneLayers.plane(layer) ? area().size() : null;
        areaGizmo.rigidFrame =
                FittedPlaneLayers.plane(layer)
                        || layer != null && layer.settings().has("rotation_frame");
        areaGizmo.modelCenter = null;
        if (layer != null && layer.settings().has("rotation_frame")) {
            var c = layer.settings().getAsJsonObject("rotation_frame").getAsJsonArray("center");
            areaGizmo.modelCenter =
                    new Vec3(
                            c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble());
        }
        if (FittedPlaneLayers.plane(layer) && !FittedPlaneLayers.locked(layer))
            return FittedPlaneLayers.frame(layer.settings().getAsJsonObject(FittedPlaneLayers.KEY))
                    .size();
        if (layer != null && layer.settings().has("rotation_frame")) {
            var a = layer.settings().getAsJsonObject("rotation_frame").getAsJsonArray("size");
            return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
        }
        return area().size();
    }

    private Runnable pendingDeformationAction;

    void applyDeformationThen(Runnable after) {
        if (shapeJobs.busy()) {
            String selected = activeArea;
            pendingDeformationAction =
                    () -> {
                        if (activeArea.equals(selected)) applyDeformationThen(after);
                    };
            return;
        }
        if (deformation.identity()
                && (layers.find(activeArea) == null
                        || !layers.get(activeArea).settings().has("bounds_scale"))) {
            after.run();
            return;
        }
        transformBaseline(false, after);
    }

    void reverseDeformation() {
        if (shapeJobs.busy()) {
            String selected = activeArea;
            pendingDeformationAction =
                    () -> {
                        if (activeArea.equals(selected)) reverseDeformation();
                    };
            return;
        }
        transformBaseline(true, () -> build());
    }

    private void transformBaseline(boolean inverse, Runnable after) {
        var snapshot = layers;
        var id = activeArea;
        if (snapshot.find(id) == null) return;
        shapeCommitBase = doc();
        rememberArea();
        pendingDeformationAction =
                () -> {
                    if (activeArea.equals(id)) after.run();
                };
        submitArea(
                () -> {
                    var next =
                            inverse
                                    ? AreaDeformationSession.reverse(snapshot, id)
                                    : AreaDeformationSession.apply(snapshot, id);
                    return new AreaResult(next.compose(), next, id);
                });
        host.status(tr("shape_busy").getString());
    }

    boolean continuousSurface = true;
    // Explicit session-local permission for the active area, never part of document settings.
    boolean allowConfiguredLoss;

    void toggleContinuousSurface() {
        if (shapeJobs.busy()) {
            String selected = activeArea;
            pendingDeformationAction =
                    () -> {
                        if (activeArea.equals(selected)) toggleContinuousSurface();
                    };
            return;
        }
        continuousSurface = !continuousSurface;
        updateShape();
        build();
    }

    JsonObject areaOptions() {
        var o = new JsonObject();
        var current = layers.find(activeArea);
        if (FittedPlaneLayers.plane(current))
            o.add(FittedPlaneLayers.KEY, current.settings().get(FittedPlaneLayers.KEY).deepCopy());
        if (current != null && current.settings().has("rotation_frame"))
            o.add("rotation_frame", current.settings().get("rotation_frame").deepCopy());
        o.addProperty("generated_turns", areaTurns);
        o.addProperty("blocks", areaBlocks);
        o.addProperty("entities", areaEntities);
        o.addProperty("zones", areaZones);
        o.addProperty("generated", areaGenerated);
        o.addProperty("shape", areaShape.name());
        o.addProperty("hollow", areaHollow);
        o.addProperty("brush", state);
        o.addProperty("sticky", areaSticky);
        o.addProperty("transparent", areaTransparent);
        o.addProperty("continuous_surface", continuousSurface);
        o.add("deformation", deformation.encode());
        o.addProperty("random", randomBlocks);
        o.addProperty("seed", randomSeed);
        o.addProperty("original_weight", originalBlockWeight);
        var pool = new JsonArray();
        for (int i = 0; i < randomStates.size(); i++) {
            var v = new JsonObject();
            v.addProperty("block", randomStates.get(i));
            v.addProperty("weight", randomWeights.get(i));
            pool.add(v);
        }
        o.add("random_pool", pool);
        return o;
    }

    private void loadArea(BlockAreaLayers.Layer layer) {
        boolean sameArea = areaGizmo.areaId.equals(layer.id());
        if (!sameArea) {
            allowConfiguredLoss = false;
            deforming = false;
        }
        areaGizmo.areaId = layer.id();
        areaGizmo.clear();
        activeArea = layer.id();
        if (FittedPlaneLayers.plane(layer)) movingArea = true;
        areaOrigin = xyz(layer.region().origin());
        areaSize = xyz(layer.region().size());
        var o = layer.settings();
        areaBlocks = !o.has("blocks") || o.get("blocks").getAsBoolean();
        areaEntities = !o.has("entities") || o.get("entities").getAsBoolean();
        areaZones = !o.has("zones") || o.get("zones").getAsBoolean();
        areaGenerated = !o.has("generated") || o.get("generated").getAsBoolean();
        areaHollow = o.has("hollow") && o.get("hollow").getAsBoolean();
        areaSticky = o.has("sticky") && o.get("sticky").getAsBoolean();
        areaTransparent = o.has("transparent") && o.get("transparent").getAsBoolean();
        continuousSurface =
                !o.has("continuous_surface") || o.get("continuous_surface").getAsBoolean();
        areaShape =
                o.has("shape")
                        ? SchematicRegions.Shape.valueOf(o.get("shape").getAsString())
                        : SchematicRegions.Shape.NONE;
        state = o.has("brush") ? o.get("brush").getAsString() : "minecraft:stone";
        areaTurns = o.has("generated_turns") ? o.get("generated_turns").getAsInt() : 0;
        deformation =
                ShapeDeformation.decode(
                        o.has("deformation") ? o.getAsJsonObject("deformation") : null);
        if (!sameArea) {
            areaGizmo.tool = deformation.direct().mode() == DirectDeformation.Mode.NONE ? 0 : 1;
            areaGizmo.directTool = deformation.direct();
        } else if (areaGizmo.tool == 1
                && deformation.direct().mode() == DirectDeformation.Mode.NONE) {
            var selected = areaGizmo.directTool;
            deformation =
                    deformation.direct(
                            selected.amount(DirectDeformation.neutralAmount(selected.mode())));
        }
        if (deforming
                && areaGizmo.tool == 1
                && deformation.direct().mode() == DirectDeformation.Mode.SCALE) movingArea = false;
        if (deformation.direct().mode() != DirectDeformation.Mode.NONE)
            areaGizmo.directTool = deformation.direct();
        randomBlocks = o.has("random") && o.get("random").getAsBoolean();
        randomSeed = o.has("seed") ? o.get("seed").getAsLong() : 1;
        originalBlockWeight = o.has("original_weight") ? o.get("original_weight").getAsDouble() : 1;
        randomStates.clear();
        randomWeights.clear();
        if (o.has("random_pool"))
            for (var item : o.getAsJsonArray("random_pool")) {
                var v = item.getAsJsonObject();
                randomStates.add(v.get("block").getAsString());
                randomWeights.add(v.get("weight").getAsDouble());
            }
    }

    void ensureArea() {
        if (layers.find(activeArea) != null) return;
        var beforeCreation = layers;
        var nextLayers = layers.create(area(), areaContents(), true);
        var nextArea = nextLayers.layers().getLast().id();
        var layer = nextLayers.get(nextArea);
        nextLayers =
                nextLayers.replace(
                        nextArea, layer.region(), layer.content(), layer.mask(), areaOptions());
        nextLayers =
                nextLayers.mirrorCreatedRemoved(
                        beforeCreation, host.effectiveSymmetry(), host.mirrorSpan(), mc.level);
        // A refused native mirror must not leave a partially accepted layer/selection behind.
        layers = nextLayers;
        activeArea = nextArea;
        allowConfiguredLoss = false;
        deforming = false;
    }

    private boolean applyingArea;

    int membershipMode;

    boolean membershipAt(BlockPos pos) {
        if (tool != Tool.AREA || membershipMode == 0 || layers.find(activeArea) == null)
            return false;
        if (pos != null && !shapeJobs.busy())
            run(
                    () -> {
                        var next = layers.membership(activeArea, pos, membershipMode > 0);
                        if (next != layers) {
                            layerCommit(next);
                            loadArea(next.get(activeArea));
                        }
                    });
        return true;
    }

    void layerCommit(BlockAreaLayers.State next) {
        layerCommit(next, null);
    }

    private void layerCommit(BlockAreaLayers.State next, AABB acceptedMassRegion) {
        host.flashSymmetry();
        if (!applyingArea)
            next =
                    next.mirrorCreatedRemoved(
                            layers, host.effectiveSymmetry(), host.mirrorSpan(), mc.level);
        var before = doc();
        rememberSelection();
        if (before.blocks().size() > 65536 || next.hasLivePlanes()) {
            layers = next;
            shapeCommitBase = before;
            var snapshot = next;
            submitArea(
                    () -> new AreaResult(snapshot.compose(), snapshot, null, acceptedMassRegion));
            host.status(tr("shape_busy").getString());
            rebuild = true;
            return;
        }
        var result = next.compose();
        layers = next;
        layerHistory.put(result, next);
        areaSelections.put(result, activeArea);
        applyingShape = true;
        try {
            host.commitGesture(before, result);
            if (acceptedMassRegion != null) massRegion = acceptedMassRegion;
        } finally {
            applyingShape = false;
        }
        lastLayerDocument = doc();
        rememberArea();
        host.metadataChanged();
        rebuild = true;
    }

    void subtractFromBase() {
        if (!flushEdits() || shapeJobs.busy()) return;
        var snapshot = layers;
        var id = activeArea;
        mergeChoosing = false;
        shapeCommitBase = doc();
        submitArea(
                () -> {
                    var next = snapshot.subtractTop(id);
                    return new AreaResult(next.compose(), next, "");
                });
        host.status(tr("shape_busy").getString());
    }

    void chooseArea(String id) {
        if (!flushEdits()) return;
        inspectedArea = "";
        host.setSelectMode();
        if (mergeChoosing && !activeArea.equals(id)) {
            var target = id;
            if (doc().blocks().size() > 65536) {
                var snapshot = layers;
                var source = activeArea;
                boolean subtract = mergeSubtract;
                mergeChoosing = false;
                shapeCommitBase = doc();
                submitArea(
                        () -> {
                            var next = snapshot.merge(source, target, subtract);
                            return new AreaResult(next.compose(), next, target);
                        });
                host.status(tr("shape_busy").getString());
                return;
            }
            var result = layers.merge(activeArea, target, mergeSubtract);
            activeArea = target;
            mergeChoosing = false;
            layerCommit(result);
            loadArea(layers.get(target));
        } else {
            activeArea = id;
            layerCommit(layers.promote(id));
            loadArea(layers.get(id));
        }
        tool = Tool.AREA;
        host.selectRegion(
                layers.find(activeArea).region().origin(), layers.find(activeArea).region().size());
        build();
    }

    boolean layersDirty() {
        return layers != savedLayers;
    }

    boolean layerStateDiffers(SchematicDocument before, SchematicDocument after) {
        var previous = layerHistory.get(before);
        var next = layerHistory.get(after);
        return previous == null ? next != null : !previous.sameContents(next);
    }

    private BlockAreaLayers.State visibleLayers;

    private void updateAreaTransparency() {
        if (layers == visibleLayers) return;
        visibleLayers = layers;
        if (layers.layers().stream().noneMatch(BlockAreaLayers.Layer::transparent)) {
            host.translucent(Set.of());
            return;
        }
        var visible = new HashSet<BlockPos>();
        for (var layer : layers.allLayers()) {
            var mask = layer.mask();
            boolean transparent = layer.transparent() && !layer.applied();
            for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
                var p =
                        layer.region()
                                .origin()
                                .offset(BlockAreaLayers.position(i, layer.region().size()));
                if (transparent
                        && !layer.content()
                                .blockName(p.subtract(layer.region().origin()))
                                .equals("minecraft:air")) visible.add(p);
                else visible.remove(p);
            }
        }
        host.translucent(visible);
    }

    BlockAreaLayers.State layersForSave() {
        layers = layers.synchronize(layers.compose(), doc());
        return layers;
    }

    void layersSaved(BlockAreaLayers.State value) {
        savedLayers = value;
    }

    void applyArea() {
        if (!flushEdits() || layers.find(activeArea) == null) return;
        var id = activeArea;
        activeArea = "";
        applyingArea = true;
        try {
            layerCommit(layers.apply(id));
        } finally {
            applyingArea = false;
        }
        host.clearRegion();
        build();
    }

    void removeArea() {
        if (!flushEdits() || layers.find(activeArea) == null) return;
        var id = activeArea;
        activeArea = "";
        layerCommit(layers.remove(id));
        host.clearRegion();
        build();
    }

    void areaIcons(
            String[] names, Runnable[] actions, java.util.function.BooleanSupplier[] states) {
        int firstCell = widgets.size();
        var flow = new Flow(List.of(), EditorLayout.ROW_PITCH, names.length == 8);
        int columns = flow.columns(w), start = cursor;
        int step = flow.step(w);
        for (int i = 0; i < names.length; i++) {
            var b =
                    icon(
                            names[i],
                            x + (i % columns) * step,
                            start + (i / columns) * EditorLayout.ROW_PITCH,
                            actions[i]);
            b.setWidth(Math.min(20, step - 1));
            if (states[i] != null) b.selected(states[i]);
            if (names[i].equals("area_subtract")) b.hint(tr("area_subtract_hint"));
            if (names[i].equals("area_subtract_base")) b.hint(tr("area_subtract_base_hint"));
        }
        flows.add(
                new Flow(
                        java.util.List.copyOf(widgets.subList(firstCell, widgets.size())),
                        EditorLayout.ROW_PITCH,
                        names.length == 8));
        cursor = start + ((names.length + columns - 1) / columns) * EditorLayout.ROW_PITCH;
    }

    void areaAction(String name, Runnable action) {
        areaIcons(
                new String[] {name},
                new Runnable[] {action},
                new java.util.function.BooleanSupplier[] {null});
    }

    /** Mixed text/icon actions reuse the native icon dispatch, hints and retained Flow. */
    void areaControls(
            String[] names,
            String[] captions,
            Runnable[] actions,
            java.util.function.BooleanSupplier[] states) {
        var cells = new ArrayList<AbstractWidget>();
        var widths = new ArrayList<Integer>();
        for (int i = 0; i < names.length; i++) {
            var button = icon(names[i], x, cursor, actions[i]);
            int width = EditorLayout.CONTROL_HEIGHT;
            if (captions[i] != null) {
                var label = tr(captions[i]);
                width = Math.max(EditorLayout.CONTROL_HEIGHT, mc.font.width(label) + 8);
                button.setMessage(label);
                button.textButton(width);
                var hint =
                        tr(
                                net.minecraft.client.resources.language.I18n.exists(
                                                "npships.tools." + names[i] + "_hint")
                                        ? names[i] + "_hint"
                                        : names[i]);
                button.hint(hint);
            }
            if (states[i] != null) button.selected(states[i]);
            cells.add(button);
            widths.add(width);
        }
        var flow =
                new Flow(
                        List.copyOf(cells),
                        EditorLayout.ROW_PITCH,
                        false,
                        List.copyOf(widths),
                        true);
        flow.place(x, cursor, w);
        flows.add(flow);
        cursor += flow.height(w);
    }

    void randomBlockTools() {
        areaToolControls.randomBlockTools();
    }

    private void readCorners() {
        var a = host.firstCorner();
        var b = host.lastCorner();
        if (a == null || b == null) return;
        areaOrigin =
                xyz(
                        new BlockPos(
                                Math.min(a.getX(), b.getX()),
                                Math.min(a.getY(), b.getY()),
                                Math.min(a.getZ(), b.getZ())));
        areaSize =
                xyz(
                        new BlockPos(
                                Math.abs(a.getX() - b.getX()) + 1,
                                Math.abs(a.getY() - b.getY()) + 1,
                                Math.abs(a.getZ() - b.getZ()) + 1));
    }

    SchematicRegions.Region area() {
        var r =
                new SchematicRegions.Region(
                        BlockEditorScreen.position(areaOrigin),
                        BlockEditorScreen.position(areaSize));
        r.validate(doc());
        return r;
    }

    private SchematicRegions.Contents areaContents() {
        return new SchematicRegions.Contents(areaBlocks, areaEntities, areaZones && !host.isPart());
    }

    private void areaTools() {
        areaToolControls.areaTools();
    }

    void attachArea(BlockPos origin, BlockPos size) {
        activeArea = "";
        tool = Tool.AREA;
        areaOrigin = xyz(origin);
        areaSize = xyz(size);
        host.selectRegion(origin, size);
        rebindArea();
        layerCommit(layers);
        rememberArea();
        build();
    }

    private void rebindArea() {
        shapeBase = null;
        if (host.firstCorner() != null && !host.selectingRegion()) ensureArea();
    }

    void turnArea(boolean right) {
        if (!flushEdits()) return;
        ensureArea();
        if (layers.get(activeArea).settings().has("bounds_scale")) {
            applyDeformationThen(() -> turnArea(right));
            return;
        }
        var values = deformation.parameters();
        values[1] += right ? 90 : -90;
        deformation = deformation.parameters(values);
        updateShape();
        build();
    }

    private void copyArea(boolean cut) {
        if (!flushEdits()) return;
        ensureArea();
        var layer = layers.get(activeArea);
        areaClipboard = layer.content();
        clipboardMask = layer.mask();
        clipboardRegion = new SchematicRegions.Region(BlockPos.ZERO, layer.region().size());
        clipboardContents = areaContents();
        if (cut) deleteArea();
        host.status(tr(cut ? "area_cut_done" : "area_copy_done").getString());
        build();
    }

    void deleteArea() {
        if (!flushEdits()) return;
        if (layers.find(activeArea) != null) {
            var l = layers.find(activeArea);
            areaGenerated = false;
            randomBlocks = false;
            deformation = ShapeDeformation.IDENTITY;
            var options = areaOptions();
            // Clearing a live plane must also stop its generator, otherwise refresh restores it.
            options.remove(FittedPlaneLayers.KEY);
            options.remove("plane_error");
            options.remove("rotation_frame");
            layerCommit(layers.clearContents(activeArea, options));
            build();
        } else removeArea();
    }

    void pasteArea(boolean move) {
        if (!flushEdits()) return;
        if (!move && areaClipboard == null) return;
        if (move) {
            area();
            movingArea = !movingArea;
            host.status(tr("area_move_drag").getString());
            build();
            return;
        }
        beginPaste(areaClipboard, clipboardMask);
    }

    void beginPaste(SchematicDocument document, BitSet mask) {
        var snapshot = BlockAreaLayers.duplicate(document);
        var sourceMask = mask == null ? BlockAreaLayers.mask(snapshot) : (BitSet) mask.clone();
        cancelPaste();
        pasteDocument = snapshot;
        pasteMask = sourceMask;
        pasteRenderer =
                new com.eynny.npships.client.editor.render.SchematicPreviewRenderer(
                        new SchematicPreviewLevel(snapshot, mc.level));
        pasteRenderer.translucent(snapshot.blocks().keySet());
        pasteEntities = new EntityPreview();
        pasteEntities.refresh(snapshot, mc.level);
        tool = Tool.AREA;
        fittedPlane = null;
        rebuild = true;
        host.setSelectMode();
    }

    private SchematicDocument pasteDocument;
    private BitSet pasteMask;
    private BlockPos pastePosition;
    private com.eynny.npships.client.editor.render.SchematicPreviewRenderer pasteRenderer;
    private EntityPreview pasteEntities;

    boolean pasting() {
        return pasteDocument != null;
    }

    boolean cancelPaste() {
        boolean had = pasting();
        if (pasteRenderer != null) pasteRenderer.close();
        pasteRenderer = null;
        pasteDocument = null;
        pasteMask = null;
        pastePosition = null;
        if (pasteEntities != null) pasteEntities.close();
        pasteEntities = null;
        return had;
    }

    java.util.List<net.minecraft.network.chat.Component> pasteEntityDiagnostics() {
        return pasteEntities == null ? java.util.List.of() : pasteEntities.diagnostics.lines();
    }

    void renderPaste(GuiGraphics g, PoseStack pose, BlockPos point) {
        if (!pasting()) return;
        pastePosition = point;
        if (point == null) return;
        pasteEntities.refresh(pasteDocument, mc.level);
        var region = new SchematicRegions.Region(point, pasteDocument.size());
        boolean valid = host.checkBounds(region.bounds());
        pasteRenderer.buildNextBatch();
        pose.pushPose();
        try {
            pose.translate(point.getX(), point.getY(), point.getZ());
            pasteRenderer.render(pose, g.bufferSource());
            for (var e : pasteEntities.entities) {
                if (pasteEntities.failedEntities.contains(e)) continue;
                pasteEntities.render(e, pose, g.bufferSource(), Vec3.ZERO, true);
            }
            g.bufferSource().endBatch();
        } finally {
            pose.popPose();
        }
        SceneOutlines.box(g, pose, region.bounds(), valid ? 0xffdf40 : 0xff3030, false);
    }

    void acceptPaste() {
        if (!pasting() || pastePosition == null) return;
        var region = new SchematicRegions.Region(pastePosition, pasteDocument.size());
        if (!host.checkBounds(region.bounds())) return;
        var next = layers.create(region, areaContents(), false);
        activeArea = next.layers().getLast().id();
        var settings = new JsonObject();
        settings.addProperty("generated", false);
        settings.addProperty("sticky", false);
        next =
                next.withSource(activeArea, pasteDocument, pasteMask)
                        .replace(activeArea, region, pasteDocument, pasteMask, settings);
        layerCommit(next);
        loadArea(layers.get(activeArea));
        tool = Tool.AREA;
        movingArea = true;
        host.selectRegion(pastePosition, pasteDocument.size());
        cancelPaste();
        build();
    }

    private void blocks() {
        blockToolControls.blocks();
    }

    void placementModes() {
        blockToolControls.placementModes();
    }

    net.minecraft.world.level.block.state.BlockState parse(String text) {
        try {
            var reader = new com.mojang.brigadier.StringReader(text.trim());
            var result =
                    BlockStateParser.parseForBlock(
                            mc.level.holderLookup(Registries.BLOCK), reader, false);
            if (reader.canRead())
                throw invalid("block_trailing", "Unexpected text after block state.", text);
            return text.indexOf('[') < 0
                    ? SchematicPlacement.defaultState(result.blockState().getBlock(), mc.level)
                    : result.blockState();
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private boolean retainPlacementTarget;

    private void place(boolean delete) {
        if (pos() == null) throw new IllegalArgumentException(tr("select_hint").getString());
        var block = delete ? Blocks.AIR.defaultBlockState() : parse(state);
        if (!delete && doc().blocks().containsKey(pos())) {
            var source = doc();
            var target = pos();
            var existing =
                    NbtUtils.readBlockState(
                            mc.level.holderLookup(Registries.BLOCK),
                            source.paletteCopy().getCompound(source.blocks().get(target)));
            var proposal = blockPlacements.combine(existing, block);
            if (proposal != null) {
                int inventorySlot = blockPlacements.inventorySelection(proposal);
                retainPlacementTarget = inventorySlot >= 0;
                var data =
                        blockPlacements.data(
                                proposal,
                                existing,
                                block,
                                source.blockEntityData(target),
                                mc.level.registryAccess());
                var candidate =
                        SchematicBlocks.combine(
                                proposal.provider(),
                                source,
                                target,
                                existing,
                                proposal.state(),
                                data);
                if (inventorySlot >= 0
                        && inventorySlot
                                >= SchematicResources.open(candidate, target, mc.level).size())
                    throw new IllegalArgumentException(
                            "Placement requested an unavailable inventory slot");
                if (doc() != source || !target.equals(pos()))
                    throw new IllegalArgumentException(
                            "Placement source changed during provider callback");
                commit(candidate);
                if (inventorySlot >= 0) {
                    resourceOwner = target;
                    resourceSlot = inventorySlot;
                    fluidsMode = false;
                }
                remember(recent, state);
                return;
            }
        }
        var next = SchematicPlacement.place(doc(), pos(), block, mc.level);
        if (!delete && blockBrushData != null && block.hasBlockEntity())
            next = SchematicEdits.blockData(next, pos(), blockBrushData);
        if (!delete && !blockBrushPatches.isEmpty()) {
            var config = next.configCopy();
            if (config == null) config = new JsonObject();
            var entries = new ArrayList<JsonObject>();
            for (var patch : SchematicPatches.read(config))
                if (!SchematicPatches.position(patch.getAsJsonArray("pos")).equals(pos()))
                    entries.add(patch);
            var remap = new com.eynny.npships.authoring.patches.PatchInstances.Remap();
            for (var patch : blockBrushPatches)
                entries.add(PatchDevices.copy(doc(), patch, pos(), remap));
            SchematicPatches.set(config, entries);
            next = next.withConfig(config);
        }
        commit(next);
        if (delete) host.select(null);
        if (!delete) {
            remember(recent, state);
        }
    }

    void replaceBlock(BlockPos p) {
        placeBlockAt(p, false);
    }

    boolean canCombineBlock(net.minecraft.world.level.block.state.BlockState existing) {
        if (!blocksTool()) return false;
        try {
            return blockPlacements.canCombine(existing, parse(state));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    void placeBlockAt(BlockPos p, boolean delete) {
        run(
                () -> {
                    host.select(null);
                    entityIndex = -1;
                    if (p == null) return;
                    host.select(p);
                    retainPlacementTarget = false;
                    try {
                        place(delete);
                    } finally {
                        if (!retainPlacementTarget) host.select(null);
                    }
                });
        rebuild = true;
    }

    boolean repeatTool() {
        return !massRegionEditing && (blocksTool() || tool == Tool.ENTITIES);
    }

    void rotateBrush(int direction) {
        if (direction == 0) return;
        if (entityTool() && tool != Tool.GLUE) {
            run(
                    () -> {
                        if (!flushEdits()) return;
                        var data =
                                entityIndex >= 0
                                        ? doc().entitiesCopy()
                                                .getCompound(entityIndex)
                                                .getCompound("nbt")
                                        : entityPlacementData();
                        var turned =
                                com.eynny.npships.authoring.document.EntitySettings.turn(
                                        data, mc.level, direction);
                        if (entityIndex >= 0) {
                            var entry = doc().entitiesCopy().getCompound(entityIndex);
                            var at = entry.getList("pos", Tag.TAG_DOUBLE);
                            commit(
                                    SchematicEdits.entity(
                                            doc(),
                                            entityIndex,
                                            new Vec3(
                                                    at.getDouble(0),
                                                    at.getDouble(1),
                                                    at.getDouble(2)),
                                            turned));
                        } else entityBrush = turned;
                        ghostEntity = null;
                        rebuild = true;
                    });
            return;
        }
        run(
                () -> {
                    state =
                            BlockStateParser.serialize(
                                    SchematicRotations.cycle(parse(state), direction, mc.level));
                    ghostStateText = null;
                    rebuild = true;
                });
    }

    boolean attachmentRotationAvailable() {
        return entityTool()
                && tool != Tool.GLUE
                && com.eynny.npships.authoring.document.EntitySettings.attachmentRotation(
                        entityIndex >= 0
                                ? doc().entitiesCopy()
                                        .getCompound(entityIndex)
                                        .getCompound("nbt")
                                        .getString("id")
                                : entityType);
    }

    void clickedBlock() {
        entityIndex = -1;
        if (tool == Tool.BLOCKS && !mode().equals("select"))
            run(() -> place(mode().equals("delete")));
        if (entityTool() && mode().equals("place")) run(this::addEntity);
    }

    boolean entityTool() {
        return !massRegionEditing && (tool == Tool.ENTITIES || tool == Tool.GLUE);
    }

    boolean filteredBlockTool() {
        // Welding needs the first real block on the ray, including refused targets/occluders.
        // Do not walk every schematic position through the patch-device picking filter.
        return tool == Tool.PATCHES && !patchRegionTool.choosing() || tool == Tool.LOOT;
    }

    java.util.Collection<BlockPos> pickableBlocks() {
        if (tool == Tool.PATCHES) {
            if (linkRole != null) return linkTargets;
            cachePatchHighlights();
            return patchHighlights.keySet();
        }
        return host.containers();
    }

    boolean acceptsBlock(BlockPos point) {
        if (point == null) return false;
        if (tool == Tool.PATCHES) {
            if (patchRegionTool.choosing()) return true;
            if (linkRole != null) return true;
            cachePatchHighlights();
            return patchHighlights.containsKey(point);
        }
        return tool != Tool.LOOT || host.containers().contains(point);
    }

    boolean blockPlacement() {
        return blocksTool() && mode().equals("place");
    }

    boolean entityPlacement() {
        return tool != Tool.GLUE && entityTool() && mode().equals("place");
    }

    void placeEntityAt(BlockPos point) {
        host.select(point);
        run(this::addEntity);
        host.select(null);
    }

    void hoverEntity(double x, double y, Matrix4f inverse, float depth) {
        hoveredEntity = entityTool() ? entityAt(x, y, inverse, depth) : -1;
    }

    final PreviewRendererScope ghostRenderScope = new PreviewRendererScope();
    final com.eynny.npships.client.editor.render.PreviewCameraContext ghostCameraContext =
            new com.eynny.npships.client.editor.render.PreviewCameraContext();
    private long previewResourceEpoch =
            com.eynny.npships.client.editor.render.PreviewResources.epoch();

    void refreshPreviewResources() {
        long next = com.eynny.npships.client.editor.render.PreviewResources.epoch();
        if (next == previewResourceEpoch) return;
        previewResourceEpoch = next;
        ghostRenderScope.close();
        ghostCameraContext.release();
        ghostEntityKey = null;
        ghostEntity = null;
        ghostStateText = null;
        ghostLevel = null;
        deviceSource = null;
        patchLinks.resourcesChanged();
    }

    private Entity ghostEntity;
    private String ghostEntityKey;
    private CompoundTag ghostEntityBrush;

    void renderEntityGhost(GuiGraphics g, PoseStack pose, BlockPos target) {
        refreshPreviewResources();
        if (!entityPlacement() || target == null) return;
        String key = entityType;
        if (!key.equals(ghostEntityKey) || ghostEntityBrush != entityBrush) {
            ghostEntityKey = key;
            ghostEntityBrush = entityBrush;
            ghostEntity = null;
            try {
                var type =
                        BuiltInRegistries.ENTITY_TYPE.getOptional(
                                ResourceLocation.parse(entityType));
                if (type.isPresent()) {
                    ghostEntity = type.get().create(new SchematicPreviewLevel(doc(), mc.level));
                    if (ghostEntity != null && entityBrush != null) {
                        com.eynny.npships.authoring.document.PreviewEntityData.loadAt(
                                ghostEntity, entityBrush, target);
                    }
                }
            } catch (Exception e) {
                if (!(e instanceof EditorValidationFeedback.Failure))
                    host.error(EditorErrorText.message(e));
            }
        }
        if (ghostEntity == null) return;
        var e = ghostEntity;
        if (e.level() instanceof SchematicPreviewLevel virtual) virtual.refresh(doc());
        e.setPos(target.getX() + .5, target.getY(), target.getZ() + .5);
        e.xo = e.getX();
        e.yo = e.getY();
        e.zo = e.getZ();
        e.yRotO = e.getYRot();
        e.xRotO = e.getXRot();
        EntityHeadPose.apply(e);
        g.bufferSource().endBatch();
        try (var context = ghostCameraContext.open(e.level(), pose)) {
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
                    1,
                    host.containsDraft(target) ? 1 : .15f,
                    host.containsDraft(target) ? 1 : .15f,
                    .55f);
            ghostRenderScope.render(
                    pose,
                    g.bufferSource(),
                    (detached, isolated) ->
                            mc.getEntityRenderDispatcher()
                                    .render(
                                            e,
                                            e.getX(),
                                            e.getY(),
                                            e.getZ(),
                                            e.getYRot(),
                                            0,
                                            detached,
                                            type -> isolated.getBuffer(GhostRenderType.of(type)),
                                            0xf000f0));
        } catch (Exception ex) {
            String failure =
                    ghostRenderScope.failure(
                            "placement entity",
                            BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
                            target,
                            ex);
            ghostEntity = null;
            host.error(failure);
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1, 1, 1, 1);
        }
        SceneOutlines.box(
                g,
                pose,
                e.getBoundingBox(),
                host.containsDraft(target) ? 0xffdf40 : 0xff3030,
                false);
    }

    void renderGhost(GuiGraphics g, PoseStack pose, BlockPos target) {
        refreshPreviewResources();
        if (!blockPlacement() || target == null) return;
        if (!state.equals(ghostStateText)
                || !java.util.Objects.equals(blockBrushData, ghostBrushData)) {
            ghostStateText = state;
            ghostBrushData = blockBrushData == null ? null : blockBrushData.copy();
            try {
                ghostState = parse(state);
                var empty = AuthoringCreation.empty(new BlockPos(1, 1, 1), ExportProfile.EMPTY);
                var preview =
                        SchematicBlocks.put(empty, BlockPos.ZERO, BlockPos.ZERO, ghostState, null);
                if (ghostState.hasBlockEntity() && blockBrushData != null)
                    preview = SchematicEdits.blockData(preview, BlockPos.ZERO, blockBrushData);
                ghostLevel = new SchematicPreviewLevel(preview, mc.level);
            } catch (Exception ignored) {
                ghostState = null;
                ghostLevel = null;
            }
        }
        if (ghostState == null || ghostState.isAir()) return;
        g.bufferSource().endBatch();
        // The ghost level's block is local zero, while its render origin is the chosen target.
        var cameraPose = new PoseStack();
        cameraPose.mulPose(pose.last().pose());
        cameraPose.translate(target.getX(), target.getY(), target.getZ());
        try (var context = ghostCameraContext.open(ghostLevel, cameraPose)) {
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
                    1,
                    host.containsDraft(target) ? 1 : .15f,
                    host.containsDraft(target) ? 1 : .15f,
                    .55f);
            ghostRenderScope.render(
                    pose,
                    g.bufferSource(),
                    (detached, isolated) -> {
                        detached.translate(target.getX(), target.getY(), target.getZ());
                        net.minecraft.client.renderer.MultiBufferSource buffers =
                                type -> isolated.getBuffer(GhostRenderType.of(type));
                        var be = ghostLevel.getBlockEntity(BlockPos.ZERO);
                        if (ghostState.getRenderShape()
                                        != net.minecraft.world.level.block.RenderShape
                                                .ENTITYBLOCK_ANIMATED
                                || be == null)
                            mc.getBlockRenderer()
                                    .renderSingleBlock(
                                            ghostState,
                                            detached,
                                            buffers,
                                            0xf000f0,
                                            net.minecraft.client.renderer.texture.OverlayTexture
                                                    .NO_OVERLAY,
                                            mc.getBlockRenderer()
                                                    .getBlockModel(ghostState)
                                                    .getModelData(
                                                            ghostLevel,
                                                            BlockPos.ZERO,
                                                            ghostState,
                                                            ghostLevel.getModelData(BlockPos.ZERO)),
                                            null);
                        if (be != null)
                            mc.getBlockEntityRenderDispatcher()
                                    .renderItem(
                                            be,
                                            detached,
                                            buffers,
                                            0xf000f0,
                                            net.minecraft.client.renderer.texture.OverlayTexture
                                                    .NO_OVERLAY);
                    });
        } catch (Exception ex) {
            String failure =
                    ghostRenderScope.failure(
                            "placement block/BER",
                            BuiltInRegistries.BLOCK.getKey(ghostState.getBlock()),
                            target,
                            ex);
            ghostState = null;
            host.error(failure);
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1, 1, 1, 1);
        }
        SceneOutlines.box(
                g, pose, new AABB(target), host.containsDraft(target) ? 0xffdf40 : 0xff3030, false);
    }

    boolean wantsAdjacent() {
        return !massRegionEditing
                && !(tool == Tool.BLOCKS && host.deleting())
                && mode().equals("place")
                && (tool == Tool.BLOCKS || tool == Tool.ENTITIES);
    }

    void registry(boolean types) {
        blockToolControls.registry(types);
    }

    boolean searchTypes;
    int searchStart = -1, searchY, searchEnd = -1, registryMax, lootMax;

    void updateSearch(boolean types) {
        blockToolControls.updateSearch(types);
    }

    void appendRegistry(boolean types) {
        blockToolControls.appendRegistry(types);
    }

    int quickColumns() {
        return Math.max(1, EditorLayout.listWidth(w) / 18);
    }

    void trimQuickRow(List<String> list) {
        if (list == pinnedBlocks || list == pinnedEntities) return;
        while (list.size() > quickColumns()) list.removeLast();
    }

    private static boolean pinsLoaded;

    private void loadPins() {
        if (pinsLoaded || !com.eynny.npships.config.NPShipsEditorConfig.SPEC.isLoaded()) return;
        pinsLoaded = true;
        try {
            var o =
                    com.google.gson.JsonParser.parseString(
                                    com.eynny.npships.config.NPShipsEditorConfig.PINS.get())
                            .getAsJsonObject();
            if (o.has("blocks"))
                for (var v : o.getAsJsonArray("blocks")) pinnedBlocks.add(v.getAsString());
            if (o.has("entities"))
                for (var v : o.getAsJsonArray("entities")) pinnedEntities.add(v.getAsString());
        } catch (RuntimeException ignored) {
        }
    }

    private void savePins() {
        if (!com.eynny.npships.config.NPShipsEditorConfig.SPEC.isLoaded()) return;
        var o = new JsonObject();
        var blocks = new JsonArray();
        pinnedBlocks.forEach(blocks::add);
        var entities = new JsonArray();
        pinnedEntities.forEach(entities::add);
        o.add("blocks", blocks);
        o.add("entities", entities);
        com.eynny.npships.config.NPShipsEditorConfig.withNativeOwner(
                () -> {
                    com.eynny.npships.config.NPShipsEditorConfig.PINS.set(o.toString());
                    com.eynny.npships.config.NPShipsEditorConfig.SPEC.save();
                });
    }

    private String quickIdentity(String value, boolean blocks) {
        var entry = blocks ? EditorBlockCatalog.find(value, mc.level, blockPresentations) : null;
        return entry != null ? entry.id() : value.split("\\[", 2)[0];
    }

    private void remember(List<String> list, String value) {
        String key = quickIdentity(value, list == recent);
        list.removeIf(v -> quickIdentity(v, list == recent).equals(key));
        list.addFirst(value);
        trimQuickRow(list);
    }

    private void chooseBrush(boolean types, String value) {
        run(
                () -> {
                    if (!host.creating()) host.setCreateMode();
                    if (types
                            ? java.util.Objects.equals(entityType, value)
                            : java.util.Objects.equals(state, value)) {
                        rebuild = true;
                        return;
                    }
                    if (types) {
                        entityType = value;
                        entityBrush = null;
                        entityBrushPatches = List.of();
                    } else {
                        state = value;
                        blockBrushData = null;
                        blockBrushPatches = List.of();
                        ghostStateText = null;
                        if (tool == Tool.AREA
                                && (areaGenerated
                                        || FittedPlaneLayers.plane(layers.find(activeArea))))
                            updateShape();
                    }
                    rebuild = true;
                });
    }

    Button catalogCell(boolean types, String value, int bx, int by, boolean pinned) {
        var id = ResourceLocation.parse(types ? value : value.split("\\[", 2)[0]);
        ItemStack icon;
        if (types) {
            var egg = SpawnEggItem.byId(BuiltInRegistries.ENTITY_TYPE.get(id));
            icon =
                    egg == null
                            ? BuiltInRegistries.ITEM
                                    .getOptional(id)
                                    .map(ItemStack::new)
                                    .orElse(ItemStack.EMPTY)
                            : new ItemStack(egg);
        } else icon = new ItemStack(BuiltInRegistries.BLOCK.get(id));
        var entry = types ? null : EditorBlockCatalog.find(value, mc.level, blockPresentations);
        if (entry != null && !entry.icon().isEmpty()) icon = entry.icon();
        final ItemStack drawnIcon = icon;
        var name =
                types
                        ? BuiltInRegistries.ENTITY_TYPE.get(id).getDescription()
                        : !drawnIcon.isEmpty()
                                ? drawnIcon.getHoverName()
                                : BuiltInRegistries.BLOCK.get(id).getName();
        var cell =
                new Button(
                        bx,
                        by,
                        18,
                        18,
                        Component.empty(),
                        b -> chooseBrush(types, value),
                        supplier -> supplier.get()) {
                    @Override
                    public boolean mouseClicked(double mx, double my, int button) {
                        if (button == 1 && isMouseOver(mx, my)) {
                            var list = types ? pinnedEntities : pinnedBlocks;
                            if (pinned) list.remove(value);
                            else if (!list.contains(value)) list.add(value);
                            savePins();
                            rebuild = true;
                            return true;
                        }
                        return super.mouseClicked(mx, my, button);
                    }

                    @Override
                    protected void renderWidget(GuiGraphics g, int mx, int my, float t) {
                        EditorButton.cell(
                                g,
                                getX(),
                                getY(),
                                isHovered(),
                                value.equals(types ? entityType : state));
                        if (!drawnIcon.isEmpty()) g.renderItem(drawnIcon, getX() + 1, getY() + 1);
                        else
                            g.drawString(
                                    mc.font,
                                    name.getString()
                                            .substring(0, Math.min(2, name.getString().length())),
                                    getX() + 2,
                                    getY() + 5,
                                    EditorButtonTheme.text());
                    }
                };
        cell.setTooltip(
                Tooltip.create(
                        Component.literal(name.getString() + "\n" + value)
                                .append("\n")
                                .append(tr(pinned ? "unpin_hint" : "pin_hint"))));
        return cell;
    }

    void quickRows(boolean types) {
        blockToolControls.quickRows(types);
    }

    String plainName(String value) {
        try {
            return Component.Serializer.fromJson(value, mc.level.registryAccess()).getString();
        } catch (Exception e) {
            return value;
        }
    }

    void blockData() {
        blockToolControls.blockData();
    }

    boolean nbtOpen, entityNbtOpen;

    private void entityTools() {
        entityToolControls.entityTools();
    }

    void entitySpecializedControls(CompoundTag original) {
        var entityKey = ResourceLocation.tryParse(original.getString("id"));
        var type =
                entityKey == null
                        ? null
                        : BuiltInRegistries.ENTITY_TYPE.getOptional(entityKey).orElse(null);
        var example = type == null ? null : type.create(mc.level);
        if (example instanceof net.minecraft.world.entity.LivingEntity
                || original.contains("HandItems")
                || original.contains("ArmorItems")) {
            entityEquipmentControls(original);
        }
        if (original.getString("id").equals("minecraft:villager")) {
            sections.begin("trades", "trades", false);
            {
                var offers = original.getCompound("Offers").getList("Recipes", Tag.TAG_COMPOUND);
                for (int i = 0; i < offers.size(); i++) {
                    int index = i;
                    var offer = offers.getCompound(i);
                    button(
                            "- "
                                    + offer.getCompound("buy").getString("id")
                                    + " -> "
                                    + offer.getCompound("sell").getString("id"),
                            () -> {
                                var data =
                                        doc().entitiesCopy()
                                                .getCompound(entityIndex)
                                                .getCompound("nbt")
                                                .copy();
                                data.getCompound("Offers")
                                        .getList("Recipes", Tag.TAG_COMPOUND)
                                        .remove(
                                                currentRow(
                                                        data.getCompound("Offers")
                                                                .getList(
                                                                        "Recipes",
                                                                        Tag.TAG_COMPOUND),
                                                        offer,
                                                        index,
                                                        offers));
                                replaceEntityData(data);
                            });
                }
                String[] buy = {"minecraft:emerald"},
                        sell = {"minecraft:bread"},
                        bc = {"1"},
                        sc = {"1"},
                        uses = {"12"};
                field("trade_buy", buy[0], v -> buy[0] = v);
                field("count", bc[0], v -> bc[0] = v);
                field("trade_sell", sell[0], v -> sell[0] = v);
                field("count", sc[0], v -> sc[0] = v);
                field("trade_uses", uses[0], v -> uses[0] = v);
                action(
                        "add_trade",
                        () -> {
                            var buyId = ResourceLocation.parse(buy[0]);
                            var sellId = ResourceLocation.parse(sell[0]);
                            if (!BuiltInRegistries.ITEM.containsKey(buyId)
                                    || !BuiltInRegistries.ITEM.containsKey(sellId))
                                throw invalid("trade_items", "Unknown trade item", buy[0], sell[0]);
                            int buyCount = Integer.parseInt(bc[0]),
                                    sellCount = Integer.parseInt(sc[0]),
                                    maxUses = Integer.parseInt(uses[0]);
                            if (buyCount < 1
                                    || buyCount > 64
                                    || sellCount < 1
                                    || sellCount > 64
                                    || maxUses < 1)
                                throw invalid(
                                        "trade_counts",
                                        "Invalid trade counts",
                                        buyCount,
                                        sellCount,
                                        maxUses);
                            var data =
                                    doc().entitiesCopy()
                                            .getCompound(entityIndex)
                                            .getCompound("nbt")
                                            .copy();
                            var root = data.getCompound("Offers");
                            var list = root.getList("Recipes", Tag.TAG_COMPOUND);
                            var entry = new CompoundTag();
                            var cost = new CompoundTag();
                            cost.putString("id", buyId.toString());
                            cost.putInt("count", buyCount);
                            entry.put("buy", cost);
                            entry.put(
                                    "sell",
                                    new ItemStack(BuiltInRegistries.ITEM.get(sellId), sellCount)
                                            .save(mc.level.registryAccess()));
                            entry.putInt("uses", 0);
                            entry.putInt("maxUses", maxUses);
                            entry.putInt("xp", 1);
                            entry.putFloat("priceMultiplier", .05f);
                            entry.putBoolean("rewardExp", true);
                            list.add(entry);
                            root.put("Recipes", list);
                            data.put("Offers", root);
                            replaceEntityData(data);
                        });
            }
            sections.end();
        }
    }

    private int equipmentEntity = -1, equipmentSlot;

    private ItemStack entityEquipment(SchematicDocument source, int entity, int slot) {
        var data = source.entitiesCopy().getCompound(entity).getCompound("nbt");
        var saved =
                data.getList(slot < 2 ? "HandItems" : "ArmorItems", Tag.TAG_COMPOUND)
                        .getCompound(slot < 2 ? slot : slot - 2);
        try {
            return ItemStack.CODEC
                    .parse(
                            mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                            saved)
                    .result()
                    .orElse(ItemStack.EMPTY);
        } catch (RuntimeException unavailableComponent) {
            // A read-only preview must not prevent editing retained native SNBT from an absent mod.
            return ItemStack.EMPTY;
        }
    }

    private SchematicDocument entityEquipment(
            SchematicDocument source, int entity, int slot, ItemStack item) {
        var entry = source.entitiesCopy().getCompound(entity);
        var data = entry.getCompound("nbt").copy();
        var key = slot < 2 ? "HandItems" : "ArmorItems";
        var items = data.getList(key, Tag.TAG_COMPOUND);
        while (items.size() < (slot < 2 ? 2 : 4)) items.add(new CompoundTag());
        items.set(slot < 2 ? slot : slot - 2, item.saveOptional(mc.level.registryAccess()));
        data.put(key, items);
        var at = entry.getList("pos", Tag.TAG_DOUBLE);
        return SchematicEdits.entity(
                source, entity, new Vec3(at.getDouble(0), at.getDouble(1), at.getDouble(2)), data);
    }

    private void entityEquipmentControls(CompoundTag original) {
        if (equipmentEntity != entityIndex) {
            equipmentEntity = entityIndex;
            equipmentSlot = 0;
        }
        for (String group : List.of("HandItems", "ArmorItems"))
            for (int slot = 0; slot < (group.equals("HandItems") ? 2 : 4); slot++) {
                parameters.handled(group + "/" + slot + "/id");
                parameters.handled(group + "/" + slot + "/count");
            }
        sections.begin("equipment", "equipment", hasEquipment(original));
        final int owner = entityIndex, selected = equipmentSlot;
        var names = com.eynny.npships.authoring.CrewEquipment.SLOTS;
        for (int slot = 0; slot < names.size(); slot++) {
            final int target = slot;
            var cell =
                    itemCell(
                            entityEquipment(doc(), owner, slot),
                            x + slot * 18,
                            cursor,
                            () -> {
                                equipmentSlot = target;
                                build();
                            },
                            () -> {
                                commit(entityEquipment(doc(), owner, target, ItemStack.EMPTY));
                                entityIndex = owner;
                            },
                            () -> equipmentSlot == target);
            cell.setTooltip(
                    Tooltip.create(
                            Component.translatable("npships.crew.slot." + names.get(slot))
                                    .append("\n")
                                    .append(tr("equipment_slot_hint"))));
            widgets.add(cell);
        }
        cursor += 24;
        wrappedNote(Component.translatable("npships.crew.slot." + names.get(selected)));
        var initial = entityEquipment(doc(), owner, selected);
        String[] item = {
            initial.isEmpty()
                    ? "minecraft:stone"
                    : BuiltInRegistries.ITEM.getKey(initial.getItem()).toString()
        };
        String[] count = {"" + (initial.isEmpty() ? 1 : initial.getCount())};
        var itemField = field("item", item[0], value -> item[0] = value);
        var countField = field("count", count[0], value -> count[0] = value);
        action(
                "item_apply",
                () -> {
                    var current = entityEquipment(doc(), owner, selected);
                    var data = (CompoundTag) current.saveOptional(mc.level.registryAccess());
                    data.putString("id", item[0].trim());
                    validateField(
                            countField, () -> data.putInt("count", Integer.parseInt(count[0])));
                    validateField(
                            itemField,
                            () -> {
                                var parsed =
                                        com.eynny.npships.authoring.document.ItemSettings.parse(
                                                data, mc.level.registryAccess());
                                commit(entityEquipment(doc(), owner, selected, parsed));
                                entityIndex = owner;
                            });
                });
        action(
                "item_clear",
                () -> {
                    commit(entityEquipment(doc(), owner, selected, ItemStack.EMPTY));
                    entityIndex = owner;
                });
        sections.end();
    }

    private boolean hasEquipment(CompoundTag data) {
        for (var name : List.of("HandItems", "ArmorItems"))
            for (var item : data.getList(name, Tag.TAG_COMPOUND))
                if (item instanceof CompoundTag stack && !stack.getString("id").isEmpty())
                    return true;
        return false;
    }

    private void replaceEntityData(CompoundTag data) {
        int index = entityIndex;
        var e = doc().entitiesCopy().getCompound(index);
        var p = e.getList("pos", Tag.TAG_DOUBLE);
        commit(
                SchematicEdits.entity(
                        doc(),
                        index,
                        new Vec3(p.getDouble(0), p.getDouble(1), p.getDouble(2)),
                        data));
        entityIndex = index;
    }

    private CompoundTag entityPlacementData() {
        var data = entityBrush == null ? new CompoundTag() : entityBrush.copy();
        data.putString("id", entityType);
        var id = ResourceLocation.tryParse(entityType);
        var type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        // Turning a new mob brush must not turn off its existing native spawn initialization.
        if (entityBrush == null
                && type != null
                && type.create(mc.level) instanceof net.minecraft.world.entity.Mob) {
            var persistent = data.getCompound("NeoForgeData");
            persistent.put("npships_initial_spawn", new CompoundTag());
            data.put("NeoForgeData", persistent);
        }
        return data;
    }

    private void addEntity() {
        if (pos() == null) throw new IllegalArgumentException(tr("select_hint").getString());
        var id = ResourceLocation.parse(entityType);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id))
            throw invalid("unknown_entity", "Unknown entity: " + id, entityType);
        var type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (!type.canSerialize())
            throw invalid("entity_unsaveable", "This entity cannot be saved in a schematic.", id);
        var data = entityPlacementData();
        if (!data.contains("PersistenceRequired")) data.putBoolean("PersistenceRequired", true);
        var added =
                SchematicEdits.entity(
                        doc(),
                        -1,
                        new Vec3(pos().getX() + .5, pos().getY(), pos().getZ() + .5),
                        data);
        if (entityBrush != null && !entityBrushPatches.isEmpty()) {
            var remap = new com.eynny.npships.authoring.patches.PatchInstances.Remap();
            for (var patch : entityBrushPatches)
                added =
                        EntityPatches.put(
                                added, added.entitiesCopy().size() - 1, remap.copy(patch, true));
        }
        commit(added);
        remember(recentEntities, entityType);
    }

    private void refreshEntities() {
        entityPreview.refresh(doc(), mc.level);
    }

    boolean isGlueId(String id) {
        return SchematicGlue.supported(id);
    }

    boolean glue(Entity e) {
        return EditorInspection.glue(e);
    }

    List<Integer> entityHits(double x, double y, Matrix4f inverse, float depth) {
        return editorGestures.entityHits(x, y, inverse, depth);
    }

    private int entityAt(double x, double y, Matrix4f inverse, float depth) {
        return editorGestures.entityAt(x, y, inverse, depth);
    }

    boolean pickEntity(double x, double y, Matrix4f inverse, float depth) {
        return editorGestures.pickEntity(x, y, inverse, depth);
    }

    private BlockAreaLayers.Layer movingLayer() {
        return tool == Tool.AREA
                        && movingArea
                        && dragging()
                        && !gizmoGesture
                        && !massRegionEditing
                        && host.specialBounds() == null
                        && proposedBounds != null
                ? layers.find(activeArea)
                : null;
    }

    private Vec3 areaMoveOffset() {
        return movingLayer() == null
                ? Vec3.ZERO
                : new Vec3(
                        proposedBounds.minX - dragBounds.minX,
                        proposedBounds.minY - dragBounds.minY,
                        proposedBounds.minZ - dragBounds.minZ);
    }

    void previewAreaMovement(
            com.eynny.npships.client.editor.render.SchematicPreviewRenderer renderer) {
        var layer = movingLayer();
        renderer.movement(
                layer,
                p ->
                        layer != null
                                && layer.content()
                                        .blocks()
                                        .containsKey(p.subtract(layer.region().origin())),
                areaMoveOffset());
    }

    void renderRotationAngles(GuiGraphics g, int left, int top, int right) {
        if (tool == Tool.AREA
                && inspectedArea.isEmpty()
                && deforming
                && areaGizmo.tool == 0
                && host.firstCorner() != null
                && !host.selectingRegion()
                && !massRegionEditing)
            areaGizmo.renderAngles(g, deformation, left + 8, top + 28, right);
    }

    void renderControls(GuiGraphics g, PoseStack pose) {
        advanceMass();
        if (showMass || showRegionMass)
            for (var part : massParts)
                if (part.center() != null) {
                    SceneOutlines.massCross(g, pose, part.center());
                    SceneOutlines.massPlanes(g, pose, part.center(), doc().size(), host.massPlanes);
                }
        if (tool == Tool.AREA
                && inspectedArea.isEmpty()
                && host.firstCorner() != null
                && !host.selectingRegion()
                && !massRegionEditing) {
            areaGizmo.modelSize = rotationBasisSize();
            areaGizmo.render(g, pose, gizmoRegion(), deformation, deforming);
        }
    }

    void renderScene(GuiGraphics g, PoseStack pose) {
        renderEntities(g, pose);
        renderSceneOverlays(g, pose);
    }

    void renderSceneOverlays(GuiGraphics g, PoseStack pose) {
        assemblyPoints.render(g, pose);
        refreshPreviewResources();
        refreshEntities();
        patchRegionTool.renderScene(g, pose);
        var zoneOutlines = new ArrayList<SceneOutlines.Zone>();
        if (deviceSource != doc()) cacheDeviceLines();
        if (tool == Tool.LOOT && host.deviceHints())
            for (var position : host.containers())
                if (host.visible(position))
                    SceneOutlines.patch(g, pose, new AABB(position), 0xffd040);
        // Native static Ponder renderer does not access a level.
        if (tool == Tool.PATCHES || host.allZones()) {
            if (tool == Tool.PATCHES) cachePatchHighlights();
            if (tool == Tool.PATCHES && host.deviceHints() && linkRole == null)
                for (var entry : patchHighlights.entrySet())
                    if (host.visible(entry.getKey())) {
                        SceneOutlines.patch(
                                g,
                                pose,
                                new AABB(entry.getKey()),
                                entry.getValue() ? 0x70ffb0 : 0xbba0ff);
                    }
            if (linkRole != null)
                for (var p : linkTargets) SceneOutlines.box(g, pose, new AABB(p), 0x70ffb0, false);
            for (var line : deviceLines)
                SceneOutlines.link(
                        g,
                        pose,
                        Vec3.atCenterOf(line.owner()),
                        Vec3.atCenterOf(line.target()),
                        line.color());
        }
        for (int i = 0; i < entityPreview.entities.size(); i++) {
            var e = entityPreview.entities.get(i);
            if (entityPreview.failedEntities.contains(e)
                    || !host.visible(BlockPos.containing(e.position()))) continue;
            if (glue(e) && authoringOutline(Tool.GLUE)) {
                if (tool == Tool.GLUE
                        && !host.allZones()
                        && host.deleting()
                        && entityPreview.entityIndices.get(i) == hoveredEntity)
                    SceneOutlines.deletion(g, pose, e.getBoundingBox(), 0xff3030);
                else
                    zoneOutlines.add(
                            new SceneOutlines.Zone(
                                    e.getBoundingBox(),
                                    SchematicGlue.color(EntityType.getKey(e.getType()).toString()),
                                    entityPreview.entityIndices.get(i) == entityIndex));
            }
            if ((entityTool() || entityPatchTarget()) && !glue(e)) {
                if (entityPreview.entityIndices.get(i) == entityIndex)
                    box(g, pose, e.getBoundingBox(), 0x70ffb0);
                else if (!dragging() && entityPreview.entityIndices.get(i) == hoveredEntity) {
                    if (host.deleting())
                        SceneOutlines.deletion(g, pose, e.getBoundingBox(), 0xff3030);
                    else box(g, pose, e.getBoundingBox(), 0xe6e6e6);
                }
            }
        }
        if (tool == Tool.SEGMENTS || host.allZones())
            for (var s : segments)
                if (authoringOutline(Tool.SEGMENTS))
                    zoneOutlines.add(
                            new SceneOutlines.Zone(
                                    bounds(s.origin(), s.size()),
                                    0xc899ff,
                                    s.id().equals(segment)));
        if (tool == Tool.ZONES || host.allZones())
            for (var z : zones)
                if (authoringOutline(Tool.ZONES))
                    zoneOutlines.add(
                            new SceneOutlines.Zone(
                                    bounds(z.origin(), z.size()), 0x55dd88, z.id().equals(zone)));
        if (tool == Tool.AREA || host.allZones())
            for (var a : layers.layers())
                if (authoringOutline(Tool.AREA))
                    zoneOutlines.add(
                            new SceneOutlines.Zone(
                                    a.region().bounds(),
                                    FittedPlaneLayers.plane(a) ? 0xff9f60 : 0x70c8ff,
                                    a.id().equals(selectedAreaId())));
        SceneOutlines.zones(g, pose, zoneOutlines);
        if (tool == Tool.AREA || host.allZones()) {
            for (var layer : layers.layers()) {
                if (!FittedPlaneLayers.plane(layer)) continue;
                if (!authoringOutline(Tool.AREA)) continue;
                var seed = FittedPlaneLayers.seed(layer);
                if (!host.visible(seed)) continue;
                var offset =
                        movingLayer() != null && layer.id().equals(activeArea)
                                ? areaMoveOffset()
                                : Vec3.ZERO;
                SceneOutlines.patch(g, pose, new AABB(seed).move(offset), 0xff9f30, .35f);
            }
        }
        if (blocked != null
                && System.currentTimeMillis() < blockedUntil
                && host.showWarning(blocked))
            SceneOutlines.patch(g, pose, new AABB(blocked), 0xff3030);
    }

    private void box(GuiGraphics g, PoseStack pose, AABB box, int color) {
        SceneOutlines.box(g, pose, box, color, false);
    }

    void renderEntities(GuiGraphics graphics, PoseStack pose) {
        refreshEntities();
        for (var entity : entityPreview.entities) {
            if (entityPreview.failedEntities.contains(entity)
                    || !host.visible(BlockPos.containing(entity.position()))) continue;
            var offset =
                    movingLayer() != null && areaEntities && dragBounds.contains(entity.position())
                            ? areaMoveOffset()
                            : Vec3.ZERO;
            if (!entityPreview.render(entity, pose, graphics.bufferSource(), offset, false))
                host.error(entityPreview.entityError);
        }
    }

    private boolean authoringOutline(Tool category) {
        // Contextual visibility is independent of inspection/editing activation. Alt retains
        // its cross-tool reveal; nested contexts still exclude unavailable categories.
        return toolAvailable(category) && (host.allZones() || tool == category);
    }

    private void segmentTools() {
        regionToolControls.segmentTools();
    }

    boolean hasSegment(String id) {
        return StructureSegments.read(doc().configCopy()).stream().anyMatch(s -> s.id().equals(id));
    }

    void addBaseVariant(StructureSegments.Segment s) {
        importVariant(s, doc(), s.origin(), "variant_" + (s.pool().size() + 1));
    }

    void importVariant(
            StructureSegments.Segment segment,
            SchematicDocument source,
            BlockPos origin,
            String name) {
        var sourceTag = source.nbtCopy();
        SchematicPatches.embed(sourceTag, source.configCopy());
        var part =
                StructureSegments.crop(
                        new SchematicDocument(sourceTag, source.configCopy()),
                        origin,
                        segment.size());
        String id = UUID.randomUUID().toString();
        var tag = doc().nbtCopy();
        var all = tag.getCompound(StructureSegments.VARIANTS);
        all.put(id, part);
        tag.put(StructureSegments.VARIANTS, all);
        var c = doc().configCopy();
        var entry = new JsonObject();
        entry.addProperty("variant", id);
        entry.addProperty("name", name);
        entry.addProperty("weight", 1);
        var tables = EmbeddedLoot.read(source.configCopy());
        for (var loot : StructureSegments.cropLoot(source.configCopy(), origin, segment.size())) {
            var table = loot.table();
            if (tables.has(table.toString())) {
                var scoped =
                        ResourceLocation.parse(
                                "npships:local/variant_"
                                        + id
                                        + "/"
                                        + table.getPath().substring("local/".length()));
                c =
                        EmbeddedLoot.assign(
                                c, scoped.toString(), tables.getAsJsonObject(table.toString()));
                table = scoped;
            }
            entry = LootAssignments.assign(entry, loot.pos(), table, loot.rolls());
        }
        for (var value : c.getAsJsonArray("segments")) {
            var obj = value.getAsJsonObject();
            if (!obj.get("id").getAsString().equals(segment.id())) continue;
            var pool = obj.has("pool") ? obj.getAsJsonArray("pool") : new JsonArray();
            pool.add(entry);
            obj.add("pool", pool);
        }
        EntityPatches.ensureOccurrences(tag, c);
        StructureSegments.validate(tag, StructureSegments.read(c));
        commit(new SchematicDocument(tag, c));
    }

    void openVariant(StructureSegments.Segment s, StructureSegments.Variant v) {
        var source = doc();
        var part = source.nbtCopy().getCompound(StructureSegments.VARIANTS).getCompound(v.id());
        var config = new JsonObject();
        config.addProperty("cost", 1);
        for (var loot : v.loot())
            config = LootAssignments.assign(config, loot.pos(), loot.table(), loot.rolls());
        var tables = EmbeddedLoot.read(source.configCopy());
        if (!tables.isEmpty()) config.add("loot_tables", tables);
        var patches = SchematicPatches.fromTemplate(part);
        SchematicPatches.set(config, patches);
        host.editVariant(
                v.id(),
                s.id() + " / " + v.name(),
                config,
                part,
                edited -> StructureSegments.variantValidationDocument(source, s, v.id(), edited),
                edited -> {
                    if (!edited.size().equals(s.size()))
                        throw invalid(
                                "variant_size",
                                "Variant size must match its segment.",
                                v.id(),
                                edited.size().toShortString(),
                                s.id(),
                                s.size().toShortString());
                    var current = doc();
                    SchematicPatches.validatePatches(
                            StructureSegments.variantValidationDocument(current, s, v.id(), edited),
                            true);
                    var tag = current.nbtCopy();
                    var t = edited.nbtCopy();
                    SchematicPatches.embed(t, edited.configCopy());
                    var all = tag.getCompound(StructureSegments.VARIANTS);
                    all.put(v.id(), t);
                    tag.put(StructureSegments.VARIANTS, all);
                    var c = current.configCopy();
                    for (var value : c.getAsJsonArray("segments"))
                        for (var item :
                                value.getAsJsonObject().has("pool")
                                        ? value.getAsJsonObject().getAsJsonArray("pool")
                                        : new JsonArray()) {
                            var e = item.getAsJsonObject();
                            if (!e.get("variant").getAsString().equals(v.id())) continue;
                            e.remove(LootAssignments.KEY);
                            for (var l : LootAssignments.read(edited.configCopy())) {
                                var updated =
                                        LootAssignments.assign(e, l.pos(), l.table(), l.rolls());
                                for (var p : updated.entrySet()) e.add(p.getKey(), p.getValue());
                            }
                        }
                    var local = EmbeddedLoot.read(edited.configCopy());
                    for (var e : local.entrySet())
                        c = EmbeddedLoot.assign(c, e.getKey(), e.getValue().getAsJsonObject());
                    EntityPatches.ensureOccurrences(tag, c);
                    commit(new SchematicDocument(tag, c));
                },
                variantStep(s, v, -1),
                variantStep(s, v, 1),
                () -> openGallery(currentSegment(s.id())));
    }

    StructureSegments.Segment currentSegment(String id) {
        return StructureSegments.read(doc().configCopy()).stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private Runnable variantStep(
            StructureSegments.Segment s, StructureSegments.Variant v, int step) {
        int index = s.pool().indexOf(v) + step;
        if (index < 0 || index >= s.pool().size()) return null;
        String id = s.pool().get(index).id();
        return () -> {
            var fresh = currentSegment(s.id());
            openVariant(
                    fresh,
                    fresh.pool().stream()
                            .filter(entry -> entry.id().equals(id))
                            .findFirst()
                            .orElseThrow());
        };
    }

    void openGallery(StructureSegments.Segment s) {
        mc.setScreen(
                new VariantGalleryScreen(
                        host,
                        doc(),
                        s,
                        v -> openVariant(s, v),
                        v -> {
                            commit(SchematicReframe.deleteVariant(doc(), segment, v.id()));
                            openGallery(currentSegment(s.id()));
                        },
                        () -> refreshGallery(s.id())));
    }

    private void glueTools() {
        regionToolControls.glueTools();
    }

    private void createGlue() {
        var a = host.firstCorner();
        var b = host.lastCorner();
        if (a == null || b == null) return;
        var box =
                new AABB(
                        Math.min(a.getX(), b.getX()),
                        Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()) + 1,
                        Math.max(a.getY(), b.getY()) + 1,
                        Math.max(a.getZ(), b.getZ()) + 1);
        int index = doc().entitiesCopy().size();
        commit(SchematicGlue.put(doc(), -1, glueType, box));
        entityIndex = index;
        host.clearRegion();
    }

    private void zoneTools() {
        regionToolControls.zoneTools();
    }

    static String xyz(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    JsonObject zoneObject(JsonObject c) {
        return c.getAsJsonArray("crew").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(o -> o.get("id").getAsString().equals(zone))
                .findFirst()
                .orElseThrow();
    }

    void createZone() {
        var a = host.firstCorner();
        var b = host.lastCorner();
        if (a == null || b == null)
            throw new IllegalArgumentException(tr("select_region").getString());
        var c = doc().configCopy();
        var rules = c.has("crew") ? c.getAsJsonArray("crew") : new JsonArray();
        String id = "zone_" + (rules.size() + 1);
        while (hasZone(rules, id)) id += "_";
        var rule = new JsonObject();
        rule.addProperty("id", id);
        rule.add(
                "origin",
                SchematicPatches.vector(
                        new BlockPos(
                                Math.min(a.getX(), b.getX()),
                                Math.min(a.getY(), b.getY()),
                                Math.min(a.getZ(), b.getZ()))));
        rule.add(
                "size",
                SchematicPatches.vector(
                        new BlockPos(
                                Math.abs(a.getX() - b.getX()) + 1,
                                Math.abs(a.getY() - b.getY()) + 1,
                                Math.abs(a.getZ() - b.getZ()) + 1)));
        rule.addProperty("min_count", 1);
        rule.addProperty("max_count", 1);
        rule.addProperty("placement", "ground");
        var pool = new JsonArray();
        var entry = new JsonObject();
        entry.addProperty("type", "minecraft:villager");
        entry.addProperty("weight", 1);
        pool.add(entry);
        rule.add("pool", pool);
        rules.add(rule);
        c.add("crew", rules);
        StructureCrew.validateBounds(doc(), c);
        commit(doc().withConfig(c));
        zone = id;
        host.clearRegion();
    }

    private boolean hasZone(JsonArray list, String id) {
        return list.asList().stream()
                .anyMatch(v -> v.getAsJsonObject().get("id").getAsString().equals(id));
    }

    void deleteZone() {
        var c = doc().configCopy();
        c.getAsJsonArray("crew")
                .asList()
                .removeIf(v -> v.getAsJsonObject().get("id").getAsString().equals(zone));
        commit(doc().withConfig(c));
        zone = "";
    }

    void beginWholeResize(BlockPos origin, BlockPos size) {
        rememberArea();
        layers = layers.reframe(origin, size);
        if (layers.find(activeArea) != null) loadArea(layers.get(activeArea));
        if (shapeBase != null) shapeBase = shapeBase.reframe(origin, size);
        var a = BlockEditorScreen.position(areaOrigin).subtract(origin);
        var extent = BlockEditorScreen.position(areaSize);
        var lo = new BlockPos(Math.max(0, a.getX()), Math.max(0, a.getY()), Math.max(0, a.getZ()));
        var hi =
                new BlockPos(
                        Math.min(size.getX(), a.getX() + extent.getX()),
                        Math.min(size.getY(), a.getY() + extent.getY()),
                        Math.min(size.getZ(), a.getZ() + extent.getZ()));
        if (hi.getX() > lo.getX() && hi.getY() > lo.getY() && hi.getZ() > lo.getZ()) {
            areaOrigin = xyz(lo);
            areaSize = xyz(hi.subtract(lo));
        } else {
            shapeBase = null;
            areaOrigin = "0 0 0";
            areaSize = "1 1 1";
        }
        applyingShape = true;
    }

    void finishWholeResize() {
        applyingShape = false;
        layerHistory.put(doc(), layers);
        lastLayerDocument = doc();
        host.metadataChanged();
    }

    void beginRegion() {
        areaGizmo.clear();
        boundaryDrag = false;
        deferredShape = false;
        movingArea = false;
        areaBlocks = true;
        areaEntities = true;
        areaZones = !host.isPart();
        areaHollow = false;
        shapeListOpen = false;
        deforming = false;
        activeArea = "";
        areaSticky = false;
        areaTransparent = false;
        continuousSurface = true;
        originalBlockWeight = 1;
        randomBlocks = false;
        randomStates.clear();
        randomWeights.clear();
        shapeJobs.discard();
        shapeCommitBase = null;
        shapeBase = null;
        areaGenerated = true;
        areaShape = SchematicRegions.Shape.NONE;
        areaTurns = 0;
        deformation = ShapeDeformation.IDENTITY;
        segment = "";
        zone = "";
        host.select(null);
    }

    void finishRegion() {
        run(this::finishRegionChecked);
    }

    private void finishRegionChecked() {
        host.finishCreationMode();
        automaticCreationMode = false;
        if (massRegionEditing) {
            run(this::calculateRegionMass);
            build();
            return;
        }
        if (tool == Tool.AREA) {
            readCorners();
            if (layers.find(activeArea) == null) {
                // New empty areas arm generation, but do not choose a shape on the user's behalf.
                // Do this after pending controls have flushed, not only at the first corner.
                areaGenerated = true;
                areaShape = SchematicRegions.Shape.NONE;
                boundaryDrag = false;
            }
            if (doc().blocks().size() > 65536 && layers.find(activeArea) == null) {
                var snapshot = layers;
                var region = area();
                var contents = areaContents();
                var options = areaOptions();
                var brush = parse(state);
                var shape = areaShape;
                boolean hollow = areaHollow;
                boolean generated =
                        areaGenerated && areaBlocks && shape != SchematicRegions.Shape.NONE;
                shapeCommitBase = doc();
                submitArea(
                        () -> {
                            var next = snapshot.create(region, contents, true);
                            var l = next.layers().getLast();
                            var result =
                                    AreaGeometry.render(
                                            l.source(),
                                            l.sourceMask(),
                                            region.size(),
                                            shape,
                                            generated,
                                            hollow,
                                            brush,
                                            ShapeDeformation.IDENTITY,
                                            List.of(),
                                            1);
                            next =
                                    next.replace(
                                            l.id(),
                                            region,
                                            result.content(),
                                            result.mask(),
                                            options);
                            return new AreaResult(next.compose(), next, l.id());
                        });
                host.status(tr("shape_busy").getString());
                build();
                return;
            }
            run(
                    () -> {
                        ensureArea();
                        updateShape();
                    });
            rememberArea();
            build();
            return;
        }
        build();
        if (tool == Tool.GLUE) {
            run(this::createGlue);
            return;
        }
        if (tool == Tool.ZONES) run(this::createZone);
        else if (tool == Tool.SEGMENTS)
            for (var widget : List.copyOf(widgets))
                if (widget instanceof Button b
                        && b.getMessage().equals(tr("new_segment"))
                        && b.active) {
                    b.onPress();
                    break;
                }
    }

    net.minecraft.world.level.block.state.BlockState selectedBlockState() {
        var i = doc().blocks().get(pos());
        return i == null
                ? Blocks.AIR.defaultBlockState()
                : NbtUtils.readBlockState(
                        mc.level.holderLookup(Registries.BLOCK),
                        doc().paletteCopy().getCompound(i));
    }

    List<JsonObject> blockBrushPatches = List.of();

    void copyBlock(BlockPos p) {
        blockBrushPatches =
                SchematicPatches.readIdentified(SchematicPatches.authoringConfig(doc())).stream()
                        .filter(e -> SchematicPatches.position(e.getAsJsonArray("pos")).equals(p))
                        .map(JsonObject::deepCopy)
                        .toList();
        host.select(p);
        state = BlockStateParser.serialize(selectedBlockState());
        blockBrushData = doc().blockEntityData(p).copy();
        boolean stripped = false;
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            stripped |= code.brush(PatchDevices.block(doc(), p), blockBrushData);
        if (stripped)
            blockBrushPatches =
                    blockBrushPatches.stream().filter(ExternalPatches::external).toList();
        ghostStateText = null;
        host.select(null);
        rebuild = true;
    }

    boolean pickRegion(double x, double y, Matrix4f inverse, float depth) {
        return editorGestures.pickRegion(x, y, inverse, depth);
    }

    AABB bounds(BlockPos o, BlockPos size) {
        return new AABB(Vec3.atLowerCornerOf(o), Vec3.atLowerCornerOf(o.offset(size)));
    }

    Vec3[] ray(double x, double y, Matrix4f inverse, float depth) {
        return host.interactionRay(
                new Vec3(inverse.transformProject(new Vector3f((float) x, (float) y, depth))),
                new Vec3(inverse.transformProject(new Vector3f((float) x, (float) y, -depth))));
    }

    AABB selectedBounds() {
        if (tool == Tool.ASSEMBLY) return assemblyPoints.bounds();
        if (host.specialBounds() != null) return host.specialBounds();
        if (tool == Tool.AREA && !inspectedArea.isEmpty()) {
            var layer = layers.find(inspectedArea);
            return layer == null ? null : layer.region().bounds();
        }
        if ((tool == Tool.AREA || massRegionEditing)
                && host.firstCorner() != null
                && host.lastCorner() != null) {
            readCorners();
            return bounds(
                    BlockEditorScreen.position(areaOrigin), BlockEditorScreen.position(areaSize));
        }
        if (blockSelectionTool() && pos() != null) return new AABB(pos());
        if (entityTool() && entityIndex >= 0) {
            int i = entityPreview.entityIndices.indexOf(entityIndex);
            if (i >= 0) return entityPreview.entities.get(i).getBoundingBox();
        }
        if (tool == Tool.SEGMENTS)
            for (var s : segments) if (s.id().equals(segment)) return bounds(s.origin(), s.size());
        if (tool == Tool.ZONES)
            for (var z : zones) if (z.id().equals(zone)) return bounds(z.origin(), z.size());
        return null;
    }

    boolean beginDrag(double x, double y, Matrix4f inverse, float depth) {
        if (tool == Tool.AREA && !inspectedArea.isEmpty()) return false;
        return editorGestures.beginDrag(x, y, inverse, depth);
    }

    boolean authoringGestureAt(double x, double y, Matrix4f inverse, float depth) {
        if (tool != Tool.AREA || !inspectedArea.isEmpty() || massRegionEditing || inverse == null)
            return false;
        if (deforming || areaGizmo.pickingCenter) return host.firstCorner() != null;
        var layer = layers.find(activeArea);
        if (!FittedPlaneLayers.plane(layer)) return false;
        var seed = FittedPlaneLayers.seed(layer);
        var handle = new AABB(seed).deflate(.15);
        var ray = ray(x, y, inverse, depth);
        return host.visible(seed)
                && host.interactionAllowed(handle.getCenter())
                && handle.clip(ray[0], ray[1]).isPresent();
    }

    boolean dragging() {
        return editorGestures.dragging();
    }

    boolean dragChanged() {
        return editorGestures.dragChanged();
    }

    void cancelDrag() {
        editorGestures.cancelDrag();
    }

    boolean drag(double x, double y, Matrix4f camera) {
        return editorGestures.drag(x, y, camera);
    }

    void endDrag() {
        editorGestures.endDrag();
    }

    void rotateSelected(int direction) {
        editorGestures.rotateSelected(direction);
    }

    Component warning(BlockPos p) {
        return p != null && p.equals(blocked) && System.currentTimeMillis() < blockedUntil
                ? tr("occupied")
                : null;
    }

    private void refreshGallery(String id) {
        var fresh =
                StructureSegments.read(doc().configCopy()).stream()
                        .filter(s -> s.id().equals(id))
                        .findFirst();
        if (fresh.isPresent()) openGallery(fresh.get());
        else mc.setScreen(host);
    }

    private void lootTools() {
        lootToolControls.lootTools();
    }

    void updateLootRows() {
        lootToolControls.updateLootRows();
    }

    void draftAction(String key, Runnable action) {
        var button =
                EditorButton.flat(
                                tr(key),
                                ignored -> {
                                    try {
                                        action.run();
                                        settingsDirty = true;
                                    } catch (Exception e) {
                                        if (e instanceof EditorValidationFeedback.Failure failure)
                                            sections.reveal(failure.owner);
                                        else host.error(EditorErrorText.message(e));
                                    }
                                })
                        .bounds(x, cursor, contentWidth(), 20)
                        .build();
        widgets.add(button);
        cursor += 24;
    }

    void editLocalTable(String original, JsonObject existing) {
        lootToolControls.editLocalTable(original, existing);
    }

    Component entityInfo() {
        if (!entityTool() || entityIndex < 0) return null;
        if (entityIndex >= entityPreview.entityEntries.size()) return null;
        var e = entityPreview.entityEntries.getCompound(entityIndex);
        return Component.translatable(
                        "npships.editor.entity_identity",
                        e.getCompound("nbt").getString("id").isBlank()
                                ? Component.translatable(
                                        "npships.editor.entity_without_type", entityIndex + 1)
                                : e.getCompound("nbt").getString("id"))
                .append("\n")
                .append(
                        Component.translatable(
                                "npships.editor.entity_position",
                                e.getList("pos", Tag.TAG_DOUBLE).toString()));
    }

    String itemQuery = "", itemCount = "1";
    int itemPage, itemMax, itemRowsY;
    final List<AbstractWidget> itemCells = new ArrayList<>();

    Button itemCell(ItemStack stack, int bx, int by, Runnable choose, Runnable remove) {
        return itemCell(stack, bx, by, choose, remove, () -> false);
    }

    Button itemCell(
            ItemStack stack,
            int bx,
            int by,
            Runnable choose,
            Runnable remove,
            java.util.function.BooleanSupplier selected) {
        var b =
                new Button(
                        bx,
                        by,
                        18,
                        18,
                        Component.empty(),
                        ignored -> run(choose),
                        supplier -> supplier.get()) {
                    @Override
                    public boolean mouseClicked(double mx, double my, int button) {
                        if (button == 1 && isMouseOver(mx, my)) {
                            run(remove);
                            return true;
                        }
                        return super.mouseClicked(mx, my, button);
                    }

                    @Override
                    protected void renderWidget(GuiGraphics g, int mx, int my, float t) {
                        EditorButton.cell(
                                g, getX(), getY(), isHovered(), selected.getAsBoolean(), active);
                        if (!stack.isEmpty()) {
                            g.renderItem(stack, getX() + 1, getY() + 1);
                            g.renderItemDecorations(mc.font, stack, getX() + 1, getY() + 1);
                        }
                    }
                };
        if (!stack.isEmpty())
            b.setTooltip(
                    Tooltip.create(
                            stack.getHoverName()
                                    .copy()
                                    .append(
                                            "\n"
                                                    + BuiltInRegistries.ITEM.getKey(
                                                            stack.getItem()))));
        return b;
    }

    BlockPos resourceOwner;
    int resourceSlot;
    boolean fluidsMode;
    List<ItemStack> resourceCatalog;
    com.eynny.npships.authoring.patches.PatchItemPresentations.Plan resourcePresentations;
    com.eynny.npships.authoring.patches.PatchInventoryEditor.Session resourceInventory;
    SchematicFluids resourceFluids;

    void inventory() {
        lootToolControls.inventory();
    }

    boolean inventoryNeedsExpansion(EditorLayout next) {
        return lootToolControls.inventoryNeedsExpansion(next);
    }

    Button fluidCell(
            net.neoforged.neoforge.fluids.FluidStack stack,
            int bx,
            int by,
            Runnable choose,
            Runnable clear) {
        return fluidCell(stack, bx, by, choose, clear, () -> false);
    }

    Button fluidCell(
            net.neoforged.neoforge.fluids.FluidStack stack,
            int bx,
            int by,
            Runnable choose,
            Runnable clear,
            java.util.function.BooleanSupplier selected) {
        var b =
                new Button(
                        bx,
                        by,
                        18,
                        18,
                        Component.empty(),
                        ignored -> run(choose),
                        supplier -> supplier.get()) {
                    @Override
                    public boolean mouseClicked(double mx, double my, int button) {
                        if (button == 1 && isMouseOver(mx, my)) {
                            run(clear);
                            return true;
                        }
                        return super.mouseClicked(mx, my, button);
                    }

                    @Override
                    protected void renderWidget(GuiGraphics g, int mx, int my, float t) {
                        EditorButton.cell(
                                g, getX(), getY(), isHovered(), selected.getAsBoolean(), active);
                        if (!stack.isEmpty()) {
                            var ext =
                                    net.neoforged.neoforge.client.extensions.common
                                            .IClientFluidTypeExtensions.of(stack.getFluid());
                            var texture = ext.getStillTexture(stack);
                            if (texture != null) {
                                int color = ext.getTintColor(stack);
                                var sprite =
                                        mc.getTextureAtlas(
                                                        net.minecraft.world.inventory.InventoryMenu
                                                                .BLOCK_ATLAS)
                                                .apply(texture);
                                g.setColor(
                                        (color >> 16 & 255) / 255f,
                                        (color >> 8 & 255) / 255f,
                                        (color & 255) / 255f,
                                        (color >>> 24) / 255f);
                                g.blit(getX() + 1, getY() + 1, 0, 16, 16, sprite);
                                g.setColor(1, 1, 1, 1);
                            }
                        }
                    }
                };
        if (!stack.isEmpty())
            b.setTooltip(
                    Tooltip.create(
                            stack.getHoverName()
                                    .copy()
                                    .append(
                                            "\n"
                                                    + BuiltInRegistries.FLUID.getKey(
                                                            stack.getFluid())
                                                    + "\n"
                                                    + stack.getAmount()
                                                    + " mB")));
        return b;
    }

    void updateItemRows() {
        lootToolControls.updateItemRows();
    }

    record DeviceLine(BlockPos owner, BlockPos target, int color) {}

    private SchematicDocument deviceSource;
    Map<BlockPos, Boolean> patchHighlights = Map.of();
    private List<DeviceLine> deviceLines = List.of();
    List<BlockPos> linkTargets = List.of();

    private void cacheDeviceLines() {
        deviceSource = doc();
        var lines = new ArrayList<DeviceLine>();
        for (var patch :
                deviceSource.hasConfig()
                        ? SchematicPatches.read(deviceSource.configCopy())
                        : SchematicPatches.fromTemplate(deviceSource.nbtCopy())) {
            var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
            boolean valid = true;
            try {
                PatchDevices.validateLinks(patch, deviceSource);
            } catch (Exception e) {
                valid = false;
            }
            for (var target : PatchDevices.targets(patch)) {
                lines.add(new DeviceLine(owner, target, valid ? 0x70c8ff : 0xff3030));
            }
        }
        deviceLines = List.copyOf(lines);
    }

    private final com.eynny.npships.authoring.patches.PatchHighlightIndex patchHighlightIndex =
            new com.eynny.npships.authoring.patches.PatchHighlightIndex();

    void cachePatchHighlights() {
        patchEditors.refresh();
        long revision = patchEditors.revision();
        if (highlightSource == doc() && highlightRevision == revision) return;
        var entries = new LinkedHashMap<>(patchHighlightIndex.entries(doc()));
        patchEditors.highlights(entries);
        patchHighlights = entries;
        highlightSource = doc();
        highlightRevision = revision;
    }

    private SchematicDocument highlightSource;
    private long highlightRevision = -1;

    String linkRole, referenceName = "device";
    JsonObject linkPatch;
    BlockPos firstLink;

    private void patchTools() {
        if (entityPatchTarget()) externalPatchControls.renderEntity();
        else patchToolControls.patchTools();
    }

    void beginLink(JsonObject patch, String role) {
        patchLinks.begin(patch, role);
    }

    boolean compatibleLink(BlockPos target) {
        return patchLinks.compatible(target);
    }

    boolean pickDeviceLink(BlockPos target) {
        return patchLinks.pick(target);
    }

    void putPatch(JsonObject patch) {
        var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
        boolean related =
                patchEditors.selects(patch, pos())
                        || com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all()
                                .stream()
                                .anyMatch(code -> code.acceptsOwner(doc(), patch, pos()));
        if (!owner.equals(pos()) && !related)
            throw invalid("patch_owner", "Keep the selected block position.", owner, pos());
        commit(PatchDevices.put(patchEditors.prepareEdit(doc(), patch), patch));
    }

    private final EditorScrollBar panelBar = new EditorScrollBar(),
            registryBar = new EditorScrollBar(),
            lootBar = new EditorScrollBar(),
            itemBar = new EditorScrollBar();

    void setScroll(int next) {
        layoutScroll(next);
        arrangement.preferScroll(this);
    }

    void layoutScroll(int next) {
        int delta = next - scroll;
        scroll = next;
        for (var b : widgets) if (b != codeEditor || !expandedCode()) b.setY(b.getY() - delta);
        if (codeEditor != null && !expandedCode())
            layout.codeDock(codeDockY, scroll, codeDockHeight)
                    .intersect(layout.inspectorContent())
                    .apply(codeEditor);
    }

    void scrollBars() {
        int available = Math.max(1, layout.inspectorContent().height());
        panelBar.configure(
                layout.inspector().right() - EditorLayout.CONTENT_GUTTER + 3,
                layout.inspectorContent().top(),
                available,
                available,
                contentHeight,
                scroll,
                this::setScroll);
        boolean registry =
                searchStart >= 0
                        && sections.registryVisible()
                        && (tool == Tool.BLOCKS && pos() == null
                                || tool == Tool.AREA
                                || tool == Tool.ENTITIES && entityIndex < 0);
        registryBar.configure(
                x + quickColumns() * 18 + 2,
                searchY - scroll,
                90,
                5,
                registry ? registryMax + 5 : 5,
                page,
                v -> {
                    page = v;
                    updateSearch(searchTypes);
                });
        lootBar.configure(
                x + contentWidth() - 6,
                lootRowsY - scroll,
                120,
                5,
                tool == Tool.LOOT && sections.tablesVisible() ? lootMax + 5 : 5,
                lootPage,
                v -> {
                    lootPage = v;
                    updateLootRows();
                });
        itemBar.configure(
                x + quickColumns() * 18 + 2,
                itemRowsY - scroll,
                90,
                5,
                itemCells.isEmpty() || !sections.contentsVisible() ? 5 : itemMax + 5,
                itemPage,
                v -> {
                    itemPage = v;
                    updateItemRows();
                });
    }

    void render(GuiGraphics g, int mx, int my, float t) {
        refreshCodeLayout();
        if (!layout.inspectorUsable()) return;
        if (codeEditor != null && !expandedCode() && sections.inputAvailable(codeEditor))
            layout.codeDock(codeDockY, scroll, codeDockHeight)
                    .intersect(layout.inspectorContent())
                    .apply(codeEditor);
        for (var widget : widgets)
            if (widget instanceof AbstractStringWidget text)
                text.setColor(EditorButtonTheme.text());
        layout.inspectorSurface().fill(g, EditorButtonTheme.surface(0xff1a2532));
        layout.toolSeparator().fill(g, EditorButtonTheme.edge());
        if (EditorIconButton.currentStyle() == EditorIconButton.Style.TOOLBAR)
            layout.toolTabSurface().fill(g, EditorButtonTheme.surface(0xff202e3c));
        for (var b : toolbar) b.render(g, mx, my, t);
        if (host.extensionPanelActive()) return;
        layout.inspectorClip().scissor(g);
        for (var b : widgets)
            if (sections.inspectorVisible(b)
                    && b.getY() + b.getHeight() > layout.inspectorClip().top()
                    && b.getY() < layout.inspectorClip().bottom())
                b.render(
                        g,
                        my >= layout.inspectorClip().top() && my < layout.inspectorClip().bottom()
                                ? mx
                                : -1,
                        my >= layout.inspectorClip().top() && my < layout.inspectorClip().bottom()
                                ? my
                                : -1,
                        t);
        g.disableScissor();
        scrollBars();
        layout.inspectorClip().scissor(g);
        registryBar.render(g);
        lootBar.render(g);
        itemBar.render(g);
        panelBar.render(g);
        g.disableScissor();
        if (codeEditor != null && !expandedCode() && sections.inputAvailable(codeEditor))
            codeEditor.suggestions(
                    g, layout.inspectorContent().top(), layout.inspector().bottom(), mx, my);
        if (focused instanceof RegistryEditBox box
                && box.getY() >= layout.inspectorContent().top()
                && box.getY() + 20 <= y + h) box.suggestions(g);
    }

    boolean mouseClicked(double mx, double my, int button) {
        if (!layout.inspectorUsable() && !codeAt(mx, my) && !inventoryAt(mx, my)) return false;
        if (inventoryAt(mx, my)) {
            if (inventoryBar.click(mx, my, button)) return true;
            for (var cell : inventoryCells) if (cell.mouseClicked(mx, my, button)) return true;
            return true;
        }
        if (codeEditor != null
                && sections.inputAvailable(codeEditor)
                && codeEditor.choose(mx, my, button)) return true;
        if (codeAt(mx, my)) {
            focusCode();
            codeEditor.mouseClicked(mx, my, button);
            return true;
        }
        if (focused instanceof RegistryEditBox box && box.choose(mx, my, button)) return true;
        if (sections.click(mx, my, button)) return true;
        if (mx < x || mx >= x + w || my < y || my >= layout.inspectorClip().bottom()) {
            if (focused != null) focused.setFocused(false);
            focused = null;
            return false;
        }
        scrollBars();
        if (!host.extensionPanelActive()
                && my >= layout.inspectorContent().top()
                && (panelBar.click(mx, my, button)
                        || registryBar.click(mx, my, button)
                        || lootBar.click(mx, my, button)
                        || itemBar.click(mx, my, button))) return true;
        for (var b : toolbar) if (b.mouseClicked(mx, my, button)) return true;
        if (host.extensionPanelActive()) return true;
        if (my >= layout.inspectorClip().top())
            for (var b : List.copyOf(widgets)) {
                int navigation = validationNavigation;
                if (sections.inspectorVisible(b) && b.mouseClicked(mx, my, button)) {
                    if (navigation != validationNavigation || !widgets.contains(b)) return true;
                    if (codeEditor != null
                            && codeEditor.isFocused()
                            && !(b instanceof EditBox)
                            && !(b instanceof MultiLineEditBox)) {
                        focused = codeEditor;
                        return true;
                    }
                    focusWidget(b);
                    return true;
                }
            }
        return true;
    }

    boolean textPopupContains(double mx, double my) {
        syncTextPopup();
        return pinnedHelp != null && pinnedHelp.referenceContains(mx, my)
                || codeEditor != null
                        && textSurfaceAvailable(codeEditor)
                        && codeEditor.valuePickerOpen()
                        && codeEditor.isMouseOver(mx, my)
                || codeEditor != null
                        && textSurfaceAvailable(codeEditor)
                        && codeEditor.popupContains(mx, my)
                || focused instanceof RegistryEditBox box && box.popupContains(mx, my);
    }

    boolean controlTextHelpAt(double mx, double my) {
        return net.minecraft.client.gui.screens.Screen.hasControlDown()
                && (textPopupContains(mx, my)
                        || codeAt(mx, my)
                        || codeEditor != null
                                && textSurfaceAvailable(codeEditor)
                                && codeEditor.isMouseOver(mx, my)
                                && layout.inspectorClip().contains(mx, my));
    }

    boolean changingLocalLootTarget(EditorInspection.Candidate candidate) {
        if (tool != Tool.LOOT
                || sections.find("table_editor") == null
                || inspectionMatches(candidate)) return false;
        host.status(tr("loot_finish_target").getString());
        return true;
    }

    boolean captureActive() {
        return dragging()
                || patchRegionTool.dragging()
                || inventorySliding
                || inventoryBar.dragging()
                || panelBar.dragging()
                || registryBar.dragging()
                || lootBar.dragging()
                || itemBar.dragging()
                || pinnedHelp != null && pinnedHelp.pointerCaptured()
                || codeEditor != null && codeEditor.pointerCaptured();
    }

    boolean overlayAt(double mx, double my) {
        syncTextPopup();
        return pinnedHelp != null && pinnedHelp.popupContains(mx, my)
                || codeEditor != null
                        && textSurfaceAvailable(codeEditor)
                        && codeEditor.popupContains(mx, my)
                || focused instanceof RegistryEditBox box && box.popupContains(mx, my);
    }

    boolean clickOverlay(double mx, double my, int button) {
        if (pinnedHelp != null && pinnedHelp.popupContains(mx, my)) {
            pinnedHelp.choose(mx, my, button);
            return true;
        }
        if (codeEditor != null
                && sections.inputAvailable(codeEditor)
                && codeEditor.popupContains(mx, my)) {
            codeEditor.choose(mx, my, button);
            return true;
        }
        if (focused instanceof RegistryEditBox box && box.popupContains(mx, my)) {
            box.choose(mx, my, button);
            return true;
        }
        return false;
    }

    boolean clickTextPopup(double mx, double my, int button) {
        return pinnedHelp != null
                && pinnedHelp.referenceOpen()
                && pinnedHelp.choose(mx, my, button);
    }

    void renderPinnedHelp(GuiGraphics g, int mx, int my) {
        if (pinnedHelp != null) pinnedHelp.renderReference(g, mx, my);
    }

    boolean scrollTextPopup(double mx, double my, double delta) {
        syncTextPopup();
        if (pinnedHelp != null
                && pinnedHelp.referenceOpen()
                && pinnedHelp.scrollSuggestions(mx, my, delta)) return true;
        if (codeEditor != null
                && textSurfaceAvailable(codeEditor)
                && codeEditor.scrollSuggestions(mx, my, delta)) return true;
        return focused instanceof RegistryEditBox box
                && sections.inputAvailable(box)
                && box.scrollSuggestions(mx, my, delta);
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        if (host.extensionPanelActive()) return layout.inspector().contains(mx, my);
        refreshCodeLayout();
        if (!layout.inspectorUsable() && !codeAt(mx, my) && !inventoryAt(mx, my))
            return layout.inspector().contains(mx, my);
        if (inventoryAt(mx, my)) {
            inventoryOffsetY = Math.max(0, inventoryOffsetY - (int) (delta * 36));
            layoutInventory(layout());
            return true;
        }
        if (scrollTextPopup(mx, my, delta)) return true;
        if (codeAt(mx, my)) {
            if (textSurfaceAvailable(codeEditor)) codeEditor.mouseScrolled(mx, my, 0, delta);
            return true;
        }
        if (codeEditor != null
                && sections.inputAvailable(codeEditor)
                && !expandedCode()
                && codeEditor.isMouseOver(mx, my)
                && my >= layout.inspectorContent().top()
                && my < layout.inspectorClip().bottom())
            return codeEditor.mouseScrolled(mx, my, 0, delta) || scrollInspector(delta);
        if (mx < x
                || mx >= x + w
                || my < layout.inspectorClip().top()
                || my >= layout.inspectorClip().bottom()) return false;
        if (captureActive()) return true;
        if (searchStart >= 0
                && sections.registryVisible()
                && (tool == Tool.BLOCKS && pos() == null
                        || tool == Tool.AREA
                        || tool == Tool.ENTITIES && entityIndex < 0)
                && my >= searchY - scroll
                && my < searchY + 90 - scroll) {
            page = Math.max(0, Math.min(registryMax, page - (int) Math.signum(delta)));
            updateSearch(searchTypes);
            return true;
        }
        if (tool == Tool.LOOT
                && sections.tablesVisible()
                && !lootRows.isEmpty()
                && my >= lootRowsY - scroll
                && my < lootRowsY + 120 - scroll) {
            lootPage = Math.max(0, Math.min(lootMax, lootPage - (int) Math.signum(delta)));
            updateLootRows();
            return true;
        }
        if (!itemCells.isEmpty()
                && sections.contentsVisible()
                && my >= itemRowsY - scroll
                && my < itemRowsY + 90 - scroll) {
            itemPage = Math.max(0, Math.min(itemMax, itemPage - (int) Math.signum(delta)));
            updateItemRows();
            return true;
        }
        if (focused instanceof MultiLineEditBox box
                && textSurfaceAvailable(box)
                && box.isMouseOver(mx, my)
                && box.mouseScrolled(mx, my, 0, delta)) return true;
        return scrollInspector(delta);
    }

    private boolean scrollInspector(double delta) {
        if (captureActive()) return true;
        int next =
                Math.max(
                        0,
                        Math.min(
                                Math.max(0, contentHeight - (layout.inspectorContent().height())),
                                scroll - (int) (delta * 24)));
        setScroll(next);
        return true;
    }

    boolean completeCode(int key, int modifiers) {
        if (codeEditor == null
                || !textSurfaceAvailable(codeEditor)
                || !(key == org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH
                                && (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_ALT) != 0
                        || key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
                                && ((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0
                                        || Screen.hasControlDown()))) return false;
        focusCode();
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE)
            codeEditor.keyPressed(key, 0, modifiers | org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL);
        else codeEditor.complete();
        return true;
    }

    boolean sectionKeyPressed(int key, int scan, int modifiers) {
        if (!layout.inspectorUsable()) return false;
        if (sections.headerKey(key, scan, modifiers)) return true;
        return sections.any()
                && !editingText()
                && key == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB
                && focusNext((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) == 0);
    }

    private boolean focusNext(boolean forward) {
        if (focused == null) return false;
        var available = new ArrayList<AbstractWidget>();
        var event =
                new net.minecraft.client.gui.navigation.FocusNavigationEvent.TabNavigation(forward);
        for (var widget : widgets)
            if (sections.inputAvailable(widget)
                    && widget.active
                    && (widget == focused || widget.nextFocusPath(event) != null))
                available.add(widget);
        if (available.isEmpty()) return false;
        int at = available.indexOf(focused);
        int next = Math.floorMod(at + (forward ? 1 : -1), available.size());
        var widget = available.get(next);
        revealWidget(widget);
        focusWidget(widget);
        return true;
    }

    /** Called after the previous draft was accepted; the clipboard brush survives the handoff. */
    private void beginObjectPlacement(Tool destination) {
        host.clearObjectSelectionValidated();
        replaceToolContext(destination, false);
        host.setCreateMode();
        rebuild = true;
    }

    boolean keyPressed(int key, int scan, int modifiers) {
        if (tool == Tool.AREA
                && !inspectedArea.isEmpty()
                && !editingText()
                && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
                        || Screen.hasControlDown()
                                && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_C
                                        || key == org.lwjgl.glfw.GLFW.GLFW_KEY_X
                                        || key == org.lwjgl.glfw.GLFW.GLFW_KEY_V))) {
            host.status(tr("area_inspection_hint").getString());
            return true;
        }
        if (!editingText()
                && Screen.hasControlDown()
                && key == org.lwjgl.glfw.GLFW.GLFW_KEY_V
                && (blockSelectionTool() || tool == Tool.ENTITIES)) {
            if (flushEdits()) {
                beginObjectPlacement(
                        tool == Tool.ENTITIES || entityPatchTarget() ? Tool.ENTITIES : Tool.BLOCKS);
            }
            return true;
        }
        if (blockSelectionTool()
                && !entityPatchTarget()
                && !editingText()
                && Screen.hasControlDown()
                && key == org.lwjgl.glfw.GLFW.GLFW_KEY_X) {
            var target = pos() != null ? pos() : host.blockUnderCursor();
            if (target != null)
                run(
                        () -> {
                            copyBlock(target);
                            commit(
                                    SchematicBlocks.put(
                                            doc(),
                                            target,
                                            target,
                                            Blocks.AIR.defaultBlockState(),
                                            null));
                            beginObjectPlacement(Tool.BLOCKS);
                        });
            return true;
        }
        if (tool != Tool.AREA
                && !editingText()
                && Screen.hasControlDown()
                && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_C
                        || (tool == Tool.ENTITIES || entityPatchTarget())
                                && key == org.lwjgl.glfw.GLFW.GLFW_KEY_X)) {
            if (!flushEdits()) return true;
            if (blockSelectionTool() && !entityPatchTarget()) {
                var target = pos() != null ? pos() : host.blockUnderCursor();
                if (target != null) {
                    copyBlock(target);
                    beginObjectPlacement(Tool.BLOCKS);
                }
            } else if (tool == Tool.ENTITIES || entityPatchTarget()) {
                int index = entityIndex >= 0 ? entityIndex : hoveredEntity;
                if (index >= 0) {
                    entityBrush = doc().entitiesCopy().getCompound(index).getCompound("nbt").copy();
                    var copiedId = EntityIdentity.read(entityBrush);
                    entityBrushPatches =
                            EntityPatches.entries(doc()).stream()
                                    .filter(
                                            e ->
                                                    com.eynny.npships.authoring.patches
                                                            .EntityPatches.token(e)
                                                            .equals(copiedId))
                                    .map(
                                            e ->
                                                    com.eynny.npships.authoring.patches
                                                            .PatchInstances.materialize(
                                                            e, true, EntityPatches.schema(doc())))
                                    .toList();
                    entityType = entityBrush.getString("id");
                    if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_X)
                        commit(SchematicEdits.entity(doc(), index, Vec3.ZERO, null));
                    beginObjectPlacement(Tool.ENTITIES);
                }
            }
            return true;
        }
        if (tool == Tool.AREA && !editingText()) {
            if (Screen.hasControlDown() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_C) {
                run(() -> copyArea(false));
                return true;
            }
            if (Screen.hasControlDown() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_X) {
                run(() -> copyArea(true));
                return true;
            }
            if (Screen.hasControlDown() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_V) {
                run(() -> pasteArea(false));
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE) {
                run(this::removeArea);
                return true;
            }
        }
        if (!(focused instanceof EditBox)
                && !(focused instanceof MultiLineEditBox)
                && key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
                && tool == Tool.SEGMENTS
                && !segment.isEmpty()) {
            run(
                    () -> {
                        commit(SchematicReframe.deleteSegment(doc(), segment));
                        segment = "";
                    });
            return true;
        }
        if (!(focused instanceof EditBox)
                && !(focused instanceof MultiLineEditBox)
                && key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
                && tool == Tool.ZONES
                && !zone.isEmpty()) {
            run(this::deleteZone);
            return true;
        }
        if (!(focused instanceof EditBox)
                && !(focused instanceof MultiLineEditBox)
                && key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
                && entityIndex >= 0
                && entityTool()) {
            int target = entityIndex, count = doc().entityCount();
            // A document update can precede the scheduled inspector rebuild. Do not flush
            // that old editor into a replacement entity at the same numeric index.
            if (cached == null
                    || target >= cached.entityCount()
                    || target >= count
                    || cached != doc()
                            && !cached.entitiesCopy()
                                    .getCompound(target)
                                    .equals(doc().entitiesCopy().getCompound(target))) {
                host.error(
                        EditorErrorText.message(
                                invalid(
                                        "entity_stale",
                                        "Entity selection is stale; refresh the inspector before"
                                                + " deleting.",
                                        target)));
                return true;
            }
            var targetTool = tool;
            var targetEditor = codeEditor;
            run(
                    () -> {
                        // Pending acceptance may change the list or semantic editing context.
                        if (entityIndex != target
                                || doc().entityCount() != count
                                || tool != targetTool
                                || codeEditor != targetEditor)
                            throw invalid(
                                    "entity_stale",
                                    "Entity selection changed while accepting pending edits.",
                                    target);
                        commit(SchematicEdits.entity(doc(), target, Vec3.ZERO, null));
                        entityIndex = -1;
                    });
            return true;
        }
        if (focused == null) return false;
        if (!textSurfaceAvailable(focused) && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
            return true;
        boolean used = focused.keyPressed(key, scan, modifiers);
        if (!used
                && sections.any()
                && !(focused instanceof LuaEditBox)
                && key == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB)
            return focusNext((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) == 0);
        return used
                || ((focused instanceof EditBox || focused instanceof MultiLineEditBox)
                        && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
    }

    boolean referenceKeyPressed(int key, int scan, int modifiers) {
        return pinnedHelp != null && pinnedHelp.referenceKeyPressed(key, scan, modifiers);
    }

    boolean charTyped(char c, int modifiers) {
        if (pinnedHelp != null && pinnedHelp.referenceTyping()) return true;
        boolean consumed =
                focused != null
                        && (!textSurfaceAvailable(focused) || focused.charTyped(c, modifiers));
        refreshCodeLayout();
        return consumed;
    }

    boolean dismissCodeCompletion() {
        if (pinnedHelp != null && pinnedHelp.referenceOpen()) return pinnedHelp.dismissCompletion();
        return focused instanceof LuaEditBox box && box.dismissCompletion();
    }

    private boolean textSurfaceAvailable(AbstractWidget widget) {
        if (!sections.inputAvailable(widget)) return false;
        return widget == codeEditor && expandedCode()
                ? layout.expandedCodeField().width() >= 24
                        && layout.expandedCodeField().height() >= 20
                : layout.inspectorUsable();
    }

    boolean editingText() {
        return focused instanceof EditBox || focused instanceof MultiLineEditBox;
    }

    boolean keyboardControl() {
        return focused != null && focused.active && textSurfaceAvailable(focused);
    }

    boolean keyboardPopup() {
        return pinnedHelp != null && pinnedHelp.referenceOpen()
                || focused instanceof LuaEditBox box
                        && textSurfaceAvailable(box)
                        && box.keyboardPopup()
                || focused instanceof RegistryEditBox box
                        && textSurfaceAvailable(box)
                        && box.keyboardPopup();
    }

    boolean dismissKeyboardPopup(int key, int scan, int modifiers) {
        if (pinnedHelp != null && pinnedHelp.referenceOpen()) return pinnedHelp.dismissCompletion();
        if (focused instanceof LuaEditBox box && textSurfaceAvailable(box))
            return box.dismissCompletion();
        return focused instanceof RegistryEditBox box
                && textSurfaceAvailable(box)
                && box.keyboardPopup()
                && box.keyPressed(key, scan, modifiers);
    }

    boolean keyboardPopupActivation(int key, int modifiers) {
        return focused instanceof LuaEditBox box
                        && textSurfaceAvailable(box)
                        && box.keyboardPopupActivation(key, modifiers)
                || focused instanceof RegistryEditBox registry
                        && textSurfaceAvailable(registry)
                        && registry.keyboardPopupActivation(key);
    }

    boolean focusedKeyPressed(int key, int scan, int modifiers) {
        return keyboardControl() && focused.keyPressed(key, scan, modifiers);
    }

    void deleteSelectedBlock() {
        if (geometryBusy()) {
            host.status(tr("shape_busy").getString());
            return;
        }
        run(
                () -> {
                    // Pending XYZ may have moved the target during the native acceptance boundary.
                    var target = pos();
                    if (target == null) return;
                    commit(
                            SchematicBlocks.put(
                                    doc(), target, target, Blocks.AIR.defaultBlockState(), null));
                    host.select(null);
                });
    }

    boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (pinnedHelp != null && pinnedHelp.selectReference(x, y, button, dx, dy)) return true;
        if (pinnedHelp != null && pinnedHelp.dragHelp(dx, dy)) return true;
        if (codeEditor != null && codeEditor.dragHelp(dx, dy)) return true;
        if (codeEditor != null && textSurfaceAvailable(codeEditor) && codeEditor.valuePickerOpen())
            return true;
        if (button == 0
                && (panelBar.drag(y) || registryBar.drag(y) || lootBar.drag(y) || itemBar.drag(y)))
            return true;
        return (layout.inspectorUsable() || codeAt(x, y))
                && focused != null
                && sections.inputAvailable(focused)
                && (focused != codeEditor || !expandedCode() || codeAt(x, y))
                && focused.mouseDragged(x, y, button, dx, dy);
    }

    void mouseReleased(double x, double y, int button) {
        if (pinnedHelp != null) pinnedHelp.releaseHelp();
        if (codeEditor != null) codeEditor.releaseHelp();
        panelBar.release();
        registryBar.release();
        lootBar.release();
        itemBar.release();
        if (focused != null) focused.mouseReleased(x, y, button);
        sections.released();
    }
}
