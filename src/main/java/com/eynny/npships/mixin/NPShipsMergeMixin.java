package com.eynny.npships.mixin;

import com.eynny.npships.authoring.patches.PatchedTemplate;
import com.eynny.npships.block.ClaimBlockEntity;
import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.lifecycle.NPShipsSavedData;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Observe the committed block transfer, not proximity or the later removal notification. */
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "dev.simulated_team.simulated.util.SimAssemblyHelper", remap = false)
public abstract class NPShipsMergeMixin {
    @WrapMethod(method = "disassembleSubLevel", require = 1)
    private static void npships$currentTransfer(
            Level originalLevel,
            SubLevel source,
            BlockPos from,
            BlockPos to,
            Rotation rotation,
            boolean sound,
            Operation<Void> original) {
        if (!(originalLevel instanceof ServerLevel level)
                || !(source instanceof ServerSubLevel sourceBody)) {
            original.call(originalLevel, source, from, to, rotation, sound);
            return;
        }
        var target = Sable.HELPER.getContaining(level, to);
        var data = NPShipsSavedData.peek(level);
        boolean owned =
                NativeBlockAccess.active(level)
                        || PatchedTemplate.observesNativeAssembly(level)
                        || data != null
                                && (data.isTracked(source.getUniqueId())
                                        || target != null && data.isTracked(target.getUniqueId()))
                        || ClaimBlockEntity.observesTransfer(level, source, target);
        if (!owned) {
            original.call(originalLevel, source, from, to, rotation, sound);
            return;
        }
        // Source block selection happens before moveBlocks. Keep the entire existing native
        // operation on the captured owners, including legitimate source retirement during cleanup.
        try (var reads =
                NativeBlockAccess.openTransfer(
                        level,
                        key -> {
                            var at = new ChunkPos(key);
                            if (target instanceof ServerSubLevel body
                                    && body.getPlot().contains(at)) return body;
                            return sourceBody.getPlot().contains(at) ? sourceBody : null;
                        },
                        source != target ? sourceBody : null)) {
            original.call(originalLevel, source, from, to, rotation, sound);
        }
    }

    @Redirect(
            method = "disassembleSubLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper;moveBlocks(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper$AssemblyTransform;Ljava/lang/Iterable;)V"),
            remap = false,
            require = 1)
    private static void npships$merge(
            ServerLevel level,
            SubLevelAssemblyHelper.AssemblyTransform transform,
            Iterable<BlockPos> blocks,
            Level originalLevel,
            SubLevel source,
            BlockPos from,
            BlockPos to,
            Rotation rotation,
            boolean sound) {
        var target = Sable.HELPER.getContaining(level, to);
        boolean merge = source != target && target instanceof ServerSubLevel;
        boolean keep =
                merge
                        && NPShipsLifecycleManager.isTracked(level, source.getUniqueId())
                        && NPShipsLifecycleManager.isTracked(level, target.getUniqueId());
        long deadline =
                merge
                        ? Math.max(
                                NaturalSpawnPhysicalChunkManager.protectedUntil(
                                        source.getUniqueId()),
                                NaturalSpawnPhysicalChunkManager.protectedUntil(
                                        target.getUniqueId()))
                        : 0;
        // Observe the committed native transfer once; the enclosing scope also owns source
        // selection.
        SubLevelAssemblyHelper.moveBlocks(level, transform, blocks);
        if (merge) {
            NPShipsLifecycleManager.finishMerge(
                    level, source.getUniqueId(), target.getUniqueId(), keep);
            NaturalSpawnPhysicalChunkManager.untrack(source.getUniqueId());
            NaturalSpawnPhysicalChunkManager.untrack(target.getUniqueId());
            if (keep && deadline > level.getGameTime())
                NaturalSpawnPhysicalChunkManager.protectUntil((ServerSubLevel) target, deadline);
        }
    }
}
