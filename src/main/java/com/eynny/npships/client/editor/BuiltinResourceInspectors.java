package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.patches.SchematicResources;
import com.eynny.npships.integration.radars.RadarFilters;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Native resource controls, installed only through their registered CLIENT provider. */
public final class BuiltinResourceInspectors {
    private final EditorToolsPanel panel;

    private BuiltinResourceInspectors(EditorToolsPanel panel) {
        this.panel = panel;
    }

    public static <T> T capability(String provider, Class<T> contract) {
        if (contract != BlockInspectorContribution.class
                || !Set.of("cbc_default_connections", "radars_network").contains(provider))
            return null;
        return contract.cast(
                new BlockInspectorContribution() {
                    public Slot slot() {
                        return Slot.RESOURCE_SETTINGS;
                    }

                    public boolean applies(BlockState state, BlockEntity detached) {
                        return provider.equals("cbc_default_connections")
                                ? com.eynny.npships.integration.cannons.CannonResources.ammoBox(
                                                detached)
                                        || com.eynny.npships.integration.cannons.CannonResources
                                                .breech(detached)
                                : BuiltInRegistries.BLOCK
                                        .getKey(state.getBlock())
                                        .toString()
                                        .equals("create_radar:network_filterer");
                    }

                    public void build(
                            EditorToolsPanel panel,
                            BlockState state,
                            CompoundTag saved,
                            String[] draft) {
                        var controls = new BuiltinResourceInspectors(panel);
                        if (provider.equals("cbc_default_connections"))
                            controls.ammunitionControls();
                        else
                            panel.sections.group(
                                    "filters",
                                    "section_filters",
                                    panel.tool != EditorToolsPanel.Tool.BLOCKS,
                                    controls::filterSettings);
                    }
                });
    }

    private void ammunitionControls() {
        boolean tracer =
                panel.resourceInventory.kind().equals("ammo")
                        || panel.resourceInventory.kind().equals("breech")
                                && panel.resourceInventory.size() > 1;
        var magazines = new ArrayList<BlockPos>();
        if (panel.resourceInventory.kind().equals("breech")
                && panel.resourceInventory.get(0).isEmpty())
            for (var direction : net.minecraft.core.Direction.values()) {
                var box = panel.pos().relative(direction);
                if (com.eynny.npships.integration.cannons.CannonResources.ammoBox(
                        SchematicResources.detached(
                                panel.cached, box, panel.mc.level.registryAccess())))
                    magazines.add(box);
            }
        boolean grouped = tracer || magazines.size() > 1;
        if (grouped) panel.sections.begin("ammunition", "section_ammunition", true);
        if (tracer) {
            int original = panel.resourceInventory.spacing();
            String[] spacing = {"" + original};
            panel.field("tracer_spacing", spacing[0], v -> spacing[0] = v);
            panel.defer(
                    () -> {
                        if (Integer.parseInt(spacing[0]) != original)
                            panel.commit(
                                    SchematicResources.open(
                                                    panel.doc(), panel.pos(), panel.mc.level)
                                            .spacing(Integer.parseInt(spacing[0])));
                    });
        }
        for (var box : magazines)
            panel.button(
                    panel.tr("install_magazine").getString() + " " + box.toShortString(),
                    () ->
                            panel.commit(
                                    com.eynny.npships.integration.cannons.CannonResources
                                            .installBox(
                                                    panel.doc(),
                                                    box,
                                                    panel.pos(),
                                                    panel.mc.level)));
        if (grouped) panel.sections.end();
    }

    void filterSettings() {
        int slot = panel.resourceSlot;
        var initial = RadarFilters.read(panel.resourceInventory.get(slot), slot);
        var changed = new LinkedHashMap<String, String>();
        if (slot == 1) {
            for (String key : List.of("names", "label")) {
                String value =
                        key.equals("names")
                                ? initial.getList(key, Tag.TAG_STRING).stream()
                                        .map(Tag::getAsString)
                                        .collect(java.util.stream.Collectors.joining(", "))
                                : initial.getString(key);
                panel.field("filter_" + key, value, v -> changed.put(key, v));
            }
            panel.defer(
                    () -> {
                        if (changed.isEmpty()) return;
                        var inv = SchematicResources.open(panel.doc(), panel.pos(), panel.mc.level);
                        var settings = RadarFilters.read(inv.get(slot), slot);
                        changed.forEach(
                                (key, value) -> {
                                    if (key.equals("names")) {
                                        var names = new ListTag();
                                        for (String name : value.split(","))
                                            if (!name.isBlank())
                                                names.add(StringTag.valueOf(name.trim()));
                                        settings.put(key, names);
                                    } else settings.putString(key, value);
                                });
                        panel.commit(
                                RadarFilters.set(
                                        panel.doc(), panel.pos(), slot, settings, panel.mc.level));
                    });
        } else
            for (String key :
                    slot == 0
                            ? List.of(
                                    "player",
                                    "sable",
                                    "contraption",
                                    "mob",
                                    "projectile",
                                    "animal",
                                    "item")
                            : List.of(
                                    "player",
                                    "contraption",
                                    "mob",
                                    "animal",
                                    "projectile",
                                    "autoTarget",
                                    "lineSight"))
                panel.button(
                                panel.tr("filter_" + key).getString()
                                        + ": "
                                        + initial.getBoolean(key),
                                () -> {
                                    var settings =
                                            RadarFilters.read(
                                                    SchematicResources.open(
                                                                    panel.doc(),
                                                                    panel.pos(),
                                                                    panel.mc.level)
                                                            .get(slot),
                                                    slot);
                                    settings.putBoolean(key, !settings.getBoolean(key));
                                    panel.commit(
                                            RadarFilters.set(
                                                    panel.doc(),
                                                    panel.pos(),
                                                    slot,
                                                    settings,
                                                    panel.mc.level));
                                })
                        .selected(() -> initial.getBoolean(key));
    }
}
