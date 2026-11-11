package com.eynny.npships.network;

import com.eynny.npships.NPShips;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.ship.NPShipsSchematicManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;

/** Server-authoritative small ACKs and revision-correlated, demand-driven catalogue pages. */
@EventBusSubscriber(modid = NPShips.MOD_ID)
public final class RuntimeSettingsNetworking {
    public static Consumer<String> receiver = value -> {};
    public static Consumer<String> policyReceiver = value -> {};

    public static void register(RegisterPayloadHandlersEvent event) {
        // The wire shape changed: a v2 aggregate client must not negotiate a v3 page stream.
        var registrar = event.registrar("3");
        registrar
                .executesOn(HandlerThread.NETWORK)
                .playToServer(
                        Request.TYPE,
                        Request.CODEC,
                        (request, context) ->
                                NetworkRequestGate.reply(
                                        context,
                                        NetworkRequestGate.Kind.SETTINGS,
                                        () ->
                                                context.player() instanceof ServerPlayer player
                                                        ? new State(json(response(player, request)))
                                                        : null));
        registrar
                .executesOn(HandlerThread.NETWORK)
                .playToServer(
                        PageRequest.TYPE,
                        PageRequest.CODEC,
                        (request, context) ->
                                NetworkRequestGate.reply(
                                        context,
                                        NetworkRequestGate.Kind.SETTINGS_PAGE,
                                        () ->
                                                context.player() instanceof ServerPlayer player
                                                        ? new State(json(page(player, request)))
                                                        : null));
        registrar
                .executesOn(HandlerThread.NETWORK)
                .playToServer(
                        PolicyRequest.TYPE,
                        PolicyRequest.CODEC,
                        (request, context) ->
                                NetworkRequestGate.reply(
                                        context,
                                        NetworkRequestGate.Kind.PATCH_POLICY,
                                        () ->
                                                context.player() instanceof ServerPlayer player
                                                        ? new State(
                                                                json(
                                                                        PatchManagerPolicy.response(
                                                                                player,
                                                                                request,
                                                                                context
                                                                                        .connection())))
                                                        : null));
        registrar
                .executesOn(HandlerThread.MAIN)
                .playToClient(
                        State.TYPE,
                        State.CODEC,
                        (state, context) -> {
                            if (context.connection().isConnected()) {
                                // Canonical policy envelopes put kind first. Ordinary catalogue
                                // pages retain their single screen-owned parse and allocation.
                                if (state.json().startsWith("{\"kind\":\"patch_state\","))
                                    policyReceiver.accept(state.json());
                                else receiver.accept(state.json());
                            }
                        });
    }

    static JsonObject response(ServerPlayer player, Request request) {
        String error = "";
        String resourceDetail = null;
        if (!request.key().isEmpty()) {
            if (!player.hasPermissions(2)) error = "npships.settings.read_only";
            else {
                try {
                    if (request.key().equals("structure_token")
                            && request.revision()
                                    != RuntimeSettingsPages.view(player.server).revision)
                        error = "npships.settings.stale";
                    else apply(player, request);
                } catch (com.eynny.npships.resources.ResourceLimitException refused) {
                    RuntimeSettingsPages.invalidate(player.server);
                    error = "npships.settings.resource_limit";
                    resourceDetail = refused.getMessage();
                } catch (IllegalArgumentException ex) {
                    error = "npships.settings.invalid";
                } catch (RuntimeException ex) {
                    LogUtils.getLogger()
                            .warn("[NPShips settings] Server configuration update failed", ex);
                    error = "npships.settings.update_failed";
                }
            }
        }
        var result = metadata(player, error);
        if (resourceDetail != null) result.addProperty("resource_limit", resourceDetail);
        result.addProperty("kind", "state");
        result.addProperty("request_id", request.id());
        result.addProperty("session", request.session());
        if (encodedBytes(result) > RuntimeSettingsPages.PAGE_BYTES)
            throw new IllegalStateException("Settings metadata exceeded its encoded envelope");
        return result;
    }

    /** Publication invalidates derived ownership without touching the server configuration. */
    public static void invalidateCatalogue(MinecraftServer server) {
        RuntimeSettingsPages.invalidate(server);
    }

    static JsonObject page(ServerPlayer player, PageRequest request) {
        return RuntimeSettingsPages.page(player, request);
    }

