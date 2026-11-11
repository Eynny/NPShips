package com.eynny.npships.network;

import com.eynny.npships.NPShips;
import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Server-only setting; clients never infer permission from a local config file. */
@EventBusSubscriber(modid = NPShips.MOD_ID)
public final class DevelopmentModeNetworking {
    private static volatile boolean clientEnabled;

    private DevelopmentModeNetworking() {}

    public static boolean clientEnabled() {
        return clientEnabled;
    }

    public static void clearClient() {
        clientEnabled = false;
    }

    static void receive(State state) {
        clientEnabled = state.enabled();
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(
                        State.TYPE,
                        State.CODEC,
                        (state, context) -> {
                            if (context.connection().isConnected()) receive(state);
                        });
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            PacketDistributor.sendToPlayer(player, current());
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != NPShipsRuntimeConfig.SPEC) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.execute(() -> refresh(server));
    }

    /** Rebuild native suggestions after the authoritative visibility condition changes. */
    public static void refresh(net.minecraft.server.MinecraftServer server) {
        PacketDistributor.sendToAllPlayers(current());
        for (var player : server.getPlayerList().getPlayers())
            server.getCommands().sendCommands(player);
    }

    private static State current() {
        return new State(NPShipsRuntimeConfig.developerModeEnabled());
    }

    public record State(boolean enabled) implements CustomPacketPayload {
        public static final Type<State> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(NPShips.MOD_ID, "development_mode"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC =
                StreamCodec.of(
                        (buf, state) -> buf.writeBoolean(state.enabled()),
                        buf -> new State(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
