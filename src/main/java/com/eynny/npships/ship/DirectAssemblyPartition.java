package com.eynny.npships.ship;

import com.eynny.npships.api.patches.PatchAssembly;
import com.eynny.npships.api.patches.PatchPolicy;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.authoring.AssemblyEligibility;
import com.eynny.npships.authoring.patches.DetachedPatchAssembly;
import com.eynny.npships.authoring.patches.PatchCode;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.LongConsumer;

/** Resumable native membership discovery; imported groups never merge with adjacent groups. */
final class DirectAssemblyPartition implements AutoCloseable {
    record Body(
            BlockPos anchorLocal,
            Set<BlockPos> membersLocal,
            List<PatchAssembly.Plan> attachments) {}

    private static final class Group {
        Group parent = this;
        int rank;
        final BlockPos anchor;
        final List<PatchAssembly.Plan> plans = new ArrayList<>();

        Group(BlockPos anchor) {
            this.anchor = anchor;
        }

        Group root() {
            Group root = this;
            while (root.parent != root) root = root.parent;
            Group next = this;
            while (next.parent != next) {
                Group old = next.parent;
                next.parent = root;
                next = old;
            }
            return root;
        }

        void join(Group other) {
            Group a = root(), b = other.root();
            if (a == b) return;
            if (a.rank < b.rank) a.parent = b;
            else {
                b.parent = a;
                if (a.rank == b.rank) a.rank++;
            }
        }
    }

    private DetachedAssemblySource source;
    private Map<BlockPos, Set<BlockPos>> imported;
    private LongConsumer reserve;
    private List<Map.Entry<ResourceLocation, PatchAssembly>> ordinary, defaults;
    private Iterator<BlockPos> seeds;
    private Map<BlockPos, Group> assigned = new HashMap<>();
    private List<Group> groups = new ArrayList<>();
    private LinkedHashMap<Group, LinkedHashSet<BlockPos>> finalMembers = new LinkedHashMap<>();
    private Map<Group, List<PatchAssembly.Plan>> finalPlans = new IdentityHashMap<>();
    private List<Body> bodies = new ArrayList<>();
    private Iterator<Map.Entry<BlockPos, Group>> materialize;
    private Iterator<Group> planGroups;
    private Iterator<Map.Entry<Group, LinkedHashSet<BlockPos>>> publish;
    private DetachedPatchAssembly.Traversal traversal;
    private Iterator<BlockPos> members;
    private Group current;
    private PatchAssembly.Plan membershipPlan;
    private boolean membershipOnly;
    private BlockPos seed;
    private boolean deferredSeeds, complete, closed, cleaning;
    private Iterator<Group> cleanupGroups;
    private Iterator<PatchAssembly.Plan> cleanupPlans = Collections.emptyIterator();
    private Exception cleanupFailure;
    private int cleanupPhase;
    private long cleanupProgress;

    DirectAssemblyPartition(
            DetachedAssemblySource source,
            Map<BlockPos, Set<BlockPos>> importedWorld,
            PatchPolicy policy,
            LongConsumer reserve) {
        this.source = source;
        this.imported = importedWorld;
        this.reserve = reserve;
        this.seeds = source.permittedSourceWorld().iterator();
        ordinary =
                PatchCode.applicable(
                        PatchAssembly.class,
                        code -> !code.defaultRules(),
                        PatchStage.BEFORE_NATIVE_ASSEMBLY,
                        policy);
        defaults =
                PatchCode.applicable(
                        PatchAssembly.class,
                        PatchAssembly::defaultRules,
                        PatchStage.BEFORE_NATIVE_ASSEMBLY,
                        policy);
    }

