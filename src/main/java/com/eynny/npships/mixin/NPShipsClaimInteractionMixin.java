package com.eynny.npships.mixin;

import com.eynny.npships.block.ClaimBlockEntity;
import com.eynny.npships.integration.NativeBlockAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;

/** Qualify our Claim interaction before vanilla selects the block through the Level cache. */
@Mixin(ServerPlayerGameMode.class)
public abstract class NPShipsClaimInteractionMixin {
    @WrapMethod(method = "useItemOn", require = 1)
    private InteractionResult npships$currentClaim(
            ServerPlayer player,
            Level world,
            ItemStack stack,
            InteractionHand hand,
            BlockHitResult hit,
            Operation<InteractionResult> original) {
        if (!(world instanceof ServerLevel level)
                || !(NativeBlockAccess.at(level, hit.getBlockPos()) instanceof ClaimBlockEntity))
            return original.call(player, world, stack, hand, hit);
        // Original permission/event/item/block routing still runs once. The native view expires
        // with this synchronous call, including exceptions and reentrant interactions.
        try (var read = NativeBlockAccess.capture(level, hit.getBlockPos())) {
            return original.call(player, world, stack, hand, hit);
        }
    }
}
