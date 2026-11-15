package com.eynny.npships.client.editor;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.document.SchematicEdits;
import com.eynny.npships.client.editor.render.SchematicPreviewLevel;
import com.eynny.npships.client.editor.widget.EditorPopupMenu;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** CLIENT native form modules; factories hold no panel, document, level, widget or resource. */
public final class BuiltinBlockInspectors {
    private BuiltinBlockInspectors() {}

    public static <T> T capability(String provider, Class<T> contract) {
        if (contract == PatchNativeRenderStyle.class && provider.equals("simulated_devices"))
            return contract.cast(
                    new PatchNativeRenderStyle() {
                        public org.joml.Vector4f color(
                                BlockEntity target, org.joml.Vector4f original) {
                            var data = target.getPersistentData();
                            if (!data.contains("npships_laser_color")) return original;
                            int color = data.getInt("npships_laser_color");
                            return new org.joml.Vector4f(
                                    ((color >> 16) & 255) / 255f,
                                    ((color >> 8) & 255) / 255f,
                                    (color & 255) / 255f,
                                    original.w);
                        }

                        public boolean hidden(BlockEntity target) {
                            return target.getPersistentData().getBoolean("npships_hide_laser");
                        }
                    });
        if (contract == PatchBlockAppearance.class && provider.equals("create_devices"))
            return contract.cast(
                    new PatchBlockAppearance() {
                        public boolean applies(
                                BlockState state, net.minecraft.core.BlockPos position) {
                            return state.getBlock()
                                            instanceof
                                            net.minecraft.world.level.block.EntityBlock factory
                                    && factory.newBlockEntity(position, state)
                                            instanceof
                                            com.simibubi.create.content.decoration.copycat
                                                    .CopycatBlockEntity;
                        }

                        public CompoundTag validate(
                                com.eynny.npships.authoring.document.SchematicDocument document,
                                net.minecraft.core.BlockPos position,
                                BlockState state,
                                BlockState material,
                                net.minecraft.world.level.Level level) {
                            if (material.isAir()
                                    || material.getBlock().asItem()
                                            == net.minecraft.world.item.Items.AIR)
                                throw invalid(
                                        "copycat_item", "Choose a material with a block item.");
                            if (state.getBlock()
                                    instanceof
                                    com.simibubi.create.content.decoration.copycat.CopycatBlock
                                                    copycat) {
                                var accepted =
                                        copycat.getAcceptedBlockState(
                                                new SchematicPreviewLevel(document, level),
                                                position,
                                                new ItemStack(material.getBlock()),
                                                null);
                                if (accepted == null || !accepted.is(material.getBlock()))
                                    throw invalid(
                                            "copycat_material",
                                            "This block cannot be used as the copycat material.");
                            }
                            return NbtUtils.writeBlockState(material);
                        }
                    });
        if (contract != BlockInspectorContribution.class) return null;
        var resources = BuiltinResourceInspectors.capability(provider, contract);
        if (resources != null) return resources;
        if (provider.equals("create_devices"))
            return contract.cast(
                    new BlockInspectorContribution() {
                        public Slot slot() {
                            return Slot.BEFORE_DATA;
                        }

                        public boolean applies(BlockState state, BlockEntity detached) {
                            return state.getBlock()
                                    instanceof
                                    com.simibubi.create.content.decoration.copycat.CopycatBlock;
                        }

                        public void build(
                                EditorToolsPanel panel,
                                BlockState actual,
                                CompoundTag saved,
                                String[] material) {
                            var target = panel.pos().immutable();
                            panel.sections.begin("appearance.material", "material", true);
                            var savedMaterial =
                                    panel.cached
                                            .blockEntityData(panel.pos())
                                            .getCompound("Material");
                            if (!savedMaterial.isEmpty())
                                material[0] =
                                        BlockStateParser.serialize(
                                                NbtUtils.readBlockState(
                                                        panel.mc.level.holderLookup(
                                                                Registries.BLOCK),
                                                        savedMaterial));
                            panel.field("material", material[0], v -> material[0] = v);
                            String initialMaterial = material[0];
                            panel.defer(
                                    () -> {
                                        if (material[0].equals(initialMaterial)) return;
                                        var current = panel.currentBlock(target, actual.getBlock());
                                        var copycat =
                                                (com.simibubi.create.content.decoration.copycat
                                                                .CopycatBlock)
                                                        current.getBlock();
                                        var value = panel.parse(material[0]);
                                        var virtual =
                                                new SchematicPreviewLevel(
                                                        panel.doc(), panel.mc.level);
                                        var accepted =
                                                copycat.getAcceptedBlockState(
                                                        virtual,
                                                        panel.pos(),
                                                        new ItemStack(value.getBlock()),
                                                        null);
                                        if (accepted == null || !accepted.is(value.getBlock()))
                                            throw invalid(
                                                    "copycat_material",
                                                    "This material is not supported by the selected copycat.",
                                                    material[0]);
                                        panel.commit(
                                                SchematicBlocks.put(
                                                        panel.doc(),
                                                        target,
                                                        target,
                                                        current,
                                                        NbtUtils.writeBlockState(value)));
                                    });
                            panel.sections.end();
                        }
                    });
        if (provider.equals("simulated_devices"))
            return contract.cast(
                    new BlockInspectorContribution() {
                        public Slot slot() {
                            return Slot.AFTER_DATA;
                        }

                        public boolean applies(BlockState state, BlockEntity detached) {
                            return detached
                                    instanceof
                                    dev.simulated_team.simulated.content.blocks.lasers
                                            .AbstractLaserBlockEntity;
                        }

                        public void build(
                                EditorToolsPanel panel,
                                BlockState state,
                                CompoundTag saved,
                                String[] draft) {
                            panel.sections.begin("appearance.laser", "section_laser", false);
                            var target = panel.pos().immutable();
                            String targetBlock = panel.doc().blockName(target);
                            int menuY = panel.cursor;
                            panel.button(
                                    panel.tr("laser_color").getString(),
                                    () -> {
                                        panel.currentBlock(target, state.getBlock());
                                        var entries =
                                                new java.util.ArrayList<EditorPopupMenu.Entry>();
                                        for (var color : net.minecraft.world.item.DyeColor.values())
                                            entries.add(
                                                    new EditorPopupMenu.Entry(
                                                            Component.translatable(
                                                                    "color.minecraft."
                                                                            + color.getName()),
                                                            () ->
                                                                    panel.doc()
                                                                                    .blockEntityData(
                                                                                            panel
                                                                                                    .pos())
                                                                                    .getCompound(
                                                                                            "NeoForgeData")
                                                                                    .getInt(
                                                                                            "npships_laser_color")
                                                                            == color.getTextColor(),
                                                            () -> {
                                                                panel.currentBlock(
                                                                        target, state.getBlock());
                                                                var data =
                                                                        panel.doc()
                                                                                .blockEntityData(
                                                                                        panel
                                                                                                .pos());
                                                                var custom =
                                                                        data.getCompound(
                                                                                "NeoForgeData");
                                                                custom.putInt(
                                                                        "npships_laser_color",
                                                                        color.getTextColor());
                                                                data.put("NeoForgeData", custom);
                                                                panel.commit(
                                                                        SchematicEdits.blockData(
                                                                                panel.doc(),
                                                                                panel.pos(),
                                                                                data));
                                                            }));
                                        panel.host.toolMenu(
                                                panel.x, menuY - panel.scroll, panel.w, entries);
                                    });
                            panel.button(
                                            panel.tr("laser_hidden").getString(),
                                            () -> {
                                                panel.currentBlock(target, state.getBlock());
                                                var data = panel.doc().blockEntityData(target);
                                                var custom = data.getCompound("NeoForgeData");
                                                custom.putBoolean(
                                                        "npships_hide_laser",
                                                        !custom.getBoolean("npships_hide_laser"));
                                                data.put("NeoForgeData", custom);
                                                panel.commit(
                                                        SchematicEdits.blockData(
                                                                panel.doc(), panel.pos(), data));
                                            })
                                    .selected(
                                            () ->
                                                    java.util.Objects.equals(panel.pos(), target)
                                                            && panel.doc()
                                                                    .blockName(target)
                                                                    .equals(targetBlock)
                                                            && panel.doc()
                                                                    .blockEntityBoolean(
                                                                            target,
                                                                            "NeoForgeData",
                                                                            "npships_hide_laser"))
                                    .setTooltip(Tooltip.create(panel.tr("laser_hidden_hint")));
                            panel.sections.end();
                        }
                    });
        return null;
    }
}
