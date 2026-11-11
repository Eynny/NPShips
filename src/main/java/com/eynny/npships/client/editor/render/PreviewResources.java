package com.eynny.npships.client.editor.render;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Reload generation only: never holds a screen, preview level, model or world. */
@EventBusSubscriber(modid = "npships", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PreviewResources {
    private static volatile long epoch;

    private PreviewResources() {}

    public static long epoch() {
        return epoch;
    }

    @SubscribeEvent
    public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (barrier, manager, preparation, application, background, game) -> {
                    // Native reload invokes listeners before starting their asynchronous
                    // preparation.
                    // Invalidate even a failed reload, which may have partially replaced native
                    // resources.
                    com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThreadOrInit();
                    PreviewRenderType.clearResources();
                    GhostRenderType.clearResources();
                    epoch++;
                    return barrier.wait(null)
                            .thenRunAsync(
                                    () -> {
                                        PreviewRenderType.clearResources();
                                        GhostRenderType.clearResources();
                                        epoch++;
                                    },
                                    game);
                });
    }
}
