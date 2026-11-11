package com.eynny.npships.integration.cannons;

import static com.eynny.npships.authoring.patches.SchematicResources.*;

import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;

/** Native CBC resource grammar and detached inventory operations, used by its registered code. */
public final class CannonResources {
    private static final String CBC = "rbasamoyai.createbigcannons.";

    private CannonResources() {}

    public static boolean ammoBox(BlockEntity be) {
        return instance(
                be, CBC + "munitions.autocannon.ammo_container.IAutocannonAmmoContainerContainer");
    }

    public static boolean breech(BlockEntity be) {
        return instance(be, CBC + "cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity");
    }

    public static boolean magazine(ItemStack stack) {
        return instance(
                stack.getItem(),
                CBC + "munitions.autocannon.ammo_container.AutocannonAmmoContainerItem");
    }

    public static boolean ammunition(ItemStack stack) {
        if (!instance(stack.getItem(), CBC + "munitions.autocannon.AutocannonAmmoItem"))
            return false;
        if (instance(stack.getItem(), CBC + "munitions.autocannon.AutocannonCartridgeItem"))
            return (boolean) invoke(stack.getItem().getClass(), "hasProjectile", stack);
        return true;
    }

    public static List<ItemStack> ammunitionVariants() {
        var result = new ArrayList<ItemStack>();
        for (var item : BuiltInRegistries.ITEM) {
            var stack = item.getDefaultInstance();
            if (ammunition(stack)) result.add(stack);
            if (instance(item, CBC + "munitions.autocannon.AutocannonRoundItem")) {
                var cartridge = (ItemStack) invoke(item, "getCreativeTabCartridgeItem");
                if (ammunition(cartridge)) result.add(cartridge);
            }
        }
        return result;
    }

    public static SchematicDocument installBox(
            SchematicDocument doc, BlockPos box, BlockPos breech, Level context) {
        var source = detached(doc, box, context.registryAccess());
        var target = open(doc, breech, context);
        if (!ammoBox(source) || !target.kind().equals("breech") || !target.get(0).isEmpty())
            throw new IllegalArgumentException("Select an ammo box and an empty breech.");
        var stack = new ItemStack(source.getBlockState().getBlock());
        source.saveToItem(stack, context.registryAccess());
        var next =
                com.eynny.npships.authoring.document.SchematicExtensions.intermediate(
                        () ->
                                SchematicBlocks.put(
                                        target.put(0, stack),
                                        box,
                                        box,
                                        net.minecraft.world.level.block.Blocks.AIR
                                                .defaultBlockState(),
                                        null));
        return com.eynny.npships.authoring.document.SchematicExtensions.apply(
                doc,
                next,
                com.eynny.npships.authoring.document.SchematicExtensions.operation(
                        com.eynny.npships.api.patches.PatchAuthoring.Kind.EDIT,
                        p -> p.equals(box) ? java.util.List.of() : java.util.List.of(p),
                        p -> java.util.List.of(p),
                        java.util.function.UnaryOperator.identity(),
                        java.util.Optional::of));
    }
}
