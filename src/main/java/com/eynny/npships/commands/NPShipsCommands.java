package com.eynny.npships.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class NPShipsCommands {

    private NPShipsCommands() {}

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        final LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("npships").requires(source -> source.hasPermission(2));

        SpawnShipCommand.register(root);
        NaturalSpawnCommand.register(root);
        RarityCommand.register(root);
        SpawnVisualizationCommand.register(root);

        RemoveShipCommand.register(root);
        ClaimShipCommand.register(root);
        UnclaimShipCommand.register(root);
        InfoShipCommand.register(root);
        RefreshShipCommand.register(root);

        dispatcher.register(root);
    }
}
