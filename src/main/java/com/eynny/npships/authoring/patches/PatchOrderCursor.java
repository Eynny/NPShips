package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.*;

import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** The existing provider graph ordering with incremental instance indexing and topological work. */
final class PatchOrderCursor implements AutoCloseable {
    private static final Comparator<PatchIdentity> INSTANCES =
            Comparator.comparing(PatchIdentity::instanceId)
                    .thenComparing(
                            PatchIdentity::occurrenceId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));
    private final PatchStage stage;
    private final List<PatchIdentity> source;
    private final PatchPolicy policy;
    private final List<ResourceLocation> defaults;
    private final LongConsumer reserve, release;
    private long reserved;
    private final Map<ResourceLocation, PatchTypes.Provider> registry = PatchTypes.providers();
    private Map<ResourceLocation, NavigableSet<PatchIdentity>> active = new TreeMap<>();
    private Set<UUID> distinct = new HashSet<>();
    private Set<ResourceLocation> checked = new HashSet<>(), defaultSeen = new HashSet<>();
    private Deque<ResourceLocation> pending = new ArrayDeque<>();
    private Map<ResourceLocation, Set<ResourceLocation>> outgoing = new TreeMap<>();
    private Map<ResourceLocation, Integer> indegree = new HashMap<>(),
            rank = new HashMap<>(),
            defaultRank = new HashMap<>();
    private List<PatchIdentity> result = new ArrayList<>();
    private Iterator<ResourceLocation> providers, dependencies, edges, conflicts;
    private Iterator<PatchIdentity> instances;
    private PriorityQueue<ResourceLocation> ready;
    private Set<ResourceLocation> cycle;
    private PatchIdentity identity;
    private ResourceLocation dependencyRoot, current;
    private int phase, index, edgeKind, emitted;
    private long progress;
    private boolean closed;

    PatchOrderCursor(
            PatchStage stage,
            List<PatchIdentity> source,
            PatchPolicy policy,
            List<ResourceLocation> defaults,
            LongConsumer reserve) {
        this(stage, source, policy, defaults, reserve, ignored -> {});
    }

    PatchOrderCursor(
            PatchStage stage,
            List<PatchIdentity> source,
            PatchPolicy policy,
            List<ResourceLocation> defaults,
            LongConsumer reserve,
            LongConsumer release) {
        this.stage = Objects.requireNonNull(stage);
        this.source = Objects.requireNonNull(source);
        this.policy = Objects.requireNonNull(policy);
        this.defaults = Objects.requireNonNull(defaults);
        this.reserve = Objects.requireNonNull(reserve);
        this.release = Objects.requireNonNull(release);
    }

    private void charge(long bytes) {
        long next = Math.addExact(reserved, bytes);
        reserve.accept(bytes);
        reserved = next;
    }

    boolean step(BooleanSupplier canContinue) {
        if (closed) throw new IllegalStateException("Patch ordering is closed");
        while (phase != 12 && canContinue.getAsBoolean()) {
            switch (phase) {
                case 0 -> {
                    if (index == defaults.size()) {
                        index = 0;
                        phase++;
                        break;
                    }
                    var id = Objects.requireNonNull(defaults.get(index));
                    if (!defaultSeen.add(id))
                        throw new IllegalArgumentException(
                                "Duplicate patch provider compatibility default");
                    charge(48);
                    defaultRank.put(id, index++);
                }
                case 1 -> {
                    if (index == source.size()) {
                        providers = active.keySet().iterator();
                        phase = 4;
                        break;
                    }
                    identity = Objects.requireNonNull(source.get(index++));
                    if (!distinct.add(identity.instanceId()))
                        throw new IllegalArgumentException(
                                "Duplicate patch instance: "
                                        + identity.type()
                                        + "/"
                                        + identity.instanceId());
                    charge(32);
                    var provider = registry.get(identity.type());
                    if (provider == null)
                        throw new IllegalArgumentException(
                                "Unknown patch provider: " + identity.type());
                    if (!policy.enabled(identity.type())) {
                        if (provider.registration().disabledBehavior()
                                        == PatchRegistration.DisabledBehavior.SKIP
                                && provider.execution() != PatchTypes.Execution.LEGACY_DISPATCH) {
                            identity = null;
                            break;
                        }
                        throw new IllegalArgumentException(
                                "Patch provider disabled by SERVER policy: " + identity.type());
                    }
                    if (!active.containsKey(identity.type())) {
                        dependencyRoot = identity.type();
                        pending.add(dependencyRoot);
                        phase = 2;
                    } else phase = 3;
                }
                case 2 -> {
                    if (dependencies != null && dependencies.hasNext()) {
                        pending.add(dependencies.next());
                        charge(8);
                        break;
                    }
                    dependencies = null;
                    if (pending.isEmpty()) {
                        phase = 3;
                        break;
                    }
                    var id = pending.removeFirst();
                    if (!checked.add(id)) break;
                    charge(32);
                    var provider = registry.get(id);
                    if (provider == null)
                        throw new IllegalArgumentException(
                                "Required patch provider is not registered: "
                                        + id
                                        + " (required by "
                                        + dependencyRoot
                                        + ")");
                    if (!policy.enabled(id))
                        throw new IllegalArgumentException(
                                "Required patch provider is disabled: "
                                        + id
                                        + " (required by "
                                        + dependencyRoot
                                        + ")");
                    dependencies = provider.registration().requires().iterator();
                }
                case 3 -> {
                    charge(48);
                    active.computeIfAbsent(identity.type(), ignored -> new TreeSet<>(INSTANCES))
                            .add(identity);
                    identity = null;
                    phase = 1;
                }
                case 4 -> {
                    if (!providers.hasNext()) {
                        providers = active.keySet().iterator();
                        phase++;
                        break;
                    }
                    current = providers.next();
                    charge(64);
                    outgoing.put(current, new TreeSet<>());
                    indegree.put(current, 0);
                }
                case 5 -> {
                    if (conflicts != null && conflicts.hasNext()) {
                        var conflict = conflicts.next();
                        if (active.containsKey(conflict))
                            throw new IllegalArgumentException(
                                    "Conflicting authored patch providers: "
                                            + current
                                            + " and "
                                            + conflict);
                        break;
                    }
                    if (!providers.hasNext()) {
                        providers = active.keySet().iterator();
                        edges = null;
                        edgeKind = 0;
                        phase++;
                        break;
                    }
                    current = providers.next();
                    conflicts = registry.get(current).registration().conflicts().iterator();
                }
                case 6 -> {
                    if (edges != null && edges.hasNext()) {
                        var other = edges.next();
                        var before = edgeKind == 2 ? current : other;
                        var after = edgeKind == 2 ? other : current;
                        if (outgoing.containsKey(before)
                                && outgoing.containsKey(after)
                                && outgoing.get(before).add(after)) {
                            charge(32);
                            indegree.merge(after, 1, Integer::sum);
                        }
                        break;
                    }
                    if (edgeKind == 0 || edgeKind == 3) {
                        if (!providers.hasNext()) {
                            index = 0;
                            phase++;
                            break;
                        }
                        current = providers.next();
                        edgeKind = 1;
                        edges = registry.get(current).registration().requires().iterator();
                    } else {
                        edgeKind++;
                        var ordering = registry.get(current).registration().ordering().get(stage);
                        edges =
                                ordering == null
                                        ? Collections.emptyIterator()
                                        : (edgeKind == 2 ? ordering.before() : ordering.after())
                                                .iterator();
                    }
                }
                case 7 -> {
                    if (index < policy.preferredTypes().size()) {
                        charge(32);
                        rank.put(policy.preferredTypes().get(index), index++);
                    } else {
                        ready =
                                new PriorityQueue<>(
                                        Comparator.comparingInt(
                                                        (ResourceLocation id) ->
                                                                rank.getOrDefault(
                                                                        id, Integer.MAX_VALUE))
                                                .thenComparingInt(
                                                        id ->
                                                                defaultRank.getOrDefault(
                                                                        id, Integer.MAX_VALUE))
                                                .thenComparing(Comparator.naturalOrder()));
                        providers = active.keySet().iterator();
                        phase++;
                    }
                }
                case 8 -> {
                    if (providers.hasNext()) {
                        var id = providers.next();
                        if (indegree.get(id) == 0) {
                            charge(8);
                            ready.add(id);
                        }
                    } else phase++;
                }
                case 9 -> {
                    if (ready.isEmpty()) {
                        if (emitted != active.size()) {
                            cycle = new TreeSet<>();
                            providers = indegree.keySet().iterator();
                            phase = 13;
                            break;
                        }
                        phase = 12;
                        break;
                    }
                    current = ready.remove();
                    emitted++;
                    instances = active.get(current).iterator();
                    phase++;
                }
                case 10 -> {
                    if (instances.hasNext()) {
                        charge(8);
                        result.add(instances.next());
                    } else {
                        edges = outgoing.get(current).iterator();
                        phase++;
                    }
                }
                case 11 -> {
                    if (edges.hasNext()) {
                        var after = edges.next();
                        if (indegree.merge(after, -1, Integer::sum) == 0) {
                            charge(8);
                            ready.add(after);
                        }
                    } else phase = 9;
                }
                case 13 -> {
                    if (providers.hasNext()) {
                        var id = providers.next();
                        if (indegree.get(id) > 0) {
                            charge(32);
                            cycle.add(id);
                        }
                    } else
                        throw new IllegalArgumentException(
                                "Patch ordering cycle in " + stage + ": " + cycle);
                }
                default -> throw new IllegalStateException("Invalid patch ordering phase");
            }
            progress++;
        }
        return phase == 12;
    }

    long progress() {
        return progress;
    }

    List<PatchIdentity> result() {
        if (closed || phase != 12) throw new IllegalStateException("Patch ordering unfinished");
        return Collections.unmodifiableList(result);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        active = null;
        distinct = null;
        checked = defaultSeen = null;
        pending = null;
        outgoing = null;
        indegree = rank = defaultRank = null;
        result = null;
        providers = dependencies = edges = conflicts = null;
        instances = null;
        ready = null;
        cycle = null;
        release.accept(reserved);
        reserved = 0;
    }
}
