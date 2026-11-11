package com.eynny.npships.integration.cannons;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.integration.OptionalCalls;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;

/** Initialize editor-built autocannon joints only when no saved connection state exists. */
public final class CannonPlacement {
    private CannonPlacement() {}

    public static void initialize(ServerLevel level, BlockPos origin, SchematicDocument doc)
            throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("createbigcannons")) return;
        if (doc.paletteCopy().stream()
                .noneMatch(
                        t ->
                                ((net.minecraft.nbt.CompoundTag) t)
                                        .getString("Name")
                                        .startsWith("createbigcannons:"))) return;
        var type = Class.forName("rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlock");
        for (var local : doc.blocks().keySet()) {
            if (!doc.blockName(local).startsWith("createbigcannons:")
                    || doc.blockEntityData(local).contains("Connections")) continue;
            var p = origin.offset(local);
            var state = level.getBlockState(p);
            var block = state.getBlock();
            if (!type.isInstance(block)) continue;
            var be = level.getBlockEntity(p);
            if (be == null) continue;
            var material =
                    OptionalCalls.call(block, "getAutocannonMaterialInLevel", level, state, p);
            for (var side : Direction.values()) {
                var next = local.relative(side);
                if (!doc.blocks().containsKey(next)) continue;
                var q = origin.offset(next);
                var other = level.getBlockState(q);
                if (!type.isInstance(other.getBlock())) continue;
                if (!(boolean) OptionalCalls.call(block, "canConnectToSide", state, side)
                        || !(boolean)
                                OptionalCalls.call(
                                        other.getBlock(),
                                        "canConnectToSide",
                                        other,
                                        side.getOpposite())) continue;
                if (!material.equals(
                        OptionalCalls.call(
                                other.getBlock(), "getAutocannonMaterialInLevel", level, other, q)))
                    continue;
                var neighbor = level.getBlockEntity(q);
                if (neighbor == null) continue;
                var a = OptionalCalls.call(be, "cannonBehavior");
                var b = OptionalCalls.call(neighbor, "cannonBehavior");
                // An explicitly saved disconnected neighbor is intentional, not an omitted default.
                if (doc.blockEntityData(next).contains("Connections")
                        && !(boolean) OptionalCalls.call(b, "isConnectedTo", side.getOpposite()))
                    continue;
                OptionalCalls.call(a, "setConnectedFace", side, true);
                OptionalCalls.call(b, "setConnectedFace", side.getOpposite(), true);
                be.setChanged();
                neighbor.setChanged();
            }
        }
    }
}
