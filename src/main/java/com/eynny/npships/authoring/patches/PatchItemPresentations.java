package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.*;
import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Ordinary editor catalogue projection. Presentation never mutates source or inventory data. */
public final class PatchItemPresentations {
    private PatchItemPresentations() {}

    public static Plan capture() {
        var policy = NPShipsRuntimeConfig.patchPolicy();
        var code = PatchTypes.capabilities(PatchItemPresentation.class);
        var active = new TreeSet<net.minecraft.resources.ResourceLocation>();
        for (var id : code.keySet()) if (policy.enabled(id)) active.add(id);
        PatchCode.validate(policy, active);
        return new Plan(
                PatchOrdering.discoveryOrder(PatchStage.AUTHOR_PREPARATION, active, policy).stream()
                        .map(id -> Map.entry(id, code.get(id)))
                        .toList());
    }

    /** One editor's immutable code/policy snapshot, without a retained level or document. */
    public static final class Plan {
        private final List<
                        Map.Entry<net.minecraft.resources.ResourceLocation, PatchItemPresentation>>
                code;

        private Plan(
                List<Map.Entry<net.minecraft.resources.ResourceLocation, PatchItemPresentation>>
                        code) {
            this.code = List.copyOf(code);
        }

        public List<ItemStack> variants() {
            var result = new ArrayList<ItemStack>();
            for (var code : this.code) {
                try {
                    var variants = code.getValue().variants();
                    if (variants == null || variants.size() > 8192)
                        throw new IllegalArgumentException("Too many provider item variants");
                    for (var stack : variants) result.add(Objects.requireNonNull(stack).copy());
                } catch (RuntimeException | LinkageError error) {
                    throw new IllegalArgumentException(
                            "Patch "
                                    + code.getKey()
                                    + " item catalogue refused: "
                                    + error.getMessage(),
                            error);
                }
            }
            return List.copyOf(result);
        }

        public ItemStack blockIcon(net.minecraft.world.level.block.state.BlockState state) {
            ItemStack result = ItemStack.EMPTY;
            for (var owner : code)
                try {
                    var icon = Objects.requireNonNull(owner.getValue().blockIcon(state));
                    if (icon.isEmpty()) continue;
                    var value = Objects.requireNonNull(icon.get());
                    if (value.isEmpty())
                        throw new IllegalArgumentException("Empty declared block icon");
                    if (!result.isEmpty())
                        throw new IllegalArgumentException("Competing block icon presentations");
                    result = value.copy();
                } catch (RuntimeException | LinkageError failure) {
                    throw new IllegalArgumentException(
                            "Patch "
                                    + owner.getKey()
                                    + " block icon refused: "
                                    + failure.getMessage(),
                            failure);
                }
            return result;
        }

        public Component description(ItemStack stack) {
            Component result = null;
            for (var code : this.code) {
                try {
                    var description = code.getValue().description(stack.copy());
                    if (description.isPresent()) {
                        if (result != null)
                            throw new IllegalArgumentException("Competing item presentations");
                        result = description.orElseThrow().copy();
                    }
                } catch (RuntimeException | LinkageError error) {
                    throw new IllegalArgumentException(
                            "Patch " + code.getKey() + " item label refused: " + error.getMessage(),
                            error);
                }
            }
            return result == null ? stack.getHoverName() : result;
        }
    }
}
