package com.eynny.npships.network;

import com.eynny.npships.config.NPShipsRuntimeConfig;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.UUID;
import java.util.function.Consumer;

/** Only effect identity and timing cross the network; geometry stays on the observing client. */
public final class ClaimNetworking {
    public static final int PREPARE = 0, IMPACT = 1, CANCEL = 2, CANCEL_ALL = 3;
    private static Consumer<Wave> client = packet -> {};

    private ClaimNetworking() {}

    public static void client(Consumer<Wave> handler) {
        client = handler;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(
                        Wave.TYPE,
                        Wave.CODEC,
                        (packet, context) -> {
                            if (context.connection().isConnected()) client.accept(packet);
                        });
    }

    public static void send(ServerSubLevel body, BlockPos origin, int action) {
        broadcast(body.getLevel(), body.getUniqueId(), origin, action);
    }

    public static void cancel(ServerLevel level, UUID body, BlockPos origin) {
        broadcast(level, body, origin, CANCEL);
    }

    private static void broadcast(ServerLevel level, UUID body, BlockPos origin, int action) {
        if ((action == PREPARE || action == IMPACT) && !NPShipsRuntimeConfig.claimWavesEnabled())
            return;
        PacketDistributor.sendToPlayersInDimension(
                level,
                new Wave(level.dimension().location(), body, origin, action, level.getGameTime()));
    }

    /** Reload notification only; no polling or retained server effect registry. */
    public static void onReload(net.neoforged.fml.event.config.ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != NPShipsRuntimeConfig.SPEC) return;
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null)
            server.execute(
                    () -> {
                        if (!NPShipsRuntimeConfig.claimWavesEnabled())
                            PacketDistributor.sendToAllPlayers(
                                    new Wave(
                                            net.minecraft.world.level.Level.OVERWORLD.location(),
                                            new UUID(0, 0),
                                            BlockPos.ZERO,
                                            CANCEL_ALL,
                                            0));
                    });
    }

    public record Wave(
            ResourceLocation dimension, UUID body, BlockPos origin, int action, long tick)
            implements CustomPacketPayload {
        public static final Type<Wave> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("npships", "claim_wave"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Wave> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeResourceLocation(p.dimension);
                            b.writeUUID(p.body);
                            b.writeBlockPos(p.origin);
                            b.writeVarInt(p.action);
                            b.writeLong(p.tick);
                        },
                        b ->
                                new Wave(
                                        b.readResourceLocation(),
                                        b.readUUID(),
                                        b.readBlockPos(),
                                        b.readVarInt(),
                                        b.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
