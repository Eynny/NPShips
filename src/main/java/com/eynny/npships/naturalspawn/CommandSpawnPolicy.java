package com.eynny.npships.naturalspawn;

import com.eynny.npships.config.EnvironmentRule;
import com.eynny.npships.config.InsideRule;
import com.eynny.npships.config.NPShipsStructureConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Per-command conditions only. Authored content and construction failures remain unchanged. */
final class CommandSpawnPolicy {
    private CommandSpawnPolicy() {}

    static void warn(CommandSourceStack source, String reason) {
        if (source == null) return;
        source.sendSuccess(
                () ->
                        Component.literal("BYPASS: ")
                                .withStyle(ChatFormatting.YELLOW)
                                .append(
                                        Component.translatable(messageKey(reason))
                                                .withStyle(ChatFormatting.RED)),
                false);
    }

    private static String messageKey(String reason) {
        return switch (reason) {
            case "disabled" -> "npships.automatic.result_disabled";
            case "cooldown" -> "npships.automatic.result_recent_spawn_cooldown";
            case "player_distance" -> "npships.automatic.result_player_too_close";
            case "player_relevance" -> "npships.automatic.result_no_relevant_player";
            case "physical_overlap" -> "npships.automatic.result_physical_overlap";
            default -> "npships.spawn.bypass." + reason;
        };
    }

    static NaturalSpawnStructureCandidate unrestricted(
            NaturalSpawnStructureCandidate source, NaturalSpawnAttempt attempt) {
        var c = source.config();

        var p = c.placement();

        var config =
                new NPShipsStructureConfig(
                        c.selectionWeight(),
                        new NPShipsStructureConfig.EnvironmentConfig(
                                EnvironmentRule.none(),
                                EnvironmentRule.none(),
                                InsideRule.emptySpace()),
                        NPShipsStructureConfig.RestrictionsConfig.defaults(),
                        new NPShipsStructureConfig.PlacementConfig(
                                false, p.rotationSpreadDegrees(), false, p.disableAutoRotation()),
                        c.patches(),
                        c.lootContainers(),
                        c.segments(),
                        c.authoring(),
                        c.rarity());
        return new NaturalSpawnStructureCandidate(
                source.structureId(),
                source.schematic(),
                config,
                source.size(),
                source.raritySelection());
    }
}
