package com.eynny.npships.network;

import com.eynny.npships.commands.InfoShipCommand;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Read-only OP2 listing. Client supplies only a bounded display capacity, never query authority.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = "npships")
public final class InfoShipNetworking {
    private static long sequence;

    private record Pending(
            long sequence,
            String query,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            net.minecraft.world.phys.Vec3 position) {}

    // At most one latest request per connected operator; detached coordinates, no retained Level.
    private static final java.util.Map<ServerPlayer, Pending> pending =
            new java.util.IdentityHashMap<>();
    private static Consumer<Layout> layoutReceiver = ignored -> {};
    private static Consumer<Page> pageReceiver = ignored -> {};

    private InfoShipNetworking() {}

    public static void client(Consumer<Layout> layout, Consumer<Page> page) {
        layoutReceiver = layout;
        pageReceiver = page;
    }

    public static void request(net.minecraft.commands.CommandSourceStack source, String query) {
        var player = source.getPlayer();
        long id = ++sequence;
        pending.put(
                player,
                new Pending(
                        id,
                        query,
                        source.getLevel().dimension(),
                        dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(
                                source.getLevel(), source.getPosition())));
        PacketDistributor.sendToPlayer(player, new Layout(query, id));
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void logout(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) pending.remove(player);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        pending.clear();
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar
                .executesOn(HandlerThread.MAIN)
                .playToClient(
                        Layout.TYPE,
                        Layout.CODEC,
                        (p, ctx) -> {
                            if (ctx.connection().isConnected()) layoutReceiver.accept(p);
                        });
        registrar
                .executesOn(HandlerThread.MAIN)
                .playToClient(
                        Page.TYPE,
                        Page.CODEC,
                        (p, ctx) -> {
                            if (ctx.connection().isConnected()) pageReceiver.accept(p);
                        });
        registrar
                .executesOn(HandlerThread.NETWORK)
                .playToServer(
                        Query.TYPE,
                        Query.CODEC,
                        (p, ctx) ->
                                NetworkRequestGate.submit(
                                        ctx,
                                        NetworkRequestGate.Kind.INFO,
                                        () -> {
                                            if (!(ctx.player() instanceof ServerPlayer player))
                                                return;
                                            var request = pending.get(player);
                                            if (request == null
                                                    || request.sequence() != p.sequence()
                                                    || !request.query().equals(p.query())) return;
                                            pending.remove(player);
                                            if (!player.hasPermissions(2)) return;
                                            var level =
                                                    player.getServer()
                                                            .getLevel(request.dimension());
                                            if (level == null) {
                                                ctx.reply(
                                                        new Page(
                                                                List.of(
                                                                        Component.translatable(
                                                                                        "npships.info.dimension_missing",
                                                                                        request.dimension()
                                                                                                .location())
                                                                                .withStyle(
                                                                                        net
                                                                                                .minecraft
                                                                                                .ChatFormatting
                                                                                                .RED)),
                                                                p.sequence()));
                                                return;
                                            }
                                            try {
                                                var result =
                                                        InfoShipCommand.page(
                                                                player.createCommandSourceStack()
                                                                        .withLevel(level)
                                                                        .withPosition(
                                                                                request.position()),
                                                                request.query(),
                                                                p.rows(),
                                                                true,
                                                                request.position());
                                                ctx.reply(new Page(result.lines(), p.sequence()));
                                            } catch (IllegalArgumentException error) {
                                                ctx.reply(
                                                        new Page(
                                                                List.of(
                                                                        Component.literal(
                                                                                        error
                                                                                                .getMessage())
                                                                                .withStyle(
                                                                                        net
                                                                                                .minecraft
                                                                                                .ChatFormatting
                                                                                                .RED)),
                                                                p.sequence()));
                                            }
                                        }));
    }

    public record Layout(String query, long sequence) implements CustomPacketPayload {
        public Layout {
            if (query.length() > 1024) throw new IllegalArgumentException("Query too long");
        }

        public static final Type<Layout> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("npships", "info_layout"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Layout> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeUtf(p.query(), 1024);
                            b.writeLong(p.sequence());
                        },
                        b -> new Layout(b.readUtf(1024), b.readLong()));

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Query(String query, int rows, long sequence) implements CustomPacketPayload {
        public Query {
            if (query.length() > 1024 || rows < 1 || rows > 20)
                throw new IllegalArgumentException("Invalid info query");
        }

        public static final Type<Query> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("npships", "info_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeUtf(p.query(), 1024);
                            b.writeVarInt(p.rows());
                            b.writeLong(p.sequence());
                        },
                        b -> new Query(b.readUtf(1024), b.readVarInt(), b.readLong()));

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Page(List<Component> lines, long sequence) implements CustomPacketPayload {
        public Page {
            lines = List.copyOf(lines);
            if (lines.size() > 22) throw new IllegalArgumentException("Invalid info page");
        }

        public static final Type<Page> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("npships", "info_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Page> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.sequence());
                            b.writeVarInt(p.lines().size());
                            for (var line : p.lines())
                                ComponentSerialization.STREAM_CODEC.encode(b, line);
                        },
                        b -> {
                            long sequence = b.readLong();
                            int count = b.readVarInt();
                            if (count < 0 || count > 22)
                                throw new IllegalArgumentException("Invalid info page");
                            var lines = new ArrayList<Component>(count);
                            for (int i = 0; i < count; i++)
                                lines.add(ComponentSerialization.STREAM_CODEC.decode(b));
                            return new Page(lines, sequence);
                        });

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
