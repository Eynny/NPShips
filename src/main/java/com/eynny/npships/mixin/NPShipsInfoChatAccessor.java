package com.eynny.npships.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Remove only this client's exact previous info-page objects; retain all unrelated chat/history.
 */
@Mixin(ChatComponent.class)
public interface NPShipsInfoChatAccessor {
    @Accessor("allMessages")
    List<GuiMessage> npships$messages();

    @Invoker("refreshTrimmedMessages")
    void npships$refresh();
}
