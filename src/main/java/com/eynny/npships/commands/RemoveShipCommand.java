package com.eynny.npships.commands;

import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.lifecycle.NPShipsSavedData;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

public final class RemoveShipCommand {
    // Bounds apply only to explicit command snapshots, never to tracking or gameplay.
    private static final int MAX_TARGETS = 256;

    private record Target(UUID uuid, long generation) {}

    /** One stack-owned immutable command selection; nothing survives the native call. */
    private record Selection(
            ResourceKey<Level> dimension, NPShipsSavedData dataOwner, List<Target> targets) {}

    private enum Removal {
        REQUESTED,
        MARKED,
        ALREADY_MARKED,
        DISAPPEARED,
        REFUSED
    }

    private RemoveShipCommand() {}

    public static void register(final LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(
                Commands.literal("remove")
                        .then(
                                Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(
                                                c ->
                                                        execute(
                                                                c.getSource(),
                                                                UuidArgument.getUuid(c, "uuid"))))
                        .then(Commands.literal("all").executes(c -> removeAll(c.getSource())))
                        .then(
                                Commands.literal("nearest")
                                        .then(
                                                Commands.argument(
                                                                "radius",
                                                                com.mojang.brigadier.arguments
                                                                        .DoubleArgumentType
                                                                        .doubleArg(0, 30000000))
                                                        .executes(
                                                                c ->
                                                                        removeNearby(
                                                                                c.getSource(),
                                                                                com.mojang.brigadier
                                                                                        .arguments
                                                                                        .DoubleArgumentType
                                                                                        .getDouble(
                                                                                                c,
                                                                                                "radius"),
                                                                                false))))
                        .then(
                                Commands.literal("random")
                                        .then(
                                                Commands.argument(
                                                                "radius",
                                                                com.mojang.brigadier.arguments
                                                                        .DoubleArgumentType
                                                                        .doubleArg(0, 30000000))
                                                        .executes(
                                                                c ->
                                                                        removeNearby(
                                                                                c.getSource(),
                                                                                com.mojang.brigadier
                                                                                        .arguments
                                                                                        .DoubleArgumentType
                                                                                        .getDouble(
                                                                                                c,
                                                                                                "radius"),
                                                                                true)))));
    }

    private static boolean authorized(CommandSourceStack source) {
        if (!source.getServer().isSameThread())
            throw new IllegalStateException("Removal commands require the server thread");
        if (source.hasPermission(2)) return true;
        source.sendFailure(Component.translatable("npships.remove.forbidden"));
        return false;
    }

    private static int removeAll(CommandSourceStack source) {
        if (!authorized(source)) return 0;
        var data = NPShipsSavedData.peek(source.getLevel());
        if (data == null || data.trackedCount() == 0) {
            source.sendFailure(Component.translatable("npships.remove.empty"));
            return 0;
        }
        // Check size before getTrackedShips(), whose public contract creates a full snapshot.
        if (data.trackedCount() > MAX_TARGETS) {
            source.sendFailure(
                    Component.translatable(
                            "npships.remove.too_many", data.trackedCount(), MAX_TARGETS));
            return 0;
        }
        var targets =
                data.getTrackedShips().keySet().stream()
                        .sorted()
                        .map(id -> new Target(id, data.trackingGeneration(id)))
                        .toList();
        return executeSelection(source, data, targets);
    }

