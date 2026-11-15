package com.eynny.npships.integration.computercraft;

import com.eynny.npships.api.patches.PatchBlockPlacement;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/** Existing native CC cable/modem combination, exported by its registered peripheral owner. */
public final class ComputerBlockPlacement
        implements PatchBlockPlacement, com.eynny.npships.api.patches.PatchItemPresentation {
    @Override
    public Set<ResourceLocation> blocks() {
        return Set.of(ResourceLocation.parse("computercraft:cable"));
    }

    @Override
    public boolean applies(BlockState existing, BlockState requested) {
        return ComputerCables.addCable(existing, requested) != null;
    }

    @Override
    public BlockState combine(BlockState existing, BlockState requested) {
        var result = ComputerCables.addCable(existing, requested);
        if (result == null)
            throw new IllegalArgumentException("Cable/modem states are not compatible");
        return result;
    }

    @Override
    public java.util.Optional<net.minecraft.world.item.ItemStack> blockIcon(BlockState state) {
        var encoded = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state);
        if (!encoded.startsWith("computercraft:cable[")) return java.util.Optional.empty();
        return java.util.Optional.of(
                new net.minecraft.world.item.ItemStack(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                ResourceLocation.parse(
                                        encoded.contains("modem=none")
                                                ? "computercraft:cable"
                                                : "computercraft:wired_modem"))));
    }
}
