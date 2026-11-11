package com.eynny.npships.client;

import com.eynny.npships.client.editor.screen.SchematicBrowserScreen;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Entry points for the detached editor; no world selection or placement state. */
@EventBusSubscriber(modid = "npships", value = Dist.CLIENT)
public final class EditorAccess {
    public static final KeyMapping MENU =
            new KeyMapping(
                    "npships.editor.key.menu",
                    org.lwjgl.glfw.GLFW.GLFW_KEY_O,
                    "npships.editor.title");

    public static void open() {
        var mc = Minecraft.getInstance();
        if (mc.level != null) mc.setScreen(new SchematicBrowserScreen(mc.screen));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        while (MENU.consumeClick())
            if (com.eynny.npships.network.DevelopmentModeNetworking.clientEnabled()) open();
    }
}
