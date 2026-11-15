package com.eynny.npships.mixin;

import com.eynny.npships.integration.create.PendingBearingAssembly;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;

import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** One unfinished source position, without retaining the construction owner after publication. */
@Mixin(value = MechanicalBearingBlockEntity.class, remap = false)
public abstract class NPShipsBearingRetryMixin implements PendingBearingAssembly.Waiting {
    @Shadow protected boolean assembleNextTick;
    @Unique private BlockPos npships$awaitedCell;

    public BlockPos npships$awaitedCell() {
        return npships$awaitedCell;
    }

    public void npships$awaitedCell(BlockPos position) {
        npships$awaitedCell = position;
    }

    public void npships$retryAssembly() {
        assembleNextTick = true;
    }
}
