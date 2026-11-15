package com.eynny.npships.commands;

import com.eynny.npships.diagnostics.SpawnVisualizationServer;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class SpawnVisualizationCommand {
    private SpawnVisualizationCommand() {}

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(
                Commands.literal("dev")
                        .requires(
                                s ->
                                        s.hasPermission(2)
                                                && com.eynny.npships.config.NPShipsRuntimeConfig
                                                        .developerModeEnabled())
                        .then(
                                Commands.literal("spawnvisualization")
                                        .then(
                                                Commands.literal("on")
                                                        .executes(c -> change(c.getSource(), true)))
                                        .then(
                                                Commands.literal("off")
                                                        .executes(
                                                                c ->
                                                                        change(
                                                                                c.getSource(),
                                                                                false)))));
    }

    private static int change(CommandSourceStack source, boolean on)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        if (on) {
            String failure = SpawnVisualizationServer.enable(player);
            if (failure != null) {
                source.sendFailure(Component.translatable("npships.spawnvisualization." + failure));
                return 0;
            }
        } else SpawnVisualizationServer.disable(player);
        source.sendSuccess(
                () -> Component.translatable("npships.spawnvisualization." + (on ? "on" : "off")),
                false);
        return 1;
    }
}
