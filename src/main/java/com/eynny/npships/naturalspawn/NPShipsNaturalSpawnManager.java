package com.eynny.npships.naturalspawn;

import com.eynny.npships.api.NPShipsSpawnApi;
import com.eynny.npships.api.SpawnJob;
import com.eynny.npships.api.SpawnResult;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.diagnostics.SpawnVisualizationData.Schedule;
import com.mojang.logging.LogUtils;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Global natural-spawn scheduler.
 *
 * <p>Automatic spawning, OP triggers and direct manual/API requests use the same
 * NaturalSpawnAttempt pipeline.
 *
 * <p>There is at most one active natural-spawn attempt on a server.
 */
public final class NPShipsNaturalSpawnManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable private static NaturalSpawnAttempt activeAttempt;

    private static boolean playerCountDirty = true;
    private static int eligiblePlayerCount;
    private static long nextRosterCheck;
    @Nullable private static NaturalSpawnOutcome lastOutcome;
    private static boolean stopping;
    private static boolean exactPlacement;

    // Events can precede the actual list/mode change or be cancelled by another
    // listener. Read the final server state once on the next scheduling tick.
    @SubscribeEvent
    public static void onPlayerLogin(final PlayerEvent.PlayerLoggedInEvent event) {
        invalidatePlayerCount(event);
    }

    @SubscribeEvent
    public static void onPlayerLogout(final PlayerEvent.PlayerLoggedOutEvent event) {
        invalidatePlayerCount(event);
    }

    @SubscribeEvent
    public static void onPlayerGameModeChange(final PlayerEvent.PlayerChangeGameModeEvent event) {
        invalidatePlayerCount(event);
    }

    @SubscribeEvent
    public static void onPlayerDimensionChange(
            final PlayerEvent.PlayerChangedDimensionEvent event) {
        invalidatePlayerCount(event);
    }

    private static void invalidatePlayerCount(final PlayerEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer) {
            playerCountDirty = true;
        }
    }

    private NPShipsNaturalSpawnManager() {}

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();

        tick(server);
    }

    static int currentEligiblePlayers(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (playerCountDirty || now >= nextRosterCheck) {
            eligiblePlayerCount = NaturalSpawnAttemptContext.countEligiblePlayers(server);
            nextRosterCheck = now + 20;
        }
        return eligiblePlayerCount;
    }

    static long opportunityDelay(net.minecraft.util.RandomSource random) {
        return opportunityDelay(random, 1);
    }

    static long opportunityDelay(net.minecraft.util.RandomSource random, int players) {
        long minimum = com.eynny.npships.config.NPShipsRuntimeConfig.opportunityIntervalMinTicks();
        // Clamp against this captured minimum even if live config reload races the two reads.
        long maximum =
                Math.max(
                        minimum,
                        com.eynny.npships.config.NPShipsRuntimeConfig
                                .opportunityIntervalMaxTicks());
        long base = minimum + random.nextInt((int) (maximum - minimum) + 1);
        // Honor deliberately shorter configured intervals; one player remains exactly unchanged.
        return Math.max(
                Math.min(minimum, 2400L),
                Math.round(
                        base
                                / Math.sqrt(
                                        Math.clamp(
                                                players,
                                                1,
                                                com.eynny.npships.config.NPShipsRuntimeConfig
                                                        .opportunityPlayerCap()))));
    }

    static long successfulCooldown(int players) {
        return Math.round(
                NaturalSpawnLimits.recent_spawn_cooldown_ticks()
                        * Math.sqrt(
                                Math.clamp(
                                        players,
                                        1,
                                        com.eynny.npships.config.NPShipsRuntimeConfig
                                                .cooldownPlayerCap())));
    }

    private static long opportunityDelayIfNew(NPShipsNaturalSpawnData data, ServerLevel level) {
        return data.isInitialized()
                ? 0
                : opportunityDelay(
                        level.getRandom(),
                        NaturalSpawnAttemptContext.countEligiblePlayers(level.getServer()));
    }

    /** The event and native manager tests use this same scheduling/attempt path. */
    static void tick(MinecraftServer server) {
        if (exactPlacement) return;
        var level = server.overworld();
        long now = level.getGameTime();
        int players = currentEligiblePlayers(server);
        // Queries during a pre-change player event must not consume its post-tick invalidation.
        playerCountDirty = false;
        var data = NPShipsNaturalSpawnData.get(level);
        boolean initialized = data.isInitialized();
        // An admitted no-schedule automatic request must not initialize a fresh world
        // indirectly on its first manager tick. Other scheduler operations remain independent.
        if (activeAttempt == null
                || activeAttempt.mode != NaturalSpawnAttemptMode.AUTOMATIC
                || activeAttempt.automaticEffects.reschedule())
            data.initializeIfNeeded(now, opportunityDelayIfNew(data, level));
        if (!initialized && data.isInitialized() && NaturalSpawnLimits.dev_diagnostics())
            LOGGER.info(
                    "[NPShips NaturalSpawn] Initial opportunity scheduled: now={}, next={}, players={}",
                    now,
                    data.getNextSelectionGameTime(),
                    players);
        if (activeAttempt != null) {

            if (activeAttempt.mode == NaturalSpawnAttemptMode.AUTOMATIC
                    && !activeAttempt.isFinished()) {
                if (!com.eynny.npships.config.NPShipsRuntimeConfig.enabled())
                    activeAttempt.finishFailed(
                            NaturalSpawnOutcome.DISABLED, "Automatic spawning disabled.");
                else if (players == 0)
                    activeAttempt.finishFailed(
                            NaturalSpawnOutcome.NO_ELIGIBLE_PLAYER, "No eligible players remain.");
            }
            var advancing = activeAttempt;
            if (!advancing.isFinished()) advancing.tick();
            if (activeAttempt == advancing && advancing.isFinished()) {
                collectFinishedAttempt(server);
            }
            return;
        }
        if (!com.eynny.npships.config.NPShipsRuntimeConfig.enabled()
                || players == 0
                || now < data.getNextSelectionGameTime()) return;
        startDueAutomatic(server, data, now, players, null);
    }

    public enum AutomaticRequest {
        STARTED,
        DISABLED,
        BUSY,
        NO_ELIGIBLE_PLAYER,
        STOPPING,
        FORBIDDEN,
        INVALID_REFERENCE
    }

    /**
     * OP diagnostic exception: expire one deadline and relax only this attempt's recent-success
     * guard. Saved cooldowns/rarity histories stay intact; ordinary due ticks never inherit it.
     */
    public static AutomaticRequest requestAutomatic(CommandSourceStack source) {
        return requestAutomatic(source, null, null);
    }

    /**
     * Selected-source and optional reference constraints still consume one AUTOMATIC opportunity.
     */
    public static AutomaticRequest requestAutomatic(
            CommandSourceStack source,
            @Nullable NaturalSpawnStructureCandidate selected,
            @Nullable BlockPos reference) {
        var server = source.getServer();
        requireServerThread(server, source.getLevel());
        if (!source.hasPermission(2)) return AutomaticRequest.FORBIDDEN;
        if (!acceptsExternalRequests(server)) return AutomaticRequest.STOPPING;
        if (exactPlacement) return AutomaticRequest.BUSY;
        collectFinishedAttempt(server);
        if (activeAttempt != null
                || exactPlacement
                || com.eynny.npships.integration.events.NPShipsEventBridge
                        .hasAcceptedDirectOperation()) return AutomaticRequest.BUSY;
        if (!com.eynny.npships.config.NPShipsRuntimeConfig.enabled())
            return AutomaticRequest.DISABLED;
        // A command may follow a roster event in this same tick; use the final native roster.
        int players = NaturalSpawnAttemptContext.countEligiblePlayers(server);
        if (players == 0
                || reference != null
                        && source.getLevel().players().stream()
                                .noneMatch(NaturalSpawnAttemptContext::isEligiblePlayer))
            return AutomaticRequest.NO_ELIGIBLE_PLAYER;
        if (reference != null
                && (reference.getY() < source.getLevel().getMinBuildHeight()
                        || reference.getY() >= source.getLevel().getMaxBuildHeight()
                        || !source.getLevel().hasChunkAt(reference)))
            return AutomaticRequest.INVALID_REFERENCE;
        var level = server.overworld();
        long now = level.getGameTime();
        var data = NPShipsNaturalSpawnData.get(level);
        data.initializeIfNeeded(now, 0);
        data.setNextSelectionGameTime(now);
        startDueAutomatic(server, data, now, players, source, selected, reference);
        return AutomaticRequest.STARTED;
    }

    /** Trusted Java admission; shares the automatic attempt and terminal/deadline owner. */
    public static NPShipsSpawnApi.AutomaticStartResult requestAutomatic(
            @Nullable MinecraftServer server,
            NPShipsSpawnApi.AutomaticOptions options,
            @Nullable java.util.function.Consumer<NPShipsSpawnApi.AutomaticResult> observer) {
        return requestAutomatic(
                server, options, NPShipsSpawnApi.AutomaticEffects.DEFAULT, observer);
    }

    public static NPShipsSpawnApi.AutomaticStartResult requestAutomatic(
            @Nullable MinecraftServer server,
            NPShipsSpawnApi.AutomaticOptions options,
            NPShipsSpawnApi.AutomaticEffects effects,
            @Nullable java.util.function.Consumer<NPShipsSpawnApi.AutomaticResult> observer) {
        java.util.Objects.requireNonNull(options);
        java.util.Objects.requireNonNull(effects);
        if (server != null && !server.isSameThread())
            throw new IllegalStateException("Automatic requests require the server thread.");
        if (server == null || !acceptsExternalRequests(server) || server.overworld() == null)
            return automaticRefused(NPShipsSpawnApi.AutomaticStatus.SERVER_UNAVAILABLE);
        collectFinishedAttempt(server);
        if (activeAttempt != null
                || exactPlacement
                || com.eynny.npships.integration.events.NPShipsEventBridge
                        .hasAcceptedDirectOperation())
            return automaticRefused(NPShipsSpawnApi.AutomaticStatus.BUSY);
        if (!com.eynny.npships.config.NPShipsRuntimeConfig.enabled())
            return automaticRefused(NPShipsSpawnApi.AutomaticStatus.DISABLED);
        if (NaturalSpawnAttemptContext.countEligiblePlayers(server) == 0)
            return automaticRefused(NPShipsSpawnApi.AutomaticStatus.NO_ELIGIBLE_PLAYER);
        if (effects.reschedule()) {
            var data = NPShipsNaturalSpawnData.get(server.overworld());
            long now = server.overworld().getGameTime();
            data.initializeIfNeeded(now, 0);
            data.setNextSelectionGameTime(now);
        }
        var attempt = NaturalSpawnAttempt.start(server, null, effects).managedProfile();
        attempt.relaxRecentCooldown =
                options.cooldownPolicy() == NPShipsSpawnApi.CooldownPolicy.BYPASS;
        var job = ManagedSpawnJob.automatic(attempt, observer);
        attempt.externalJob = job;
        var timing = attempt.timing();
        if (timing != null) timing.job(job.id());
        activeAttempt = attempt;
        attempt.notifyStarted();
        return new NPShipsSpawnApi.AutomaticStartResult(
                NPShipsSpawnApi.AutomaticStatus.STARTED, java.util.Optional.of(job), "");
    }

    private static NPShipsSpawnApi.AutomaticStartResult automaticRefused(
            NPShipsSpawnApi.AutomaticStatus status) {
        return new NPShipsSpawnApi.AutomaticStartResult(
                status, java.util.Optional.empty(), status.name());
    }

    private static void startDueAutomatic(
            MinecraftServer server,
            NPShipsNaturalSpawnData data,
            long now,
            int players,
            @Nullable CommandSourceStack source) {
        startDueAutomatic(server, data, now, players, source, null, null);
    }

    private static void startDueAutomatic(
            MinecraftServer server,
            NPShipsNaturalSpawnData data,
            long now,
            int players,
            @Nullable CommandSourceStack source,
            @Nullable NaturalSpawnStructureCandidate selected,
            @Nullable BlockPos reference) {
        activeAttempt = NaturalSpawnAttempt.start(server, source).managedProfile();
        activeAttempt.operatorCandidate = selected;
        activeAttempt.operatorPoint = reference == null ? null : reference.immutable();
        activeAttempt.operatorLevel = reference == null ? null : source.getLevel();
        // This private shared entry receives a source only after requestAutomatic's OP guards.
        activeAttempt.relaxRecentCooldown = source != null;
        if (NaturalSpawnLimits.dev_diagnostics())
            LOGGER.info(
                    "[NPShips NaturalSpawn] Due automatic opportunity started: now={}, deadline={}, players={}",
                    now,
                    data.getNextSelectionGameTime(),
                    players);
        var started = activeAttempt;
        started.notifyStarted();
    }

    @Nullable
    public static NaturalSpawnOutcome lastOutcome() {
        return lastOutcome;
    }

    /** Diagnostics reuse existing data and the manager's {@code <=20}-tick player count. */
    public static Schedule diagnosticSchedule(@Nullable NPShipsNaturalSpawnData data) {
        var attempt = activeAttempt;
        return new Schedule(
                com.eynny.npships.config.NPShipsRuntimeConfig.enabled(),
                eligiblePlayerCount,
                data == null || !data.isInitialized() ? -1 : data.getNextSelectionGameTime(),
                attempt == null ? "" : attempt.getModeName(),
                attempt == null ? "" : attempt.getPhaseName(),
                attempt == null ? 0 : attempt.getElapsedTicks(),
                lastOutcome == null ? "" : lastOutcome.name());
    }

    /** Also settle cancellation when an API caller replaces its finished handle between ticks. */
    private static void collectFinishedAttempt(MinecraftServer server) {
        if (activeAttempt == null || !activeAttempt.isFinished()) return;
        collectFinishedAttempt(server, NPShipsNaturalSpawnData.get(server.overworld()));
    }

    private static void collectFinishedAttempt(
            MinecraftServer server, @Nullable NPShipsNaturalSpawnData data) {
        if (activeAttempt == null || !activeAttempt.isFinished()) return;
        var completed = activeAttempt;
        var timing = completed.timing();
        long token = timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.COLLECTION);
        try {
            var level = server.overworld();
            lastOutcome = activeAttempt.getOutcome();
            if (completed.mode == NaturalSpawnAttemptMode.AUTOMATIC
                    && completed.automaticEffects.reschedule()
                    && data != null)
                data.setNextSelectionGameTime(
                        level.getGameTime()
                                + opportunityDelay(
                                        level.getRandom(),
                                        NaturalSpawnAttemptContext.countEligiblePlayers(server)));
            if (completed.isSuccessful() && completed.getSpawnedShip() != null)
                LOGGER.info(
                        "[NPShips] Created structure {} ({})",
                        completed.getSpawnedShip().getId(),
                        completed.getModeName());
            activeAttempt = null;
            if (completed.mode == NaturalSpawnAttemptMode.AUTOMATIC
                    && completed.commandSource != null) {
                var selected = completed.getSelectedStructure();
                var result =
                        completed.isSuccessful() && selected != null
                                ? Component.translatable(
                                        "npships.spawn.placed", selected.structureId().toString())
                                : Component.translatable(
                                        "npships.automatic.result_"
                                                + completed
                                                        .getOutcome()
                                                        .name()
                                                        .toLowerCase(Locale.ROOT));
                long remaining =
                        data == null
                                ? -1
                                : Math.max(
                                        0, data.getNextSelectionGameTime() - level.getGameTime());
                long seconds = remaining < 0 ? 0 : remaining / 20 + (remaining % 20 == 0 ? 0 : 1);
                var time =
                        remaining < 0
                                ? Component.literal("\u2014")
                                : Component.literal(
                                        String.format(
                                                Locale.ROOT,
                                                "%d:%02d",
                                                seconds / 60,
                                                seconds % 60));
                var message = Component.translatable("npships.automatic.completed", result, time);
                if (completed.isSuccessful() && completed.bypassedRecentCooldown) {
                    message.append(" ")
                            .append(Component.translatable("npships.automatic.cooldown_bypassed"));
                }
                if (completed.getFailureReason() != null) {
                    String raw = completed.getFailureReason();
                    String detail =
                            (raw.length() > 240 ? raw.substring(0, 237) + "..." : raw)
                                    .replace('\r', ' ')
                                    .replace('\n', ' ')
                                    .replace('\t', ' ');
                    message.withStyle(
                            style ->
                                    style.withHoverEvent(
                                            new net.minecraft.network.chat.HoverEvent(
                                                    net.minecraft.network.chat.HoverEvent.Action
                                                            .SHOW_TEXT,
                                                    Component.literal(detail))));
                }
                if (completed.commandSource != null) {
                    if (completed.isSuccessful())
                        completed.commandSource.sendSuccess(() -> message, false);
                    else completed.commandSource.sendFailure(message);
                }
            }
            // Release ownership before external code; a callback may request a new job.
            if (completed.externalJob != null) {
                var job = completed.externalJob;
                completed.externalJob = null;
                job.collect();
            } else completed.notifyTerminal();
        } finally {
            completed.finishTiming();
            if (timing != null) timing.end(token);
        }
    }

    public static boolean startManual(
            final MinecraftServer server,
            final ServerLevel level,
            final CommandSourceStack source,
            final NaturalSpawnStructureCandidate candidate,
            final BlockPos origin) {

        requireServerThread(server, level);
        if (!acceptsExternalRequests(server)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Server is stopping."));
            return false;
        }
        if (exactPlacement
                || com.eynny.npships.integration.events.NPShipsEventBridge
                        .hasAcceptedDirectOperation()) return false;
        collectFinishedAttempt(server);

        if (activeAttempt != null) {

            source.sendFailure(
                    net.minecraft.network.chat.Component.literal(
                            "Another natural spawn is already "
                                    + "being processed. Active attempt: "
                                    + activeAttempt.getModeName()
                                    + " / "
                                    + activeAttempt.getPhaseName()
                                    + ", elapsed "
                                    + activeAttempt.getElapsedTicks()
                                    + " ticks."));

            return false;
        }

        LOGGER.info(
                "[NPShips NaturalSpawn] "
                        + "Starting manual attempt: "
                        + "structure={}, level={}, origin={}.",
                candidate.commandName(),
                level.dimension().location(),
                origin);

        activeAttempt =
                NaturalSpawnAttempt.startManual(server, level, source, candidate, origin)
                        .managedProfile();
        var started = activeAttempt;
        started.notifyStarted();
        return true;
    }

    /** Starts a selected-structure EXTERNAL search. Null means the server is busy. */
    @Nullable
    public static NaturalSpawnAttempt startExternal(
            final ServerLevel level,
            final NaturalSpawnStructureCandidate candidate,
            final BlockPos referencePoint) {
        return startExternalAttempt(level, candidate, referencePoint, true);
    }

    @Nullable
    private static NaturalSpawnAttempt startExternalAttempt(
            ServerLevel level,
            NaturalSpawnStructureCandidate candidate,
            BlockPos referencePoint,
            boolean notify) {
        requireServerThread(level.getServer(), level);
        if (!acceptsExternalRequests(level.getServer()))
            throw new IllegalStateException("Server is stopping.");
        if (exactPlacement
                || com.eynny.npships.integration.events.NPShipsEventBridge
                        .hasAcceptedDirectOperation()) return null;
        collectFinishedAttempt(level.getServer());
        if (activeAttempt != null) {
            return null;
        }
        var attempt =
                NaturalSpawnAttempt.startExternal(level, candidate, referencePoint)
                        .managedProfile();
        activeAttempt = attempt;
        if (notify) attempt.notifyStarted();
        return attempt;
    }

    /** Internal facade bridge. One callback and one handle, bound only to the active attempt. */
    @Nullable
    public static SpawnJob startExternalJob(
            ServerLevel level,
            NaturalSpawnStructureCandidate candidate,
            BlockPos reference,
            @Nullable java.util.function.Consumer<SpawnResult> observer) {
        var attempt = startExternalAttempt(level, candidate, reference, false);
        if (attempt == null) return null;
        var job = new ManagedSpawnJob(attempt, observer);
        attempt.externalJob = job;
        var timing = attempt.timing();
        if (timing != null) timing.job(job.id());
        attempt.notifyStarted();
        return job;
    }

    /**
     * Synchronous owner fence only: no new attempt, ticker, saved state or scheduler accounting.
     */
    public static boolean beginExactPlacement(MinecraftServer server) {
        requireServerThread(server, server.overworld());
        if (!acceptsExternalRequests(server)
                || activeAttempt != null
                || exactPlacement
                || com.eynny.npships.integration.events.NPShipsEventBridge
                        .hasAcceptedDirectOperation()) return false;
        exactPlacement = true;
        return true;
    }

    public static void endExactPlacement(MinecraftServer server) {
        requireServerThread(server, server.overworld());
        exactPlacement = false;
    }

    public static boolean acceptsExternalRequests(MinecraftServer server) {
        return !stopping && server.isRunning() && !server.isStopped();
    }

    private static void requireServerThread(final MinecraftServer server, final ServerLevel level) {
        if (level.getServer() != server || !server.isSameThread()) {
            throw new IllegalStateException(
                    "Natural spawn must be started on the target server thread.");
        }
    }

    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent event) {
        var level = event.getServer().overworld();
        var data = NPShipsNaturalSpawnData.peek(level);
        playerCountDirty = true;
        eligiblePlayerCount = 0;
        nextRosterCheck = 0L;
        stopping = true;
        try {
            if (activeAttempt != null) {
                activeAttempt.cancel();
                collectFinishedAttempt(event.getServer(), data);
            }
        } finally {
            stopping = false;
        }

        lastOutcome = null;
    }

    @Nullable
    public static NaturalSpawnAttempt getActiveAttempt() {
        return activeAttempt;
    }
}
