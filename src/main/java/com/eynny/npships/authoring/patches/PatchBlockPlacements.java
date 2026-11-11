package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.*;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Immutable registration index and one editor-local state-pair cache; never a world/tick owner. */
public final class PatchBlockPlacements {
    private PatchBlockPlacements() {}

    public record Proposal(ResourceLocation provider, BlockState state) {}

    private static final class Index {
        static final Map<ResourceLocation, List<Map.Entry<ResourceLocation, PatchBlockPlacement>>>
                CODE = build();

        static Map<ResourceLocation, List<Map.Entry<ResourceLocation, PatchBlockPlacement>>>
                build() {
            var result =
                    new HashMap<
                            ResourceLocation,
                            List<Map.Entry<ResourceLocation, PatchBlockPlacement>>>();
            for (var entry : PatchTypes.capabilities(PatchBlockPlacement.class).entrySet())
                for (var block : Set.copyOf(entry.getValue().blocks())) {
                    result.computeIfAbsent(block, ignored -> new ArrayList<>())
                            .add(Map.entry(entry.getKey(), entry.getValue()));
                }
            result.replaceAll((key, value) -> List.copyOf(value));
            return Map.copyOf(result);
        }
    }

    private record Match(Map.Entry<ResourceLocation, PatchBlockPlacement> owner, String refusal) {
        static final Match NONE = new Match(null, null);
    }

    /** Captures the same WORLD policy as the owning editor; caches no document, level or screen. */
    public static final class Matcher {
        private final PatchPolicy policy;
        private BlockState previous, brush;
        private Match cached;

        public Matcher(PatchPolicy policy) {
            this.policy = Objects.requireNonNull(policy);
        }

        private Match match(BlockState existing, BlockState requested) {
            if (cached != null && previous == existing && brush == requested) return cached;
            var active = new LinkedHashMap<ResourceLocation, PatchBlockPlacement>();
            String refusal = null;
            final List<Map.Entry<ResourceLocation, PatchBlockPlacement>> candidates;
            try {
                candidates =
                        Index.CODE.getOrDefault(
                                BuiltInRegistries.BLOCK.getKey(existing.getBlock()), List.of());
            } catch (RuntimeException | LinkageError error) {
                previous = existing;
                brush = requested;
                cached = new Match(null, "Cannot plan placement providers: " + error.getMessage());
                return cached;
            }
            for (var entry : candidates) {
                try {
                    if (entry.getValue().applies(existing, requested))
                        active.put(entry.getKey(), entry.getValue());
                } catch (RuntimeException | LinkageError error) {
                    refusal =
                            "Patch "
                                    + entry.getKey()
                                    + " placement eligibility failed: "
                                    + error.getMessage();
                    break;
                }
            }
            Match result = Match.NONE;
            if (refusal != null) result = new Match(null, refusal);
            else if (!active.isEmpty())
                try {
                    PatchCode.validate(policy, active.keySet());
                    var enabled =
                            PatchOrdering.discoveryOrder(
                                    PatchStage.AUTHOR_PREPARATION, active.keySet(), policy);
                    if (enabled.size() > 1)
                        result = new Match(null, "Competing placement providers: " + enabled);
                    else if (!enabled.isEmpty()) {
                        var id = enabled.getFirst();
                        result = new Match(Map.entry(id, active.get(id)), null);
                    }
                } catch (IllegalArgumentException error) {
                    result = new Match(null, error.getMessage());
                }
            previous = existing;
            brush = requested;
            cached = result;
            return result;
        }

        public boolean canCombine(BlockState existing, BlockState requested) {
            var result = match(existing, requested);
            return result.owner() != null && result.refusal() == null;
        }

        /**
         * Returns null for an ordinary placement; otherwise computes exactly one provider proposal.
         */
        public Proposal combine(BlockState existing, BlockState requested) {
            var match = match(existing, requested);
            if (match.refusal() != null) throw new IllegalArgumentException(match.refusal());
            if (match.owner() == null) return null;
            var owner = match.owner();
            final BlockState result;
            try {
                result = owner.getValue().combine(existing, requested);
            } catch (RuntimeException | LinkageError error) {
                throw new IllegalArgumentException(
                        "Patch " + owner.getKey() + " placement refused: " + error.getMessage(),
                        error);
            }
            if (result == null
                    || result.getBlock() != existing.getBlock()
                    || !BuiltInRegistries.BLOCK.containsKey(
                            BuiltInRegistries.BLOCK.getKey(result.getBlock()))
                    || !result.getBlock().getStateDefinition().getPossibleStates().contains(result))
                throw new IllegalArgumentException(
                        "Patch " + owner.getKey() + " returned an invalid placement state");
            return new Proposal(owner.getKey(), result);
        }

        /** A copied native proposal, validated before the editor can commit state/data together. */
        public net.minecraft.nbt.CompoundTag data(
                Proposal proposal,
                BlockState existing,
                BlockState requested,
                net.minecraft.nbt.CompoundTag original,
                net.minecraft.core.HolderLookup.Provider registries) {
            try {
                var owner = match(existing, requested).owner();
                if (owner == null || !owner.getKey().equals(proposal.provider()))
                    throw new IllegalArgumentException("Placement owner changed");
                var fields = Set.copyOf(owner.getValue().dataFields());
                if (fields.size() > 64
                        || fields.stream()
                                .anyMatch(
                                        key ->
                                                key == null
                                                        || key.isBlank()
                                                        || key.length() > 256
                                                        || Set.of(
                                                                        "id",
                                                                        "x",
                                                                        "y",
                                                                        "z",
                                                                        "NeoForgeData")
                                                                .contains(key)
                                                        || key.startsWith("npships:")))
                    throw new IllegalArgumentException(
                            "Invalid placement data ownership: " + proposal.provider());
                var candidate =
                        owner.getValue()
                                .combineData(existing, requested, original.copy(), registries);
                if (candidate == null)
                    throw new IllegalArgumentException(
                            "Null placement data: " + proposal.provider());
                com.eynny.npships.resources.PreparedTemplateResources.measureSupplied(candidate);
                var keys = new HashSet<>(original.getAllKeys());
                keys.addAll(candidate.getAllKeys());
                for (var key : keys)
                    if (!fields.contains(key)
                            && !Objects.equals(original.get(key), candidate.get(key)))
                        throw new IllegalArgumentException(
                                "Patch "
                                        + proposal.provider()
                                        + " changed an unclaimed native field: "
                                        + key);
                return candidate.copy();
            } catch (RuntimeException | LinkageError error) {
                throw new IllegalArgumentException(
                        "Patch "
                                + proposal.provider()
                                + " native placement data refused: "
                                + error.getMessage(),
                        error);
            }
        }

        public int inventorySelection(Proposal proposal) {
            try {
                int result =
                        PatchTypes.capabilities(PatchBlockPlacement.class)
                                .get(proposal.provider())
                                .inventorySelection();
                if (result < -1 || result > 4095)
                    throw new IllegalArgumentException("Invalid placement inventory selection");
                return result;
            } catch (RuntimeException | LinkageError error) {
                throw new IllegalArgumentException(
                        "Patch "
                                + proposal.provider()
                                + " placement selection refused: "
                                + error.getMessage(),
                        error);
            }
        }
    }
}
