package com.eynny.npships.commands;

import com.eynny.npships.lifecycle.NPShipsLifecycleQuery;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

public final class InfoShipCommand {

    private InfoShipCommand() {}

    public static void register(final LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(
                Commands.literal("info")
                        .executes(c -> search(c.getSource(), ""))
                        .then(
                                Commands.literal("find")
                                        .executes(c -> search(c.getSource(), ""))
                                        .then(
                                                Commands.argument(
                                                                "filters",
                                                                com.mojang.brigadier.arguments
                                                                        .StringArgumentType
                                                                        .greedyString())
                                                        .suggests(
                                                                (c, b) -> {
                                                                    String query = b.getRemaining();
                                                                    var tail =
                                                                            b.createOffset(
                                                                                    b.getStart()
                                                                                            + query
                                                                                                    .lastIndexOf(
                                                                                                            ' ')
                                                                                            + 1);
                                                                    return net.minecraft.commands
                                                                            .SharedSuggestionProvider
                                                                            .suggest(
                                                                                    StructureInfoFilter
                                                                                            .suggestions(
                                                                                                    query,
                                                                                                    java
                                                                                                            .util
                                                                                                            .stream
                                                                                                            .StreamSupport
                                                                                                            .stream(
                                                                                                                    c.getSource()
                                                                                                                            .getServer()
                                                                                                                            .getAllLevels()
                                                                                                                            .spliterator(),
                                                                                                                    false)
                                                                                                            .map(
                                                                                                                    level ->
                                                                                                                            level.dimension()
                                                                                                                                    .location()
                                                                                                                                    .toString())
                                                                                                            .toList()),
                                                                                    tail);
                                                                })
                                                        .executes(
                                                                c ->
                                                                        search(
                                                                                c.getSource(),
                                                                                com.mojang.brigadier
                                                                                        .arguments
                                                                                        .StringArgumentType
                                                                                        .getString(
                                                                                                c,
                                                                                                "filters"))))));
    }

    private static String sourceName(
            CommandSourceStack source, NPShipsLifecycleQuery.StructureInfo info) {
        var level = source.getServer().getLevel(info.dimension());
        var data = level == null ? null : com.eynny.npships.lifecycle.NPShipsSavedData.peek(level);
        String name = data == null ? "" : data.source(info.uuid());
        if (!name.isEmpty() || level == null) return name;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        var body = container == null ? null : container.getSubLevel(info.uuid());
        return body == null || body.getName() == null ? "" : body.getName();
    }

    private static double distance(
            CommandSourceStack source,
            NPShipsLifecycleQuery.StructureInfo info,
            net.minecraft.world.phys.Vec3 reference) {
        return info.hasPosition() && info.dimension().equals(source.getLevel().dimension())
                ? reference.distanceToSqr(
                        new net.minecraft.world.phys.Vec3(info.x(), info.y(), info.z()))
                : Double.POSITIVE_INFINITY;
    }

    private static int search(CommandSourceStack source, String query) {
        try {
            StructureInfoFilter.parse(query);
            var player = source.getPlayer();
            if (player != null
                    && source.source == player
                    && player.connection != null
                    && player.connection.hasChannel(
                            com.eynny.npships.network.InfoShipNetworking.Layout.TYPE)) {
                com.eynny.npships.network.InfoShipNetworking.request(source, query);
                return 1;
            }
            var page = page(source, query, 4, false);
            for (var line : page.lines()) source.sendSuccess(() -> line, false);
            return page.total();
        } catch (IllegalArgumentException error) {
            source.sendFailure(Component.literal(error.getMessage()));
            return 0;
        }
    }

    public record Listing(List<Component> lines, int total) {
        public Listing {
            lines = List.copyOf(lines);
        }
    }

    /** Read-only scalar query; caller-supplied page size is bounded again on the server. */
    public static Listing page(
            CommandSourceStack source, String query, int requestedRows, boolean compact) {
        return page(
                source,
                query,
                requestedRows,
                compact,
                dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(
                        source.getLevel(), source.getPosition()));
    }

