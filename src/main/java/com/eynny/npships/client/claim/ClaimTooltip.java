package com.eynny.npships.client.claim;

import com.eynny.npships.registry.NPShipsRegistry;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = "npships", value = Dist.CLIENT)
public final class ClaimTooltip {
    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        if (!event.getItemStack().is(NPShipsRegistry.CLAIM_BLOCK_ITEM.get())) return;
        if (!Screen.hasShiftDown()) {
            event.getToolTip()
                    .add(
                            Component.translatable("npships.claim.hold_shift")
                                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        for (String key : new String[] {"purpose", "attachment", "status", "repeat"})
            event.getToolTip()
                    .add(
                            Component.translatable("npships.claim.tooltip." + key)
                                    .withStyle(ChatFormatting.GRAY));
    }
}
