package com.eynny.npships.mixin;

import com.eynny.npships.lifecycle.NPShipsLifecycleManager;

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
