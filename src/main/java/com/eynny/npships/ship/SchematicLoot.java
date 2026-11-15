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
        try (var preparation = beginApply(template, assignments, level, local, origin, null)) {
            while (!preparation.step(() -> true)) {}
        }
    }

    public static Preparation beginApply(
            CompoundTag template,
            List<LootAssignments.Entry> assignments,
            ServerLevel level,
            com.google.gson.JsonObject local,
            BlockPos origin,
            com.eynny.npships.resources.PreparedTemplateResources resources) {
        return beginApply(
                template,
                assignments,
                level,
                local,
                origin,
                resources,
                net.minecraft.util.RandomSource.create(level.random.nextLong()));
    }

    /** The enclosing preparation owns this stream across every yielded roll. */
    public static Preparation beginApply(
            CompoundTag template,
            List<LootAssignments.Entry> assignments,
            ServerLevel level,
            com.google.gson.JsonObject local,
            BlockPos origin,
            com.eynny.npships.resources.PreparedTemplateResources resources,
            net.minecraft.util.RandomSource random) {
        return new Preparation(template, assignments, level, local, origin, resources, random);
    }

    /**
     * All assignment/palette validation completes before the first detached container is changed.
     */
    public static final class Preparation implements AutoCloseable {
        private enum Phase {
            REQUESTS,
            COMPILE_START,
            COMPILE,
            INDEX,
            VALIDATE,
            VALIDATE_PALETTE,
            APPLY,
            COPY,
            SANITIZE,
            SPACE,
            FILL,
            ITEMS_START,
            ITEMS,
            SAVE,
            WRITE,
            DONE
        }

        private final CompoundTag template;
        private final List<LootAssignments.Entry> assignments;
        private final ServerLevel level;
        private final net.minecraft.util.RandomSource random;
        private final com.google.gson.JsonObject local,
                requested = new com.google.gson.JsonObject();
        private final BlockPos origin;
        private final com.eynny.npships.resources.PreparedTemplateResources resources;
        private java.util.Set<BlockPos> wanted = new java.util.HashSet<>();
        private java.util.Map<BlockPos, CompoundTag> entries = new HashMap<>();
        private java.util.Map<ResourceLocation, net.minecraft.world.level.storage.loot.LootTable>
                localTables = new HashMap<>();
        private java.util.Iterator<java.util.Map.Entry<String, com.google.gson.JsonElement>> tables;
        private final net.minecraft.nbt.ListTag blocks, palettes;
        private Phase phase;
        private int index, paletteIndex, roll, slot;
        private long progress;
        private boolean closed;
        private LootAssignments.Entry assignment;
        private CompoundTag block, nbt;
        private com.eynny.npships.resources.NbtWorkCursor plainCopy;
        private com.eynny.npships.resources.PreparedTemplateResources.Copy<CompoundTag> ownedCopy;
        private net.minecraft.world.Container container;
        private net.minecraft.world.level.storage.loot.LootParams params;
        private net.minecraft.world.level.storage.loot.LootTable table;
        private net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> items;

        private Preparation(
                CompoundTag template,
                List<LootAssignments.Entry> assignments,
                ServerLevel level,
                com.google.gson.JsonObject local,
                BlockPos origin,
                com.eynny.npships.resources.PreparedTemplateResources resources,
                net.minecraft.util.RandomSource random) {
            this.random = java.util.Objects.requireNonNull(random);
            this.template = template;
            this.assignments = assignments;
            this.level = level;
            this.local = local;
            this.origin = origin.immutable();
            this.resources = resources;
            blocks = template.getList("blocks", Tag.TAG_COMPOUND);
            palettes = template.getList("palettes", Tag.TAG_LIST);
            phase = assignments.isEmpty() ? Phase.DONE : Phase.REQUESTS;
        }

        public boolean step(java.util.function.BooleanSupplier canContinue) {
            if (closed) throw new IllegalStateException("Loot preparation is closed");
            while (phase != Phase.DONE) {
                if (phase == Phase.COPY) {
                    long before = ownedCopy == null ? plainCopy.progress() : ownedCopy.progress();
                    boolean done =
                            ownedCopy == null
                                    ? plainCopy.step(canContinue)
                                    : ownedCopy.step(canContinue);
                    progress +=
                            (ownedCopy == null ? plainCopy.progress() : ownedCopy.progress())
                                    - before;
                    if (!done) return false;
                    nbt =
                            ownedCopy == null
                                    ? (CompoundTag) plainCopy.copyResult()
                                    : ownedCopy.result();
                    closeCopy();
                    phase = Phase.SANITIZE;
                    progress++;
                    continue;
                }
                if (!canContinue.getAsBoolean()) return false;
                switch (phase) {
                    case REQUESTS -> {
                        if (index < assignments.size()) {
                            var entry = assignments.get(index++);
                            wanted.add(entry.pos());
                            if (local.has(entry.table().toString()))
                                requested.add(
                                        entry.table().toString(),
                                        local.get(entry.table().toString()));
                        } else {
                            index = 0;
                            phase = Phase.COMPILE_START;
                        }
                    }
                    case COMPILE_START -> {
                        com.eynny.npships.authoring.validation.JsonStructure.validate(requested);
                        tables = requested.entrySet().iterator();
                        phase = Phase.COMPILE;
                    }
                    case COMPILE -> {
                        if (tables.hasNext()) {
                            var entry = tables.next();
                            var single = new com.google.gson.JsonObject();
                            single.add(entry.getKey(), entry.getValue());
                            // One native codec/table validation boundary; no host loop over tables.
                            localTables.putAll(
                                    com.eynny.npships.authoring.EmbeddedLoot.compile(
                                            single, level));
                        } else phase = Phase.INDEX;
                    }
                    case INDEX -> {
                        if (index < blocks.size()) {
                            var value = blocks.getCompound(index++);
                            var pos = value.getList("pos", Tag.TAG_INT);
                            if (pos.size() == 3) {
                                var at = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
                                if (wanted.contains(at)) entries.put(at, value);
                            }
                        } else {
                            index = 0;
                            phase = Phase.VALIDATE;
                        }
                    }
                    case VALIDATE -> {
                        if (index == assignments.size()) {
                            index = 0;
                            phase = Phase.APPLY;
                            break;
                        }
                        assignment = assignments.get(index);
                        if (!localTables.containsKey(assignment.table())
                                && !isContainerTable(level.getServer(), assignment.table()))
                            throw new IllegalArgumentException(
                                    "Missing loot table or incompatible container context: "
                                            + assignment.table());
                        block = entries.get(assignment.pos());
                        if (block == null)
                            throw new IllegalArgumentException(
                                    "No loot container block at " + assignment.pos());
                        paletteIndex = 0;
                        phase = Phase.VALIDATE_PALETTE;
                    }
                    case VALIDATE_PALETTE -> {
                        var palette =
                                palettes.isEmpty()
                                        ? template.getList("palette", Tag.TAG_COMPOUND)
                                        : palettes.getList(paletteIndex);
                        int stateIndex = block.getInt("state");
                        if (stateIndex < 0 || stateIndex >= palette.size())
                            throw new IllegalArgumentException("Invalid container palette index.");
                        var state =
                                NbtUtils.readBlockState(
                                        level.holderLookup(Registries.BLOCK),
                                        palette.getCompound(stateIndex));
                        if (!(state.getBlock() instanceof EntityBlock entityBlock)
                                || !(entityBlock.newBlockEntity(assignment.pos(), state)
                                        instanceof RandomizableContainer))
                            throw new IllegalArgumentException(
                                    "Block does not support loot tables at " + assignment.pos());
                        if (++paletteIndex >= Math.max(1, palettes.size())) {
                            index++;
                            phase = Phase.VALIDATE;
                        }
                    }
                    case APPLY -> {
                        if (index == assignments.size()) {
                            phase = Phase.DONE;
                            break;
                        }
                        assignment = assignments.get(index);
                        block = entries.get(assignment.pos());
                        if (resources == null)
                            plainCopy =
                                    com.eynny.npships.resources.NbtWorkCursor.copy(
                                            block.getCompound("nbt"), bytes -> {});
                        else ownedCopy = resources.copyInputIncrementally(block.getCompound("nbt"));
                        phase = Phase.COPY;
                    }
                    case SANITIZE -> {
                        nbt.remove("Items");
                        nbt.remove("LootTableSeed");
                        var components = nbt.getCompound("components");
                        components.remove("minecraft:container");
                        components.remove("minecraft:container_loot");
                        nbt.putString("LootTable", assignment.table().toString());
                        if (!localTables.containsKey(assignment.table())
                                && assignment.rolls() <= 1) {
                            phase = Phase.WRITE;
                            break;
                        }
                        var palette =
                                palettes.isEmpty()
                                        ? template.getList("palette", Tag.TAG_COMPOUND)
                                        : palettes.getList(0);
                        var state =
                                NbtUtils.readBlockState(
                                        level.holderLookup(Registries.BLOCK),
                                        palette.getCompound(block.getInt("state")));
                        var entity =
                                ((EntityBlock) state.getBlock())
                                        .newBlockEntity(assignment.pos(), state);
                        if (!(entity instanceof net.minecraft.world.Container value))
                            throw new IllegalArgumentException(
                                    "Local loot requires a Container at " + assignment.pos());
                        container = value;
                        params =
                                new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                                        .withParameter(
                                                net.minecraft.world.level.storage.loot.parameters
                                                        .LootContextParams.ORIGIN,
                                                net.minecraft.world.phys.Vec3.atCenterOf(
                                                        origin.offset(assignment.pos())))
                                        .create(LootContextParamSets.CHEST);
                        table = localTables.get(assignment.table());
                        if (table == null)
                            table =
                                    level.getServer()
                                            .reloadableRegistries()
                                            .lookup()
                                            .lookupOrThrow(Registries.LOOT_TABLE)
                                            .getOrThrow(
                                                    ResourceKey.create(
                                                            Registries.LOOT_TABLE,
                                                            assignment.table()))
                                            .value();
                        roll = slot = 0;
                        phase = Phase.SPACE;
                    }
                    case SPACE -> {
                        if (slot == container.getContainerSize()) phase = Phase.ITEMS_START;
                        else if (container.getItem(slot++).isEmpty()) phase = Phase.FILL;
                    }
                    case FILL -> {
                        table.fill(container, params, random.nextLong());
                        slot = 0;
                        phase = ++roll >= assignment.rolls() ? Phase.ITEMS_START : Phase.SPACE;
                    }
                    case ITEMS_START -> {
                        items =
                                net.minecraft.core.NonNullList.withSize(
                                        container.getContainerSize(),
                                        net.minecraft.world.item.ItemStack.EMPTY);
                        slot = 0;
                        phase = Phase.ITEMS;
                    }
                    case ITEMS -> {
                        if (slot < items.size()) {
                            items.set(slot, container.getItem(slot));
                            slot++;
                        } else phase = Phase.SAVE;
                    }
                    case SAVE -> {
                        nbt.remove("LootTable");
                        net.minecraft.world.ContainerHelper.saveAllItems(
                                nbt, items, level.registryAccess());
                        phase = Phase.WRITE;
                    }
                    case WRITE -> {
                        block.put("nbt", nbt);
                        nbt = block = null;
                        container = null;
                        params = null;
                        table = null;
                        items = null;
                        index++;
                        phase = Phase.APPLY;
                    }
                    default -> throw new IllegalStateException("Invalid loot preparation phase");
                }
                progress++;
            }
            return true;
        }

        public long progress() {
            return progress;
        }

        private void closeCopy() {
            if (plainCopy != null) plainCopy.close();
            if (ownedCopy != null) ownedCopy.close();
            plainCopy = null;
            ownedCopy = null;
        }

        @Override
        public void close() {
            closed = true;
            closeCopy();
            wanted = null;
            entries = null;
            localTables = null;
            container = null;
            params = null;
            table = null;
            items = null;
            nbt = block = null;
        }
    }
}
