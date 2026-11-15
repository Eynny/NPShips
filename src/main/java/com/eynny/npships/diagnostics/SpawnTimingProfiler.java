package com.eynny.npships.diagnostics;

import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.resources.ResourceWorkMeter;
import com.mojang.logging.LogUtils;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Server-thread, attempt-owned observation only. No world, job, scheduler or native owner. */
public final class SpawnTimingProfiler {
    public enum Stage {
        SLICE("server slice"),
        INITIALIZE("initialize/restrictions"),
        PLAYERS("eligible players"),
        INITIAL_POINT("initial point/frozen pressure"),
        CATALOGUE("catalogue/eligibility"),
        LOTTERY("rarity/groups/C8"),
        ENVELOPE("search envelope/snapshot"),
        SEARCH("candidate search/environment"),
        OBSERVE("world observation"),
        FALLBACK("candidate/fallback transition"),
        CHUNKS("chunk readiness/tickets"),
        RENEW("ticket renewal"),
        RELEASE("ticket release"),
        SAFETY("overlap/proximity/relevance"),
        ROTATION("rotation clearance"),
        PLACEMENT("placement pipeline"),
        DECODE("schematic decode"),
        COMPOSE("schematic composition"),
        CREW("generated crew"),
        PREPARE("devices/patch preparation"),
        CC("ComputerCraft preparation"),
        STATIC("native static placement"),
        ASSEMBLY("native Simulated/Sable assembly (atomic)"),
        PATCHES("native/provider patch callbacks"),
        CLEANUP("cleanup"),
        COLLECTION("terminal collection/callback");
        final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    public enum Gap {
        NONE,
        CHUNKS,
        PREPARATION
    }

    private static final int CANDIDATES = 8, DISTINCT_TICKETS = 4096;
    private final UUID id;
    private final String mode;
    @Nullable private UUID jobId;
    private final LongSupplier clock;
    private final BooleanSupplier enabled;
    private final Consumer<String> output;
    private final long started, startTick;
    private final long[] count = new long[Stage.values().length],
            inclusive = new long[count.length],
            exclusive = new long[count.length],
            maximum = new long[count.length];
    private int[] stack = new int[32];
    private long[] starts = new long[32], children = new long[32];
    private int depth;
    private boolean retired, reported;
    private String terminal;
    private long terminalTick, synchronous, maxSlice, lastEnd, chunkWait, preparationYield;
    private Gap gap = Gap.NONE;
    private long searchCredits, scanCredits, nativeReads, readinessProbes, renewals, crew, yields;
    private long ownedTickets, peakTickets;
    private long crewRequested, crewFactoryProbes, crewWork;
    private final HashSet<Long> tickets = new HashSet<>();
    private boolean ticketSetCapped;
    private int candidates, catalogueChecked, catalogueEligible, drawSlots;
    private final String[] names = new String[CANDIDATES];
    private final long[] candidateSearchTime = new long[CANDIDATES],
            candidateObserveTime = new long[CANDIDATES],
            candidatePrepareTime = new long[CANDIDATES],
            candidateAssemblyTime = new long[CANDIDATES];
    private final long[] candidateSearch = new long[CANDIDATES],
            candidateScan = new long[CANDIDATES];

    /** OFF returns before UUID creation, clock sampling, arrays or strings. */
    @Nullable
    public static SpawnTimingProfiler start(@Nullable UUID id, String mode, long tick) {
        if (!NPShipsRuntimeConfig.developerModeEnabled()) return null;
        return new SpawnTimingProfiler(
                id == null ? UUID.randomUUID() : id,
                mode,
                tick,
                System::nanoTime,
                NPShipsRuntimeConfig::developerModeEnabled,
                text -> LogUtils.getLogger().info("{}", text));
    }

    SpawnTimingProfiler(
            UUID id,
            String mode,
            long tick,
            LongSupplier clock,
            BooleanSupplier enabled,
            Consumer<String> output) {
        this.id = id;
        this.mode = mode;
        this.startTick = tick;
        this.clock = clock;
        this.enabled = enabled;
        this.output = output;
        started = clock.getAsLong();
    }

    /** Once disabled, a partial profile never resumes, even if the mode is enabled again. */
    public void discard() {
        retired = true;
        tickets.clear();
    }

    public boolean active() {
        if (!retired && !enabled.getAsBoolean()) {
            retired = true;
            tickets.clear();
        }
        return !retired && !reported;
    }

