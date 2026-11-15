package com.eynny.npships.commands;

import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.lifecycle.NPShipsSavedData;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public final class ClaimShipCommand {

    private ClaimShipCommand() {}

    public static void register(final LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(
                Commands.literal("claim")
                        .then(
                                Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(
                                                context ->
                                                        execute(
                                                                context.getSource(),
                                                                UuidArgument.getUuid(
                                                                        context, "uuid")))));
    }

    private static int execute(final CommandSourceStack source, final UUID uuid) {
        final NPShipsSavedData data = NPShipsSavedData.get(source.getLevel());

        if (!data.isTracked(uuid)) {
            source.sendFailure(Component.translatable("npships.command.not_tracked"));

            return 0;
        }

        NPShipsLifecycleManager.untrack(source.getLevel(), uuid);

        source.sendSuccess(() -> Component.translatable("npships.command.claimed"), false);

        return 1;
    }
}
