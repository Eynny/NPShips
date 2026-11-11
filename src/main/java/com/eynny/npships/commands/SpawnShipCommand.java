package com.eynny.npships.commands;

import com.eynny.npships.api.events.NPShipsLifecycleEvent.Route;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.SpawnPhase;
import com.eynny.npships.integration.events.NPShipsEventBridge;
import com.eynny.npships.ship.NPShipsSchematicManager;
import com.eynny.npships.ship.ShipSpawner;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SpawnShipCommand {

    private SpawnShipCommand() {}

    private static final SuggestionProvider<CommandSourceStack> STRUCTURE_SUGGESTIONS =
            SpawnShipCommand::suggestStructures;

    public static void register(final LiteralArgumentBuilder<CommandSourceStack> root) {

        // Registered argument accepts case-insensitive schematic IDs on both sides.
        root.then(
                Commands.literal("spawn")

                        /*
                         * Namespaced form:
                         *
                         * /npships spawn custommod:foo ~ ~ ~
                         */
                        .then(
                                Commands.argument("structure", new SchematicArgument())
                                        .suggests(STRUCTURE_SUGGESTIONS)
                                        .then(
                                                Commands.argument(
                                                                "pos", BlockPosArgument.blockPos())
                                                        .executes(SpawnShipCommand::execute))));
    }

    private static int execute(final CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {

        final CommandSourceStack source = context.getSource();

        final ServerLevel level = source.getLevel();

        final ResourceLocation requestedId = SchematicArgument.getId(context, "structure");

        final BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");

        final ResourceLocation id = NPShipsSchematicManager.resolveRequestedId(requestedId);
        final boolean exists =
                NPShipsSchematicManager.find(source.getServer(), id.toString()) != null;

        if (!exists) {

            source.sendFailure(
                    Component.translatable("npships.spawn.unknown", requestedId.toString()));

            return 0;
        }

        var operation =
                new NPShipsEventBridge.Operation(
                        java.util.UUID.randomUUID(), Route.DIRECT_COMMAND, id, pos);
        var notifications = NPShipsEventBridge.enter(operation, () -> true);
        final com.eynny.npships.ship.Ship ship;
        try (notifications) {
            NPShipsEventBridge.accepted(level);
            ship = ShipSpawner.spawn(level, id, pos);
        } catch (final Exception e) {
            NPShipsEventBridge.complete(
                    level,
                    operation,
                    SpawnPhase.FAILED,
                    "PLACEMENT_FAILED",
                    e.getMessage(),
                    com.eynny.npships.api.SpawnResult.Placement.UNCONFIRMED,
                    java.util.List.of(),
                    null,
                    notifications.placementStarted());

            final String message = e.getMessage();

            source.sendFailure(
                    message == null
                            ? Component.translatable("npships.spawn.failed_no_detail")
                            : Component.translatable("npships.spawn.failed", message));

            return 0;
        }

        NPShipsEventBridge.complete(
                level,
                operation,
                SpawnPhase.SUCCEEDED,
                "SUCCESS",
                "",
                ship == null
                        ? com.eynny.npships.api.SpawnResult.Placement.STATIC
                        : com.eynny.npships.api.SpawnResult.Placement.PHYSICAL,
                ship == null ? java.util.List.of() : ship.getPhysicalIds(),
                pos,
                notifications.placementStarted());
        source.sendSuccess(
                () -> Component.translatable("npships.spawn.placed", requestedId.toString()), true);

        return 1;
    }

    private static CompletableFuture<Suggestions> suggestStructures(
            final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {

        final List<String> names =
                NPShipsSchematicManager.getSuggestionNames(context.getSource().getServer());

        final String remaining = builder.getRemaining();

        for (final String name : names) {

            if (remaining.isEmpty()
                    || name.substring(name.indexOf(':') + 1)
                            .toLowerCase(java.util.Locale.ROOT)
                            .startsWith(remaining.toLowerCase(java.util.Locale.ROOT))
                    || name.regionMatches(true, 0, remaining, 0, remaining.length())) {

                builder.suggest(name);
            }
        }

        return builder.buildFuture();
    }
}