    /** Primitive stack token: no per-invocation scope, lambda or stopwatch allocation. */
    public long begin(Stage stage) {
        if (!active()) return 0;
        long now = clock.getAsLong();
        if (depth == 0 && lastEnd != 0) {
            if (gap == Gap.CHUNKS) chunkWait += now - lastEnd;
            else if (gap == Gap.PREPARATION) preparationYield += now - lastEnd;
            gap = Gap.NONE;
        }
        if (depth == stack.length) {
            stack = Arrays.copyOf(stack, depth * 2);
            starts = Arrays.copyOf(starts, depth * 2);
            children = Arrays.copyOf(children, depth * 2);
        }
        stack[depth] = stage.ordinal();
        starts[depth] = now;
        children[depth] = 0;
        return ++depth;
    }

    public void end(long token) {
        if (token == 0) return;
        int index = (int) token - 1;
        // All measured owners unwind in finally, including reentrant cancel/collection.
        if (depth != index + 1) throw new IllegalStateException("Unbalanced spawn timing scope");
        long now = clock.getAsLong(), elapsed = now - starts[index];
        depth = index;
        if (active()) {
            int phase = stack[index];
            count[phase]++;
            inclusive[phase] += elapsed;
            exclusive[phase] += elapsed - children[index];
            maximum[phase] = Math.max(maximum[phase], elapsed);
            int candidate = candidates - 1;
            if (candidate >= 0 && candidate < CANDIDATES) {
                if (phase == Stage.SEARCH.ordinal()) candidateSearchTime[candidate] += elapsed;
                else if (phase == Stage.OBSERVE.ordinal())
                    candidateObserveTime[candidate] += elapsed;
                else if (phase == Stage.PREPARE.ordinal())
                    candidatePrepareTime[candidate] += elapsed;
                else if (phase == Stage.ASSEMBLY.ordinal())
                    candidateAssemblyTime[candidate] += elapsed;
            }
            if (depth > 0) children[depth - 1] += elapsed;
            else {
                synchronous += elapsed;
                maxSlice = Math.max(maxSlice, elapsed);
                lastEnd = now;
            }
            if (depth == 0 && terminal != null) report(now);
        }
    }

    public void gap(Gap value) {
        if (active()) {
            gap = value;
            if (value == Gap.PREPARATION) yields++;
        }
    }

    public void candidate(String name) {
        if (!active()) return;
        if (candidates < CANDIDATES) names[candidates] = bounded(name, 96);
        candidates++;
    }

    public void search(long searched, long scanned) {
        if (!active()) return;
        searchCredits += searched;
        scanCredits += scanned;
        int index = candidates - 1;
        if (index >= 0 && index < CANDIDATES) {
            candidateSearch[index] += searched;
            candidateScan[index] += scanned;
        }
    }

    public void drawSlots(int slots) {
        if (active()) drawSlots = slots;
    }

    public void job(UUID id) {
        if (active()) jobId = id;
    }

    public void catalogue(int checked, int eligible) {
        if (active()) {
            catalogueChecked += checked;
            catalogueEligible += eligible;
        }
    }

    public void nativeRead() {
        if (!retired) nativeReads++;
    }

    public void readinessProbe() {
        if (!retired) readinessProbes++;
    }

    public void renewed() {
        if (!retired) renewals++;
    }

    private final long[] ownedWork = new long[ResourceWorkMeter.Family.values().length];
    private long preparedCapacityPeak;

    public void ownedResources(ResourceWorkMeter work, long capacity) {
        if (!active()) return;
        preparedCapacityPeak = Math.max(preparedCapacityPeak, capacity);
        for (var family : ResourceWorkMeter.Family.values())
            ownedWork[family.ordinal()] =
                    diagnosticTotal(ownedWork[family.ordinal()], work.count(family));
    }

    public void crewCreated() {
        if (!retired) crew++;
    }

    /** Once per detached composition; these submissions are not bytes or elapsed time. */
    public void crewResources(long requested, long probes, long work) {
        if (!active()) return;
        crewRequested = diagnosticTotal(crewRequested, requested);
        crewFactoryProbes = diagnosticTotal(crewFactoryProbes, probes);
        crewWork = diagnosticTotal(crewWork, work);
    }

    private static long diagnosticTotal(long total, long amount) {
        return amount > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + amount;
    }