    /** One graph node, member, seed or output record per call; no ticker or private time budget. */
    boolean step() throws Exception {
        requireOpen();
        if (complete) return true;
        if (publish != null) {
            if (!publish.hasNext()) {
                complete = true;
                return true;
            }
            var entry = publish.next();
            bodies.add(
                    new Body(
                            source.local(entry.getKey().anchor),
                            Collections.unmodifiableSet(entry.getValue()),
                            Collections.unmodifiableList(
                                    finalPlans.getOrDefault(entry.getKey(), List.of()))));
            return false;
        }
        if (planGroups != null) {
            if (planGroups.hasNext()) {
                Group group = planGroups.next();
                if (!group.plans.isEmpty())
                    finalPlans
                            .computeIfAbsent(group.root(), key -> new ArrayList<>())
                            .addAll(group.plans);
                return false;
            }
            publish = finalMembers.entrySet().iterator();
            return false;
        }
        if (materialize != null) {
            if (materialize.hasNext()) {
                var entry = materialize.next();
                reserve.accept(6L * Long.BYTES);
                finalMembers
                        .computeIfAbsent(entry.getValue().root(), key -> new LinkedHashSet<>())
                        .add(source.local(entry.getKey()));
                return false;
            }
            planGroups = groups.iterator();
            return false;
        }
        if (members != null) {
            if (members.hasNext()) {
                BlockPos position = members.next();
                if (!allowed(position)
                        || source.getBlockState(position).isAir()
                        || AssemblyEligibility.excluded(
                                source.getBlockState(position), source, position))
                    throw new IllegalArgumentException(
                            "Invalid direct assembly member: " + position);
                var previous = assigned.get(position);
                if (previous != null) current.join(previous);
                else {
                    reserve.accept(8L * Long.BYTES);
                    assigned.put(position, current);
                }
                return false;
            }
            if (!assigned.containsKey(seed))
                throw new IllegalArgumentException("Direct assembly omitted its anchor");
            members = null;
            if (membershipPlan != null) {
                var consumedPlan = membershipPlan;
                membershipPlan = null;
                consumedPlan.close();
            }
            current = null;
            seed = null;
            return false;
        }
        if (traversal != null) {
            if (!traversal.step()) return false;
            var plan = traversal.result();
            if (membershipOnly) membershipPlan = plan;
            else current.plans.add(plan);
            members = plan.blocks().iterator();
            traversal.close();
            traversal = null;
            return false;
        }
        if (!seeds.hasNext()) {
            if (!deferredSeeds) {
                deferredSeeds = true;
                seeds = source.permittedSourceWorld().iterator();
                return false;
            }
            if (assigned.size() != source.permittedSourceWorld().size())
                throw new IllegalStateException("Incomplete direct assembly partition");
            materialize = assigned.entrySet().iterator();
            return false;
        }
        seed = seeds.next();
        if (assigned.containsKey(seed) || !deferredSeeds && deferSeed(seed)) {
            seed = null;
            return false;
        }
        if (AssemblyEligibility.excluded(source.getBlockState(seed), source, seed))
            throw new IllegalArgumentException("Block cannot join a physical body: " + seed);
        // Group metadata and later output containers are retained until partition cleanup.
        reserve.accept(64L * Long.BYTES);
        current = new Group(seed);
        groups.add(current);
        var importedGroup = imported.get(seed);
        if (importedGroup != null) {
            members = importedGroup.iterator();
            return false;
        }
        Set<BlockPos> permitted =
                new AbstractSet<>() {
                    @Override
                    public boolean contains(Object p) {
                        return p instanceof BlockPos pos && allowed(pos);
                    }

                    @Override
                    public Iterator<BlockPos> iterator() {
                        return source.permittedSourceWorld().stream()
                                .filter(DirectAssemblyPartition.this::allowed)
                                .iterator();
                    }

                    @Override
                    public int size() {
                        return source.permittedSourceWorld().size();
                    }
                };
        traversal = select(ordinary, permitted);
        if (traversal == null) traversal = select(defaults, permitted);
        if (traversal == null)
            throw new IllegalArgumentException("No direct assembly rules for " + seed);
        return false;
    }

    private boolean deferSeed(BlockPos position) {
        var providers = ordinary.isEmpty() ? defaults : ordinary;
        for (var entry : providers)
            if (entry.getValue() instanceof DetachedPatchAssembly detached
                    && detached.deferSeed(source.getBlockState(position))) return true;
        return false;
    }

