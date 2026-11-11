package com.eynny.npships.commands;

import com.eynny.npships.naturalspawn.NPShipsNaturalSpawnManager;
import com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate;
import com.eynny.npships.ship.NPShipsSchematicManager;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** OP early automatic opportunity, optionally constrained to a source and search reference. */
public final class NaturalSpawnCommand {

    private NaturalSpawnCommand() {}

    private static final SuggestionProvider<CommandSourceStack> STRUCTURE_SUGGESTIONS =
            NaturalSpawnCommand::suggestStructures;

    public static void register(final LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(
                Commands.literal("naturalspawn")
                        .requires(source -> source.hasPermission(2))
                        .executes(AutomaticOpportunityCommand::execute)
                        .then(
                                Commands.argument("structure", new SchematicArgument())
                                        .suggests(STRUCTURE_SUGGESTIONS)
                                        .executes(ctx -> execute(ctx, false))
                                        .then(
                                                Commands.argument(
                                                                "pos", BlockPosArgument.blockPos())
                                                        .executes(ctx -> execute(ctx, true)))));
    }

    private static int execute(CommandContext<CommandSourceStack> context, boolean positioned) {
        var source = context.getSource();
        var requestedId = SchematicArgument.getId(context, "structure");
        var id = NPShipsSchematicManager.resolveRequestedId(requestedId);
        var schematic = NPShipsSchematicManager.find(source.getServer(), id.toString());
        if (schematic == null
                || schematic.config() == null
                || schematic.naturalSpawnSize() == null) {
            source.sendFailure(
                    Component.translatable(
                            "npships.automatic.invalid_source", requestedId.toString()));
            return 0;
        }
        BlockPos point = null;
        if (positioned) {
            var requested = BlockPosArgument.getBlockPos(context, "pos");
            point =
                    new BlockPos(
                            requested.getX(),
                            net.minecraft.util.Mth.clamp(
                                    requested.getY(),
                                    source.getLevel().getMinBuildHeight(),
                                    source.getLevel().getMaxBuildHeight() - 1),
                            requested.getZ());
        }
        return AutomaticOpportunityCommand.report(
                source,
                NPShipsNaturalSpawnManager.requestAutomatic(
                        source,
                        new NaturalSpawnStructureCandidate(
                                id, schematic, schematic.config(), schematic.naturalSpawnSize()),
                        point));
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
