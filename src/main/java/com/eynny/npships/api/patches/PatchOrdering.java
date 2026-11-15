package com.eynny.npships.api.patches;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Detached fixed-stage plans. No factories, world access, per-tick work or retained viewer state.
 */
public final class PatchOrdering {
    private PatchOrdering() {}

    /**
     * Validate all registered stage graphs, independently of a document. Missing hard providers
     * stay representable so an optional addon can be absent; using its dependent provider then
     * refuses. Native/mod availability is checked by the host before executing a returned plan.
     *
     * @param registrations complete detached provider metadata
     */
    public static void validate(Map<ResourceLocation, PatchRegistration> registrations) {
        Objects.requireNonNull(registrations);
        for (var stage : PatchStage.values()) types(stage, registrations, List.of(), List.of());
    }

    /**
     * Hard dependencies/precedence dominate administrator preferences. Order is per provider type;
     * all its instances follow stable instance/occurrence ordering, never an authored arbitrary
     * index. The caller validates target multiplicity and native dependency availability.
     *
     * @param stage fixed host callback boundary
     * @param identities active authored provider instances
     * @param policy captured SERVER disable/preference policy
     * @return immutable fixed-stage instance order
     */
    public static List<PatchIdentity> order(
            PatchStage stage, List<PatchIdentity> identities, PatchPolicy policy) {
        return order(stage, identities, policy, List.of());
    }

    /**
     * Host-unit compatibility defaults break independent ties after administrator preferences. They
     * never add dependency edges or move a callback across its real fixed host boundary.
     *
     * @param stage fixed host callback boundary
     * @param identities active authored provider instances
     * @param policy captured SERVER disable/preference policy
     * @param defaultOrder host-unit compatibility tie breakers
     * @return immutable fixed-stage instance order
     */
    public static List<PatchIdentity> order(
            PatchStage stage,
            List<PatchIdentity> identities,
            PatchPolicy policy,
            List<ResourceLocation> defaultOrder) {
        defaultOrder = List.copyOf(defaultOrder);
        if (new HashSet<>(defaultOrder).size() != defaultOrder.size())
            throw new IllegalArgumentException("Duplicate patch provider compatibility default");
        Objects.requireNonNull(stage);
        Objects.requireNonNull(identities);
        Objects.requireNonNull(policy);
        var registry = PatchTypes.providers();
        var active = new TreeMap<ResourceLocation, List<PatchIdentity>>();
        var distinct = new HashSet<UUID>();
        var checkedDependencies = new HashSet<ResourceLocation>();
        for (var identity : identities) {
            Objects.requireNonNull(identity);
            if (!distinct.add(identity.instanceId()))
                throw new IllegalArgumentException(
                        "Duplicate patch instance: "
                                + identity.type()
                                + "/"
                                + identity.instanceId());
            var provider = registry.get(identity.type());
            if (provider == null)
                throw new IllegalArgumentException("Unknown patch provider: " + identity.type());
            if (!policy.enabled(identity.type())) {
                if (provider.registration().disabledBehavior()
                                == PatchRegistration.DisabledBehavior.SKIP
                        && provider.execution() != PatchTypes.Execution.LEGACY_DISPATCH) continue;
                throw new IllegalArgumentException(
                        "Patch provider disabled by SERVER policy: " + identity.type());
            }
            if (!active.containsKey(identity.type()))
                requireProviders(provider.id(), registry, policy, checkedDependencies);
            active.computeIfAbsent(identity.type(), ignored -> new ArrayList<>()).add(identity);
        }
        var metadata = new TreeMap<ResourceLocation, PatchRegistration>();
        active.forEach((id, values) -> metadata.put(id, registry.get(id).registration()));
        for (var entry : metadata.entrySet())
            for (var conflict : entry.getValue().conflicts())
                if (metadata.containsKey(conflict))
                    throw new IllegalArgumentException(
                            "Conflicting authored patch providers: "
                                    + entry.getKey()
                                    + " and "
                                    + conflict);
        var result = new ArrayList<PatchIdentity>(identities.size());
        Comparator<PatchIdentity> instances =
                Comparator.comparing(PatchIdentity::instanceId)
                        .thenComparing(
                                PatchIdentity::occurrenceId,
                                Comparator.nullsFirst(Comparator.naturalOrder()));
        for (var id : types(stage, metadata, policy.preferredTypes(), defaultOrder)) {
            var values = active.get(id);
            values.sort(instances);
            result.addAll(values);
        }
        return List.copyOf(result);
    }

    /**
     * Pure capture discovery orders code capabilities, not authored active instances. Conflicts are
     * checked with order() only after detached proposals establish applicability. Availability of a
     * capability's hard/native dependencies remains the invoking host's responsibility.
     *
     * @param stage fixed host callback boundary
     * @param candidates provider code capabilities considered for discovery
     * @param policy captured SERVER disable/preference policy
     * @return immutable provider discovery order
     */
    public static List<ResourceLocation> discoveryOrder(
            PatchStage stage, Set<ResourceLocation> candidates, PatchPolicy policy) {
        return discoveryOrder(stage, candidates, policy, List.of());
    }

