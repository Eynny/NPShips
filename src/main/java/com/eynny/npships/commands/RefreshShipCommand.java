package com.eynny.npships.commands;

import com.eynny.npships.ship.NPShipsSchematicManager;
import com.eynny.npships.ship.SchematicSource;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class RefreshShipCommand {

    private RefreshShipCommand() {}

    public static void register(final LiteralArgumentBuilder<CommandSourceStack> root) {

        root.then(Commands.literal("refresh").executes(context -> execute(context.getSource())));
    }

    public static int execute(final CommandSourceStack source) {

        if (!source.hasPermission(2)) return 0;
        try {

            /*
             * Completely rebuild the catalog.
             *
             * This reparses both NBT and JSON.
             */
            if (!NPShipsSchematicManager.tryRefresh(source.getServer())) {
                source.sendFailure(Component.translatable("npships.catalogue.refresh_failed"));
                return 0;
            }

        } catch (final RuntimeException e) {

            final String message = e.getMessage();

            source.sendFailure(
                    message == null
                            ? Component.translatable("npships.catalogue.refresh_failed")
                            : Component.translatable("npships.catalogue.refresh_error", message));

            return 0;
        }

        int excluded = NPShipsSchematicManager.status(source.getServer()).excludedEntries();
        if (excluded > 0)
            source.sendFailure(Component.translatable("npships.catalogue.excluded", excluded));
        final var schematics = NPShipsSchematicManager.getAll(source.getServer());

        int total = schematics.size();

        int configured = 0;

        int external = 0;

        int resource = 0;

        for (final var schematic : schematics) {

            if (schematic.config() != null) {
                configured++;
            }

            if (schematic.source() == SchematicSource.EXTERNAL) {

                external++;

            } else if (schematic.source() == SchematicSource.RESOURCE) {

                resource++;
            }
        }

        final int finalTotal = total;

        final int finalConfigured = configured;

        final int finalExternal = external;

        final int finalResource = resource;
        final int automatic = NPShipsSchematicManager.getAutomatic(source.getServer()).size();

        source.sendSuccess(
                () ->
                        Component.translatable(
                                        "npships.catalogue.refresh_summary",
                                        finalTotal,
                                        finalConfigured,
                                        finalResource,
                                        finalExternal,
                                        automatic)
                                .withStyle(ChatFormatting.GREEN),
                true);
        source.sendSuccess(() -> Component.translatable("npships.catalogue.refresh_help"), false);

        return 1;
    }
}
