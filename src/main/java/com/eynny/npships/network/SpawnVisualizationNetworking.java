package com.eynny.npships.network;

import com.eynny.npships.diagnostics.SpawnVisualizationData;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Field;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Frame;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Marker;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Schedule;
import com.eynny.npships.diagnostics.SpawnVisualizationServer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Client world nonce + server subscription epoch + ordered full snapshots. No gameplay requests.
 */
public final class SpawnVisualizationNetworking {
    public static final int START = 0, UPDATE = 1, CLEAR = 2;
    private static Consumer<State> client = p -> {};

    private SpawnVisualizationNetworking() {}

    public static void client(Consumer<State> receiver) {
        client = receiver;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar
                .executesOn(HandlerThread.MAIN)
                .playToClient(
                        State.TYPE,
                        State.CODEC,
                        (p, c) -> {
                            if (c.connection().isConnected()) client.accept(p);
                        });
        registrar
                .executesOn(HandlerThread.NETWORK)
                .playToServer(
                        World.TYPE,
                        World.CODEC,
                        (p, c) ->
                                NetworkRequestGate.submitLatestWorld(
                                        c,
                                        () -> {
                                            if (c.player() instanceof ServerPlayer player)
                                                SpawnVisualizationServer.world(player, p);
                                        }));
    }

    private static ResourceLocation dimension(RegistryFriendlyByteBuf b) {
        return ResourceLocation.parse(b.readUtf(128));
    }

    private static int count(RegistryFriendlyByteBuf b, int maximum) {
        int n = b.readVarInt();
        if (n < 0 || n > maximum)
            throw new IllegalArgumentException("Oversized visualization payload");
        return n;
    }

    public record World(UUID token, long generation, ResourceLocation dimension)
            implements CustomPacketPayload {
        public World {
            if (token == null
                    || generation < 1
                    || dimension == null
                    || dimension.toString().length() > 128)
                throw new IllegalArgumentException("Invalid diagnostic world identity");
        }

        public static final Type<World> TYPE =
                new Type<>(ResourceLocation.parse("npships:spawn_visualization_world"));
        public static final StreamCodec<RegistryFriendlyByteBuf, World> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeUUID(p.token);
                            b.writeLong(p.generation);
                            b.writeUtf(p.dimension.toString(), 128);
                        },
                        b ->
                                new World(
                                        b.readUUID(),
                                        b.readLong(),
                                        SpawnVisualizationNetworking.dimension(b)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record State(
            UUID world,
            long epoch,
            long sequence,
            ResourceLocation dimension,
            int kind,
            Frame frame)
            implements CustomPacketPayload {
        public State {
            if (world == null
                    || epoch < 1
                    || sequence < 0
                    || dimension == null
                    || dimension.toString().length() > 128
                    || kind < START
                    || kind > CLEAR
                    || frame == null
                    || kind == CLEAR && (!frame.fields().isEmpty() || !frame.markers().isEmpty()))
                throw new IllegalArgumentException("Invalid visualization state");
        }

        public static final Type<State> TYPE =
                new Type<>(ResourceLocation.parse("npships:spawn_visualization"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeUUID(p.world);
                            b.writeLong(p.epoch);
                            b.writeLong(p.sequence);
                            b.writeUtf(p.dimension.toString(), 128);
                            b.writeByte(p.kind);
                            b.writeLong(p.frame.tick());
                            b.writeBoolean(p.frame.overflow());
                            b.writeDouble(p.frame.maximumPressure());
                            b.writeVarInt(p.frame.fields().size());
                            for (var f : p.frame.fields()) {
                                b.writeUUID(f.owner());
                                b.writeDouble(f.position().x);
                                b.writeDouble(f.position().y);
                                b.writeDouble(f.position().z);
                                b.writeDouble(f.pressure());
                                b.writeDouble(f.radius());
                            }
                            b.writeVarInt(p.frame.markers().size());
                            for (var m : p.frame.markers()) {
                                b.writeLong(m.id());
                                b.writeInt(m.position().getX());
                                b.writeInt(m.position().getY());
                                b.writeInt(m.position().getZ());
                                b.writeLong(m.assigned());
                                b.writeDouble(m.frozenPressure());
                            }
                            var schedule = p.frame.schedule();
                            b.writeBoolean(schedule.enabled());
                            b.writeVarInt(schedule.eligiblePlayers());
                            b.writeLong(schedule.nextSelectionTick());
                            b.writeUtf(schedule.mode(), 16);
                            b.writeUtf(schedule.phase(), 32);
                            b.writeLong(schedule.elapsedTicks());
                            b.writeUtf(schedule.lastOutcome(), 64);
                        },
                        b -> {
                            UUID token = b.readUUID();
                            long epoch = b.readLong(), sequence = b.readLong();
                            var dimension = SpawnVisualizationNetworking.dimension(b);
                            int kind = b.readUnsignedByte();
                            long tick = b.readLong();
                            boolean overflow = b.readBoolean();
                            double maximumPressure = b.readDouble();
                            int n = count(b, SpawnVisualizationData.MAX_FIELDS);
                            var fields = new ArrayList<Field>(n);
                            for (int i = 0; i < n; i++)
                                fields.add(
                                        new Field(
                                                b.readUUID(),
                                                new Vec3(
                                                        b.readDouble(),
                                                        b.readDouble(),
                                                        b.readDouble()),
                                                b.readDouble(),
                                                b.readDouble()));
                            n = count(b, SpawnVisualizationData.MAX_MARKERS);
                            var markers = new ArrayList<Marker>(n);
                            for (int i = 0; i < n; i++)
                                markers.add(
                                        new Marker(
                                                b.readLong(),
                                                new BlockPos(b.readInt(), b.readInt(), b.readInt()),
                                                b.readLong(),
                                                b.readDouble()));
                            var schedule =
                                    new Schedule(
                                            b.readBoolean(),
                                            b.readVarInt(),
                                            b.readLong(),
                                            b.readUtf(16),
                                            b.readUtf(32),
                                            b.readLong(),
                                            b.readUtf(64));
                            return new State(
                                    token,
                                    epoch,
                                    sequence,
                                    dimension,
                                    kind,
                                    new Frame(
                                            tick,
                                            overflow,
                                            fields,
                                            markers,
                                            schedule,
                                            maximumPressure));
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
