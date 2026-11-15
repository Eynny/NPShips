package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Internal native serializer capability. The host still owns accepted documents/history. */
public interface PatchInventoryEditor {
    boolean applies(SchematicDocument document, BlockPos position, BlockEntity detached);

    Session open(
            SchematicDocument document,
            BlockPos position,
            Level context,
            BlockEntity detached,
            CompoundTag data);

    interface Session {
        default boolean available() {
            return true;
        }

        default String kind() {
            return "normal";
        }

        int columns();

        int slotX(int slot);

        int slotY(int slot);

        int gridHeight();

        int size();

        ItemStack get(int slot);

        String label(int slot);

        ItemStack prepared(int slot, ItemStack input);

        boolean accepts(int slot, ItemStack input);

        int limit(int slot, ItemStack input);

        SchematicDocument put(int slot, ItemStack input);

        default int spacing() {
            throw new IllegalArgumentException("This inventory has no spacing setting");
        }

        default SchematicDocument spacing(int spacing) {
            throw new IllegalArgumentException("This inventory has no spacing setting");
        }
    }
}