    public void ticketAdded(long key) {
        if (!active()) return;
        peakTickets = Math.max(peakTickets, ++ownedTickets);
        if (tickets.size() < DISTINCT_TICKETS) tickets.add(key);
        else if (!tickets.contains(key)) ticketSetCapped = true;
    }

    public void ticketRemoved() {
        if (!retired) ownedTickets--;
    }

    /**
     * Call after owned collection. Reentrant terminal callbacks cannot emit before assembly
     * unwinds.
     */
    public void finished(String outcome, long tick) {
        if (!active() || terminal != null) return;
        terminal = bounded(outcome, 64);
        terminalTick = tick;
        if (depth == 0) report(clock.getAsLong());
    }

    private void report(long now) {
        if (!active()) return;
        reported = true;
        StringBuilder text = new StringBuilder(4096);
        text.append("[NPShips spawn timing] id=")
                .append(id)
                .append(" mode=")
                .append(mode)
                .append(" job_id=")
                .append(jobId == null ? "none" : jobId)
                .append(" outcome=")
                .append(terminal)
                .append(" wall_ms=")
                .append(ms(now - started))
                .append(" sync_ms=")
                .append(ms(synchronous))
                .append(" ticks=")
                .append(Math.max(0, terminalTick - startTick))
                .append(" max_slice_ms=")
                .append(ms(maxSlice))
                .append(" chunk_wait_gap_ms=")
                .append(ms(chunkWait))
                .append(" preparation_yield_gap_ms=")
                .append(ms(preparationYield))
                .append(" yields=")
                .append(yields)
                .append("\n  credits(search/scan)=")
                .append(searchCredits)
                .append('/')
                .append(scanCredits)
                .append(" scanner_native_state_reads=")
                .append(nativeReads)
                .append(" readiness_probes=")
                .append(readinessProbes)
                .append(" ticket_distinct=")
                .append(ticketSetCapped ? ">=" : "")
                .append(tickets.size())
                .append(" ticket_peak=")
                .append(peakTickets)
                .append(" ticket_remaining=")
                .append(ownedTickets)
                .append(" renewals=")
                .append(renewals)
                .append(" prepared_crew=")
                .append(crew)
                .append(" catalogue_checked/eligible=")
                .append(catalogueChecked)
                .append('/')
                .append(catalogueEligible)
                .append(" candidates=")
                .append(candidates)
                .append(" c8_slots=")
                .append(drawSlots)
                .append(" fallbacks=")
                .append(Math.max(0, candidates - 1));
        text.append("\n  crew_requests/probes/work=")
                .append(crewRequested)
                .append('/')
                .append(crewFactoryProbes)
                .append('/')
                .append(crewWork);
        for (Stage stage : Stage.values()) {
            int i = stage.ordinal();
            if (count[i] == 0) continue;
            text.append("\n  ")
                    .append(stage.label)
                    .append(": calls=")
                    .append(count[i])
                    .append(" inclusive_ms=")
                    .append(ms(inclusive[i]))
                    .append(" exclusive_ms=")
                    .append(ms(exclusive[i]))
                    .append(" max_ms=")
                    .append(ms(maximum[i]));
        }
        for (int i = 0; i < Math.min(candidates, CANDIDATES); i++)
            text.append("\n  candidate[")
                    .append(i + 1)
                    .append("]=")
                    .append(names[i])
                    .append(" credits(search/scan)=")
                    .append(candidateSearch[i])
                    .append('/')
                    .append(candidateScan[i])
                    .append(" search_ms=")
                    .append(ms(candidateSearchTime[i]))
                    .append(" observe_ms=")
                    .append(ms(candidateObserveTime[i]))
                    .append(" prepare_ms=")
                    .append(ms(candidatePrepareTime[i]))
                    .append(" assembly_ms=")
                    .append(ms(candidateAssemblyTime[i]));
        text.append("\nowned_work prepared_capacity_at_close=").append(preparedCapacityPeak);
        for (var family : ResourceWorkMeter.Family.values())
            text.append(' ').append(family.name()).append('=').append(ownedWork[family.ordinal()]);
        tickets.clear();
        output.accept(text.toString());
    }

    private static String ms(long nanos) {
        long micros = nanos / 1000L, fraction = micros % 1000;
        return (micros / 1000)
                + "."
                + (fraction < 100 ? "0" : "")
                + (fraction < 10 ? "0" : "")
                + fraction;
    }

    private static String bounded(String value, int limit) {
        String part = value.substring(0, Math.min(value.length(), limit));
        return part.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }
}
