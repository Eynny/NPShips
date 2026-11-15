package com.eynny.npships.client.editor.screen;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.client.editor.EditorErrorText;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorEditBox;
import com.eynny.npships.client.editor.widget.EditorTooltips;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.function.Consumer;

/** Registry-driven block/state editor. Only detached template data is modified. */
public final class BlockEditorScreen extends Screen {
    private final Screen parent;
    private final SchematicDocument source;
    private final BlockPos selected;
    private final Consumer<SchematicDocument> accept;
    private String stateText, positionText, materialText;
    private EditBox state, position, material;
    private String error = "";
    private boolean placeNew;
    private String lastQuery;
    private List<String> matches = List.of();
    private final List<String> ids =
            BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString).sorted().toList();

    public BlockEditorScreen(
            Screen parent,
            SchematicDocument source,
            BlockPos pos,
            Consumer<SchematicDocument> accept) {
        super(Component.translatable("npships.blocks.edit"));
        this.parent = parent;
        this.source = source;
        selected = pos;
        this.accept = accept;
        var palette = source.paletteCopy();
        var index = source.blocks().get(pos);
        var tag =
                index == null
                        ? NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState())
                        : palette.getCompound(index);
        stateText = tag.getString("Name");
        var props = tag.getCompound("Properties");
        if (!props.isEmpty())
            stateText +=
                    "["
                            + props.getAllKeys().stream()
                                    .sorted()
                                    .map(k -> k + "=" + props.getString(k))
                                    .collect(java.util.stream.Collectors.joining(","))
                            + "]";
        positionText = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        var saved = com.eynny.npships.client.editor.BlockAppearances.saved(source, pos);
        materialText = saved.isEmpty() ? "" : saved.getString("Name");
        var materialProps = saved.getCompound("Properties");
        if (!materialProps.isEmpty())
            materialText +=
                    "["
                            + materialProps.getAllKeys().stream()
                                    .sorted()
                                    .map(k -> k + "=" + materialProps.getString(k))
                                    .collect(java.util.stream.Collectors.joining(","))
                            + "]";
    }

    @Override
    protected void init() {
        int x = 12, w = width - 24;
        state = field(x, 42, w, stateText, v -> stateText = v);
        position = field(x, 82, w / 2 - 4, positionText, v -> positionText = v);
        material = field(x + w / 2 + 4, 82, w / 2 - 4, materialText, v -> materialText = v);
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable(
                                        placeNew
                                                ? "npships.blocks.mode.add"
                                                : "npships.blocks.mode.move"),
                                b -> {
                                    placeNew = !placeNew;
                                    b.setMessage(
                                            Component.translatable(
                                                    placeNew
                                                            ? "npships.blocks.mode.add"
                                                            : "npships.blocks.mode.move"));
                                })
                        .bounds(x, 108, w, 20)
                        .build());
        int bw = (w - 12) / 4;
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("npships.blocks.rotate"),
                                b ->
                                        run(
                                                () -> {
                                                    var parsed =
                                                            com.eynny.npships.authoring.document
                                                                    .SchematicRotations.cycle(
                                                                    parse(stateText),
                                                                    1,
                                                                    minecraft.level);
                                                    state.setValue(
                                                            BlockStateParser.serialize(parsed));
                                                }))
                        .bounds(x, height - 64, bw, 20)
                        .build());
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("npships.blocks.delete"),
                                b ->
                                        run(
                                                () -> {
                                                    accept.accept(
                                                            SchematicBlocks.put(
                                                                    source,
                                                                    selected,
                                                                    selected,
                                                                    Blocks.AIR.defaultBlockState(),
                                                                    null));
                                                    onClose();
                                                }))
                        .bounds(x + bw + 4, height - 64, bw, 20)
                        .build());
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("gui.done"),
                                b ->
                                        run(
                                                () -> {
                                                    var block = parse(stateText);
                                                    var to = position(positionText);
                                                    net.minecraft.nbt.CompoundTag visual = null;
                                                    if (!materialText.isBlank()) {
                                                        var materialState = parse(materialText);
                                                        var previewDoc =
                                                                SchematicBlocks.put(
                                                                        source,
                                                                        placeNew ? null : selected,
                                                                        to,
                                                                        block,
                                                                        null);
                                                        visual =
                                                                com.eynny.npships.client.editor
                                                                        .BlockAppearances.material(
                                                                        previewDoc,
                                                                        to,
                                                                        block,
                                                                        materialState,
                                                                        minecraft.level);
                                                    }
                                                    accept.accept(
                                                            SchematicBlocks.put(
                                                                    source,
                                                                    placeNew ? null : selected,
                                                                    to,
                                                                    block,
                                                                    visual));
                                                    onClose();
                                                }))
                        .bounds(x + 2 * (bw + 4), height - 64, bw, 20)
                        .build());
        addRenderableWidget(
                EditorButton.flat(Component.translatable("gui.cancel"), b -> onClose())
                        .bounds(x + 3 * (bw + 4), height - 64, bw, 20)
                        .build());
    }

    private EditBox field(int x, int y, int w, String initial, Consumer<String> listener) {
        var box = new EditorEditBox(font, x, y, w, 20, Component.empty());
        box.setTooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("npships.tools.block_field_hint")));
        box.setMaxLength(1024);
        box.setValue(initial);
        box.setResponder(listener);
        return addRenderableWidget(box);
    }

    private net.minecraft.world.level.block.state.BlockState parse(String value) throws Exception {
        var reader = new com.mojang.brigadier.StringReader(value.trim());
        var parsed =
                BlockStateParser.parseForBlock(
                        minecraft.level.holderLookup(Registries.BLOCK), reader, false);
        if (reader.canRead())
            throw invalid("block_trailing", "Unexpected text after block state.", value);
        return value.indexOf('[') < 0
                ? com.eynny.npships.authoring.document.SchematicPlacement.defaultState(
                        parsed.blockState().getBlock(), minecraft.level)
                : parsed.blockState();
    }

    public static BlockPos position(String value) {
        var xyz = value.trim().split("[ ,;]+");
        if (xyz.length != 3) throw invalid("position_xyz", "Enter X Y Z.", value);
        return new BlockPos(
                Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
    }

    private interface Action {
        void run() throws Exception;
    }

    private void run(Action action) {
        try {
            action.run();
        } catch (Exception e) {
            error = EditorErrorText.message(e);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float t) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float t) {
        g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        g.drawCenteredString(font, title, width / 2, 12, EditorButtonTheme.text());
        g.drawString(font, Component.translatable("npships.blocks.state"), 12, 30, 0xaabbd0);
        g.drawString(font, Component.translatable("npships.blocks.position"), 12, 70, 0xaabbd0);
        g.drawString(
                font,
                Component.translatable("npships.blocks.material"),
                width / 2 + 4,
                70,
                0xaabbd0);
        int row = 134;
        for (String id : suggestions()) {
            g.drawString(font, id, 16, row + 4, 0x73dfcc);
            row += 18;
        }
        g.drawString(font, font.plainSubstrByWidth(error, width - 24), 12, height - 38, 0xffaaaa);
        g.drawString(
                font,
                font.plainSubstrByWidth(
                        Component.translatable("npships.blocks.hint").getString(), width - 24),
                12,
                height - 20,
                0xaabbd0);
        EditorButtonTheme.widgets(this);
        super.render(g, x, y, t);
        if (!error.isEmpty() && y >= height - 42 && y < height - 22)
            EditorTooltips.show(font, font.split(Component.literal(error), width - 24), x, y);
    }

    private List<String> suggestions() {
        String query =
                (material.isFocused() ? materialText : stateText)
                        .toLowerCase(java.util.Locale.ROOT);
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            matches = ids.stream().filter(id -> id.contains(query)).toList();
        }
        return matches.subList(0, Math.min(matches.size(), Math.max(0, (height - 206) / 18)));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (com.eynny.npships.client.editor.widget.EditorMessageCopy.click(
                minecraft, error, x, y, button, 12, height - 42, width - 24, 20)) return true;
        int row = ((int) y - 134) / 18;
        var list = suggestions();
        if (button == 0 && x >= 12 && x < width - 12 && y >= 134 && row < list.size()) {
            (material.isFocused() ? material : state).setValue(list.get(row));
            return true;
        }
        return super.mouseClicked(x, y, button);
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
