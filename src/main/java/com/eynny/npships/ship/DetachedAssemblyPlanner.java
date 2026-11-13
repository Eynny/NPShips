package com.eynny.npships.ship;

import com.eynny.npships.api.patches.PatchAssembly;
import com.eynny.npships.api.patches.PatchPolicy;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.authoring.AssemblyEligibility;
import com.eynny.npships.authoring.AssemblyPoints;
import com.eynny.npships.authoring.patches.DetachedPatchAssembly;
import com.eynny.npships.authoring.patches.PatchCode;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;

/** Registry-owned native membership snapshots; never clears blocks or allocates native bodies. */
public final class DetachedAssemblyPlanner {
    private DetachedAssemblyPlanner() {}

    public sealed interface Result permits Ready, Unsupported {}

    public record Ready(
            List<Component> components,
            Map<UUID, Integer> markerComponents,
            Set<BlockPos> staticResidueLocal)
            implements Result {}

    public record Unsupported(ResourceLocation provider, String operation, String detail)
            implements Result {}

    public record Component(
            UUID markerId,
            BlockPos anchorLocal,
            Set<BlockPos> membersLocal,
            ResourceLocation provider) {}

    public static Result plan(
            DetachedAssemblySource source,
            List<AssemblyPoints.Point> rootMarkers,
            Map<BlockPos, Set<BlockPos>> importedGroupsLocal,
            PatchPolicy policy,
            LongConsumer reserveReferences)
            throws Exception {
        var permitted = source.permittedSourceWorld();
        DetachedAssemblySource.reserveMap(reserveReferences, permitted.size());
        reserveReferences.accept(
                Math.multiplyExact(Math.multiplyExact((long) rootMarkers.size(), 5), Long.BYTES));
        var covered = new HashMap<BlockPos, Integer>();
        var aliases = new LinkedHashMap<UUID, Integer>();
        var components = new ArrayList<Component>();
        ResourceLocation consulting = null;
        try {
            for (var marker : rootMarkers) {
                if (aliases.containsKey(marker.id()))
                    throw new IllegalArgumentException("Duplicate root marker identity");
                var point = source.origin().offset(marker.position());
                if (!permitted.contains(point) || source.getBlockState(point).isAir())
                    throw new IllegalArgumentException(
                            "Assembly point has no prepared block: " + marker.position());
                if (AssemblyEligibility.excluded(source.getBlockState(point), source, point))
                    throw new IllegalArgumentException(
                            "Assembly point is not movable: " + marker.position());
                var previous = covered.get(point);
                if (previous != null) {
                    aliases.put(marker.id(), previous);
                    continue;
                }
                PatchAssembly.Plan chosen = null;
                ResourceLocation provider = null;
                try {
                    var imported = importedGroupsLocal.get(marker.position());
                    java.util.Collection<BlockPos> selected;
                    if (imported != null) {
                        reserveReferences.accept(
                                Math.multiplyExact((long) imported.size(), Long.BYTES));
                        var world = new ArrayList<BlockPos>(imported.size());
                        for (var local : imported) world.add(source.origin().offset(local));
                        selected = world;
                    } else {
                        for (int phase = 0; chosen == null && phase < 2; phase++) {
                            boolean defaults = phase == 1;
                            for (var entry :
                                    PatchCode.applicable(
                                            PatchAssembly.class,
                                            code -> code.defaultRules() == defaults,
                                            PatchStage.BEFORE_NATIVE_ASSEMBLY,
                                            policy)) {
                                consulting = entry.getKey();
                                if (!(entry.getValue() instanceof DetachedPatchAssembly code))
                                    return new Unsupported(
                                            consulting,
                                            "provider_capability",
                                            "Registered assembly provider has no detached capability");
                                if (!code.detachedTransferIsNoop())
                                    return new Unsupported(
                                            consulting,
                                            "transfer_callback",
                                            "Provider requires transfer side effects");
                                var candidate = code.planDetached(source, point, permitted);
                                if (candidate == null) continue;
                                if (chosen != null) {
                                    candidate.close();
                                    throw new IllegalArgumentException(
                                            "Conflicting detached assembly membership providers");
                                }
                                chosen = candidate;
                                provider = consulting;
                            }
                        }
                        if (chosen == null)
                            throw new IllegalArgumentException(
                                    "No applicable detached assembly rules");
                        selected = chosen.blocks();
                    }
                    if (selected == null
                            || selected.isEmpty()
                            || selected.size() > permitted.size())
                        throw new IllegalArgumentException("Invalid detached membership");
                    DetachedAssemblySource.reserveMap(reserveReferences, selected.size());
                    var localMembers = new LinkedHashSet<BlockPos>();
                    for (var pos : selected) {
                        if (!permitted.contains(pos)
                                || source.getBlockState(pos).isAir()
                                || AssemblyEligibility.excluded(
                                        source.getBlockState(pos), source, pos))
                            throw new IllegalArgumentException(
                                    "Detached membership selected non-source or excluded block");
                        if (covered.containsKey(pos))
                            return new Unsupported(
                                    provider,
                                    "overlapping_components",
                                    "Later native membership overlaps an earlier physical component");
                        if (!localMembers.add(source.local(pos).immutable()))
                            throw new IllegalArgumentException("Duplicate detached membership");
                    }
                    if (!localMembers.contains(marker.position()))
                        throw new IllegalArgumentException("Detached membership omitted marker");
                    int index = components.size();
                    for (var local : localMembers)
                        covered.put(source.origin().offset(local), index);
                    components.add(
                            new Component(
                                    marker.id(),
                                    marker.position().immutable(),
                                    Collections.unmodifiableSet(localMembers),
                                    provider));
                    aliases.put(marker.id(), index);
                } finally {
                    if (chosen != null) chosen.close();
                }
            }
            DetachedAssemblySource.reserveMap(reserveReferences, permitted.size() - covered.size());
            var residue = new LinkedHashSet<BlockPos>();
            for (var position : permitted)
                if (!covered.containsKey(position)) residue.add(source.local(position).immutable());
            return new Ready(
                    List.copyOf(components),
                    Collections.unmodifiableMap(aliases),
                    Collections.unmodifiableSet(residue));
        } catch (DetachedAssemblySource.UnsupportedAccess unsupported) {
            return new Unsupported(consulting, unsupported.operation(), unsupported.getMessage());
        }
    }
}
