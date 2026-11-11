package com.eynny.npships.network;

import com.eynny.npships.NPShips;
import com.eynny.npships.ship.SchematicLoot;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Read-only container table IDs; complete sync generations never mix their partial pages. */
@EventBusSubscriber(modid = NPShips.MOD_ID)
public final class LootTableNetworking {
    static final int MAX_TABLES = 2048;
    static final int MAX_FRAME_BYTES = 32 * 1024;
    private static final int PAGE_HEADER_RESERVE = 32;
    private static final Map<MinecraftServer, ServerCatalog> SERVER_TABLES = new WeakHashMap<>();
    private static long serverGeneration;
    private static Reply clientReply;
    private static int requestId;
    private static long clientGeneration;
    private static int nextOffset, expectedTotal;
    private static boolean complete;
    private static final ArrayList<String> pendingTables = new ArrayList<>();

    private record ServerCatalog(long generation, List<String> ids) {}

    private LootTableNetworking() {}

    /** Retained source compatibility; the unused v1 request channel is not registered in v2. */
    public static int nextRequestId() {
        clearClient();
        return ++requestId;
    }

    public static Reply clientReply() {
        return clientReply;
    }

    public static void clearClient() {
        clientReply = null;
        pendingTables.clear();
        clientGeneration = 0;
        nextOffset = expectedTotal = 0;
        complete = false;
    }

