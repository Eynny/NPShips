package com.eynny.npships.integration.cannons;

import com.eynny.npships.api.patches.PatchItemPresentation;
import com.eynny.npships.authoring.patches.SchematicResources;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Complete native cartridges, including registered addon rounds; no synthetic native data. */
public final class CannonItemPresentation implements PatchItemPresentation {
    @Override
    public List<ItemStack> variants() {
        return com.eynny.npships.integration.cannons.CannonResources.ammunitionVariants();
    }

    @Override
    public Optional<Component> description(ItemStack stack) {
        if (!SchematicResources.instance(
                stack.getItem(),
                "rbasamoyai.createbigcannons.munitions.autocannon.AutocannonCartridgeItem"))
            return Optional.empty();
        var projectile =
                (ItemStack)
                        SchematicResources.invoke(
                                stack.getItem().getClass(), "getProjectileStack", stack);
        String text = stack.getHoverName().getString();
        if (!projectile.isEmpty())
            text +=
                    " — "
                            + projectile.getHoverName().getString()
                            + " ["
                            + BuiltInRegistries.ITEM.getKey(projectile.getItem())
                            + "]";
        return Optional.of(Component.literal(text));
    }
}
