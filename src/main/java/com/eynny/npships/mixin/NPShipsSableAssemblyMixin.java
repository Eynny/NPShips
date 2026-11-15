package com.eynny.npships.mixin;

import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.ship.DirectSpawnOwnership;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes Sable-created sub-levels after SubLevelAssemblyHelper has finished initializing them.
 *
 * <p>This is important for split handling because ServerSubLevel#setSplitFrom() is called inside
 * assembleBlocks() shortly before it returns.
 */
@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
public abstract class NPShipsSableAssemblyMixin {

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(
            method = "moveBlocks",
            require = 1,
            remap = false)
    private static void npships$nativeConnectionScope(
            ServerLevel level,
            SubLevelAssemblyHelper.AssemblyTransform transform,
            Iterable<net.minecraft.core.BlockPos> blocks,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        try (var scope = com.eynny.npships.integration.create.LogisticsNativeTransfer.open(level)) {
            original.call(level, transform, blocks);
        }
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "moveBlocks",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/entity/BlockEntity;saveWithFullMetadata(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"),
            require = 1,
            remap = false)
    private static net.minecraft.nbt.CompoundTag npships$preserveNativeConnections(
            net.minecraft.world.level.block.entity.BlockEntity entity,
            net.minecraft.core.HolderLookup.Provider registries,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<
                            net.minecraft.nbt.CompoundTag>
                    original) {
        var saved = original.call(entity, registries);
        com.eynny.npships.integration.create.LogisticsNativeTransfer.saved(entity);
        return saved;
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "moveBlocks",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/entity/BlockEntity;loadWithComponents(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V"),
            require = 1,
            remap = false)
    private static void npships$mappedNativeConnections(
            net.minecraft.world.level.block.entity.BlockEntity entity,
            net.minecraft.nbt.CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider registries,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        try {
            original.call(entity, tag, registries);
            com.eynny.npships.integration.create.LogisticsNativeTransfer.loaded(entity);
        } catch (Throwable failure) {
            try {
                com.eynny.npships.integration.create.LogisticsNativeTransfer.failed();
            } catch (Throwable rollback) {
                failure.addSuppressed(rollback);
            }
            DirectSpawnOwnership.nativeTransferFailed(entity, failure);
            throw failure;
        }
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "assembleBlocks",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;allocateNewSubLevel(Ldev/ryanhcode/sable/companion/math/Pose3d;)Ldev/ryanhcode/sable/sublevel/SubLevel;"),
            require = 1,
            remap = false)
    private static dev.ryanhcode.sable.sublevel.SubLevel npships$ownedChild(
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.companion.math.Pose3d pose,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<
                            dev.ryanhcode.sable.sublevel.SubLevel>
                    original,
            ServerLevel level,
            net.minecraft.core.BlockPos anchor,
            Iterable<net.minecraft.core.BlockPos> blocks,
            dev.ryanhcode.sable.companion.math.BoundingBox3ic bounds) {
        try (var allocation = DirectSpawnOwnership.nativeChild(level, anchor)) {
            return original.call(container, pose);
        }
    }

    @Redirect(
            method = "assembleBlocks",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper;moveBlocks(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper$AssemblyTransform;Ljava/lang/Iterable;)V"),
            remap = false,
            require = 1)
    private static void npships$ownedTransfer(
            ServerLevel level,
            SubLevelAssemblyHelper.AssemblyTransform transform,
            Iterable<net.minecraft.core.BlockPos> blocks) {
        try (var scope =
                com.eynny.npships.authoring.patches.PatchedTemplate.nativeTransferScope(
                        level, transform, blocks)) {
            SubLevelAssemblyHelper.moveBlocks(level, transform, blocks);
        }
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "moveBlocks",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
                            ordinal = 0),
            require = 1,
            remap = false)
    private static net.minecraft.world.level.block.state.BlockState npships$mappedNativeCell(
            net.minecraft.world.level.chunk.LevelChunk chunk,
            net.minecraft.core.BlockPos position,
            net.minecraft.world.level.block.state.BlockState state,
            boolean moved,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<
                            net.minecraft.world.level.block.state.BlockState>
                    original,
            ServerLevel level,
            SubLevelAssemblyHelper.AssemblyTransform transform,
            Iterable<net.minecraft.core.BlockPos> blocks) {
        var before = original.call(chunk, position, state, moved);
        DirectSpawnOwnership.nativeCellTransferred(level, transform, position);
        return before;
    }

    @Inject(method = "assembleBlocks", at = @At("RETURN"), remap = false, require = 1)
    private static void npships$afterAssembleBlocks(
            final ServerLevel level,
            final net.minecraft.core.BlockPos anchor,
            final Iterable<net.minecraft.core.BlockPos> blocks,
            final dev.ryanhcode.sable.companion.math.BoundingBox3ic bounds,
            final CallbackInfoReturnable<ServerSubLevel> cir) {
        final ServerSubLevel subLevel = cir.getReturnValue();

        if (subLevel == null) {
            return;
        }

        DirectSpawnOwnership.nativeAssemblyFinished(level, subLevel);

        com.eynny.npships.authoring.patches.PatchedTemplate.onAssembled(
                level, anchor, blocks, subLevel);

        final java.util.UUID parentUUID = subLevel.getSplitFromSubLevel();

        /*
         * Normal assembly from the main world:
         *
         *     splitFrom == null
         *
         * Split-created child:
         *
         *     splitFrom == parent UUID
         */
        if (parentUUID == null) {
            return;
        }

        NPShipsLifecycleManager.onSplitChild(level, subLevel, parentUUID);
    }
}
