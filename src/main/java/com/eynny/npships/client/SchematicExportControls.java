package com.eynny.npships.client;

import com.eynny.npships.NPShips;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.network.DevelopmentModeNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Map;
import java.util.WeakHashMap;

/** Entry buttons for the schematic editor. */
@EventBusSubscriber(modid = NPShips.MOD_ID, value = Dist.CLIENT)
public final class SchematicExportControls {
    private static final Map<Screen, AbstractWidget> BUTTONS = new WeakHashMap<>();

    private SchematicExportControls() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof PauseScreen)
                && !screen.getClass().getSimpleName().equals("SchematicPromptScreen")) return;
        var button =
                EditorButton.flat(
                                Component.translatable("npships.editor.open"),
                                b -> EditorAccess.open())
                        .bounds(6, screen.height - 26, 150, 20)
                        .build();
        button.visible =
                button.active =
                        Minecraft.getInstance().level != null
                                && DevelopmentModeNetworking.clientEnabled();
        BUTTONS.put(screen, button);
        event.addListener(button);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        var button = BUTTONS.get(event.getScreen());
        if (button != null)
            button.visible =
                    button.active =
                            Minecraft.getInstance().level != null
                                    && DevelopmentModeNetworking.clientEnabled();
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        DevelopmentModeNetworking.clearClient();
        com.eynny.npships.network.LootTableNetworking.clearClient();
        com.eynny.npships.network.WeatherNetworking.clearClient();
        BUTTONS.clear();
    }
}
