package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.SchematicPlacement;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

import java.util.*;

/** Includes distinct items sharing a block (for example a CC cable and a wired modem). */
final class EditorBlockCatalog {
    record Entry(String state, ItemStack icon, String id) {}

    private static List<Entry> entries;

    static List<Entry> entries(Level level) {
        if (entries != null) return entries;
        var result = new LinkedHashMap<String, Entry>();
        for (var block : BuiltInRegistries.BLOCK) {
            var state = BlockStateParser.serialize(SchematicPlacement.defaultState(block, level));
            result.put(
                    state,
                    new Entry(
                            state,
                            new ItemStack(block),
                            (block.asItem() == Items.AIR
                                            ? BuiltInRegistries.BLOCK.getKey(block)
                                            : BuiltInRegistries.ITEM.getKey(block.asItem()))
                                    .toString()));
        }
        for (var item : BuiltInRegistries.ITEM)
            if (item instanceof BlockItem blockItem && item != blockItem.getBlock().asItem())
                try {
                    var placed = SchematicPlacement.state(blockItem, level, Direction.UP);
                    if (placed == null) continue;
                    String state = BlockStateParser.serialize(placed);
                    result.put(
                            state,
                            new Entry(
                                    state,
                                    new ItemStack(item),
                                    BuiltInRegistries.ITEM.getKey(item).toString()));
                } catch (RuntimeException ignored) {
                } // Some specialised items require a live player; don't simulate their use.
        entries = List.copyOf(result.values());
        return entries;
    }

    static Entry find(
            String state,
            Level level,
            com.eynny.npships.authoring.patches.PatchItemPresentations.Plan presentations) {
        var all = entries(level);
        var exact = all.stream().filter(e -> e.state().equals(state)).findFirst();
        if (exact.isPresent()) return exact.get();
        try {
            var parsed =
                    BlockStateParser.parseForBlock(
                                    level.holderLookup(
                                            net.minecraft.core.registries.Registries.BLOCK),
                                    state,
                                    false)
                            .blockState();
            var icon = presentations.blockIcon(parsed);
            if (!icon.isEmpty()) {
                String item = BuiltInRegistries.ITEM.getKey(icon.getItem()).toString();
                return all.stream()
                        .filter(e -> e.id().equals(item))
                        .findFirst()
                        .orElse(new Entry(state, icon, item));
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
            return null;
        }
        return null;
    }
}
