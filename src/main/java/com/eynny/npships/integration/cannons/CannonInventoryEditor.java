package com.eynny.npships.integration.cannons;

import static com.eynny.npships.authoring.patches.SchematicResources.*;
import static com.eynny.npships.integration.cannons.CannonResources.*;

import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.DetachedInventory;
import com.eynny.npships.authoring.patches.PatchInventoryEditor;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** CBC owns magazine/round semantics; the generic editor owns the accepted transaction. */
public final class CannonInventoryEditor implements PatchInventoryEditor {
    private static final String CBC = "rbasamoyai.createbigcannons.";

    @Override
    public boolean applies(SchematicDocument doc, BlockPos pos, BlockEntity entity) {
        return ammoBox(entity) || breech(entity);
    }

    @Override
    public Session open(
            SchematicDocument doc,
            BlockPos pos,
            Level context,
            BlockEntity entity,
            CompoundTag data) {
        return new Inventory(doc, pos, context, entity, data);
    }

    private static final class Inventory extends DetachedInventory {
        private final boolean box;
        private ItemStack magazine;
        private Container contents;

        Inventory(
                SchematicDocument doc,
                BlockPos pos,
                Level context,
                BlockEntity entity,
                CompoundTag data) {
            super(doc, pos, context, entity, data, false);
            box = ammoBox(entity);
            if (!box) {
                magazine =
                        ItemStack.parseOptional(
                                context.registryAccess(), data.getCompound("Magazine"));
                if (magazine(magazine))
                    try {
                        contents =
                                (Container)
                                        Class.forName(
                                                        CBC
                                                                + "munitions.autocannon.ammo_container.AutocannonAmmoContainerItemContainer")
                                                .getConstructor(ItemStack.class)
                                                .newInstance(magazine);
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalArgumentException("Unsupported CBC magazine", error);
                    }
            }
        }

        @Override
        public String kind() {
            return box ? "ammo" : "breech";
        }

        @Override
        public int size() {
            return box ? 2 : contents == null ? 1 : 3;
        }

        @Override
        public ItemStack get(int slot) {
            return box
                    ? ItemStack.parseOptional(
                            context.registryAccess(),
                            data.getCompound(slot == 0 ? "Ammo" : "Tracers"))
                    : slot == 0 ? magazine.copy() : contents.getItem(slot - 1).copy();
        }

        @Override
        public String label(int slot) {
            return box
                    ? (slot == 0 ? "ammo" : "tracers")
                    : slot == 0 ? "magazine" : slot == 1 ? "ammo" : "tracers";
        }

        @Override
        public ItemStack prepared(int slot, ItemStack input) {
            var stack = input.copy();
            if ((box && slot == 1 || !box && slot == 2) && ammunition(stack))
                invoke(stack.getItem(), "setTracer", stack, true);
            return stack;
        }

        @Override
        public boolean accepts(int slot, ItemStack input) {
            if (slot < 0 || slot >= size()) return false;
            if (input.isEmpty()) return true;
            if (!box && slot == 0) return magazine(input);
            if (!ammunition(input)) return false;
            var other = get(box ? 1 - slot : 3 - slot);
            return other.isEmpty()
                    || invoke(other.getItem(), "getType")
                            .equals(invoke(input.getItem(), "getType"));
        }

        @Override
        public int limit(int slot, ItemStack input) {
            if (input.isEmpty()) return 0;
            if (!box && slot == 0) return 1;
            boolean creative =
                    box
                            ? doc.blockName(pos).contains("creative_")
                            : (boolean) invoke(magazine.getItem(), "isCreative");
            if (creative) return 1;
            int capacity = (int) invoke(invoke(input.getItem(), "getType"), "getCapacity");
            return Math.max(
                    0,
                    Math.min(
                            input.getMaxStackSize(),
                            capacity - get(box ? 1 - slot : 3 - slot).getCount()));
        }

        @Override
        public SchematicDocument put(int slot, ItemStack input) {
            var stack = prepared(slot, input);
            if (!accepts(slot, stack))
                throw new IllegalArgumentException("This resource is not accepted by this slot.");
            if (!stack.isEmpty() && (stack.getCount() < 1 || stack.getCount() > limit(slot, stack)))
                throw new IllegalArgumentException("Slot capacity: " + limit(slot, stack));
            if (box)
                data.put(
                        slot == 0 ? "Ammo" : "Tracers",
                        stack.saveOptional(context.registryAccess()));
            else {
                if (slot == 0) magazine = stack;
                else contents.setItem(slot - 1, stack);
                data.put("Magazine", magazine.saveOptional(context.registryAccess()));
            }
            data.remove("LootTable");
            data.remove("LootTableSeed");
            if (!box) return update(doc, pos, data);
            var result =
                    com.eynny.npships.authoring.document.SchematicExtensions.intermediate(
                            () -> appearance(update(doc, pos, data)));
            return com.eynny.npships.authoring.document.SchematicExtensions.apply(
                    doc,
                    result,
                    com.eynny.npships.authoring.document.SchematicExtensions.identity(
                            com.eynny.npships.api.patches.PatchAuthoring.Kind.EDIT));
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private SchematicDocument appearance(SchematicDocument result) {
            try {
                var type =
                        Class.forName(
                                CBC
                                        + "munitions.autocannon.ammo_container.AutocannonAmmoContainerBlock");
                var property =
                        (net.minecraft.world.level.block.state.properties.Property)
                                type.getField("CONTAINER_STATE").get(null);
                var value =
                        (Comparable)
                                invoke(
                                        Class.forName(type.getName() + "$State"),
                                        "getFromFilled",
                                        !get(0).isEmpty() || !get(1).isEmpty());
                return SchematicBlocks.put(
                        result, pos, pos, be.getBlockState().setValue(property, value), null);
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("Unsupported CBC ammo box state", error);
            }
        }

        @Override
        public SchematicDocument spacing(int value) {
            if (value < 1 || value > 6) throw new IllegalArgumentException("Tracer spacing: 1..6");
            if (!box)
                try {
                    @SuppressWarnings("unchecked")
                    var type =
                            (net.minecraft.core.component.DataComponentType<Integer>)
                                    Class.forName(CBC + "index.CBCDataComponents")
                                            .getField("TRACER_SPACING")
                                            .get(null);
                    magazine.set(type, value);
                    data.put("Magazine", magazine.save(context.registryAccess()));
                } catch (ReflectiveOperationException error) {
                    throw new IllegalArgumentException(error);
                }
            else data.putInt("TracerSpacing", value);
            return update(doc, pos, data);
        }

        @Override
        public int spacing() {
            return !box && contents != null
                    ? (int) invoke(magazine.getItem().getClass(), "getTracerSpacing", magazine)
                    : Math.max(1, data.getInt("TracerSpacing"));
        }
    }
}
