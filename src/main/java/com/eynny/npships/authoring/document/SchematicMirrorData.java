package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.StructureSegments;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** Known native mirror schemas only. Opaque extension values are never searched for directions. */
final class SchematicMirrorData {
    private SchematicMirrorData() {}

    // These native BEs store their orientation in BlockState, not in directional author NBT.
    private static final Set<String> STATE_ORIENTED =
            Set.of(
                    "chest",
                    "trapped_chest",
                    "barrel",
                    "shulker_box",
                    "furnace",
                    "blast_furnace",
                    "smoker",
                    "hopper",
                    "dispenser",
                    "dropper",
                    "sign",
                    "hanging_sign",
                    "banner",
                    "bed",
                    "lectern",
                    "jukebox",
                    "skull",
                    "ender_chest",
                    "bell",
                    "decorated_pot");

    private static final Set<String> COORDINATES = Set.of("id", "x", "y", "z");

    static Direction direction(Direction original, int axes) {
        return (axes & (1 << original.getAxis().ordinal())) == 0
                ? original
                : original.getOpposite();
    }

    static BlockPos vector(BlockPos p, int axes) {
        return new BlockPos(
                (axes & 1) == 0 ? p.getX() : -p.getX(),
                (axes & 2) == 0 ? p.getY() : -p.getY(),
                (axes & 4) == 0 ? p.getZ() : -p.getZ());
    }

    static void requireKnownState(CompoundTag encoded) {
        var id = ResourceLocation.tryParse(encoded.getString("Name"));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
            throw unsupported("unavailable block " + encoded.getString("Name"));
    }

    static void blockEntity(BlockState state, CompoundTag data, int axes) {
        blockEntity(state, data, axes, false);
    }

    /** Only the final opaque-schema refusal may wait for an explicit provider transaction. */
    static boolean blockEntity(BlockState state, CompoundTag data, int axes, boolean deferUnknown) {
        if (data.isEmpty()) return true;
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            if (code.mirror(state, data, axes)) return true;
        var id = ResourceLocation.tryParse(data.getString("id"));
        var block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        boolean coordinateOnly = data.getAllKeys().stream().allMatch(COORDINATES::contains);
        if (coordinateOnly) return true;
        if (block.getNamespace().equals("minecraft")) {
            for (String name : STATE_ORIENTED) {
                var nativeId = ResourceLocation.withDefaultNamespace(name);
                // Editor-authored BE data can legitimately omit id. Validate the actual native
                // type/state contract; never require a redundant serialized id just to mirror.
                if ((!data.contains("id") || nativeId.equals(id))
                        && BuiltInRegistries.BLOCK_ENTITY_TYPE
                                .getOptional(nativeId)
                                .map(t -> t.isValid(state))
                                .orElse(false)) return true;
            }
        }
        // The transaction must prove ownership of every deferred field before it can escape.
        // CBC and native schema failures above are never deferred by this capability.
        if (deferUnknown) return false;
        throw unsupported("block entity " + (id == null ? block : id));
    }