    @SubscribeEvent
    public static void onSync(net.neoforged.neoforge.event.OnDatapackSyncEvent event) {
        var players =
                event.getPlayer() == null
                        ? event.getPlayerList().getPlayers()
                        : List.of(event.getPlayer());
        var server = event.getPlayerList().getServer();
        if (players.isEmpty()) {
            synchronized (SERVER_TABLES) {
                SERVER_TABLES.remove(server);
            }
            return;
        }
        var catalogue = catalog(server, event.getPlayer() == null);
        var weather = new WeatherNetworking.Reply(com.eynny.npships.api.SpawnWeather.ids());
        for (var player : players) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, weather);
            int start = 0;
            do {
                var frame = page(catalogue.ids(), catalogue.generation(), start);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, frame);
                start += frame.tables().size();
                if (!frame.truncated()) break;
            } while (start < catalogue.ids().size());
        }
    }

    private static ServerCatalog catalog(MinecraftServer server, boolean reload) {
        synchronized (SERVER_TABLES) {
            var current = SERVER_TABLES.get(server);
            if (current != null && !reload) return current;
            var ids =
                    server.reloadableRegistries().getKeys(Registries.LOOT_TABLE).stream()
                            .filter(
                                    id ->
                                            id.toString().length() <= 256
                                                    && SchematicLoot.isContainerTable(server, id))
                            .map(ResourceLocation::toString)
                            .sorted()
                            .toList();
            var replacement = new ServerCatalog(++serverGeneration, ids);
            SERVER_TABLES.put(server, replacement);
            return replacement;
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent event) {
        synchronized (SERVER_TABLES) {
            SERVER_TABLES.remove(event.getServer());
        }
    }

    public static void register(final RegisterPayloadHandlersEvent event) {
        // Production clients already receive table IDs on login/reload and never send v1 Requests.
        // Removing that unused endpoint prevents tiny client packets from forcing full registry
        // scans and an unlimited number of replies. v2 adds explicit generation and page identity.
        event.registrar("2")
                .playToClient(
                        Reply.TYPE,
                        Reply.CODEC,
                        (reply, context) -> {
                            if (context.connection().isConnected()) receive(reply);
                        });
    }

    static void receive(Reply reply) {
        if (reply.id() != 0 || reply.generation() < clientGeneration) return;
        if (reply.generation() != clientGeneration) {
            if (reply.offset() != 0) return;
            pendingTables.clear();
            clientGeneration = reply.generation();
            nextOffset = 0;
            expectedTotal = reply.total();
            complete = false;
        }
        if (complete || reply.total() != expectedTotal || reply.offset() != nextOffset) return;
        pendingTables.addAll(reply.tables());
        nextOffset += reply.tables().size();
        if (!reply.truncated()) {
            clientReply = new Reply(0, pendingTables, false, clientGeneration, 0, expectedTotal);
            pendingTables.clear();
            complete = true;
        }
    }

    static Reply page(List<String> ids, long generation, int offset) {
        if (offset < 0 || offset > ids.size())
            throw new IllegalArgumentException("Invalid loot page");
        int end = offset, bytes = PAGE_HEADER_RESERVE;
        while (end < ids.size() && end - offset < MAX_TABLES) {
            int next = idBytes(ids.get(end));
            if (bytes + next > MAX_FRAME_BYTES) break;
            bytes += next;
            end++;
        }
        return new Reply(
                0, ids.subList(offset, end), end < ids.size(), generation, offset, ids.size());
    }

    private static int varIntBytes(int value) {
        int count = 1;
        while ((value & -128) != 0) {
            value >>>= 7;
            count++;
        }
        return count;
    }

    private static int idBytes(String id) {
        if (id == null || id.length() > 256 || ResourceLocation.tryParse(id) == null)
            throw new IllegalArgumentException("Invalid loot table ID");
        // Native ResourceLocation syntax is ASCII; string and UTF-8 byte lengths are identical.
        return varIntBytes(id.length()) + id.length();
    }

    static int encodedBytes(Reply reply) {
        int bytes =
                varIntBytes(reply.id())
                        + Long.BYTES
                        + varIntBytes(reply.offset())
                        + varIntBytes(reply.total())
                        + 1
                        + varIntBytes(reply.tables().size());
        for (var id : reply.tables()) bytes = Math.addExact(bytes, idBytes(id));
        return bytes;
    }

    private static void validateWire(Reply reply) {
        if (reply.tables().size() > MAX_TABLES || encodedBytes(reply) > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("Oversized loot table frame");
    }

    /** Source compatibility only. No client-to-server loot request is accepted by protocol v2. */
    public record Request(int id) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(NPShips.MOD_ID, "loot_list_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
                StreamCodec.of(
                        (buffer, request) -> buffer.writeVarInt(request.id()),
                        buffer -> new Request(buffer.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Reply(
            int id, List<String> tables, boolean truncated, long generation, int offset, int total)
            implements CustomPacketPayload {
        public Reply(int id, List<String> tables, boolean truncated) {
            this(id, tables, truncated, 1, 0, Math.addExact(tables.size(), truncated ? 1 : 0));
        }

        public Reply {
            tables = List.copyOf(tables);
            long end = (long) offset + tables.size();
            if (generation < 1
                    || offset < 0
                    || total < 0
                    || end > total
                    || truncated != (end < total)
                    || truncated && tables.isEmpty())
                throw new IllegalArgumentException("Invalid loot table page identity");
        }

        public static final Type<Reply> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(NPShips.MOD_ID, "loot_list_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC =
                StreamCodec.of(
                        (buffer, reply) -> {
                            validateWire(reply);
                            buffer.writeVarInt(reply.id());
                            buffer.writeLong(reply.generation());
                            buffer.writeVarInt(reply.offset());
                            buffer.writeVarInt(reply.total());
                            buffer.writeBoolean(reply.truncated());
                            buffer.writeVarInt(reply.tables().size());
                            for (var table : reply.tables()) buffer.writeUtf(table, 256);
                        },
                        buffer -> {
                            int frameStart = buffer.readerIndex();
                            int id = buffer.readVarInt();
                            long generation = buffer.readLong();
                            int offset = buffer.readVarInt(), total = buffer.readVarInt();
                            boolean truncated = buffer.readBoolean();
                            int count = buffer.readVarInt();
                            if (count < 0 || count > MAX_TABLES)
                                throw new IllegalArgumentException("Invalid loot table count.");
                            var tables = new ArrayList<String>(count);
                            for (int i = 0; i < count; i++) {
                                tables.add(buffer.readUtf(256));
                                if (buffer.readerIndex() - frameStart > MAX_FRAME_BYTES)
                                    throw new IllegalArgumentException(
                                            "Oversized loot table frame");
                            }
                            var reply = new Reply(id, tables, truncated, generation, offset, total);
                            validateWire(reply);
                            return reply;
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
