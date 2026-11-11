package com.eynny.npships.commands;

import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.config.RarityParameters;
import com.eynny.npships.naturalspawn.NaturalSpawnAttemptContext;
import com.eynny.npships.naturalspawn.NaturalSpawnCandidatePool;
import com.eynny.npships.naturalspawn.NaturalSpawnLimits;
import com.eynny.npships.naturalspawn.NaturalSpawnRarity;
import com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate;
import com.eynny.npships.ship.NPShipsSchematicManager;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/**
 * Read-only catalogue/field diagnostics. No selection RNG, search/chunk requests or history writes.
 */
public final class RarityCommand {
    private RarityCommand() {}

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(
                Commands.literal("rarity")
                        .then(
                                Commands.argument("structure", new SchematicArgument())
                                        .suggests(
                                                (ctx, b) ->
                                                        SharedSuggestionProvider.suggest(
                                                                NPShipsSchematicManager
                                                                        .getSuggestionNames(
                                                                                ctx.getSource()
                                                                                        .getServer()),
                                                                b))
                                        .executes(
                                                ctx ->
                                                        report(
                                                                ctx.getSource(),
                                                                SchematicArgument.getId(
                                                                        ctx, "structure")))));
    }

    private static int report(
            CommandSourceStack source, net.minecraft.resources.ResourceLocation requested) {
        var id = NPShipsSchematicManager.resolveRequestedId(requested);
        var info = NPShipsSchematicManager.find(source.getServer(), id.toString());
        var metadata =
                info == null || info.snapshot() == null ? null : info.snapshot().rarityMetadata();
        if (info == null || info.config() == null || metadata == null || !metadata.available()) {
            source.sendFailure(
                    Component.translatable(
                            "npships.rarity.unavailable",
                            id.toString(),
                            metadata != null ? metadata.error() : "missing config or metadata"));
            return 0;
        }
        var range = metadata.range();
        var parameters = NPShipsRuntimeConfig.rarity();
        var rule = info.config().rarity();
        String count =
                range.minimum() == range.maximum()
                        ? Long.toString(range.minimum())
                        : range.minimum() + "\u2013" + range.maximum();
        double low =
                rule.automatic()
                        ? parameters.automaticProbability(range.maximum())
                        : rule.manualProbability();
        double high = rule.automatic() ? parameters.automaticProbability(range.minimum()) : low;
        var message =
                Component.translatable(
                        "npships.rarity.info",
                        id.toString(),
                        count,
                        Component.translatable(
                                "npships.rarity." + (rule.automatic() ? "automatic" : "manual")),
                        range(parameters, low, high, 0),
                        Component.translatable(
                                "npships.rarity.weight", info.config().selectionWeight()));
        if (info.source() == com.eynny.npships.ship.SchematicSource.EXTERNAL)
            message.append("\n").append(Component.translatable("npships.rarity.working_source"));
        var player = source.getPlayer();
        if (player == null)
            message.append("\n").append(Component.translatable("npships.rarity.no_player"));
        else {
            // Never use an execute-position/dimension override or raw Sable plot coordinates.
            var level = player.serverLevel();
            var position = NaturalSpawnAttemptContext.worldPosition(player);
            double pressure = NaturalSpawnRarity.readSample(level, position, parameters);
            message.append("\n")
                    .append(
                            Component.translatable(
                                    "npships.rarity.local",
                                    String.format(
                                            Locale.ROOT,
                                            "%.3f, %.3f, %.3f",
                                            position.x,
                                            position.y,
                                            position.z),
                                    level.dimension().location().toString(),
                                    range(parameters, low, high, pressure),
                                    String.format(Locale.ROOT, "%.3f", pressure),
                                    String.format(
                                            Locale.ROOT, "%.3f", parameters.maximumPressure()),
                                    String.format(
                                            Locale.ROOT, "%.3f", parameters.pressureStrength())));
            message.append("\n")
                    .append(shares(source, player, position, info, parameters, pressure));
        }
        message.append("\n")
                .append(
                        Component.translatable(
                                "npships.rarity.draws",
                                NaturalSpawnLimits.max_structures_per_attempt()));
        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static String range(RarityParameters p, double low, double high, double pressure) {
        String first = percentLog(p.effectiveLogRarity(low, pressure));
        return low == high
                ? first
                : first + "\u2013" + percentLog(p.effectiveLogRarity(high, pressure));
    }

    /** Retain nonzero log-domain rarity even below Double.MIN_VALUE after pressure. */
    private static String percentLog(double log) {
        if (log == Double.NEGATIVE_INFINITY) return "0%";
        if (log >= Math.log(.000001))
            return BigDecimal.valueOf(Math.exp(log) * 100)
                            .round(new MathContext(6))
                            .stripTrailingZeros()
                            .toPlainString()
                    + "%";
        double decimal = log / Math.log(10) + 2;
        int exponent = (int) Math.floor(decimal);
        var mantissa =
                BigDecimal.valueOf(Math.pow(10, decimal - exponent)).round(new MathContext(6));
        if (mantissa.compareTo(BigDecimal.TEN) >= 0) {
            mantissa = BigDecimal.ONE;
            exponent++;
        }
        return mantissa.stripTrailingZeros().toPlainString() + "e" + exponent + "%";
    }

    private static Component shares(
            CommandSourceStack source,
            net.minecraft.server.level.ServerPlayer player,
            net.minecraft.world.phys.Vec3 position,
            NPShipsSchematicManager.SchematicInfo selected,
            RarityParameters parameters,
            double pressure) {
        var pool =
                NaturalSpawnCandidatePool.inspect(
                        source.getServer(), player.serverLevel(), BlockPos.containing(position));
        if (pool.unknown()) return unknown("observations");
        var candidates = new java.util.ArrayList<NaturalSpawnStructureCandidate>();
        var probabilities = new java.util.ArrayList<Double>();
        int selectedIndex = -1;
        for (var candidate : pool.candidates()) {
            var data =
                    candidate.schematic().snapshot() == null
                            ? null
                            : candidate.schematic().snapshot().rarityMetadata();
            if (data == null || !data.available() || data.range().maximum() == 0) continue;
            var bounds = data.range();
            if (bounds.minimum() == 0
                    || candidate.config().rarity().automatic()
                            && bounds.minimum() != bounds.maximum()) return unknown("variants");
            // Catalogue find() creates a fresh info wrapper; the pinned source snapshot is stable.
            if (candidate.commandName().equals(selected.commandName())
                    && candidate.schematic().source() == selected.source()
                    && candidate.schematic().snapshot() == selected.snapshot())
                selectedIndex = candidates.size();
            candidates.add(candidate);
            probabilities.add(
                    candidate.config().rarity().automatic()
                            ? parameters.automaticProbability(bounds.minimum())
                            : candidate.config().rarity().manualProbability());
        }
        var author = new double[candidates.size()];
        var preference = new double[candidates.size()];
        for (int i = 0; i < author.length; i++) {
            author[i] = probabilities.get(i);
            preference[i] = candidates.get(i).config().selectionWeight();
        }
        double base =
                share(
                        NaturalSpawnRarity.selectionLogWeights(parameters, 0, author, preference),
                        selectedIndex);
        double local =
                share(
                        NaturalSpawnRarity.selectionLogWeights(
                                parameters, pressure, author, preference),
                        selectedIndex);
        var result =
                Component.translatable(
                        "npships.rarity.shares",
                        candidates.size(),
                        percentLog(base),
                        percentLog(local));
        if (selectedIndex < 0)
            result.append(" ").append(Component.translatable("npships.rarity.not_in_pool"));
        return result;
    }

    private static Component unknown(String reason) {
        return Component.translatable(
                "npships.rarity.shares_unknown",
                Component.translatable("npships.rarity.pool_" + reason));
    }

    private static double share(double[] weights, int index) {
        if (index < 0) return Double.NEGATIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (double weight : weights) maximum = Math.max(maximum, weight);
        double total = 0;
        for (double weight : weights) total += Math.exp(weight - maximum);
        // Same scaled native-double lottery mass as NaturalSpawnStructureQueue, no draw.
        return Math.log(Math.exp(weights[index] - maximum) / total);
    }
}
