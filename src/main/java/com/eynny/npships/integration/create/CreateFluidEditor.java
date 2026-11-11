package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchFluidEditor;
import com.eynny.npships.authoring.patches.SchematicResources;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.*;

/** Create tanks, basin inputs/outputs, spouts and drains use their own tank serializers. */
public final class CreateFluidEditor implements PatchFluidEditor.Session {
    private final SchematicDocument doc;
    private final BlockPos pos;
    private final Level context;
    private final BlockEntity be;
    private final List<Slot> slots = new ArrayList<>();

    private record Slot(
            String label,
            int capacity,
            java.util.function.Supplier<FluidStack> get,
            java.util.function.Predicate<FluidStack> accepts,
            java.util.function.Consumer<FluidStack> set) {}

    public CreateFluidEditor(SchematicDocument doc, BlockPos selected, Level context) {
        this(doc, selected, context, () -> SchematicResources.detached(doc, selected, context));
    }

    public CreateFluidEditor(
            SchematicDocument doc,
            BlockPos selected,
            Level context,
            java.util.function.Supplier<BlockEntity> selectedTarget) {
        this.doc = doc;
        this.context = context;
        var controller = NbtUtils.readBlockPos(doc.blockEntityData(selected), "Controller");
        pos =
                doc.blockName(selected).endsWith("fluid_tank")
                                && controller.isPresent()
                                && doc.blockName(controller.get()).equals(doc.blockName(selected))
                        ? controller.get()
                        : selected;
        // A native BE save also rewrites its item list; keep unavailable foreign items intact.
        if (SchematicResources.unavailableItems(doc.blockEntityData(pos))) {
            be = null;
            return;
        }
        be =
                pos.equals(selected)
                        ? selectedTarget.get()
                        : SchematicResources.detached(doc, pos, context);
        if (be == null) return;
        if (be instanceof FluidTankBlockEntity tank) {
            var inventory = tank.getTankInventory();
            slots.add(
                    new Slot(
                            "fluid",
                            inventory.getCapacity(),
                            inventory::getFluid,
                            s -> inventory.isFluidValid(0, s),
                            s -> {
                                var nbt = new CompoundTag();
                                if (!s.isEmpty())
                                    nbt.put("Fluid", s.save(context.registryAccess()));
                                inventory.readFromNBT(context.registryAccess(), nbt);
                            }));
            return;
        }
        for (var type :
                List.of(
                        SmartFluidTankBehaviour.TYPE,
                        SmartFluidTankBehaviour.INPUT,
                        SmartFluidTankBehaviour.OUTPUT)) {
            var tanks = BlockEntityBehaviour.get(be, type);
            if (tanks == null) continue;
            var capability = tanks.getCapability();
            for (int i = 0; i < tanks.getTanks().length; i++) {
                int index = i;
                var segment = tanks.getTanks()[i];
                slots.add(
                        new Slot(
                                type == SmartFluidTankBehaviour.INPUT
                                        ? "input"
                                        : type == SmartFluidTankBehaviour.OUTPUT
                                                ? "output"
                                                : "fluid",
                                capability.getTankCapacity(i),
                                () -> capability.getFluidInTank(index),
                                s -> capability.isFluidValid(index, s),
                                s -> {
                                    var data = segment.writeNBT(context.registryAccess());
                                    var content = new CompoundTag();
                                    if (!s.isEmpty())
                                        content.put("Fluid", s.save(context.registryAccess()));
                                    data.put("TankContent", content);
                                    segment.readNBT(data, context.registryAccess(), false);
                                }));
            }
        }
    }

    public int size() {
        return slots.size();
    }

    public BlockPos owner() {
        return pos;
    }

    public String label(int slot) {
        return slots.get(slot).label;
    }

    public FluidStack get(int slot) {
        return slots.get(slot).get.get().copy();
    }

    public int capacity(int slot) {
        return slots.get(slot).capacity;
    }

    public boolean accepts(int slot, FluidStack stack) {
        return slots.get(slot).accepts.test(stack);
    }

    public boolean creative() {
        return doc.blockName(pos).equals("create:creative_fluid_tank");
    }

    public SchematicDocument put(int slot, FluidStack stack) {
        if (!stack.isEmpty()
                && (stack.getAmount() < 1
                        || stack.getAmount() > capacity(slot)
                        || !accepts(slot, stack)))
            throw new IllegalArgumentException(
                    "Invalid fluid or amount; capacity: " + capacity(slot) + " mB");
        var before = be.saveWithFullMetadata(context.registryAccess());
        slots.get(slot).set.accept(stack.copy());
        var data = doc.blockEntityData(pos);
        SchematicResources.mergeSerialized(
                data, before, be.saveWithFullMetadata(context.registryAccess()));
        return SchematicResources.update(doc, pos, data, context);
    }
}
