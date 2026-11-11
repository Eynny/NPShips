package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.*;

/** Internal native-volume source codec; foreign data uses public document/editor capabilities. */
public interface PatchVolumeFormat extends PatchSourceFormat {
    /**
     * Mandatory source-faithful native volume capture at the authorized server-thread source
     * boundary; mutate only the fresh detached template. This does not enable optional runtime
     * restoration or an editor action. The installed native platform owns its bounded source read.
     */
    default void capture(
            net.minecraft.server.level.ServerLevel level, AABB selection, CompoundTag template) {}

    /** Detached native extent; null means this codec has no spatial extent for the source. */
    default AABB bounds(CompoundTag data, Vec3 position) {
        return null;
    }

    /** Recreate a missing operation-owned native volume; never reinterpret unrelated entities. */
    default net.minecraft.world.entity.Entity restore(
            net.minecraft.server.level.ServerLevel level, AABB bounds, CompoundTag saved) {
        throw new UnsupportedOperationException("Native volume restoration unavailable: " + type());
    }

    /** Complete native tracking only after a restored volume was admitted to the level. */
    default void admitted(net.minecraft.world.entity.Entity entity) {}

    String type();

    String label();

    int color();

    int order();

    SchematicDocument put(SchematicDocument source, int index, AABB bounds);

    Vec3 transform(
            CompoundTag data, Vec3 position, java.util.function.Function<Vec3, Vec3> forward);
}
