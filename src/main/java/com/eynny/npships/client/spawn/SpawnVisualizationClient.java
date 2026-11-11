package com.eynny.npships.client.spawn;

import com.eynny.npships.diagnostics.SpawnVisualizationView;
import com.eynny.npships.network.SpawnVisualizationNetworking.State;
import com.eynny.npships.network.SpawnVisualizationNetworking.World;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * A fresh nonce for every connection/world object prevents old packets reviving cleared overlays.
 */
@EventBusSubscriber(modid = "npships", value = Dist.CLIENT)
public final class SpawnVisualizationClient {
    static final SpawnVisualizationView VIEW = new SpawnVisualizationView();
    static ClientLevel owner;
    private static ClientPacketListener connection;
    private static long generation;
    static long tick;

    private SpawnVisualizationClient() {}

    public static void receive(State state) {
        var mc = Minecraft.getInstance();
        if (owner != null && mc.level == owner && mc.getConnection() == connection)
            VIEW.accept(state, tick, owner.getGameTime());
        if (!VIEW.enabled()) releaseDepth();
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        tick++;
        if (owner == mc.level && connection == mc.getConnection()) return;
        releaseDepth();
        owner = mc.level;
        connection = mc.getConnection();
        VIEW.bind(
                owner == null ? null : UUID.randomUUID(),
                owner == null ? null : owner.dimension().location());
        if (owner != null && connection != null)
            PacketDistributor.sendToServer(
                    new World(VIEW.world(), ++generation, owner.dimension().location()));
    }

    private static void releaseDepth() {
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread())
            SpawnVisualizationRenderer.releaseDepth();
        else
            com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(
                    SpawnVisualizationRenderer::releaseDepth);
    }

    private static void clear() {
        releaseDepth();
        VIEW.bind(null, null);
        owner = null;
        connection = null;
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void respawn(ClientPlayerNetworkEvent.Clone event) {
        clear();
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() == owner) clear();
    }
}
