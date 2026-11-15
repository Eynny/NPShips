package com.eynny.npships.network;

import com.eynny.npships.NPShips;
import com.eynny.npships.commands.RefreshShipCommand;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/**
 * One refresh path for the command and UI. Notifications invalidate lists, never open documents.
 */
public final class CatalogRefreshNetworking {
    public static void saved() {
        clientRevision++;
        // Working files belong to this client. Only the integrated server shares this folder.
        var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (server != null)
            server.execute(
                    () ->
                            RefreshShipCommand.execute(
                                    server.createCommandSourceStack().withSuppressedOutput()));
    }

    private static long clientRevision;

    private CatalogRefreshNetworking() {}

    public static long clientRevision() {
        return clientRevision;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar
                .executesOn(HandlerThread.NETWORK)
                .playToServer(
                        Request.TYPE,
                        Request.CODEC,
                        (request, context) ->
                                NetworkRequestGate.submit(
                                        context,
                                        NetworkRequestGate.Kind.CATALOGUE_REFRESH,
                                        () -> {
                                            if (!context.player().hasPermissions(2)) return;
                                            RefreshShipCommand.execute(
                                                    context.player()
                                                            .createCommandSourceStack()
                                                            .withSuppressedOutput());
                                        }));
        registrar
                .executesOn(HandlerThread.MAIN)
                .playToClient(
                        Changed.TYPE,
                        Changed.CODEC,
                        (changed, context) -> {
                            if (context.connection().isConnected()) clientRevision++;
                        });
    }

    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(NPShips.MOD_ID, "catalog_refresh"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
                StreamCodec.unit(new Request());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Changed() implements CustomPacketPayload {
        public static final Type<Changed> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(NPShips.MOD_ID, "catalog_changed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Changed> CODEC =
                StreamCodec.unit(new Changed());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
