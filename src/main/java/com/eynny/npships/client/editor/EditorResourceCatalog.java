package com.eynny.npships.client.editor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

import java.util.*;

/** Registry and creative-tab variants, built once per editor rather than on each frame. */
final class EditorResourceCatalog {
    static List<ItemStack> items(
            Level level,
            com.eynny.npships.authoring.patches.PatchItemPresentations.Plan presentations) {
        CreativeModeTabs.tryRebuildTabContents(
                level.enabledFeatures(), true, level.registryAccess());
        var buckets = new HashMap<Integer, List<ItemStack>>();
        var result = new ArrayList<ItemStack>();
        java.util.function.Consumer<ItemStack> add =
                stack -> {
                    if (stack.isEmpty()) return;
                    var copy = stack.copyWithCount(1);
                    var bucket =
                            buckets.computeIfAbsent(
                                    ItemStack.hashItemAndComponents(copy), k -> new ArrayList<>());
                    if (bucket.stream()
                            .noneMatch(s -> ItemStack.isSameItemSameComponents(s, copy))) {
                        bucket.add(copy);
                        result.add(copy);
                    }
                };
        for (var item : BuiltInRegistries.ITEM) add.accept(item.getDefaultInstance());
        for (var tab : CreativeModeTabs.allTabs()) tab.getDisplayItems().forEach(add);
        presentations.variants().forEach(add);
        result.sort(
                Comparator.comparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));
        return List.copyOf(result);
    }

    static String description(
            ItemStack stack,
            com.eynny.npships.authoring.patches.PatchItemPresentations.Plan presentations) {
        return presentations.description(stack).getString();
    }
}
