package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.document.SchematicEdits;
import com.eynny.npships.client.editor.widget.EditorButton;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.util.function.Consumer;

/** Native banner item previews and dye swatches, with no authored GUI textures required. */
final class BannerControls {
    private final EditorToolsPanel panel;
    private int editing = -1;
    private boolean patternsOpen;
    private net.minecraft.core.BlockPos selected;

    BannerControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    void build(Block block, CompoundTag data) {
        if (!(block instanceof AbstractBannerBlock banner)) return;
        panel.parameters.handled("patterns");
        var pos = panel.pos();
        if (!pos.equals(selected)) {
            selected = pos.immutable();
            editing = -1;
            patternsOpen = false;
        }
        var patterns = data.getList("patterns", Tag.TAG_COMPOUND);
        panel.button(
                "npships.banner.base",
                () -> {
                    editing = -1;
                    patternsOpen = false;
                    panel.build();
                });
        for (int i = 0; i < patterns.size(); i++) {
            int layer = i;
            var entry = patterns.getCompound(i);
            panel.button(
                            (i + 1)
                                    + ": "
                                    + entry.getString("pattern").replace("minecraft:", "")
                                    + " / "
                                    + entry.getString("color"),
                            () -> {
                                editing = layer;
                                patternsOpen = true;
                                panel.build();
                            })
                    .selected(() -> editing == layer);
        }
        panel.button(
                "npships.banner.add",
                () -> {
                    var changed = panel.doc().blockEntityData(pos);
                    var list = changed.getList("patterns", Tag.TAG_COMPOUND);
                    var entry = new CompoundTag();
                    entry.putString("pattern", "minecraft:stripe_center");
                    entry.putString("color", "white");
                    list.add(entry);
                    changed.put("patterns", list);
                    editing = list.size() - 1;
                    patternsOpen = true;
                    panel.commit(SchematicEdits.blockData(panel.doc(), pos, changed));
                });
        int selectedIndex = editing;
        var selectedPattern =
                editing >= 0 && editing < patterns.size()
                        ? patterns.getCompound(editing).copy()
                        : null;
        colors(
                color -> {
                    if (selectedPattern != null) {
                        var changed = panel.doc().blockEntityData(pos);
                        var current = changed.getList("patterns", Tag.TAG_COMPOUND);
                        editing =
                                panel.currentRow(current, selectedPattern, selectedIndex, patterns);
                        current.getCompound(editing).putString("color", color.getName());
                        panel.commit(SchematicEdits.blockData(panel.doc(), pos, changed));
                    } else {
                        var current = panel.selectedBlockState();
                        Block next =
                                net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                        net.minecraft.resources.ResourceLocation
                                                .withDefaultNamespace(
                                                        color.getName()
                                                                + (block instanceof WallBannerBlock
                                                                        ? "_wall_banner"
                                                                        : "_banner")));
                        var dataBefore = panel.doc().blockEntityData(pos);
                        var changed =
                                SchematicBlocks.put(
                                        panel.doc(),
                                        pos,
                                        pos,
                                        next.withPropertiesOf(current),
                                        null);
                        panel.commit(SchematicEdits.blockData(changed, pos, dataBefore));
                    }
                });
        if (editing < 0 || editing >= patterns.size()) return;
        panel.button(
                "npships.banner.remove",
                () -> {
                    var changed = panel.doc().blockEntityData(pos);
                    var current = changed.getList("patterns", Tag.TAG_COMPOUND);
                    current.remove(
                            panel.currentRow(current, selectedPattern, selectedIndex, patterns));
                    editing = -1;
                    panel.commit(SchematicEdits.blockData(panel.doc(), pos, changed));
                });
        panel.button(
                "npships.banner.up",
                () -> {
                    var changed = panel.doc().blockEntityData(pos);
                    var list = changed.getList("patterns", Tag.TAG_COMPOUND);
                    editing = panel.currentRow(list, selectedPattern, selectedIndex, patterns);
                    if (editing == 0) return;
                    var entry = list.remove(editing);
                    list.add(--editing, entry);
                    panel.commit(SchematicEdits.blockData(panel.doc(), pos, changed));
                });
        if (!patternsOpen) return;
        var registry = panel.mc.level.registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
        int firstCell = panel.widgets.size();
        int columns = Math.max(1, (panel.contentWidth()) / 34), count = 0;
        for (var id : registry.keySet().stream().sorted().toList()) {
            var sample = patterns.copy();
            sample.getCompound(editing).putString("pattern", id.toString());
            int layer = editing;
            preview(
                    stack(banner.getColor(), sample),
                    panel.x + count % columns * 34,
                    panel.cursor + count / columns * 34,
                    32,
                    () -> {
                        var changed = panel.doc().blockEntityData(pos);
                        var current = changed.getList("patterns", Tag.TAG_COMPOUND);
                        editing =
                                panel.currentRow(current, selectedPattern, selectedIndex, patterns);
                        current.getCompound(editing).putString("pattern", id.toString());
                        panel.commit(SchematicEdits.blockData(panel.doc(), pos, changed));
                    },
                    Component.translatable(registry.get(id).translationKey()));
            count++;
        }
        if (count > 0)
            panel.flows.add(
                    new EditorToolsPanel.Flow(
                            java.util.List.copyOf(
                                    panel.widgets.subList(firstCell, panel.widgets.size())),
                            34,
                            false));
        panel.cursor += ((count + columns - 1) / columns) * 34;
    }

    private ItemStack stack(DyeColor color, ListTag patterns) {
        var result = new ItemStack(BannerBlock.byColor(color));
        BannerPatternLayers.CODEC
                .parse(
                        panel.mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                        patterns)
                .result()
                .ifPresent(value -> result.set(DataComponents.BANNER_PATTERNS, value));
        return result;
    }

    void colors(Consumer<DyeColor> action) {
        int firstCell = panel.widgets.size();
        int columns = Math.max(1, (panel.contentWidth()) / 22);
        for (var color : DyeColor.values()) {
            int i = color.ordinal();
            var b =
                    new EditorButton(
                            panel.x + i % columns * 22,
                            panel.cursor + i / columns * 22,
                            20,
                            20,
                            Component.empty(),
                            ignored -> panel.run(() -> action.accept(color)),
                            supplier -> supplier.get()) {
                        @Override
                        protected void renderWidget(GuiGraphics g, int mx, int my, float tick) {
                            super.renderWidget(g, mx, my, tick);
                            g.fill(
                                    getX() + 4,
                                    getY() + 4,
                                    getX() + 16,
                                    getY() + 16,
                                    0xff000000 | color.getTextureDiffuseColor());
                        }
                    };
            b.setTooltip(
                    Tooltip.create(Component.translatable("color.minecraft." + color.getName())));
            panel.widgets.add(b);
        }
        panel.flows.add(
                new EditorToolsPanel.Flow(
                        java.util.List.copyOf(
                                panel.widgets.subList(firstCell, panel.widgets.size())),
                        22,
                        false));
        panel.cursor += ((16 + columns - 1) / columns) * 22;
    }

    private void preview(ItemStack stack, int x, int y, int size, Runnable action, Component hint) {
        var b =
                new EditorButton(
                        x,
                        y,
                        size,
                        size,
                        Component.empty(),
                        ignored -> panel.run(action),
                        supplier -> supplier.get()) {
                    @Override
                    protected void renderWidget(GuiGraphics g, int mx, int my, float tick) {
                        super.renderWidget(g, mx, my, tick);
                        g.pose().pushPose();
                        g.pose().translate(getX() + 2, getY() + 2, 0);
                        float scale = (size - 4) / 16f;
                        g.pose().scale(scale, scale, scale);
                        g.renderItem(stack, 0, 0);
                        g.pose().popPose();
                    }
                };
        b.setTooltip(Tooltip.create(hint));
        panel.widgets.add(b);
    }
}
