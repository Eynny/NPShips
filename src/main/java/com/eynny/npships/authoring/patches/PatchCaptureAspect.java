package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchAuthoring.CaptureField;
import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Source-scoped native aspect description. Discovery is read-only, never extraction/restoration.
 */
public interface PatchCaptureAspect {
    interface Fields {
        boolean inEntry(BlockPos pos, String key);

        boolean inData(BlockPos pos, String key);
    }

    record Offer(
            ResourceLocation aspect,
            List<BlockPos> positions,
            List<CaptureField> fields,
            boolean delegable) {
        public Offer {
            positions = List.copyOf(positions);
            fields = List.copyOf(fields);
        }
    }

    List<Offer> inspect(
            ServerLevel level,
            BlockPos origin,
            SchematicDocument source,
            BlockPos position,
            String name,
            CompoundTag state,
            CompoundTag data,
            Fields fields);

    int defaultRank();
}