    /**
     * Orders applicable code capabilities using the host's established default ranks. Declared
     * dependency edges and administrator preferences take precedence over these fallback ranks. The
     * caller still validates applicability, availability and conflicts before invoking code.
     *
     * @param stage fixed host callback boundary
     * @param candidates applicable provider IDs
     * @param policy captured SERVER policy
     * @param defaultOrder fallback ranks preserving the host's existing independent-operation order
     * @return immutable provider order
     */
    public static List<ResourceLocation> discoveryOrder(
            PatchStage stage,
            Set<ResourceLocation> candidates,
            PatchPolicy policy,
            List<ResourceLocation> defaultOrder) {
        var metadata = new TreeMap<ResourceLocation, PatchRegistration>();
        for (var id : candidates) {
            var provider = PatchTypes.provider(id);
            if (provider == null)
                throw new IllegalArgumentException("Unknown discovery provider: " + id);
            if (policy.enabled(id)) metadata.put(id, provider.registration());
        }
        return types(stage, metadata, policy.preferredTypes(), defaultOrder);
    }

    private static void requireProviders(
            ResourceLocation root,
            Map<ResourceLocation, PatchTypes.Provider> registry,
            PatchPolicy policy,
            Set<ResourceLocation> visited) {
        var pending = new ArrayDeque<ResourceLocation>();
        pending.add(root);
        while (!pending.isEmpty()) {
            var id = pending.removeFirst();
            if (!visited.add(id)) continue;
            var provider = registry.get(id);
            if (provider == null)
                throw new IllegalArgumentException(
                        "Required patch provider is not registered: "
                                + id
                                + " (required by "
                                + root
                                + ")");
            if (!policy.enabled(id))
                throw new IllegalArgumentException(
                        "Required patch provider is disabled: "
                                + id
                                + " (required by "
                                + root
                                + ")");
            pending.addAll(provider.registration().requires());
        }
    }

    /** Type-level graph avoids quadratic edges between two providers' many individual instances. */
    private static List<ResourceLocation> types(
            PatchStage stage,
            Map<ResourceLocation, PatchRegistration> metadata,
            List<ResourceLocation> preference,
            List<ResourceLocation> defaultOrder) {
        var outgoing = new TreeMap<ResourceLocation, Set<ResourceLocation>>();
        var indegree = new HashMap<ResourceLocation, Integer>();
        metadata.forEach(
                (id, value) -> {
                    Objects.requireNonNull(value);
                    outgoing.put(Objects.requireNonNull(id), new TreeSet<>());
                    indegree.put(id, 0);
                });
        for (var entry : metadata.entrySet()) {
            var id = entry.getKey();
            for (var dependency : entry.getValue().requires())
                edge(dependency, id, outgoing, indegree);
            var order = entry.getValue().ordering().get(stage);
            if (order != null) {
                for (var before : order.before()) edge(id, before, outgoing, indegree);
                for (var after : order.after()) edge(after, id, outgoing, indegree);
            }
        }
        var rank = new HashMap<ResourceLocation, Integer>();
        for (int i = 0; i < preference.size(); i++) rank.put(preference.get(i), i);
        var defaults = new HashMap<ResourceLocation, Integer>();
        for (int i = 0; i < defaultOrder.size(); i++) defaults.put(defaultOrder.get(i), i);
        Comparator<ResourceLocation> comparator =
                Comparator.comparingInt(
                                (ResourceLocation id) -> rank.getOrDefault(id, Integer.MAX_VALUE))
                        .thenComparingInt(id -> defaults.getOrDefault(id, Integer.MAX_VALUE))
                        .thenComparing(Comparator.naturalOrder());
        var ready = new PriorityQueue<>(comparator);
        indegree.forEach(
                (id, count) -> {
                    if (count == 0) ready.add(id);
                });
        var result = new ArrayList<ResourceLocation>(metadata.size());
        while (!ready.isEmpty()) {
            var id = ready.remove();
            result.add(id);
            for (var after : outgoing.get(id)) {
                int remaining = indegree.merge(after, -1, Integer::sum);
                if (remaining == 0) ready.add(after);
            }
        }
        if (result.size() != metadata.size()) {
            var cycle = new TreeSet<ResourceLocation>();
            indegree.forEach(
                    (id, count) -> {
                        if (count > 0) cycle.add(id);
                    });
            throw new IllegalArgumentException("Patch ordering cycle in " + stage + ": " + cycle);
        }
        return List.copyOf(result);
    }

    private static void edge(
            ResourceLocation before,
            ResourceLocation after,
            Map<ResourceLocation, Set<ResourceLocation>> outgoing,
            Map<ResourceLocation, Integer> indegree) {
        if (!outgoing.containsKey(before) || !outgoing.containsKey(after)) return;
        if (outgoing.get(before).add(after)) indegree.merge(after, 1, Integer::sum);
    }
}
