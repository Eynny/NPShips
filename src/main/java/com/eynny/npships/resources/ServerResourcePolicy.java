package com.eynny.npships.resources;

import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/** Captures WORLD-restart resource settings once per server, never from an idle tick. */
@EventBusSubscriber(modid = "npships")
public final class ServerResourcePolicy {
    private static final long MIB = 1024L * 1024L;
    private static final Map<MinecraftServer, State> POLICIES = new WeakHashMap<>();
    private static ModConfigSpec.LongValue centers,
            dependencies,
            everUnready,
            working,
            crewWork,
            stall,
            generation,
            retained;
    private static ModConfigSpec.IntValue tickets, concurrentUnready, crew, probes;

    private record State(Limits limits, ResourceMemoryBudget memory) {}

    public record Limits(
            long maxTargetChunks,
            long maxDependencyChunks,
            int maxOwnedTickets,
            int maxConcurrentUnreadyChunks,
            long maxEverUnreadyChunks,
            long maxWorkingBytes,
            int maxGeneratedCrew,
            int maxFactoryProbes,
            long maxCrewWork,
            long maxOpportunityWork,
            long chunkStallTicks,
            long maxCatalogueGenerationBytes,
            long maxCatalogueRetainedBytes) {
        public Limits {
            if (maxTargetChunks < 1
                    || maxDependencyChunks < 1
                    || maxOwnedTickets < 1
                    || maxConcurrentUnreadyChunks < 1
                    || maxEverUnreadyChunks < 0
                    || maxWorkingBytes < 1
                    || maxGeneratedCrew < 0
                    || maxFactoryProbes < 0
                    || maxCrewWork < 0
                    || maxOpportunityWork < 0
                    || chunkStallTicks < 0
                    || maxCatalogueGenerationBytes < 1
                    || maxCatalogueRetainedBytes < 1)
                throw new IllegalArgumentException("Invalid NPShips resource policy");
        }

        public static Limits defaults(long heap) {
            if (heap < 32) throw new IllegalArgumentException("Invalid maximum heap");
            return new Limits(
                    8192, 16384, 8192, 64, 0, heap / 8, 512, 4096, 0, 0, 0, heap / 32, heap / 16);
        }
    }

    private ServerResourcePolicy() {}

    public static void define(ModConfigSpec.Builder b) {
        b.comment(
                        "Server-owned admission allowances. Raising them permits more content and resource use. These are not native Minecraft/Sable memory or tick-time guarantees. World/server restart required.")
                .push("resources");
        centers =
                number(
                        b,
                        "max_target_chunks",
                        8192,
                        1,
                        "Distinct dimension-qualified chunk centers over one opportunity, including fallback. Refuse before allocating dense envelopes. Not generated chunks.");
        dependencies =
                number(
                        b,
                        "max_dependency_chunks",
                        16384,
                        1,
                        "Distinct native FULL dependency positions over the opportunity. Exact union, including sparse overlaps; not a count of chunks actually generated.");
        tickets =
                integer(
                        b,
                        "max_owned_chunk_tickets",
                        8192,
                        1,
                        "Maximum simultaneously owned temporary NPShips chunk tickets. Ready required tickets stay owned through placement.");
        concurrentUnready =
                integer(
                        b,
                        "max_concurrent_unready_chunks",
                        64,
                        1,
                        "Pace native submissions by the number of owned targets not yet observed ready. Does not cap the total legitimate cold footprint; native background futures can outlive tickets.");
        everUnready =
                number(
                        b,
                        "max_ever_unready_chunks",
                        0,
                        0,
                        "Optional cumulative distinct unavailable targets per opportunity. 0 disables this extra refusal; concurrent native submission is paced separately.");
        working =
                number(
                        b,
                        "working_memory_mib",
                        0,
                        0,
                        "Accounted NPShips working allocation capacity in MiB. 0 uses one eighth of JVM maximum heap. Known owned growth and copy overlap are charged; opaque native decode, Minecraft, Sable and other mods are not universally bounded.",
                        Long.MAX_VALUE / MIB);
        crew =
                integer(
                        b,
                        "max_generated_crew",
                        512,
                        0,
                        "Cumulative sampled requested generated-crew iterations per composition, including impossible placements. Authored existing entities are separate. 0 allows no generated iterations.");
        probes =
                integer(
                        b,
                        "max_crew_factory_probes",
                        4096,
                        0,
                        "Expanded native validation factory occurrences per composition, including duplicates, tag members and count-zero rules. Generation factories are counted separately. 0 permits no probes.");
        crewWork =
                number(
                        b,
                        "max_crew_work",
                        0,
                        0,
                        "Optional crew primitive-work quota. 0 meters without a total quota because no universal numerical threshold is established. Credits are not time or bytes.");
        stall =
                number(
                        b,
                        "chunk_stall_ticks",
                        0,
                        0,
                        "Optional game ticks without readiness for the same blocking chunk request. 0 disables this uncalibrated deadline. Productive scanning, integrated-server pause and real-time sleep do not spend a universal attempt lease.");
        generation =
                number(
                        b,
                        "catalogue_generation_mib",
                        0,
                        0,
                        "Accounted retained payload/metadata capacity per catalogue generation in MiB. 0 uses one thirty-second of maximum JVM heap. Opaque decoded/native heap is not a byte-for-byte promise.",
                        Long.MAX_VALUE / MIB);
        retained =
                number(
                        b,
                        "catalogue_retained_mib",
                        0,
                        0,
                        "Combined server-owned catalogue payload/metadata capacity across current, pinned old and replacement generations in MiB. 0 uses one sixteenth of maximum JVM heap. Refuse replacement and preserve last-good; external API handles may retain data.",
                        Long.MAX_VALUE / MIB);
        b.pop();
    }