    private static int removeNearby(CommandSourceStack source, double radius, boolean random) {
        if (!authorized(source)) return 0;
        var data = NPShipsSavedData.peek(source.getLevel());
        if (data == null || data.trackedCount() == 0) {
            source.sendFailure(Component.translatable("npships.remove.empty"));
            return 0;
        }
        if (data.trackedCount() > MAX_TARGETS) {
            source.sendFailure(
                    Component.translatable(
                            "npships.remove.too_many", data.trackedCount(), MAX_TARGETS));
            return 0;
        }
        var reference =
                dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(
                        source.getLevel(), source.getPosition());
        if (!Double.isFinite(radius)
                || !Double.isFinite(reference.x)
                || !Double.isFinite(reference.y)
                || !Double.isFinite(reference.z)) return 0;
        var container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(source.getLevel());
        var candidates = new java.util.ArrayList<UUID>();
        double nearestDistance = Double.POSITIVE_INFINITY;
        UUID nearest = null;
        if (container != null)
            for (var id : data.getTrackedShips().keySet().stream().sorted().toList()) {
                var body = container.getSubLevel(id);
                if (body == null || body.isRemoved()) continue;
                var at = body.logicalPose().position();
                double dx = at.x() - reference.x,
                        dy = at.y() - reference.y,
                        dz = at.z() - reference.z;
                double distance = dx * dx + dy * dy + dz * dz;
                if (!Double.isFinite(distance) || distance > radius * radius) continue;
                candidates.add(id);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = id;
                }
            }
        if (candidates.isEmpty()) {
            source.sendFailure(Component.translatable("npships.remove.no_nearby", radius));
            return 0;
        }
        // Command selection must not consume the world's automatic-spawn RNG sequence.
        UUID chosen =
                random
                        ? candidates.get(
                                java.util.concurrent.ThreadLocalRandom.current()
                                        .nextInt(candidates.size()))
                        : nearest;
        source.sendSuccess(
                () -> Component.translatable("npships.remove.selected", chosen.toString()), false);
        return executeSelection(
                source, data, List.of(new Target(chosen, data.trackingGeneration(chosen))));
    }

    private static int executeSelection(
            CommandSourceStack source, NPShipsSavedData data, List<Target> targets) {
        if (!authorized(source)) return 0;
        var selection = new Selection(source.getLevel().dimension(), data, List.copyOf(targets));
        // No token or callback retains this selection. Reentrant commands receive their own
        // current snapshot; every target below still checks owner, scope and tracking generation.
        int requested = 0, marked = 0, already = 0, refused = 0, disappeared = 0;
        for (var target : selection.targets) {
            if (!selection.dimension.equals(source.getLevel().dimension())
                    || NPShipsSavedData.peek(source.getLevel()) != selection.dataOwner) {
                refused++;
                continue;
            }
            if (!data.isTracked(target.uuid)) {
                disappeared++;
                continue;
            }
            if (data.trackingGeneration(target.uuid) != target.generation) {
                refused++;
                continue;
            }
            try {
                switch (removeTracked(source, target.uuid)) {
                    case REQUESTED -> requested++;
                    case MARKED -> marked++;
                    case ALREADY_MARKED -> already++;
                    case DISAPPEARED -> disappeared++;
                    case REFUSED -> refused++;
                }
            } catch (RuntimeException ex) {
                // A native failure can have partial effects; never retry this UUID implicitly.
                refused++;
                com.mojang.logging.LogUtils.getLogger()
                        .warn(
                                "NPShips bulk removal failed for {} in {}",
                                target.uuid,
                                selection.dimension.location(),
                                ex);
            }
        }
        var summary =
                Component.translatable(
                        "npships.remove.summary", requested, marked, already, refused, disappeared);
        source.sendSuccess(() -> summary, false);
        return requested + marked + already;
    }

    private static Removal removeTracked(CommandSourceStack source, UUID uuid) {
        var level = source.getLevel();
        var data = NPShipsSavedData.get(level);
        if (!data.isTracked(uuid)) return Removal.DISAPPEARED;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null || container.getSubLevel(uuid) == null) {
            long now = System.currentTimeMillis(), deadline = data.getUnloadDeadline(uuid);
            if (deadline >= 0 && deadline <= now) return Removal.ALREADY_MARKED;
            data.markUnloaded(uuid, now);
            return Removal.MARKED;
        }
        com.eynny.npships.lifecycle.NPShipsRemovalDiagnostics.log(
                level, uuid, "COMMAND_REQUEST", "NPShips remove; actor=" + source.getTextName());
        boolean removed = NPShipsLifecycleManager.remove(level, uuid);
        com.eynny.npships.lifecycle.NPShipsRemovalDiagnostics.log(
                level,
                uuid,
                "COMMAND_RESULT",
                "removed=" + removed + "; actor=" + source.getTextName());
        return removed ? Removal.REQUESTED : Removal.REFUSED;
    }

    private static int execute(CommandSourceStack source, UUID uuid) {
        if (!authorized(source)) return 0;
        var result = removeTracked(source, uuid);
        String key =
                switch (result) {
                    case REQUESTED -> "npships.command.removal_requested";
                    case MARKED -> "npships.command.removal_marked";
                    case ALREADY_MARKED -> "npships.command.already_marked";
                    case DISAPPEARED -> "npships.command.not_tracked";
                    case REFUSED -> "npships.command.not_loaded_here";
                };
        if (result == Removal.DISAPPEARED || result == Removal.REFUSED) {
            source.sendFailure(Component.translatable(key));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(key), false);
        return 1;
    }
}
