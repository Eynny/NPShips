package com.eynny.npships.ship;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

public final class ShipKineticRepair {

    private ShipKineticRepair() {}

    private static final List<PendingRepair> PENDING = new ArrayList<>();

    private static final class PendingRepair {

        final ServerLevel level;
        final ServerSubLevel subLevel;
        int ticksRemaining;
        final List<
                        java.util.Map.Entry<
                                net.minecraft.resources.ResourceLocation,
                                java.util.function.Consumer<ServerSubLevel>>>
                repairs;

        PendingRepair(
                ServerLevel level,
                ServerSubLevel subLevel,
                int ticksRemaining,
                List<
                                java.util.Map.Entry<
                                        net.minecraft.resources.ResourceLocation,
                                        java.util.function.Consumer<ServerSubLevel>>>
                        repairs) {
            this.level = level;
            this.subLevel = subLevel;
            this.ticksRemaining = ticksRemaining;
            this.repairs = List.copyOf(repairs);
        }
    }

    public static void schedule(ServerLevel level, ServerSubLevel subLevel, int delayTicks) {
        schedule(level, subLevel, delayTicks, defaultRepairs());
    }

    private static List<
                    java.util.Map.Entry<
                            net.minecraft.resources.ResourceLocation,
                            java.util.function.Consumer<ServerSubLevel>>>
            defaultRepairs() {
        var result =
                new ArrayList<
                        java.util.Map.Entry<
                                net.minecraft.resources.ResourceLocation,
                                java.util.function.Consumer<ServerSubLevel>>>();
        var policy = com.eynny.npships.config.NPShipsRuntimeConfig.patchPolicy();
        var code =
                com.eynny.npships.api.patches.PatchTypes.capabilities(
                        com.eynny.npships.authoring.patches.PatchNativePreparation.class);
        var callbacks =
                new java.util.TreeMap<
                        net.minecraft.resources.ResourceLocation,
                        java.util.function.Consumer<ServerSubLevel>>();
        for (var entry : code.entrySet())
            if (policy.enabled(entry.getKey())) {
                var callback = entry.getValue().delayedRepair();
                if (callback != null) callbacks.put(entry.getKey(), callback);
            }
        com.eynny.npships.authoring.patches.PatchCode.validate(policy, callbacks.keySet());
        for (var id :
                com.eynny.npships.api.patches.PatchOrdering.discoveryOrder(
                        com.eynny.npships.api.patches.PatchStage.POST_PLACEMENT_MAINTENANCE,
                        callbacks.keySet(),
                        policy)) result.add(java.util.Map.entry(id, callbacks.get(id)));
        return List.copyOf(result);
    }

    public static void schedule(
            ServerLevel level,
            ServerSubLevel subLevel,
            int delayTicks,
            List<
                            java.util.Map.Entry<
                                    net.minecraft.resources.ResourceLocation,
                                    java.util.function.Consumer<ServerSubLevel>>>
                    repairs) {
        if (!repairs.isEmpty())
            PENDING.add(new PendingRepair(level, subLevel, delayTicks, repairs));
    }

    /** Cancel only this captured native owner; unrelated repairs keep their deadlines. */
    static void cancel(ServerLevel level, ServerSubLevel body) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Repair cancellation requires the server thread");
        PENDING.removeIf(repair -> repair.level == level && repair.subLevel == body);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {

        if (PENDING.isEmpty()) return;

        var iterator = PENDING.iterator();
        List<PendingRepair> due = null;

        while (iterator.hasNext()) {

            PendingRepair repair = iterator.next();

            if (repair.level.getServer() != event.getServer()) continue;
            if (!com.eynny.npships.integration.NativeBlockAccess.current(
                    repair.level, repair.subLevel)) {
                iterator.remove();
                continue;
            }

            repair.ticksRemaining--;

            if (repair.ticksRemaining > 0) continue;

            iterator.remove();
            if (due == null) due = new ArrayList<>();
            due.add(repair);
        }
        // Publish the due batch before invoking native code. A callback may synchronously spawn
        // another body and schedule its own repair without invalidating this queue traversal.
        if (due == null) return;
        for (var repair : due) {
            for (var callback : repair.repairs) {
                // Earlier callbacks can synchronously remove this body or another due owner.
                if (!com.eynny.npships.integration.NativeBlockAccess.current(
                        repair.level, repair.subLevel)) break;
                try {
                    callback.getValue().accept(repair.subLevel);
                } catch (Exception failure) {
                    com.mojang.logging.LogUtils.getLogger()
                            .error(
                                    "Patch {} delayed native repair failed for body {}",
                                    callback.getKey(),
                                    repair.subLevel.getUniqueId(),
                                    failure);
                }
            }
        }
    }

    public static void rebuild(ServerSubLevel subLevel) {
        for (var callback : defaultRepairs()) callback.getValue().accept(subLevel);
    }

    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent event) {
        PENDING.removeIf(repair -> repair.level.getServer() == event.getServer());
    }
}