    private static ModConfigSpec.LongValue number(
            ModConfigSpec.Builder b, String key, long value, long min, String comment) {
        return number(b, key, value, min, comment, Long.MAX_VALUE);
    }

    private static ModConfigSpec.LongValue number(
            ModConfigSpec.Builder b, String key, long value, long min, String comment, long max) {
        return b.comment(comment)
                .translation("npships.configuration.resources." + key)
                .worldRestart()
                .defineInRange(key, value, min, max);
    }

    private static ModConfigSpec.IntValue integer(
            ModConfigSpec.Builder b, String key, int value, int min, String comment) {
        return b.comment(comment)
                .translation("npships.configuration.resources." + key)
                .worldRestart()
                .defineInRange(key, value, min, Integer.MAX_VALUE);
    }

    private static long bytes(long configured, long automatic) {
        return configured == 0 ? automatic : Math.multiplyExact(configured, MIB);
    }

    private static Limits configured() {
        var defaults = Limits.defaults(Runtime.getRuntime().maxMemory());
        if (!NPShipsRuntimeConfig.SPEC.isLoaded()) return defaults;
        return new Limits(
                centers.get(),
                dependencies.get(),
                tickets.get(),
                concurrentUnready.get(),
                everUnready.get(),
                bytes(working.get(), defaults.maxWorkingBytes()),
                crew.get(),
                probes.get(),
                crewWork.get(),
                0,
                stall.get(),
                bytes(generation.get(), defaults.maxCatalogueGenerationBytes()),
                bytes(retained.get(), defaults.maxCatalogueRetainedBytes()));
    }

    private static State state(MinecraftServer server) {
        if (!NPShipsRuntimeConfig.SPEC.isLoaded())
            throw new IllegalStateException(
                    "NPShips resource ownership requires loaded SERVER configuration");
        return POLICIES.computeIfAbsent(
                server,
                s -> {
                    var p = configured();
                    return new State(p, new ResourceMemoryBudget(p.maxWorkingBytes()));
                });
    }

    public static Limits current(@Nullable MinecraftServer server) {
        if (server == null) return configured();
        if (!NPShipsRuntimeConfig.SPEC.isLoaded())
            return Limits.defaults(Runtime.getRuntime().maxMemory());
        synchronized (POLICIES) {
            return state(server).limits();
        }
    }

    public static ResourceMemoryBudget memory(MinecraftServer server) {
        synchronized (POLICIES) {
            return state(server).memory();
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        synchronized (POLICIES) {
            POLICIES.remove(event.getServer());
        }
    }
}
