package com.eynny.npships.integration.create;

import com.eynny.npships.api.patches.PatchConnectionFields;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Native Create link behaviours also cover compatible subclasses supplied by other mods. */
public final class CreateConnectionFields implements PatchConnectionFields {
    @Override
    public List<Group> describe(
            BlockState state, CompoundTag data, HolderLookup.Provider registries) {
        if (!(state.getBlock() instanceof EntityBlock factory)) return List.of();
        var target = factory.newBlockEntity(BlockPos.ZERO, state);
        if (!(target instanceof SmartBlockEntity smart)) return List.of();
        smart.markVirtual();
        // The first native read installs deferred behaviours; no level, initialize or tick is used.
        smart.loadWithComponents(new CompoundTag(), registries);
        if (smart.getBehaviour(LinkBehaviour.TYPE) != null)
            return List.of(
                    new Group(
                            "frequency",
                            text("connection_channel"),
                            List.of(),
                            false,
                            List.of(
                                    new Field(
                                            "FrequencyFirst",
                                            text("connection_first"),
                                            ValueKind.ITEM),
                                    new Field(
                                            "FrequencyLast",
                                            text("connection_second"),
                                            ValueKind.ITEM))));
        if (smart instanceof DisplayLinkBlockEntity)
            return List.of(
                    new Group(
                            "display_target",
                            text("connection_target"),
                            List.of(),
                            false,
                            List.of(
                                    new Field(
                                            "TargetOffset",
                                            text("connection_target"),
                                            ValueKind.RELATIVE_POSITION))));
        return List.of();
    }

    private static Component text(String key) {
        return Component.translatable("npships.tools." + key);
    }
}
