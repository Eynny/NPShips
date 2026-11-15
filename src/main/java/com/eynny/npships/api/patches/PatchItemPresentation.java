package com.eynny.npships.api.patches;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Optional detached item presentation in the ordinary editor resource catalogue. Register through
 * the same provider capability method. No screen, document, world or mutable native machine is
 * supplied. The host owns deduplication, input/focus and accepted item edits. These callbacks run
 * during explicit catalogue/row rebuilds, never as a global tick or per-frame registry scan.
 */
public interface PatchItemPresentation {
    /**
     * Supplies complete component-bearing variants absent from ordinary default/creative items.
     * Returned stacks are copied; the provider must not retain host state or mutate registries.
     *
     * @return detached item variants, at most 8192 for one provider
     */
    default List<ItemStack> variants() {
        return List.of();
    }

    /**
     * Describes a privately copied item. At most one enabled provider may replace its label.
     *
     * @param stack detached item value, never a mutable inventory slot
     * @return an optional full display label; empty if the provider does not own presentation
     */
    default Optional<Component> description(ItemStack stack) {
        return Optional.empty();
    }

    /**
     * Supplies an ordinary block catalogue icon where several placement items share one block. This
     * is presentation only; it does not change placement state or item contents in a world.
     *
     * @param state immutable accepted native state
     * @return detached nonempty icon, or empty when this provider does not own that presentation
     */
    default Optional<ItemStack> blockIcon(net.minecraft.world.level.block.state.BlockState state) {
        return Optional.empty();
    }
}
