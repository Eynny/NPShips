package com.eynny.npships.integration.cannons;

import com.eynny.npships.api.patches.PatchBlockPlacement;
import com.eynny.npships.authoring.patches.SchematicResources;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Native magazine insertion expressed as the same ordinary provider-owned placement proposal. */
public final class CannonBlockPlacement implements PatchBlockPlacement {
    @Override
    public Set<ResourceLocation> blocks() {
        var result = new HashSet<ResourceLocation>();
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (id.getNamespace().equals("createbigcannons")
                    && block instanceof EntityBlock factory
                    && com.eynny.npships.integration.cannons.CannonResources.breech(
                            factory.newBlockEntity(BlockPos.ZERO, block.defaultBlockState())))
                result.add(id);
        }
        return Set.copyOf(result);
    }

    @Override
    public boolean applies(BlockState existing, BlockState requested) {
        return com.eynny.npships.integration.cannons.CannonResources.magazine(
                new ItemStack(requested.getBlock()));
    }

    @Override
    public BlockState combine(BlockState existing, BlockState requested) {
        return existing;
    }

    @Override
    public Set<String> dataFields() {
        return Set.of("Magazine", "LootTable", "LootTableSeed");
    }

    @Override
    public CompoundTag combineData(
            BlockState existing,
            BlockState requested,
            CompoundTag data,
            HolderLookup.Provider registries) {
        if (SchematicResources.unavailableItems(data))
            throw new IllegalArgumentException(
                    "Restore unavailable native items before replacing inventory data.");
        if (!ItemStack.parseOptional(registries, data.getCompound("Magazine")).isEmpty())
            throw com.eynny.npships.authoring.validation.ValidationProblem.invalid(
                    "magazine_existing", "Remove the existing magazine first.");
        data.put("Magazine", new ItemStack(requested.getBlock()).saveOptional(registries));
        data.remove("LootTable");
        data.remove("LootTableSeed");
        return data;
    }

    @Override
    public int inventorySelection() {
        return 1;
    }
}