    private static void apply(ServerPlayer player, Request request) {
        String key = request.key(), value = request.value();
        if (key.startsWith("structure:") || key.equals("structure_token")) {
            String id =
                    key.equals("structure_token")
                            ? RuntimeSettingsPages.token(
                                    player, request.revision(), request.token())
                            : key.substring(10);
            if (NPShipsSchematicManager.getAutomatic(player.server).stream()
                    .noneMatch(i -> i.commandName().equals(id)))
                throw new IllegalArgumentException();
            boolean enabled = bool(value);
            var disabled = new ArrayList<String>(NPShipsRuntimeConfig.DISABLED_STRUCTURES.get());
            disabled.removeIf(id::equals);
            if (!enabled) disabled.add(id);
            NPShipsRuntimeConfig.DISABLED_STRUCTURES.set(disabled);
        } else
            switch (key) {
                case "enabled" -> NPShipsRuntimeConfig.ENABLED.set(bool(value));
                case "developer_mode" -> NPShipsRuntimeConfig.DEV_MODE.set(bool(value));
                case "mob_movement_fix" -> NPShipsRuntimeConfig.MOB_MOVEMENT_FIX.set(bool(value));
                case "mob_cross_structure_fix" ->
                        NPShipsRuntimeConfig.MOB_CROSS_STRUCTURE_FIX.set(bool(value));
                case "mob_mounted_attack_fix" ->
                        NPShipsRuntimeConfig.MOB_MOUNTED_ATTACK_FIX.set(bool(value));
                default -> throw new IllegalArgumentException();
            }
        if (!key.equals("mob_movement_fix")
                && !key.equals("mob_cross_structure_fix")
                && !key.equals("mob_mounted_attack_fix"))
            RuntimeSettingsPages.invalidate(player.server);
        NPShipsRuntimeConfig.SPEC.save();
        if (key.equals("developer_mode")) DevelopmentModeNetworking.refresh(player.server);
    }

    private static boolean bool(String value) {
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException();
        return Boolean.parseBoolean(value);
    }

    static JsonObject metadata(ServerPlayer player, String error) {
        var result = new JsonObject();
        RuntimeSettingsPages.View view = null;
        try {
            view = RuntimeSettingsPages.view(player.server);
        } catch (com.eynny.npships.resources.ResourceLimitException refused) {
            if (error.isEmpty()) error = "npships.settings.resource_limit";
            result.addProperty("resource_limit", refused.getMessage());
        }
        result.addProperty("editable", player.hasPermissions(2));
        result.addProperty("error", error);
        result.addProperty("enabled", NPShipsRuntimeConfig.enabled());
        result.addProperty("developer_mode", NPShipsRuntimeConfig.developerModeEnabled());
        result.addProperty("mob_movement_fix", NPShipsRuntimeConfig.mobMovementFixEnabled());
        result.addProperty(
                "mob_cross_structure_fix", NPShipsRuntimeConfig.mobCrossStructureFixEnabled());
        result.addProperty(
                "mob_mounted_attack_fix", NPShipsRuntimeConfig.mobMountedAttackFixEnabled());
        var pending = new JsonArray();
        pendingRestart(NPShipsRuntimeConfig.SPEC.getValues(), pending);
        result.add("pending_restart", pending);
        var status = view == null ? NPShipsSchematicManager.status(player.server) : view.status;
        result.addProperty("catalogue_failed", status.failed());
        result.addProperty("excluded_entries", status.excludedEntries());
        result.addProperty(
                "generation",
                view == null
                        ? NPShipsSchematicManager.publicSnapshot(player.server).generation()
                        : view.generation);
        result.addProperty("revision", view == null ? 0L : view.revision);
        return result;
    }

