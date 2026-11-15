package com.eynny.npships.network;

import com.eynny.npships.api.SpawnWeather;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.List;

/** Only registered names are sent; predicates and weather state stay on the server. */
public final class WeatherNetworking {
    private static List<String> names = List.of("clear", "rain", "thunder");

    public static List<String> names() {
        return names;
    }

    public static void clearClient() {
        names = List.of("clear", "rain", "thunder");
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
                                        NetworkRequestGate.Kind.WEATHER,
                                        () -> context.reply(new Reply(SpawnWeather.ids()))));
        registrar
                .executesOn(HandlerThread.MAIN)
                .playToClient(
                        Reply.TYPE,
                        Reply.CODEC,
                        (reply, context) -> {
                            if (context.connection().isConnected()) names = reply.names();
                        });
    }

    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath("npships", "weather_names_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
                StreamCodec.of((b, p) -> {}, b -> new Request());

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Reply(List<String> names) implements CustomPacketPayload {
        public Reply {
            names = List.copyOf(names);
        }

        public static final Type<Reply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("npships", "weather_names_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarInt(p.names.size());
                            for (String id : p.names) b.writeUtf(id, 256);
                        },
                        b -> {
                            int size = b.readVarInt();
                            if (size < 0 || size > 1027)
                                throw new IllegalArgumentException("Invalid weather name count");
                            var names = new java.util.ArrayList<String>(size);
                            for (int i = 0; i < size; i++) names.add(b.readUtf(256));
                            return new Reply(names);
                        });

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
