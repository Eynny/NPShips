package com.eynny.npships.commands;

import com.eynny.npships.naturalspawn.NPShipsNaturalSpawnManager;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Shared localized result for every operator-triggered automatic command form. */
public final class AutomaticOpportunityCommand {
    private AutomaticOpportunityCommand() {}

    static int execute(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        return report(source, NPShipsNaturalSpawnManager.requestAutomatic(source));
    }

    static int report(
            net.minecraft.commands.CommandSourceStack source,
            NPShipsNaturalSpawnManager.AutomaticRequest result) {
        var message =
                Component.translatable(
                        "npships.automatic." + result.name().toLowerCase(Locale.ROOT));
        if (result == NPShipsNaturalSpawnManager.AutomaticRequest.STARTED) {
            source.sendSuccess(() -> message, false);
            return 1;
        }
        source.sendFailure(message);
        return 0;
    }
}
