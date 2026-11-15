package com.eynny.npships.ship;

import com.eynny.npships.api.patches.PatchOrdering;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.patches.PatchCode;
import com.eynny.npships.authoring.patches.PatchNativePreparation;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.naturalspawn.NPShipsNaturalSpawnManager;
import com.eynny.npships.resources.SpawnWorkBudget;
import com.mojang.logging.LogUtils;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ShipKineticRepair {

    private ShipKineticRepair() {}

    // This is the existing repair queue, including its unfinished cooperative callbacks.
    private static final LinkedList<PendingRepair> PENDING = new LinkedList<>();
    private static PendingRepair executing;
    private static MinecraftServer lastServer;
    private static long lastTick = Long.MIN_VALUE;
    private static long sequence;
    private static final int TURN_UNITS = 32;

    private static final class PendingRepair {
        final ServerLevel level;
        final ServerSubLevel subLevel;
        final long admitted = ++sequence;
        final long dueTick;
        final List<Map.Entry<ResourceLocation, Consumer<ServerSubLevel>>> repairs;
        int callbackIndex;
        long visitedTick = Long.MIN_VALUE;
        PatchNativePreparation.RepairCursor cursor;
        boolean cancelled;

        PendingRepair(
                ServerLevel level,
                ServerSubLevel subLevel,
                int delayTicks,
                List<Map.Entry<ResourceLocation, Consumer<ServerSubLevel>>> repairs) {
            this.level = level;
            this.subLevel = subLevel;
            long now = level.getServer().getTickCount();
            // Scheduling from a callback occurs after this tick's queue visit. Other callers
            // retain the original delay measured in actual repair-event opportunities.
            long first = lastServer == level.getServer() && lastTick == now ? now + 1 : now;
            dueTick = first + Math.max(1L, delayTicks) - 1;
            this.repairs = List.copyOf(repairs);
        }

        boolean current() {
            return !cancelled && NativeBlockAccess.current(level, subLevel);
        }

        void closeCursor() {
            var old = cursor;
            cursor = null;
            if (old != null) old.close();
        }

        boolean step(SpawnWorkBudget budget) {
            int[] remaining = {TURN_UNITS};
            BooleanSupplier allowance =
                    () ->
                            remaining[0] > 0
                                    && budget.hasRemaining()
                                    && current()
                                    && remaining[0]-- > 0;
            while (callbackIndex < repairs.size() && current()) {
                var callback = repairs.get(callbackIndex);
                try {
                    if (cursor == null) {
                        if (!allowance.getAsBoolean()) return false;
                        if (callback.getValue()
                                instanceof PatchNativePreparation.CooperativeRepair cooperative)
                            cursor = cooperative.begin(subLevel);
                        else {
                            // External Consumer callbacks remain one admitted indivisible unit.
                            callback.getValue().accept(subLevel);
                            callbackIndex++;
                            continue;
                        }
                    }
                    if (!cursor.step(allowance)) return false;
                    closeCursor();
                    callbackIndex++;
                } catch (Exception failure) {
                    try {
                        closeCursor();
                    } catch (Exception cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    LogUtils.getLogger()
                            .error(
                                    "Patch {} delayed native repair failed for body {}",
                                    callback.getKey(),
                                    subLevel.getUniqueId(),
                                    failure);
                    callbackIndex++;
                }
            }
            return !current() || callbackIndex >= repairs.size();
        }
    }

    public static void schedule(ServerLevel level, ServerSubLevel subLevel, int delayTicks) {
        schedule(level, subLevel, delayTicks, defaultRepairs());
    }

    private static List<Map.Entry<ResourceLocation, Consumer<ServerSubLevel>>> defaultRepairs() {
        var result = new ArrayList<Map.Entry<ResourceLocation, Consumer<ServerSubLevel>>>();
        var policy = NPShipsRuntimeConfig.patchPolicy();
        var code = PatchTypes.capabilities(PatchNativePreparation.class);
        var callbacks = new TreeMap<ResourceLocation, Consumer<ServerSubLevel>>();
        for (var entry : code.entrySet())
            if (policy.enabled(entry.getKey())) {
                var callback = entry.getValue().delayedRepair();
                if (callback != null) callbacks.put(entry.getKey(), callback);
            }
        PatchCode.validate(policy, callbacks.keySet());
        for (var id :
                PatchOrdering.discoveryOrder(
                        PatchStage.POST_PLACEMENT_MAINTENANCE, callbacks.keySet(), policy))
            result.add(Map.entry(id, callbacks.get(id)));
        return List.copyOf(result);
    }

    public static void schedule(
            ServerLevel level,
            ServerSubLevel subLevel,
            int delayTicks,
            List<Map.Entry<ResourceLocation, Consumer<ServerSubLevel>>> repairs) {
        if (!repairs.isEmpty())
            PENDING.add(new PendingRepair(level, subLevel, delayTicks, repairs));
    }

    /** Cancel only this captured native owner; unrelated repairs keep their deadlines. */
    static void cancel(ServerLevel level, ServerSubLevel body) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Repair cancellation requires the server thread");
        if (executing != null && executing.level == level && executing.subLevel == body)
            executing.cancelled = true;
        var iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            var repair = iterator.next();
            if (repair.level != level || repair.subLevel != body) continue;
            iterator.remove();
            repair.cancelled = true;
            repair.closeCursor();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        long now = server.getTickCount();
        if (lastServer == server && lastTick == now) return;
        lastServer = server;
        lastTick = now;
        if (PENDING.isEmpty()) return;
        var budget = NPShipsNaturalSpawnManager.workBudget(server);
        int turns = PENDING.size();
        long admitted = sequence;
        // Poll before native code: reentrant schedule/cancel never invalidates an iterator.
        // Only the starting cohort gets one bounded turn; newly scheduled work waits its deadline.
        for (int i = 0; i < turns && !PENDING.isEmpty(); i++) {
            try (var work = budget.tryStartWork()) {
                if (work == null) break;
                var repair = PENDING.removeFirst();
                if (repair.level.getServer() != server
                        || repair.admitted > admitted
                        || repair.visitedTick == now) {
                    PENDING.addLast(repair);
                    continue;
                }
                repair.visitedTick = now;
                if (!repair.current()) {
                    repair.closeCursor();
                    continue;
                }
                if (now < repair.dueTick) {
                    PENDING.addLast(repair);
                    continue;
                }
                executing = repair;
                boolean done;
                try {
                    done = repair.step(budget);
                } finally {
                    executing = null;
                    if (repair.cancelled) repair.closeCursor();
                }
                if (done || !repair.current()) repair.closeCursor();
                else PENDING.addLast(repair);
            }
        }
    }

    /** Explicit synchronous compatibility entry; the spawn finalizer only schedules repairs. */
    public static void rebuild(ServerSubLevel subLevel) {
        for (var callback : defaultRepairs()) callback.getValue().accept(subLevel);
    }

    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent event) {
        var iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            var repair = iterator.next();
            if (repair.level.getServer() != event.getServer()) continue;
            iterator.remove();
            repair.cancelled = true;
            repair.closeCursor();
        }
        if (executing != null && executing.level.getServer() == event.getServer())
            executing.cancelled = true;
        if (lastServer == event.getServer()) {
            lastServer = null;
            lastTick = Long.MIN_VALUE;
        }
    }
}
