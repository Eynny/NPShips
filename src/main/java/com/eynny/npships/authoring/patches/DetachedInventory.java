package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.*;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.List;

/** Generic detached Minecraft/NeoForge inventory mechanics, shared by native serializers. */
public class DetachedInventory implements PatchInventoryEditor.Session {
    protected final SchematicDocument doc;
    protected final BlockPos pos;
    protected final Level context;
    protected final BlockEntity be;
    protected final Container container;
    protected final IItemHandlerModifiable handler;
    protected final CompoundTag data;

    public DetachedInventory(
            SchematicDocument doc, BlockPos pos, Level context, BlockEntity be, CompoundTag data) {
        this(doc, pos, context, be, data, true);
    }

    protected DetachedInventory(
            SchematicDocument doc,
            BlockPos pos,
            Level context,
            BlockEntity be,
            CompoundTag data,
            boolean discoverHandler) {
        this.doc = doc;
        this.pos = pos;
        this.context = context;
        this.be = be;
        this.data = data;
        container = be instanceof Container c ? c : null;
        IItemHandlerModifiable found = null;
        if (discoverHandler && be != null && container == null) {
            for (var signature : List.of(new Class<?>[0], new Class<?>[] {Direction.class}))
                try {
                    var value =
                            be.getClass()
                                    .getMethod("getItemHandler", signature)
                                    .invoke(
                                            be,
                                            signature.length == 0
                                                    ? new Object[0]
                                                    : new Object[] {null});
                    if (value instanceof IItemHandlerModifiable m) {
                        found = m;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                } catch (ReflectiveOperationException error) {
                    throw new IllegalArgumentException("Cannot open inventory", error);
                }
            if (found == null)
                for (var getter : List.of("getInputInventory", "getInventoryOfBlock"))
                    try {
                        var value = be.getClass().getMethod(getter).invoke(be);
                        if (value instanceof IItemHandlerModifiable m) {
                            found = m;
                            break;
                        }
                    } catch (NoSuchMethodException ignored) {
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalArgumentException(error);
                    }
        }
        handler = found;
    }

    @Override
    public int columns() {
        if (be instanceof DispenserBlockEntity) return 3;
        if (be instanceof HopperBlockEntity) return 5;
        if (be instanceof ChestBlockEntity
                || be instanceof BarrelBlockEntity
                || be instanceof ShulkerBoxBlockEntity) return 9;
        return Math.max(1, Math.min(9, size()));
    }

    @Override
    public int slotX(int slot) {
        return slot % columns() * 18;
    }

    @Override
    public int slotY(int slot) {
        return slot / columns() * 18;
    }

    @Override
    public int gridHeight() {
        return ((size() + columns() - 1) / columns()) * 18;
    }

    @Override
    public int size() {
        return handler != null
                ? handler.getSlots()
                : container != null ? container.getContainerSize() : 0;
    }

    @Override
    public ItemStack get(int slot) {
        return handler != null
                ? handler.getStackInSlot(slot).copy()
                : container.getItem(slot).copy();
    }

    @Override
    public String label(int slot) {
        return "slot";
    }

    @Override
    public ItemStack prepared(int slot, ItemStack input) {
        return input.copy();
    }

    @Override
    public boolean accepts(int slot, ItemStack input) {
        if (slot < 0 || slot >= size()) return false;
        if (input.isEmpty()) return true;
        if (handler != null) return handler.isItemValid(slot, input);
        try {
            be.setLevel(context);
            return container.canPlaceItem(slot, input);
        } finally {
            be.setLevel(null);
        }
    }

    @Override
    public int limit(int slot, ItemStack input) {
        return input.isEmpty()
                ? 0
                : Math.min(
                        input.getMaxStackSize(),
                        handler != null
                                ? handler.getSlotLimit(slot)
                                : container.getMaxStackSize(input));
    }

    protected void store(int slot, ItemStack stack) {
        if (handler != null) handler.setStackInSlot(slot, stack);
        else container.setItem(slot, stack);
    }

    @Override
    public SchematicDocument put(int slot, ItemStack input) {
        var stack = prepared(slot, input);
        if (!accepts(slot, stack))
            throw new IllegalArgumentException("This resource is not accepted by this slot.");
        if (!stack.isEmpty() && (stack.getCount() < 1 || stack.getCount() > limit(slot, stack)))
            throw new IllegalArgumentException("Slot capacity: " + limit(slot, stack));
        var before = be.saveWithFullMetadata(context.registryAccess());
        store(slot, stack);
        SchematicResources.mergeSerialized(
                data, before, be.saveWithFullMetadata(context.registryAccess()));
        data.remove("LootTable");
        data.remove("LootTableSeed");
        return SchematicResources.update(doc, pos, data, context);
    }
}
