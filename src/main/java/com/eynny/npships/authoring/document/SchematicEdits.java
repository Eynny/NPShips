package com.eynny.npships.authoring.document;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.SchematicSafety;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.patches.EntityIdentity;
import com.eynny.npships.authoring.patches.EntityPatches;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

/** Detached edits shared by the editor tools. Never accesses a live level. */
public final class SchematicEdits {
    private SchematicEdits() {}

    /**
     * Keep the origin and all contents; shrinking through content is rejected, never silently
     * cropped.
     */
    public static SchematicDocument resize(SchematicDocument source, BlockPos size) {
        var tag = source.nbtCopy();
        tag.put("size", StructureSegments.ints(size));
        var result = new SchematicDocument(tag, source.configCopy());
        for (var value : tag.getList("entities", Tag.TAG_COMPOUND)) {
            var p = ((CompoundTag) value).getList("pos", Tag.TAG_DOUBLE);
            if (p.size() != 3
                    || !inside(size, new Vec3(p.getDouble(0), p.getDouble(1), p.getDouble(2))))
                throw new IllegalArgumentException(
                        "An entity lies outside the new bounds. Move or remove it first.");
        }
        StructureSegments.validate(tag, StructureSegments.read(result.configCopy()));
        StructureCrew.validateBounds(result, result.configCopy());
        com.eynny.npships.authoring.AssemblyPoints.validate(result);
        return SchematicExtensions.present(source)
                ? SchematicExtensions.apply(
                        source, result, SchematicExtensions.identity(PatchAuthoring.Kind.RESIZE))
                : result;
    }

    public static boolean inside(BlockPos size, Vec3 p) {
        return Double.isFinite(p.x)
                && Double.isFinite(p.y)
                && Double.isFinite(p.z)
                && p.x >= 0
                && p.y >= 0
                && p.z >= 0
                && p.x < size.getX()
                && p.y < size.getY()
                && p.z < size.getZ();
    }

    public static SchematicDocument entity(
            SchematicDocument source, int index, Vec3 pos, CompoundTag data) {
        var list = source.entitiesCopy();
        if (index < -1 || index >= list.size())
            throw invalid("entity_stale", "Entity selection is stale.", index);
        if (data == null) {
            if (index >= 0) list.remove(index);
        } else {
            if (!inside(source.size(), pos))
                throw invalid(
                        "entity_position",
                        "Entity is outside the schematic.",
                        pos,
                        source.size().toShortString());
            if (index < 0 && list.size() >= 1024)
                throw invalid("entity_limit", "Editor limit: 1024 entities.", 1024);
            var clean =
                    index < 0
                            ? data.copy()
                            : EntitySettings.synchronizeSpawnOverrides(
                                    list.getCompound(index).getCompound("nbt"), data);
            var id = net.minecraft.resources.ResourceLocation.tryParse(clean.getString("id"));
            if (id == null
                    || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                    || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .get(id)
                            .canSerialize())
                throw invalid(
                        "entity_type", "Unknown or unsaveable entity type.", data.getString("id"));
            if (index >= 0)
                EntityPatches.preserve(list.getCompound(index).getCompound("nbt"), clean);
            else if (EntityIdentity.read(clean) != null)
                EntityIdentity.put(clean, java.util.UUID.randomUUID());
            clean.remove("UUID");
            clean.remove("Pos");
            if (clean.getCompound("NeoForgeData").contains("npships_initial_spawn")) {
                var overrides = clean.copy();
                overrides.getCompound("NeoForgeData").remove("npships_initial_spawn");
                clean.getCompound("NeoForgeData").put("npships_initial_spawn", overrides);
            }
            var entry = index < 0 ? new CompoundTag() : list.getCompound(index).copy();
            var coords = new ListTag();
            coords.add(DoubleTag.valueOf(pos.x));
            coords.add(DoubleTag.valueOf(pos.y));
            coords.add(DoubleTag.valueOf(pos.z));
            entry.put("pos", coords);
            var anchor = BlockPos.containing(pos);
            if (index >= 0) {
                var previous = list.getCompound(index);
                var before = previous.getList("pos", Tag.TAG_DOUBLE);
                double dx = pos.x - before.getDouble(0),
                        dy = pos.y - before.getDouble(1),
                        dz = pos.z - before.getDouble(2);
                // Native hanging centers can differ from their attachment cells. Preserve
                // that offset for unchanged data and integral drag/move operations.
                if (clean.getString("id").equals(previous.getCompound("nbt").getString("id"))
                        && dx == Math.rint(dx)
                        && dy == Math.rint(dy)
                        && dz == Math.rint(dz))
                    anchor =
                            PreviewEntityData.translatedAnchor(
                                    previous, pos, new BlockPos((int) dx, (int) dy, (int) dz));
            }
            entry.put("blockPos", StructureSegments.ints(anchor));
            entry.put("nbt", clean);
            if (index < 0) list.add(entry);
            else list.set(index, entry);
        }
        var fragment = new CompoundTag();
        fragment.put("entities", list);
        SchematicSafety.validate(fragment);
        var result = EntityPatches.edited(source, list, data == null ? -1 : index);
        if (data != null && index == -1) {
            var old = EntityIdentity.read(data);
            if (old != null) {
                var remap = new com.eynny.npships.authoring.patches.PatchInstances.Remap();
                for (var patch : EntityPatches.entries(source))
                    if (EntityPatches.token(patch).equals(old))
                        result =
                                EntityPatches.put(
                                        result,
                                        list.size() - 1,
                                        remap.copy(patch, true, EntityPatches.schema(source)));
            }
        }
        EntityPatches.validate(result, false);
        return SchematicExtensions.present(source)
                ? SchematicExtensions.apply(
                        source, result, SchematicExtensions.identity(PatchAuthoring.Kind.EDIT))
                : result;
    }

    public static SchematicDocument blockData(
            SchematicDocument source, BlockPos pos, CompoundTag data) {
        return blockData(source, pos, data, null);
    }

    public static SchematicDocument blockData(
            SchematicDocument source,
            BlockPos pos,
            CompoundTag data,
            net.minecraft.world.level.Level context) {
        if (!source.blocks().containsKey(pos))
            throw new IllegalArgumentException("Select an existing block.");
        if (context != null) BlockModes.validateData(source, pos, data, context.registryAccess());
        var block = source.blockEntryCopy(pos);
        var clean = data.copy();
        clean.putInt("x", pos.getX());
        clean.putInt("y", pos.getY());
        clean.putInt("z", pos.getZ());
        block.put("nbt", clean);
        var fragment = new CompoundTag();
        fragment.put("palette", source.paletteCopy());
        var list = new ListTag();
        list.add(block);
        fragment.put("blocks", list);
        SchematicSafety.validate(fragment);
        var result =
                source.withBlocks(
                        java.util.Map.of(pos, block), source.paletteCopy(), source.configCopy());
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            result = code.blockDataEdited(source, result, pos);
        if (context != null)
            result =
                    com.eynny.npships.authoring.patches.SchematicResources.acceptResourceView(
                            source, result, pos, context);
        return SchematicExtensions.present(source)
                ? SchematicExtensions.apply(
                        source, result, SchematicExtensions.identity(PatchAuthoring.Kind.EDIT))
                : result;
    }
}