    private DetachedPatchAssembly.Traversal select(
            List<Map.Entry<ResourceLocation, PatchAssembly>> providers, Set<BlockPos> permitted)
            throws Exception {
        DetachedPatchAssembly.Traversal selected = null;
        try {
            for (var entry : providers) {
                if (!(entry.getValue() instanceof DetachedPatchAssembly code))
                    throw new IllegalArgumentException(
                            "Assembly provider requires a detached cursor: " + entry.getKey());
                var candidate = code.beginDetached(source, seed, permitted);
                if (candidate == null) continue;
                if (selected != null) {
                    candidate.close();
                    throw new IllegalArgumentException("Conflicting direct assembly providers");
                }
                membershipOnly = code.detachedTransferIsNoop();
                selected = candidate;
            }
            return selected;
        } catch (Exception | Error failure) {
            if (selected != null)
                try {
                    selected.close();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            throw failure;
        }
    }

    private boolean allowed(BlockPos position) {
        if (!source.permittedSourceWorld().contains(position)) return false;
        var group = imported.get(seed);
        return group == null ? !imported.containsKey(position) : group.contains(position);
    }

    DetachedPatchAssembly.PlacementRequirements placementRequirements(BlockPos position) {
        requireOpen();
        LinkedHashSet<BlockPos> supports = null;
        LinkedHashSet<BlockPos> peers = null;
        var providers = ordinary.isEmpty() ? defaults : ordinary;
        for (var entry : providers) {
            if (!(entry.getValue() instanceof DetachedPatchAssembly code))
                throw new IllegalArgumentException(
                        "Assembly provider has no placement requirements: " + entry.getKey());
            var requirement = code.placementRequirements(source, position);
            if (!requirement.supports().isEmpty()) {
                if (supports == null) supports = new LinkedHashSet<>();
                supports.addAll(requirement.supports());
            }
            if (!requirement.peers().isEmpty()) {
                if (peers == null) peers = new LinkedHashSet<>();
                peers.addAll(requirement.peers());
            }
        }
        if (supports == null && peers == null)
            return DetachedPatchAssembly.PlacementRequirements.NONE;
        if (supports != null && peers != null) supports.removeAll(peers);
        return new DetachedPatchAssembly.PlacementRequirements(
                supports == null ? Set.of() : supports, peers == null ? Set.of() : peers);
    }

    List<Body> bodies() {
        requireOpen();
        if (!complete) throw new IllegalStateException("Direct assembly partition is incomplete");
        return Collections.unmodifiableList(
                new AbstractList<>() {
                    @Override
                    public int size() {
                        requireOpen();
                        return bodies.size();
                    }

                    @Override
                    public Body get(int index) {
                        requireOpen();
                        return bodies.get(index);
                    }
                });
    }

    private void requireOpen() {
        if (closed || cleaning)
            throw new IllegalStateException("Direct assembly partition is closed");
    }

    void beginCleanup() {
        if (closed || cleaning) return;
        cleaning = true;
    }

    /** One existing native traversal/plan close or one cursor transition per permitted unit. */
    boolean stepCleanup(java.util.function.BooleanSupplier allowed) throws Exception {
        Objects.requireNonNull(allowed);
        if (closed) return true;
        beginCleanup();
        while (!closed && allowed.getAsBoolean()) {
            cleanupProgress++;
            if (cleanupPhase == 0) {
                var active = traversal;
                traversal = null;
                cleanupPhase = 1;
                cleanupGroups = groups.iterator();
                if (active != null)
                    try {
                        active.close();
                    } catch (Exception error) {
                        remember(error);
                    }
            } else if (cleanupPhase == 1) {
                if (membershipPlan != null) {
                    var consumedPlan = membershipPlan;
                    membershipPlan = null;
                    try {
                        consumedPlan.close();
                    } catch (Exception error) {
                        remember(error);
                    }
                    continue;
                }
                if (cleanupPlans.hasNext()) {
                    var plan = cleanupPlans.next();
                    try {
                        plan.close();
                    } catch (Exception error) {
                        remember(error);
                    }
                } else if (cleanupGroups.hasNext())
                    cleanupPlans = cleanupGroups.next().plans.iterator();
                else cleanupPhase = 2;
            } else {
                // These scratch containers are private. Published body/member records are
                // immutable, and their native plans have all completed their close boundary.
                assigned = null;
                groups = null;
                finalMembers = null;
                finalPlans = null;
                bodies = null;
                source = null;
                imported = null;
                reserve = null;
                ordinary = null;
                defaults = null;
                current = null;
                members = null;
                traversal = null;
                seed = null;
                seeds = null;
                materialize = null;
                planGroups = null;
                publish = null;
                cleanupGroups = null;
                cleanupPlans = null;
                closed = true;
                cleaning = false;
                var failure = cleanupFailure;
                cleanupFailure = null;
                if (failure != null) throw failure;
            }
        }
        return closed;
    }

    private void remember(Exception error) {
        if (cleanupFailure == null) cleanupFailure = error;
        else cleanupFailure.addSuppressed(error);
    }

    long cleanupProgress() {
        return cleanupProgress;
    }

    /** Explicit synchronous drain for legacy fixtures; runtime owns the shared allowance. */
    @Override
    public void close() throws Exception {
        beginCleanup();
        while (!stepCleanup(() -> true)) {}
    }
}
