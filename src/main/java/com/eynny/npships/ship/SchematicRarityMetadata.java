package com.eynny.npships.ship;

import com.eynny.npships.authoring.StructureSegments;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable counts from serialized cells, with native isAir semantics and no world access. */
public final class SchematicRarityMetadata {
    public record Range(long minimum, long maximum) {}

    private record SegmentCounts(long replaced, Map<String, Long> variants) {}

    private record PaletteCounts(long root, Map<String, SegmentCounts> segments) {}

    private final List<PaletteCounts> palettes;
    private final Range range;
    private final String error;

    private SchematicRarityMetadata(List<PaletteCounts> palettes, Range range, String error) {
        this.palettes = List.copyOf(palettes);
        this.range = range;
        this.error = error;
    }

    /** Identifiable primitive/reference/string content; excludes collection/object headers. */
    long catalogueCapacity() {
        long bytes = Math.addExact(2L * Long.BYTES, CatalogueResources.text(error));
        bytes = Math.addExact(bytes, CatalogueResources.references(palettes.size()));
        java.util.IdentityHashMap<String, Boolean> names = null;
        for (var palette : palettes) {
            bytes = Math.addExact(bytes, Long.BYTES);
            bytes = Math.addExact(bytes, CatalogueResources.references(1));
            for (var entry : palette.segments.entrySet()) {
                if (names == null) names = new java.util.IdentityHashMap<>();
                if (names.put(entry.getKey(), Boolean.TRUE) == null)
                    bytes = Math.addExact(bytes, CatalogueResources.text(entry.getKey()));
                bytes = Math.addExact(bytes, Long.BYTES + CatalogueResources.references(3));
                for (var variant : entry.getValue().variants.entrySet()) {
                    if (names.put(variant.getKey(), Boolean.TRUE) == null)
                        bytes = Math.addExact(bytes, CatalogueResources.text(variant.getKey()));
                    bytes = Math.addExact(bytes, Long.BYTES + CatalogueResources.references(2));
                }
            }
        }
        return bytes;
    }

    public boolean available() {
        return error.isEmpty();
    }

    public String error() {
        return error;
    }

    public int paletteCount() {
        return palettes.size();
    }

    public Range range() {
        requireAvailable();
        return range;
    }

    public long count(int paletteIndex, StructureSegments.Plan plan) {
        requireAvailable();
        var palette = palettes.get(paletteIndex);
        long count = palette.root;
        for (var choice : plan.choices().entrySet()) {
            var segment = palette.segments.get(choice.getKey());
            if (segment == null || !segment.variants.containsKey(choice.getValue()))
                throw new IllegalArgumentException("Unknown rarity segment/variant: " + choice);
            count =
                    Math.addExact(
                            count - segment.replaced, segment.variants.get(choice.getValue()));
        }
        return count;
    }

    private void requireAvailable() {
        if (!available()) throw new IllegalStateException(error);
    }

    /**
     * Failure is explicit, never zero/AIR. Native manual loading is not made stricter by this
     * count.
     */
    public static SchematicRarityMetadata inspect(
            CompoundTag tag, List<StructureSegments.Segment> segments, HolderGetter<Block> blocks) {
        try {
            return read(tag, segments, blocks);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return new SchematicRarityMetadata(List.of(), null, e.getMessage());
        }
    }

