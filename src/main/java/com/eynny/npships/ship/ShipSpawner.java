package com.eynny.npships.ship;

import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Prepared input and continuation for the existing server-wide spawn attempt owner. */
public final class ShipSpawner {
    private ShipSpawner() {}

    /** Detached composition gets its own server tick before any world mutation. */
    public static PreparedNatural prepareNatural(
            ServerLevel level,
            com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan plan) {
        return prepareNatural(level, candidate, origin, plan, null);
    }

    public static PreparedNatural prepareNatural(
            ServerLevel level,
            com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            @Nullable SpawnTimingProfiler timing) {
        return prepareNatural(
                level,
                candidate,
                origin,
                plan,
                timing,
                net.minecraft.util.RandomSource.create(level.random.nextLong()));
    }

    /** Managed attempts pass their existing private stream rather than sampling after a yield. */
    public static PreparedNatural prepareNatural(
            ServerLevel level,
            com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            @Nullable SpawnTimingProfiler timing,
            net.minecraft.util.RandomSource random) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Preparation requires the server thread");
        if (candidate.schematic().snapshot() == null)
            throw new IllegalArgumentException(
                    "Managed spawning requires a pinned catalogue source");
        if (!candidate.size().equals(candidate.schematic().snapshot().size()))
            throw new IllegalArgumentException(
                    "Candidate bounds differ from the pinned catalogue envelope");
        return new PreparedNatural(level, candidate, origin.immutable(), plan, timing, random);
    }

    public static final class PreparedNatural implements AutoCloseable {
        private final ServerLevel level;
        private final com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate;
        private final BlockPos origin;
        private final com.eynny.npships.authoring.StructureSegments.Plan plan;
        private StructureTemplate template;
        private NPShipsSchematicCatalog.Composition composition;
        private long preparationProgress;
        private final SpawnTimingProfiler timing;
        private boolean consumed, cleanupFinished;
        private int cleanupStage;
        private long cleanupProgress;
        private DirectSpawnConstruction construction;

        private PreparedNatural(
                ServerLevel level,
                com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
                BlockPos origin,
                com.eynny.npships.authoring.StructureSegments.Plan plan,
                SpawnTimingProfiler timing,
                net.minecraft.util.RandomSource random) {
            this.level = level;
            this.candidate = candidate;
            this.origin = origin;
            this.plan = plan;
            this.timing = timing;
            composition =
                    new NPShipsSchematicCatalog.Composition(
                            level,
                            candidate.schematic().snapshot(),
                            candidate.config(),
                            plan,
                            origin,
                            candidate.raritySelection() == null
                                    ? -1
                                    : candidate.raritySelection().palette(),
                            timing,
                            random);
        }

        /** Stops forward work; the existing managed owner retains this continuation. */
        public void beginCancellation() {
            consumed = true;
            if (construction != null) construction.beginCancellation();
            if (composition != null) composition.beginCleanup();
        }

        public boolean stepCleanup(java.util.function.BooleanSupplier canContinue)
                throws Exception {
            beginCancellation();
            if (cleanupFinished) return true;
            if (cleanupStage == 0) {
                if (construction != null) {
                    try {
                        if (!construction.stepCleanup(canContinue)) return false;
                    } finally {
                        if (construction.cleanupFinished()) {
                            cleanupStage++;
                            cleanupProgress++;
                        }
                    }
                } else {
                    cleanupStage++;
                    cleanupProgress++;
                }
            }
            while (true) {
                if (cleanupStage == 1 && composition != null) {
                    long before = composition.cleanupProgress();
                    boolean done;
                    try {
                        done = composition.stepCleanup(canContinue);
                    } finally {
                        cleanupProgress += composition.cleanupProgress() - before;
                    }
                    if (!done) return false;
                    composition = null;
                    cleanupStage++;
                    cleanupProgress++;
                    continue;
                }
                if (cleanupStage == 2
                        && template
                                instanceof
                                com.eynny.npships.authoring.patches.PatchedTemplate patched) {
                    long before = patched.cleanupProgress();
                    boolean done;
                    try {
                        done = patched.stepCleanup(canContinue);
                    } finally {
                        cleanupProgress += patched.cleanupProgress() - before;
                    }
                    if (!done) return false;
                    template = null;
                    cleanupStage++;
                    cleanupProgress++;
                    continue;
                }
                if (!canContinue.getAsBoolean()) return false;
                switch (cleanupStage) {
                    case 1 -> composition = null;
                    case 2 -> template = null;
                    case 3 -> {
                        cleanupFinished = true;
                        cleanupProgress++;
                        return true;
                    }
                    default -> throw new IllegalStateException("Invalid preparation cleanup phase");
                }
                cleanupStage++;
                cleanupProgress++;
            }
        }

        public boolean cleanupFinished() {
            return cleanupFinished;
        }

        public long cleanupProgress() {
            return cleanupProgress + (construction == null ? 0 : construction.progress());
        }

        /** Explicit drain for shutdown/fixtures, never used by the ordinary spawn ticker. */
        @Override
        public void close() {
            try {
                while (!stepCleanup(() -> true)) {}
            } catch (Exception failure) {
                throw new IllegalStateException("Direct construction cleanup failed", failure);
            }
        }

        public boolean matches(
                ServerLevel level,
                com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
                BlockPos origin,
                com.eynny.npships.authoring.StructureSegments.Plan plan) {
            return !consumed
                    && this.level == level
                    && this.candidate == candidate
                    && this.origin.equals(origin)
                    && this.plan == plan;
        }

        public boolean stepConstruction(double yaw, java.util.function.BooleanSupplier canContinue)
                throws Exception {
            if (consumed) throw new IllegalStateException("Spawn already consumed");
            if (composition != null) {
                boolean ready;
                try {
                    ready = composition.step(canContinue);
                } finally {
                    preparationProgress = Math.max(preparationProgress, composition.progress());
                }
                if (!ready) return false;
                template = composition.takeTemplate();
                composition = null;
                // Admission/preload used the pinned full envelope. Composition must preserve it.
                if (!template.getSize().equals(candidate.schematic().snapshot().size()))
                    throw new IllegalStateException(
                            "Prepared bounds differ from the admitted pinned envelope");
            }
            if (construction == null) {
                if (!canContinue.getAsBoolean()) return false;
                if (!(template
                        instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched))
                    throw new IllegalStateException(
                            "Direct construction requires a prepared authored template");
                construction =
                        new DirectSpawnConstruction(
                                level,
                                patched,
                                candidate.structureId(),
                                origin,
                                yaw,
                                candidate.config());
            }
            return construction.step(canContinue);
        }

        public boolean constructionFinished() {
            return construction != null && construction.finished();
        }

        public boolean constructionWaiting() {
            return false;
        }

        public long constructionProgress() {
            return preparationProgress + (construction == null ? 0 : construction.progress());
        }

        public java.util.Set<UUID> ownedPhysicalIds() {
            return construction == null ? java.util.Set.of() : construction.ownedPhysicalIds();
        }

        public Ship result() {
            if (construction == null) throw new IllegalStateException("Construction not started");
            return construction.result();
        }

        /** Final detached composition bounds, before consuming or mutating the world. */
        public Vec3i size() {
            return template == null ? candidate.schematic().snapshot().size() : template.getSize();
        }
    }

    static void registerBody(ServerLevel level, StructureTemplate template, ServerSubLevel body) {
        NPShipsLifecycleManager.track(level, body.getUniqueId());
        NaturalSpawnPhysicalChunkManager.track(body);
        if (template instanceof com.eynny.npships.authoring.patches.PatchedTemplate prepared)
            ShipKineticRepair.schedule(level, body, 5, prepared.delayedRepairs());
        else ShipKineticRepair.schedule(level, body, 5);
    }
}