    /** Frozen ordinary-world reference captured when the authorized query was requested. */
    public static Listing page(
            CommandSourceStack source,
            String query,
            int requestedRows,
            boolean compact,
            net.minecraft.world.phys.Vec3 reference) {
        if (!source.hasPermission(2))
            throw new IllegalArgumentException("Permission level 2 required");
        if (query.length() > 1024) throw new IllegalArgumentException("Query too long");
        var filters = StructureInfoFilter.parse(query);
        String base = query.replaceAll("(?i)(?:^|\\s+)page=\\d+", "").strip();
        return showListing(
                source,
                filters,
                Math.clamp(requestedRows, 1, 20),
                compact,
                reference,
                next ->
                        "/npships info find "
                                + (base.isEmpty() ? "" : base + " ")
                                + "page="
                                + next);
    }

    private record BodyKey(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            java.util.UUID id) {}

    /** Union detached tracking records with current native bodies; never load storage or chunks. */
    private static List<NPShipsLifecycleQuery.StructureInfo> bodies(
            CommandSourceStack source, String type) {
        if (type.equals("tracked")) return NPShipsLifecycleQuery.all(source.getServer());
        var result = new java.util.LinkedHashMap<BodyKey, NPShipsLifecycleQuery.StructureInfo>();
        if (type.equals("all"))
            for (var info : NPShipsLifecycleQuery.all(source.getServer()))
                result.put(new BodyKey(info.dimension(), info.uuid()), info);
        for (var level : source.getServer().getAllLevels()) {
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container == null) continue;
            var saved = com.eynny.npships.lifecycle.NPShipsSavedData.peek(level);
            for (var body : container.getAllSubLevels()) {
                if (body.isRemoved()
                        || type.equals("untracked")
                                && saved != null
                                && saved.isTracked(body.getUniqueId())) continue;
                var at = body.logicalPose().position();
                var info =
                        new NPShipsLifecycleQuery.StructureInfo(
                                body.getUniqueId(),
                                level.dimension(),
                                true,
                                -1,
                                at.x(),
                                at.y(),
                                at.z());
                result.put(new BodyKey(info.dimension(), info.uuid()), info);
            }
        }
        return List.copyOf(result.values());
    }

    private record Entry(
            NPShipsLifecycleQuery.StructureInfo info,
            String name,
            long created,
            double distance,
            NPShipsLifecycleQuery.Status status) {}

    /** All list routes share one detached scalar query and formatter; no chunk/world loading. */
    private static Listing showListing(
            CommandSourceStack source,
            StructureInfoFilter filters,
            int pageSize,
            boolean compact,
            net.minecraft.world.phys.Vec3 reference,
            java.util.function.IntFunction<String> navigationCommand) {
        var rows =
                bodies(source, filters.type()).stream()
                        .map(
                                info ->
                                        new Entry(
                                                info,
                                                sourceName(source, info),
                                                created(source, info),
                                                distance(source, info, reference),
                                                NPShipsLifecycleQuery.getStatus(info)))
                        .filter(
                                row ->
                                        (row.created != 0
                                                        || filters.from() == 0
                                                                && filters.to() == Long.MAX_VALUE)
                                                && row.created >= filters.from()
                                                && row.created <= filters.to()
                                                && filters.matches(
                                                        row.info.uuid().toString(),
                                                        row.name,
                                                        row.info.dimension().location().toString(),
                                                        row.status.name(),
                                                        row.distance))
                        .sorted(
                                (a, b) -> {
                                    int order =
                                            filters.sort().equals("nearest")
                                                    ? Double.compare(a.distance, b.distance)
                                                    : filters.sort().equals("oldest")
                                                            ? Long.compare(a.created, b.created)
                                                            : Long.compare(b.created, a.created);
                                    return order == 0
                                            ? a.info.uuid().compareTo(b.info.uuid())
                                            : order;
                                })
                        .toList();
        int pages = Math.max(1, (int) ((rows.size() + (long) pageSize - 1) / pageSize));
        int page = filters.page();
        if (page > pages)
            return new Listing(
                    List.of(
                            Component.translatable("npships.info.invalid_page", pages)
                                    .withStyle(ChatFormatting.RED)),
                    0);
        var lines = new java.util.ArrayList<Component>();
        lines.add(
                Component.translatable(
                                "npships.info."
                                        + (filters.type().equals("tracked")
                                                ? "list"
                                                : filters.type())
                                        + "_count",
                                rows.size())
                        .withStyle(
                                style ->
                                        style.withHoverEvent(
                                                new HoverEvent(
                                                        HoverEvent.Action.SHOW_TEXT,
                                                        Component.translatable(
                                                                "npships.info."
                                                                        + filters.type()
                                                                        + "_scope"))))
                        .withStyle(ChatFormatting.YELLOW)
                        .append(separator())
                        .append(
                                Component.translatable("npships.info.page", page, pages)
                                        .withStyle(ChatFormatting.WHITE)));
        rows.stream()
                .skip((page - 1L) * pageSize)
                .limit(pageSize)
                .forEach(
                        row ->
                                lines.add(
                                        infoLine(
                                                source,
                                                row.info,
                                                compact,
                                                filters.terms()
                                                        .contains(row.info.uuid().toString()))));
        var navigation = Component.empty();
        if (page > 1) navigation.append(pageLink("previous", navigationCommand.apply(page - 1)));
        if (page > 1 && page < pages) navigation.append("  ");
        if (page < pages) navigation.append(pageLink("next", navigationCommand.apply(page + 1)));
        if (!navigation.getString().isEmpty()) lines.add(navigation);
        return new Listing(lines, rows.size());
    }

    private static Component pageLink(String label, String command) {
        return Component.translatable("npships.info." + label)
                .withStyle(
                        style ->
                                style.withColor(ChatFormatting.AQUA)
                                        .withClickEvent(
                                                new ClickEvent(
                                                        ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static long created(
            CommandSourceStack source, NPShipsLifecycleQuery.StructureInfo info) {
        var level = source.getServer().getLevel(info.dimension());
        var data = level == null ? null : com.eynny.npships.lifecycle.NPShipsSavedData.peek(level);
        return data == null ? 0 : data.createdAt(info.uuid());
    }

    private static MutableComponent separator() {
        return Component.literal(" | ").withStyle(ChatFormatting.WHITE);
    }

    private static Component infoLine(
            CommandSourceStack source,
            NPShipsLifecycleQuery.StructureInfo info,
            boolean compact,
            boolean diagnostics) {
        final NPShipsLifecycleQuery.Status status = NPShipsLifecycleQuery.getStatus(info);

        /*
         * UUID:
         *
         * AQUA
         * clickable
         * copies to clipboard
         * no underline
         */
        final MutableComponent uuidComponent =
                Component.literal(info.uuid().toString())
                        .withStyle(
                                style ->
                                        style.withColor(ChatFormatting.AQUA)
                                                .withClickEvent(
                                                        new ClickEvent(
                                                                ClickEvent.Action.COPY_TO_CLIPBOARD,
                                                                info.uuid().toString()))
                                                .withHoverEvent(
                                                        new HoverEvent(
                                                                HoverEvent.Action.SHOW_TEXT,
                                                                Component.translatable(
                                                                                "npships.info.copy_uuid")
                                                                        .withStyle(
                                                                                ChatFormatting
                                                                                        .WHITE))));

        /*
         * Status.
         */
        final MutableComponent statusComponent;

        switch (status) {
            case LOADED ->
                    statusComponent =
                            Component.translatable("npships.info.status.loaded")
                                    .withStyle(ChatFormatting.GREEN);

            case UNLOADED ->
                    statusComponent =
                            Component.translatable("npships.info.status.unloaded")
                                    .withStyle(ChatFormatting.YELLOW);

            case EXPIRED ->
                    statusComponent =
                            Component.translatable("npships.info.status.expired")
                                    .withStyle(ChatFormatting.RED);

            default ->
                    statusComponent =
                            Component.literal(status.name()).withStyle(ChatFormatting.WHITE);
        }

        var level = source.getServer().getLevel(info.dimension());
        long protectedTicks =
                level == null
                        ? 0
                        : com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager
                                        .protectedUntil(info.uuid())
                                - level.getGameTime();
        if (protectedTicks > 0)
            statusComponent
                    .append(separator())
                    .append(
                            Component.translatable(
                                            "npships.info.protected", (protectedTicks + 19) / 20)
                                    .withStyle(ChatFormatting.BLUE));
        final MutableComponent message = Component.literal("");

        String name = sourceName(source, info);
        var tracking =
                level == null ? null : com.eynny.npships.lifecycle.NPShipsSavedData.peek(level);
        if (tracking != null && tracking.fragment(info.uuid()))
            message.append(
                            Component.translatable("npships.info.fragment")
                                    .withStyle(ChatFormatting.GRAY))
                    .append(separator());
        if (!name.isEmpty())
            message.append(Component.literal(name).withStyle(ChatFormatting.GOLD))
                    .append(separator());
        message.append(uuidComponent);
        long creation = created(source, info);
        message.append(separator());
        message.append(
                creation == 0
                        ? Component.translatable("npships.info.created_unknown")
                                .withStyle(ChatFormatting.GRAY)
                        : Component.translatable(
                                        "npships.info.created",
                                        java.time.Instant.ofEpochMilli(creation)
                                                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                                                .toString())
                                .withStyle(ChatFormatting.GRAY));

        message.append(separator());

        message.append(
                Component.literal(info.dimension().location().toString())
                        .withStyle(ChatFormatting.WHITE));

        message.append(separator());

        /*
         * Coordinates.
         */
        if (info.hasPosition()) {

            final long x = Math.round(info.x());

            final long y = Math.round(info.y());

            final long z = Math.round(info.z());

            final String coordinates = x + " " + y + " " + z;

            final MutableComponent positionComponent =
                    Component.literal("XYZ " + coordinates)
                            .withStyle(
                                    style ->
                                            style.withColor(ChatFormatting.AQUA)
                                                    .withClickEvent(
                                                            new ClickEvent(
                                                                    ClickEvent.Action
                                                                            .COPY_TO_CLIPBOARD,
                                                                    coordinates))
                                                    .withHoverEvent(
                                                            new HoverEvent(
                                                                    HoverEvent.Action.SHOW_TEXT,
                                                                    Component.translatable(
                                                                                    "npships.info.copy_coordinates")
                                                                            .withStyle(
                                                                                    ChatFormatting
                                                                                            .WHITE))));

            message.append(positionComponent);

        } else {

            message.append(
                    Component.translatable("npships.info.position_unavailable")
                            .withStyle(ChatFormatting.WHITE));
        }

        var saved = level == null ? null : com.eynny.npships.lifecycle.NPShipsSavedData.peek(level);
        if (saved == null || !saved.isTracked(info.uuid()))
            statusComponent
                    .append(separator())
                    .append(
                            Component.translatable("npships.info.untracked")
                                    .withStyle(ChatFormatting.GRAY));
        message.append(separator()).append(statusComponent);

        /*
         * Remaining lifetime.
         */
        if (status == NPShipsLifecycleQuery.Status.UNLOADED && info.deadline() >= 0) {

            final NPShipsLifecycleQuery.TimeRemaining remaining = info.getRemainingTime();

            message.append(separator());

            message.append(
                    Component.translatable("npships.info.expires", remaining.format())
                            .withStyle(ChatFormatting.YELLOW));

        } else if (status == NPShipsLifecycleQuery.Status.EXPIRED) {

            message.append(separator());

            message.append(
                    Component.translatable("npships.info.expired_notice")
                            .withStyle(ChatFormatting.RED));
        }

        // Preserve the former explicit-UUID diagnostics through the same find query, on demand.
        if (diagnostics && level != null) {
            for (String line :
                    com.eynny.npships.lifecycle.NPShipsLoadingDiagnostics.describe(
                            level, info.uuid()))
                message.append("\n").append(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        if (!compact) return message;
        // Keep full identity/time/native diagnostics available on hover; one visible row per body.
        var summary = Component.empty();
        summary.append(
                Component.translatable("npships.info.copy_uuid")
                        .setStyle(uuidComponent.getStyle()));
        summary.append(separator());
        if (info.hasPosition()) {
            String xyz =
                    Math.round(info.x()) + " " + Math.round(info.y()) + " " + Math.round(info.z());
            summary.append(
                    Component.literal(xyz)
                            .withStyle(
                                    style ->
                                            style.withColor(ChatFormatting.AQUA)
                                                    .withClickEvent(
                                                            new ClickEvent(
                                                                    ClickEvent.Action
                                                                            .COPY_TO_CLIPBOARD,
                                                                    xyz))
                                                    .withHoverEvent(
                                                            new HoverEvent(
                                                                    HoverEvent.Action.SHOW_TEXT,
                                                                    Component.translatable(
                                                                            "npships.info.copy_coordinates")))));
        } else summary.append(Component.translatable("npships.info.position_unavailable"));
        if (!name.isEmpty())
            summary.append(separator())
                    .append(Component.literal(name).withStyle(ChatFormatting.GOLD));
        summary.append(separator()).append(statusComponent.copy());
        summary.withStyle(
                style ->
                        style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, message)));
        return summary;
    }
}
