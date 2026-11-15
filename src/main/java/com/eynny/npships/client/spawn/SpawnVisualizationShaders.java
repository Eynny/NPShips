package com.eynny.npships.client.spawn;

import com.eynny.npships.network.SpawnVisualizationNetworking;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

@EventBusSubscriber(modid = "npships", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class SpawnVisualizationShaders {
    static ShaderInstance fields;

    private SpawnVisualizationShaders() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(
                () -> SpawnVisualizationNetworking.client(SpawnVisualizationClient::receive));
    }

    @SubscribeEvent
    public static void shaders(RegisterShadersEvent event) throws IOException {
        SpawnVisualizationRenderer.releaseDepth();
        event.registerShader(
                new ShaderInstance(
                        event.getResourceProvider(),
                        ResourceLocation.parse("npships:rarity_field"),
                        DefaultVertexFormat.POSITION),
                shader -> fields = shader);
    }
}
