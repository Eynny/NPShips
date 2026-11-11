package com.eynny.npships.client;

import com.eynny.npships.mixin.NPShipsInfoChatAccessor;
import com.eynny.npships.network.InfoShipNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * Connection-scoped replaceable info page; no retained world, ticker, signed-message deletion or
 * chat clearing.
 */
@EventBusSubscriber(modid = "npships", value = Dist.CLIENT)
public final class InfoShipChat {
    private static final Set<Component> displayed =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static ClientPacketListener connection;
    private static long sequence;

    private InfoShipChat() {}

    public static int lineCapacity(Minecraft mc) {
        int lineHeight = (int) (9 * (mc.options.chatLineSpacing().get() + 1));
        double height = ChatComponent.getHeight(mc.options.chatHeightFocused().get());
        height =
                Math.min(
                        height,
                        Math.max(0, mc.getWindow().getGuiScaledHeight() - 48)
                                / mc.gui.getChat().getScale());
        return Math.max(1, (int) height / lineHeight);
    }

    public static int rowCapacity(Minecraft mc) {
        return Math.clamp(lineCapacity(mc) - 2, 1, 20);
    }

    public static void layout(InfoShipNetworking.Layout request) {
        var mc = Minecraft.getInstance();
        var current = mc.getConnection();
        if (current == null
                || mc.player == null
                || !current.hasChannel(InfoShipNetworking.Query.TYPE)) return;
        if (connection != current) displayed.clear();
        connection = current;
        sequence = request.sequence();
        PacketDistributor.sendToServer(
                new InfoShipNetworking.Query(request.query(), rowCapacity(mc), sequence));
    }

    /**
     * Elide a single row with its native styles/copy actions intact; full record remains on hover.
     */
    public static Component fit(Component text, int width) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= width) return text;
        var result = Component.empty().setStyle(text.getStyle());
        font.substrByWidth(text, Math.max(0, width - font.width("\u2026")))
                .visit(
                        (style, part) -> {
                            result.append(Component.literal(part).setStyle(style));
                            return Optional.empty();
                        },
                        Style.EMPTY);
        return result.append("\u2026");
    }

    public static void page(InfoShipNetworking.Page page) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() != connection || connection == null || page.sequence() != sequence)
            return;
        show(page.lines());
    }

    public static void show(java.util.List<Component> lines) {
        var mc = Minecraft.getInstance();
        var chat = mc.gui.getChat();
        var access = (NPShipsInfoChatAccessor) chat;
        if (access.npships$messages().removeIf(message -> displayed.contains(message.content())))
            access.npships$refresh();
        displayed.clear();
        int width = Math.max(1, (int) Math.floor(chat.getWidth() / chat.getScale()) - 12);
        int capacity = lineCapacity(mc);
        // At the smallest native chat height, reserve the visible record and navigation.
        int start = lines.size() > capacity ? 1 : 0;
        for (int i = start; i < lines.size() && displayed.size() < capacity; i++) {
            var line = fit(lines.get(i), width);
            displayed.add(line);
            chat.addMessage(line);
        }
        chat.resetChatScroll();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        displayed.clear();
        connection = null;
        sequence = 0;
    }
}