    static void entity(
            CompoundTag entry, CompoundTag original, BlockPos size, int axes, Level level) {
        var data = entry.getCompound("nbt");
        var key = ResourceLocation.tryParse(data.getString("id"));
        var type = key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
        if (!data.getList("Passengers", Tag.TAG_COMPOUND).isEmpty())
            throw unsupported("mounted entity groups");
        if (SchematicGlue.supported(data.getString("id"))) return;
        if (type == null
                || !key.getNamespace().equals("minecraft")
                || type == EntityType.SHULKER
                || type == EntityType.BLOCK_DISPLAY
                || type == EntityType.ITEM_DISPLAY
                || type == EntityType.TEXT_DISPLAY
                || type == EntityType.LEASH_KNOT
                || type == EntityType.END_CRYSTAL) throw unsupported("entity " + key);
        boolean frame = type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME;
        boolean painting = type == EntityType.PAINTING;
        if (!frame && !painting) return;
        String field = frame ? "Facing" : "facing";
        int value = data.getByte(field), maximum = frame ? 5 : 3;
        if (!(data.get(field) instanceof ByteTag) || value < 0 || value > maximum)
            throw unsupported("invalid native attachment " + key);
        var oldDirection =
                frame ? Direction.from3DDataValue(value) : Direction.from2DDataValue(value);
        var next = direction(oldDirection, axes);
        var anchor = original.getList("blockPos", Tag.TAG_INT);
        if (anchor.size() != 3) throw unsupported("missing attachment cell " + key);
        var old = new BlockPos(anchor.getInt(0), anchor.getInt(1), anchor.getInt(2));
        var at = SchematicSymmetry.point(old, size, axes);
        double widthOffset = 0, heightOffset = 0;
        if (painting) {
            if (level != null) {
                var nativePainting = (Painting) type.create(level);
                PreviewEntityData.load(nativePainting, original);
                widthOffset = nativePainting.getVariant().value().width() % 2 == 0 ? .5 : 0;
                heightOffset = nativePainting.getVariant().value().height() % 2 == 0 ? .5 : 0;
            } else {
                // A data-only caller can recover parity from a canonical saved native center.
                // Malformed/hand-authored centers need the registry-aware overload, not a guess.
                var p = original.getList("pos", Tag.TAG_DOUBLE);
                var offset =
                        new Vec3(p.getDouble(0), p.getDouble(1), p.getDouble(2))
                                .subtract(Vec3.atCenterOf(old))
                                .relative(oldDirection, .46875);
                var tangent = oldDirection.getCounterClockWise();
                widthOffset = offset.dot(Vec3.atLowerCornerOf(tangent.getNormal()));
                heightOffset = offset.y;
                if (!halfOffset(widthOffset)
                        || !halfOffset(heightOffset)
                        || offset.subtract(
                                                Vec3.atLowerCornerOf(tangent.getNormal())
                                                        .scale(widthOffset))
                                        .subtract(0, heightOffset, 0)
                                        .lengthSqr()
                                > 1e-12)
                    throw unsupported("painting center; use native registry context");
            }
            if (widthOffset != 0 && Integer.bitCount(axes & 5) % 2 != 0)
                at = at.relative(next.getCounterClockWise(), -1);
            if (heightOffset != 0 && (axes & 2) != 0) at = at.below();
        }
        if (at.getX() < 0
                || at.getY() < 0
                || at.getZ() < 0
                || at.getX() >= size.getX()
                || at.getY() >= size.getY()
                || at.getZ() >= size.getZ())
            throw unsupported("attachment cell outside mirrored document");
        data.putByte(field, (byte) (frame ? next.get3DDataValue() : next.get2DDataValue()));
        entry.put("blockPos", StructureSegments.ints(at));
        var center = Vec3.atCenterOf(at).relative(next, -.46875);
        if (painting)
            center =
                    center.relative(next.getCounterClockWise(), widthOffset)
                            .add(0, heightOffset, 0);
        var position = new ListTag();
        position.add(DoubleTag.valueOf(center.x));
        position.add(DoubleTag.valueOf(center.y));
        position.add(DoubleTag.valueOf(center.z));
        entry.put("pos", position);
        // Tile coordinates in a captured entry may still be absolute world coordinates. As in
        // preview loading, the structure's attachment cell is authoritative.
        data.putInt("TileX", at.getX());
        data.putInt("TileY", at.getY());
        data.putInt("TileZ", at.getZ());
    }

    private static boolean halfOffset(double value) {
        return Math.abs(value) < 1e-9 || Math.abs(value - .5) < 1e-9;
    }

    private static IllegalArgumentException unsupported(String detail) {
        return com.eynny.npships.authoring.validation.ValidationProblem.invalid(
                "mirror_payload",
                "Cannot mirror this saved data safely: "
                        + detail
                        + ". Move it without symmetry or mirror supported content separately.",
                detail);
    }
}
