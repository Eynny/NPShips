package com.eynny.npships.client;

import com.eynny.npships.NPShips;
import com.eynny.npships.block.SchematicEditorBlock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@EventBusSubscriber(modid = NPShips.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class NPShipsSettings {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        com.eynny.npships.network.InfoShipNetworking.client(
                InfoShipChat::layout, InfoShipChat::page);
        SchematicEditorBlock.setClientOpener(
                () -> {
                    Minecraft.getInstance()
                            .getSoundManager()
                            .play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1f));
                    EditorAccess.open();
                });
        ModList.get()
                .getModContainerById(NPShips.MOD_ID)
                .orElseThrow()
                .registerExtensionPoint(IConfigScreenFactory.class, RuntimeSettingsScreen::new);
    }

    @SubscribeEvent
    public static void keys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(EditorAccess.MENU);
        com.eynny.npships.client.editor.EditorShortcutDispatcher.register(event);
    }
}
