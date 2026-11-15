package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.api.patches.PatchConnectionFields;

import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Linked typewriter rows use its native item-pair/key codec, not an invented inventory. */
public final class SimulatedConnectionFields
        implements PatchConnectionFields,
                com.eynny.npships.authoring.patches.PatchSourceOperations {
    @Override
    public void validateNativeBlock(String name, CompoundTag data) {
        if (name.equals("simulated:swivel_bearing") && data.hasUUID("SubLevelID"))
            throw new IllegalArgumentException(
                    "Disassemble the assembled swivel bearing before capturing or spawning it.");
    }

    @Override
    public void validateLive(
            net.minecraft.world.level.block.entity.BlockEntity entity,
            net.minecraft.core.BlockPos position) {
        if (net.neoforged.fml.ModList.get().isLoaded("simulated")
                && entity
                        instanceof
                        dev.simulated_team.simulated.content.blocks.swivel_bearing
                                                .SwivelBearingBlockEntity
                                        bearing
                && bearing.isAssembled())
            throw new IllegalArgumentException(
                    "Disassemble swivel bearing before export: " + position);
    }

    @Override
    public List<Group> describe(
            BlockState state, CompoundTag data, HolderLookup.Provider registries) {
        if (!net.neoforged.fml.ModList.get().isLoaded("simulated")) return List.of();
        if (!(state.getBlock() instanceof EntityBlock factory)
                || !(factory.newBlockEntity(BlockPos.ZERO, state)
                        instanceof LinkedTypewriterBlockEntity)) return List.of();
        return List.of(
                new Group(
                        "keyboard_channels",
                        text("connection_keys"),
                        List.of("Keys"),
                        true,
                        List.of(
                                new Field("GLFWKey", text("connection_key"), ValueKind.KEY),
                                new Field("FirstItem", text("connection_first"), ValueKind.ITEM),
                                new Field(
                                        "SecondItem", text("connection_second"), ValueKind.ITEM))));
    }

    @Override
    public boolean rotatesData(BlockState state) {
        return net.neoforged.fml.ModList.get().isLoaded("simulated")
                && state.getBlock()
                        instanceof
                        dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter
                                .LinkedTypewriterBlock;
    }

    @Override
    public void rotateData(
            BlockState state,
            CompoundTag original,
            CompoundTag data,
            net.minecraft.world.level.block.Rotation rotation) {
        if (!original.contains("Keys")) return;
        var oldRows = original.getList("Keys", net.minecraft.nbt.Tag.TAG_COMPOUND);
        var nativeRows = data.getList("Keys", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (oldRows.size() != nativeRows.size())
            throw new IllegalArgumentException(
                    "Native keyboard channels could not be retained during rotation");
        var byKey = new java.util.HashMap<Integer, CompoundTag>();
        for (var row : nativeRows) {
            var channel = (CompoundTag) row;
            if (!channel.contains("GLFWKey", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)
                    || byKey.put(channel.getInt("GLFWKey"), channel) != null)
                throw new IllegalArgumentException("Ambiguous native keyboard channel identity");
        }
        var retained = new net.minecraft.nbt.ListTag();
        for (var row : oldRows) {
            var old = (CompoundTag) row;
            if (!old.contains("GLFWKey", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC))
                throw new IllegalArgumentException("Missing keyboard channel identity");
            var transformed = byKey.remove(old.getInt("GLFWKey"));
            if (transformed == null)
                throw new IllegalArgumentException(
                        "Keyboard channel identity changed during rotation");
            for (String item : List.of("FirstItem", "SecondItem"))
                if (old.getCompound(item).contains("id") && transformed.getCompound(item).isEmpty())
                    throw new IllegalArgumentException(
                            "Native keyboard frequency item is unavailable");
            // The native codec drops unknown row fields. Keep those fields, while native output
            // remains authoritative for fields it actually serializes/transforms (including future
            // ones).
            var merged = old.copy();
            merged.merge(transformed);
            retained.add(merged);
        }
        data.put("Keys", retained);
    }

    private static Component text(String key) {
        return Component.translatable("npships.tools." + key);
    }
}