    private static SchematicRarityMetadata read(
            CompoundTag tag, List<StructureSegments.Segment> segments, HolderGetter<Block> blocks) {
        if (!segments.isEmpty() && (tag.contains("palettes") || !tag.contains("palette")))
            throw new IllegalArgumentException("Segments require a single native palette.");
        var base = scan(tag, segments, blocks);
        var variantCounts = new HashMap<String, Long>();
        var variants = tag.getCompound(StructureSegments.VARIANTS);
        var size = tag.getList("size", Tag.TAG_INT);
        for (var segment : segments) {
            for (int axis = 0; axis < 3; axis++) {
                int origin = component(segment.origin(), axis),
                        span = component(segment.size(), axis);
                if (origin < 0 || (long) origin + span > size.getInt(axis))
                    throw new IllegalArgumentException(
                            "Segment outside rarity template: " + segment.id());
            }
            for (var variant : segment.pool()) {
                if (!variants.contains(variant.id(), Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("Missing rarity variant: " + variant.id());
                var part = variants.getCompound(variant.id());
                var partSize = part.getList("size", Tag.TAG_INT);
                if (partSize.size() != 3 || part.contains("palettes") || !part.contains("palette"))
                    throw new IllegalArgumentException(
                            "Invalid rarity variant palette/size: " + variant.id());
                for (int axis = 0; axis < 3; axis++)
                    if (partSize.getInt(axis) != component(segment.size(), axis))
                        throw new IllegalArgumentException(
                                "Rarity variant size mismatch: " + variant.id());
                variantCounts.computeIfAbsent(
                        variant.id(), id -> scan(part, List.of(), blocks).totals[0]);
            }
        }
        var palettes = new ArrayList<PaletteCounts>();
        long minimum = Long.MAX_VALUE, maximum = Long.MIN_VALUE;
        for (int palette = 0; palette < base.totals.length; palette++) {
            long min = base.totals[palette], max = min;
            var counts = new HashMap<String, SegmentCounts>();
            for (int s = 0; s < segments.size(); s++) {
                var segment = segments.get(s);
                var choices = new HashMap<String, Long>();
                for (var variant : segment.pool())
                    choices.put(variant.id(), variantCounts.get(variant.id()));
                long replaced = base.inside[palette][s];
                counts.put(segment.id(), new SegmentCounts(replaced, Map.copyOf(choices)));
                if (segment.enabled() && !choices.isEmpty()) {
                    min =
                            Math.addExact(
                                    min - replaced,
                                    choices.values().stream()
                                            .mapToLong(Long::longValue)
                                            .min()
                                            .orElseThrow());
                    max =
                            Math.addExact(
                                    max - replaced,
                                    choices.values().stream()
                                            .mapToLong(Long::longValue)
                                            .max()
                                            .orElseThrow());
                }
            }
            palettes.add(new PaletteCounts(base.totals[palette], Map.copyOf(counts)));
            minimum = Math.min(minimum, min);
            maximum = Math.max(maximum, max);
        }
        return new SchematicRarityMetadata(palettes, new Range(minimum, maximum), "");
    }

    private record Scan(long[] totals, long[][] inside) {}

    private static int component(net.minecraft.core.Vec3i vector, int axis) {
        return switch (axis) {
            case 0 -> vector.getX();
            case 1 -> vector.getY();
            default -> vector.getZ();
        };
    }

    private static Scan scan(
            CompoundTag tag, List<StructureSegments.Segment> segments, HolderGetter<Block> blocks) {
        var size = tag.getList("size", Tag.TAG_INT);
        if (size.size() != 3 || size.getInt(0) < 1 || size.getInt(1) < 1 || size.getInt(2) < 1)
            throw new IllegalArgumentException("Invalid rarity template size.");
        Math.multiplyExact(
                Math.multiplyExact((long) size.getInt(0), size.getInt(1)), size.getInt(2));
        var source = new ArrayList<ListTag>();
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            var list = tag.getList("palettes", Tag.TAG_LIST);
            for (int i = 0; i < list.size(); i++) source.add(list.getList(i));
        } else source.add(tag.getList("palette", Tag.TAG_COMPOUND));
        if (source.isEmpty()) throw new IllegalArgumentException("Missing rarity palette.");
        var solid = new ArrayList<boolean[]>();
        for (var palette : source) {
            if (palette.isEmpty()) throw new IllegalArgumentException("Missing rarity palette.");
            boolean[] nonAir = new boolean[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                var entry = palette.getCompound(i);
                var id = ResourceLocation.tryParse(entry.getString("Name"));
                if (id == null || blocks.get(ResourceKey.create(Registries.BLOCK, id)).isEmpty())
                    throw new IllegalArgumentException(
                            "Unknown rarity block: " + entry.getString("Name"));
                nonAir[i] = !NbtUtils.readBlockState(blocks, entry).isAir();
            }
            solid.add(nonAir);
        }
        if (!(tag.get("blocks") instanceof ListTag entries)
                || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid rarity block list.");
        long[] totals = new long[solid.size()];
        long[][] inside = new long[solid.size()][segments.size()];
        var positions = new LongOpenHashSet(entries.size());
        for (var value : entries) {
            var entry = (CompoundTag) value;
            var pos = entry.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3 || !entry.contains("state", Tag.TAG_INT))
                throw new IllegalArgumentException("Invalid rarity block coordinates/state.");
            int x = pos.getInt(0), y = pos.getInt(1), z = pos.getInt(2);
            if (x < 0
                    || y < 0
                    || z < 0
                    || x >= size.getInt(0)
                    || y >= size.getInt(1)
                    || z >= size.getInt(2))
                throw new IllegalArgumentException("Rarity block outside template.");
            long index = ((long) x * size.getInt(1) + y) * size.getInt(2) + z;
            if (!positions.add(index))
                throw new IllegalArgumentException("Duplicate rarity block position.");
            int segmentIndex = -1;
            for (int s = 0; s < segments.size(); s++)
                if (segments.get(s).contains(x, y, z)) {
                    segmentIndex = s;
                    break;
                }
            int state = entry.getInt("state");
            for (int p = 0; p < solid.size(); p++) {
                if (state < 0 || state >= solid.get(p).length)
                    throw new IllegalArgumentException("Invalid rarity palette index.");
                if (solid.get(p)[state]) {
                    totals[p]++;
                    if (segmentIndex >= 0) inside[p][segmentIndex]++;
                }
            }
        }
        return new Scan(totals, inside);
    }
}
