package com.eynny.npships.ship;

import com.eynny.npships.authoring.patches.PatchCode;
import com.eynny.npships.authoring.patches.PatchVolumeFormat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Restore only registered native volumes consumed while assembling another source island. */
public final class AssemblyGlueSnapshot implements AutoCloseable {
    private record Glue(String type, AABB bounds, CompoundTag saved, PatchVolumeFormat codec) {}

    private final ServerLevel level;
    private final List<Glue> original = new ArrayList<>();
    private final List<Entity> temporary = new ArrayList<>();

    public AssemblyGlueSnapshot(ServerLevel level, AABB bounds) {
        this.level = level;
        var formats = new HashMap<String, PatchVolumeFormat>();
        for (var code : PatchCode.sourceFormats(PatchVolumeFormat.class).values())
            formats.put(code.type(), code);
        for (var entity :
                level.getEntities(
                        (Entity) null,
                        bounds,
                        e -> formats.containsKey(EntityType.getKey(e.getType()).toString()))) {
            String type = EntityType.getKey(entity.getType()).toString();
            var saved = entity.saveWithoutId(new CompoundTag());
            saved.remove("UUID");
            original.add(new Glue(type, entity.getBoundingBox(), saved, formats.get(type)));
        }
    }

    public void restore() {
        for (var glue : original) {
            boolean present =
                    !level.getEntities(
                                    (Entity) null,
                                    glue.bounds(),
                                    e ->
                                            EntityType.getKey(e.getType())
                                                            .toString()
                                                            .equals(glue.type())
                                                    && e.getBoundingBox().equals(glue.bounds()))
                            .isEmpty();
            if (present) continue;
            var entity = glue.codec().restore(level, glue.bounds(), glue.saved());
            if (!level.addFreshEntity(entity))
                throw new IllegalStateException(
                        "Could not restore assembly volume: " + glue.type());
            temporary.add(entity);
            glue.codec().admitted(entity);
        }
    }

    public void transferred(
            Set<net.minecraft.core.BlockPos> blocks, net.minecraft.core.BlockPos offset) {
        for (var glue : original) {
            if (blocks.stream()
                    .noneMatch(
                            p ->
                                    glue.bounds()
                                            .contains(net.minecraft.world.phys.Vec3.atCenterOf(p))))
                continue;
            var moved = glue.bounds().move(net.minecraft.world.phys.Vec3.atLowerCornerOf(offset));
            var copy = glue.codec().restore(level, moved, glue.saved());
            if (!level.addFreshEntity(copy))
                throw new IllegalStateException(
                        "Could not transfer assembly volume: " + glue.type());
            glue.codec().admitted(copy);
            for (var old :
                    level.getEntities(
                            (Entity) null,
                            glue.bounds(),
                            e ->
                                    EntityType.getKey(e.getType()).toString().equals(glue.type())
                                            && e.getBoundingBox().equals(glue.bounds())))
                old.discard();
        }
    }

    @Override
    public void close() {
        for (var entity : temporary) if (!entity.isRemoved()) entity.discard();
        temporary.clear();
    }
}