    /** ASCII JSON preserves every UTF-16 unit, including opaque unpaired author surrogates. */
    static String json(JsonElement element) {
        String source = element.toString();
        StringBuilder encoded = null;
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch >= 128) {
                if (encoded == null) {
                    encoded = new StringBuilder(source.length());
                    encoded.append(source, 0, i);
                }
                encoded.append('\\')
                        .append('u')
                        .append(hex[(ch >>> 12) & 15])
                        .append(hex[(ch >>> 8) & 15])
                        .append(hex[(ch >>> 4) & 15])
                        .append(hex[ch & 15]);
            } else if (encoded != null) encoded.append(ch);
        }
        return encoded == null ? source : encoded.toString();
    }

    static int encodedBytes(JsonElement element) {
        return encodedBytes(json(element).length());
    }

    /**
     * Exact envelope length after the existing canonical ASCII serializer, without reserializing.
     */
    static int encodedBytes(int length) {
        // Include native string VarInt and the custom payload's resource-location discriminator.
        int prefix = length < 128 ? 1 : length < 16384 ? 2 : 3;
        int type = State.TYPE.id().toString().length();
        return length + prefix + type + 1;
    }

    private static void pendingRestart(
            com.electronwill.nightconfig.core.UnmodifiableConfig config, JsonArray pending) {
        for (Object value : config.valueMap().values()) {
            if (value
                    instanceof net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> setting) {
                if (setting.getSpec().restartType()
                                != net.neoforged.neoforge.common.ModConfigSpec.RestartType.NONE
                        && !Objects.equals(setting.get(), setting.getRaw()))
                    pending.add(String.join(".", setting.getPath()));
            } else if (value instanceof com.electronwill.nightconfig.core.UnmodifiableConfig child)
                pendingRestart(child, pending);
        }
    }

    @SubscribeEvent
    public static void disconnected(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.connection != null)
            PatchManagerPolicy.disconnected(player.connection.getConnection());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        RuntimeSettingsPages.clear();
        PatchManagerPolicy.clear();
    }

    public record Request(String key, String value, long id, long session, long revision, int token)
            implements CustomPacketPayload {
        public Request(String key, String value) {
            this(key, value, 0L);
        }

        public Request(String key, String value, long id) {
            this(key, value, id, 0L, 0L, -1);
        }

        public static final Type<Request> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                "npships", "runtime_settings_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
                StreamCodec.of(
                        (b, v) -> {
                            b.writeUtf(v.key(), 512);
                            b.writeUtf(v.value(), 256);
                            b.writeLong(v.id());
                            b.writeLong(v.session());
                            b.writeLong(v.revision());
                            b.writeInt(v.token());
                        },
                        b ->
                                new Request(
                                        b.readUtf(512),
                                        b.readUtf(256),
                                        b.readLong(),
                                        b.readLong(),
                                        b.readLong(),
                                        b.readInt()));

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PageRequest(
            long id,
            long session,
            long revision,
            String view,
            String query,
            boolean disabled,
            int offset,
            int textOffset)
            implements CustomPacketPayload {
        public static final Type<PageRequest> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath("npships", "runtime_settings_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PageRequest> CODEC =
                StreamCodec.of(
                        (b, v) -> {
                            b.writeLong(v.id());
                            b.writeLong(v.session());
                            b.writeLong(v.revision());
                            b.writeUtf(v.view(), 16);
                            b.writeUtf(v.query(), 256);
                            b.writeBoolean(v.disabled());
                            b.writeInt(v.offset());
                            b.writeInt(v.textOffset());
                        },
                        b ->
                                new PageRequest(
                                        b.readLong(),
                                        b.readLong(),
                                        b.readLong(),
                                        b.readUtf(16),
                                        b.readUtf(256),
                                        b.readBoolean(),
                                        b.readInt(),
                                        b.readInt()));

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Additive v3 bounded patch-manager request; catalogue paging and revision owners are
     * unchanged.
     */
    public record PolicyRequest(String action, String json, long id, long session)
            implements CustomPacketPayload {
        public static final Type<PolicyRequest> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath("npships", "runtime_patch_policy"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PolicyRequest> CODEC =
                StreamCodec.of(
                        (b, value) -> {
                            b.writeUtf(value.action(), 16);
                            b.writeUtf(value.json(), 12000);
                            b.writeLong(value.id());
                            b.writeLong(value.session());
                        },
                        b ->
                                new PolicyRequest(
                                        b.readUtf(16),
                                        b.readUtf(12000),
                                        b.readLong(),
                                        b.readLong()));

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record State(String json) implements CustomPacketPayload {
        public static final Type<State> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("npships", "runtime_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC =
                StreamCodec.of(
                        (b, v) -> b.writeUtf(v.json(), 1048576),
                        b -> new State(b.readUtf(1048576)));

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
