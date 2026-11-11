package com.eynny.npships.ship;

import com.eynny.npships.authoring.LootAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import java.util.HashMap;
import java.util.List;

/** Apply to a newly read template before placement/assembly, never to a live container. */
public final class SchematicLoot {
    private SchematicLoot() {}

    public static boolean isContainerTable(
            final MinecraftServer server, final ResourceLocation id) {
        var holder =
                server.reloadableRegistries()
                        .lookup()
                        .lookupOrThrow(Registries.LOOT_TABLE)
                        .get(ResourceKey.create(Registries.LOOT_TABLE, id));
        return holder.isPresent()
                && LootContextParamSets.CHEST
                        .getAllowed()
                        .containsAll(holder.get().value().getParamSet().getRequired());
    }

    public static void apply(
            final CompoundTag template,
            final List<LootAssignments.Entry> assignments,
            final ServerLevel level) {
        apply(template, assignments, level, new com.google.gson.JsonObject(), BlockPos.ZERO);
    }

    public static void apply(
            CompoundTag template,
            List<LootAssignments.Entry> assignments,
            ServerLevel level,
            com.google.gson.JsonObject local,
            BlockPos origin) {
        if (assignments.isEmpty()) return;
        var requested = new com.google.gson.JsonObject();
        for (var assignment : assignments)
            if (local.has(assignment.table().toString()))
                requested.add(
                        assignment.table().toString(), local.get(assignment.table().toString()));
        var localTables = com.eynny.npships.authoring.EmbeddedLoot.compile(requested, level);
        var entries = new HashMap<BlockPos, CompoundTag>();
        for (var value : template.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            var pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() == 3)
                entries.put(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), block);
        }
        var palettes = template.getList("palettes", Tag.TAG_LIST);
        // Validate ALL assignments and palettes before changing even the temporary template.
        for (var assignment : assignments) {
            if (!localTables.containsKey(assignment.table())
                    && !isContainerTable(level.getServer(), assignment.table()))
                throw new IllegalArgumentException(
                        "Missing loot table or incompatible container context: "
                                + assignment.table());
            var block = entries.get(assignment.pos());
            if (block == null)
                throw new IllegalArgumentException(
                        "No loot container block at " + assignment.pos());
            for (int i = 0; i < Math.max(1, palettes.size()); i++) {
                var palette =
                        palettes.isEmpty()
                                ? template.getList("palette", Tag.TAG_COMPOUND)
                                : palettes.getList(i);
                int index = block.getInt("state");
                if (index < 0 || index >= palette.size())
                    throw new IllegalArgumentException("Invalid container palette index.");
                var state =
                        NbtUtils.readBlockState(
                                level.holderLookup(Registries.BLOCK), palette.getCompound(index));
                if (!(state.getBlock() instanceof EntityBlock entityBlock)
                        || !(entityBlock.newBlockEntity(assignment.pos(), state)
                                instanceof RandomizableContainer))
                    throw new IllegalArgumentException(
                            "Block does not support loot tables at " + assignment.pos());
            }
        }
        for (var assignment : assignments) {
            var block = entries.get(assignment.pos());
            var nbt = block.getCompound("nbt").copy();
            nbt.remove("Items");
            nbt.remove("LootTableSeed");
            // Components must not override the newly assigned loot or retain old inventory
            // contents.
            var components = nbt.getCompound("components");
            components.remove("minecraft:container");
            components.remove("minecraft:container_loot");
            nbt.putString("LootTable", assignment.table().toString());
            if (localTables.containsKey(assignment.table()) || assignment.rolls() > 1) {
                var palettesList = template.getList("palettes", Tag.TAG_LIST);
                var palette =
                        palettesList.isEmpty()
                                ? template.getList("palette", Tag.TAG_COMPOUND)
                                : palettesList.getList(0);
                var state =
                        NbtUtils.readBlockState(
                                level.holderLookup(Registries.BLOCK),
                                palette.getCompound(block.getInt("state")));
                var entity =
                        ((EntityBlock) state.getBlock()).newBlockEntity(assignment.pos(), state);
                if (!(entity instanceof net.minecraft.world.Container container))
                    throw new IllegalArgumentException(
                            "Local loot requires a Container at " + assignment.pos());
                var params =
                        new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                                .withParameter(
                                        net.minecraft.world.level.storage.loot.parameters
                                                .LootContextParams.ORIGIN,
                                        net.minecraft.world.phys.Vec3.atCenterOf(
                                                origin.offset(assignment.pos())))
                                .create(LootContextParamSets.CHEST);
                var table = localTables.get(assignment.table());
                if (table == null)
                    table =
                            level.getServer()
                                    .reloadableRegistries()
                                    .lookup()
                                    .lookupOrThrow(Registries.LOOT_TABLE)
                                    .getOrThrow(
                                            ResourceKey.create(
                                                    Registries.LOOT_TABLE, assignment.table()))
                                    .value();
                for (int roll = 0; roll < assignment.rolls(); roll++) {
                    boolean space = false;
                    for (int slot = 0; slot < container.getContainerSize(); slot++)
                        if (container.getItem(slot).isEmpty()) {
                            space = true;
                            break;
                        }
                    if (!space) break;
                    table.fill(container, params, level.random.nextLong());
                }
                var items =
                        net.minecraft.core.NonNullList.withSize(
                                container.getContainerSize(),
                                net.minecraft.world.item.ItemStack.EMPTY);
                for (int i = 0; i < items.size(); i++) items.set(i, container.getItem(i));
                nbt.remove("LootTable");
                net.minecraft.world.ContainerHelper.saveAllItems(
                        nbt, items, level.registryAccess());
            }
            block.put("nbt", nbt);
        }
    }
}
