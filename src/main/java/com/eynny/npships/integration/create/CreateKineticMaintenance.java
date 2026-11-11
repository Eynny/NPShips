package com.eynny.npships.integration.create;

import com.eynny.npships.api.patches.*;
import com.eynny.npships.authoring.patches.PatchNativePreparation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;

/** Native repair algorithm; the existing host queue owns the body and five-tick lifetime. */
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
    public java.util.function.Consumer<ServerSubLevel> delayedRepair() {
        return CreateKineticMaintenance::rebuild;
    }

    public static void rebuild(ServerSubLevel subLevel) {

        List<KineticBlockEntity> kinetics = collectKinetics(subLevel);

        if (kinetics.isEmpty()) return;

        for (KineticBlockEntity kinetic : kinetics) {

            if (kinetic.isRemoved()) continue;

            try {

                if (kinetic.hasNetwork()) kinetic.detachKinetics();

            } catch (Exception e) {

                System.err.println("[NPShips] Failed to detach kinetic " + kinetic.getBlockPos());
            }

            kinetic.updateSpeed = true;
        }
    }

    private static List<KineticBlockEntity> collectKinetics(ServerSubLevel subLevel) {

        List<KineticBlockEntity> result = new ArrayList<>();

        var plot = subLevel.getPlot();

        for (var holder : plot.getLoadedChunks()) {

            var chunk = holder.getChunk();

            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {

                if (blockEntity instanceof KineticBlockEntity kinetic) result.add(kinetic);
            }
        }

        return result;
    }
}
