package com.eynny.npships.integration.create;

import com.eynny.npships.api.patches.PatchPolicy;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.patches.BuiltinPatchState;
import com.eynny.npships.authoring.patches.PatchNativePreparation;
import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.resources.ResourceMemoryBudget;
import com.eynny.npships.resources.ServerResourcePolicy;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Iterator;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Native repair algorithm; the existing host queue starts after five ticks and owns every
 * continuation.
 */
public final class CreateKineticMaintenance implements PatchNativePreparation {
    private static final ResourceLocation ID = ResourceLocation.parse("npships:create_devices");

    @Override
    public boolean applies(BlockEntity entity, CompoundTag block) {
        return entity instanceof KineticBlockEntity;
    }

    @Override
    public boolean inspectBare(PatchPolicy policy, Set<ResourceLocation> planned) {
        if (!policy.enabled(ID)) return true;
        for (var id : planned) {
            var definition = PatchTypes.provider(id);
            if (definition != null && definition.registration().conflicts().contains(ID))
                return true;
            if (PatchTypes.provider(ID).registration().conflicts().contains(id)) return true;
        }
        return false;
    }

    @Override
    public void afterInitialBlock(BuiltinPatchState state, BlockPos local, BlockEntity entity) {
        if (!(entity instanceof KineticBlockEntity kinetic)) return;
        kinetic.updateSpeed = true;
        if (kinetic instanceof GeneratingKineticBlockEntity generator)
            generator.reActivateSource = true;
    }

    @Override
    public Consumer<ServerSubLevel> delayedRepair() {
        return (CooperativeRepair) Repair::new;
    }

    /** Explicit synchronous compatibility call; scheduled maintenance always uses Repair. */
    public static void rebuild(ServerSubLevel subLevel) {
        ((CooperativeRepair) Repair::new).accept(subLevel);
    }

    private static final class Repair implements RepairCursor {
        private final ServerSubLevel body;
        private final ServerLevel level;
        private final ResourceMemoryBudget.Scope memory;
        private Iterator<PlotChunkHolder> chunks;
        private int chunksRemaining;
        private LevelChunk chunk;
        private BlockEntity[] entities;
        private int entityIndex;
        private long snapshotBytes;
        private boolean closed;

        Repair(ServerSubLevel body) {
            this.body = body;
            level = body.getLevel();
            memory =
                    ServerResourcePolicy.memory(level.getServer())
                            .scope("delayed Create repair chunk snapshot");
            // Installed LevelPlot retains an append-only ObjectArrayList until this exact body
            // retires. Borrow its iterator and initial count; never collect the whole body graph.
            var loaded = body.getPlot().getLoadedChunks();
            chunks = loaded.iterator();
            chunksRemaining = loaded.size();
        }

        @Override
        public boolean step(BooleanSupplier canContinue) {
            if (closed) throw new IllegalStateException("Create repair cursor is closed");
            while (true) {
                if (entities != null && entityIndex < entities.length) {
                    if (!canContinue.getAsBoolean()) return false;
                    if (!current()) return true;
                    var entity = entities[entityIndex++];
                    if (!(entity instanceof KineticBlockEntity kinetic)
                            || kinetic.isRemoved()
                            || kinetic.getLevel() != level
                            || chunk.getBlockEntities().get(kinetic.getBlockPos()) != kinetic
                            || body.getPlot().getChunk(body.getPlot().toLocal(chunk.getPos()))
                                    != chunk) continue;
                    try {
                        // Native network detachment is one indivisible provider operation.
                        if (kinetic.hasNetwork()) kinetic.detachKinetics();
                    } catch (Exception failure) {
                        LogUtils.getLogger()
                                .warn(
                                        "Failed to detach native kinetic at {}",
                                        kinetic.getBlockPos(),
                                        failure);
                    }
                    if (current()
                            && !kinetic.isRemoved()
                            && chunk.getBlockEntities().get(kinetic.getBlockPos()) == kinetic)
                        kinetic.updateSpeed = true;
                    continue;
                }
                dropSnapshot();
                if (chunksRemaining == 0 || !chunks.hasNext()) return true;
                if (!canContinue.getAsBoolean()) return false;
                if (!current()) return true;
                chunk = chunks.next().getChunk();
                chunksRemaining--;
                if (body.getPlot().getChunk(body.getPlot().toLocal(chunk.getPos())) != chunk)
                    continue;
                var values = chunk.getBlockEntities().values();
                snapshotBytes = ResourceMemoryBudget.arrayBytes(values.size(), 8);
                memory.reserve(snapshotBytes);
                // One chunk's native map copy is indivisible; subsequent BEs are separately
                // admitted. Retaining its values iterator across native ticks would be unsafe.
                entities = values.toArray(new BlockEntity[values.size()]);
            }
        }

        private boolean current() {
            return NativeBlockAccess.current(level, body);
        }

        private void dropSnapshot() {
            entities = null;
            chunk = null;
            entityIndex = 0;
            if (snapshotBytes != 0) {
                memory.release(snapshotBytes);
                snapshotBytes = 0;
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            chunks = null;
            entities = null;
            chunk = null;
            memory.close();
        }
    }
}
