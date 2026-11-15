package com.eynny.npships.integration;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.DetachedInventory;
import com.eynny.npships.authoring.patches.PatchInventoryEditor;
import com.eynny.npships.integration.radars.RadarFilters;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.Set;

/** Native inventory serializers. All state belongs to one detached edit session. */
public final class NativeInventoryEditors implements PatchInventoryEditor {
    private final String provider;

    private NativeInventoryEditors(String provider) {
        this.provider = provider;
    }

    public static <T> T capability(String provider, Class<T> contract) {
        return contract == PatchInventoryEditor.class
                        && Set.of("create_devices", "computercraft_peripheral", "radars_network")
                                .contains(provider)
                ? contract.cast(new NativeInventoryEditors(provider))
                : null;
    }

    @Override
    public boolean applies(SchematicDocument doc, BlockPos pos, BlockEntity detached) {
        String id = doc.blockName(pos);
        return switch (provider) {
            case "create_devices" ->
                    Set.of("create:creative_crate", "create:item_vault").contains(id);
            case "computercraft_peripheral" ->
                    id.startsWith("computercraft:turtle_")
                            || Set.of("computercraft:printer", "computercraft:disk_drive")
                                    .contains(id);
            case "radars_network" -> id.equals("create_radar:network_filterer");
            default -> false;
        };
    }

    @Override
    public Session open(
            SchematicDocument doc,
            BlockPos pos,
            Level context,
            BlockEntity detached,
            CompoundTag data) {
        return switch (provider) {
            case "create_devices" -> new Create(doc, pos, context, detached, data);
            case "computercraft_peripheral" -> new Computer(doc, pos, context, detached, data);
            case "radars_network" -> new Radar(doc, pos, context, detached, data);
            default ->
                    throw new IllegalArgumentException("Unsupported inventory owner: " + provider);
        };
    }

    private static final class Create extends DetachedInventory {
        private final boolean supply;

        Create(
                SchematicDocument doc,
                BlockPos pos,
                Level context,
                BlockEntity detached,
                CompoundTag data) {
            super(
                    doc,
                    pos,
                    context,
                    detached,
                    data,
                    !doc.blockName(pos).equals("create:creative_crate"));
            supply = doc.blockName(pos).equals("create:creative_crate");
        }

        @Override
        public String kind() {
            return supply ? "supply" : "normal";
        }

        @Override
        public int size() {
            return supply ? 1 : super.size();
        }

        @Override
        public ItemStack get(int slot) {
            return supply
                    ? ItemStack.parseOptional(context.registryAccess(), data.getCompound("Filter"))
                    : super.get(slot);
        }

        @Override
        public String label(int slot) {
            return supply ? "supply" : super.label(slot);
        }

        @Override
        public boolean accepts(int slot, ItemStack input) {
            return supply ? slot == 0 : super.accepts(slot, input);
        }

        @Override
        public int limit(int slot, ItemStack input) {
            return supply ? (input.isEmpty() ? 0 : 1) : super.limit(slot, input);
        }

        @Override
        protected void store(int slot, ItemStack stack) {
            if (handler instanceof ItemStackHandler nativeItems
                    && doc.blockName(pos).equals("create:item_vault")) {
                var copy = new ItemStackHandler(nativeItems.getSlots());
                copy.deserializeNBT(
                        context.registryAccess(),
                        nativeItems.serializeNBT(context.registryAccess()));
                copy.setStackInSlot(slot, stack);
                nativeItems.deserializeNBT(
                        context.registryAccess(), copy.serializeNBT(context.registryAccess()));
            } else super.store(slot, stack);
        }

        @Override
        public SchematicDocument put(int slot, ItemStack input) {
            if (!supply) return super.put(slot, input);
            var stack = prepared(slot, input);
            if (!accepts(slot, stack))
                throw new IllegalArgumentException("This resource is not accepted by this slot.");
            if (!stack.isEmpty() && (stack.getCount() < 1 || stack.getCount() > limit(slot, stack)))
                throw new IllegalArgumentException("Slot capacity: " + limit(slot, stack));
            data.put("Filter", stack.saveOptional(context.registryAccess()));
            data.remove("LootTable");
            data.remove("LootTableSeed");
            return com.eynny.npships.authoring.patches.SchematicResources.update(
                    doc, pos, data, context);
        }
    }

    private static final class Computer extends DetachedInventory {
        private final String id;

        Computer(
                SchematicDocument doc,
                BlockPos pos,
                Level context,
                BlockEntity detached,
                CompoundTag data) {
            super(doc, pos, context, detached, data);
            id = doc.blockName(pos);
        }

        @Override
        public int columns() {
            return id.startsWith("computercraft:turtle_")
                    ? 4
                    : id.equals("computercraft:printer") ? 8 : super.columns();
        }

        @Override
        public int slotX(int slot) {
            return id.equals("computercraft:printer")
                    ? (slot == 0 ? 0 : 48 + (slot - 1) % 6 * 18)
                    : super.slotX(slot);
        }

        @Override
        public int slotY(int slot) {
            return id.equals("computercraft:printer")
                    ? (slot == 0 ? 13 : slot <= 6 ? 0 : 27)
                    : super.slotY(slot);
        }

        @Override
        public int gridHeight() {
            return id.equals("computercraft:printer") ? 45 : super.gridHeight();
        }

        @Override
        public String label(int slot) {
            return id.equals("computercraft:disk_drive")
                    ? "cc_media"
                    : id.equals("computercraft:printer")
                            ? (slot == 0 ? "cc_ink" : slot <= 6 ? "cc_paper" : "cc_output")
                            : super.label(slot);
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean accepts(int slot, ItemStack input) {
            if (slot < 0 || slot >= size()) return false;
            if (input.isEmpty()) return true;
            if (id.equals("computercraft:disk_drive"))
                try {
                    return input.getCapability(
                                    (net.neoforged.neoforge.capabilities.ItemCapability<?, Void>)
                                            OptionalCalls.call(
                                                    Class.forName(
                                                            "dan200.computercraft.api.media.MediaCapability"),
                                                    "get"))
                            != null;
                } catch (ReflectiveOperationException error) {
                    throw new IllegalArgumentException("Cannot query CC media providers", error);
                }
            if (id.equals("computercraft:printer") && slot >= 7)
                return BuiltInRegistries.ITEM
                        .getKey(input.getItem())
                        .toString()
                        .equals("computercraft:printed_page");
            return super.accepts(slot, input);
        }
    }

    private static final class Radar extends DetachedInventory {
        Radar(
                SchematicDocument doc,
                BlockPos pos,
                Level context,
                BlockEntity detached,
                CompoundTag data) {
            super(doc, pos, context, detached, data);
        }

        @Override
        public String kind() {
            return "filters";
        }

        @Override
        public String label(int slot) {
            return RadarFilters.KINDS.get(slot);
        }

        @Override
        public boolean accepts(int slot, ItemStack input) {
            return slot >= 0
                    && slot < size()
                    && (input.isEmpty() || RadarFilters.accepts(slot, input));
        }

        @Override
        public int limit(int slot, ItemStack input) {
            return input.isEmpty() ? 0 : 1;
        }

        @Override
        public SchematicDocument put(int slot, ItemStack input) {
            return RadarFilters.synchronize(super.put(slot, input), pos, context);
        }
    }
}
