package com.eynny.npships.integration.contraptions;

import com.eynny.npships.authoring.io.SubLevelImport;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

import java.util.*;

/** Exact edited membership, not a flood-fill that could consume neighbouring hull blocks. */
public final class PhysicalGroups {
    private PhysicalGroups() {}

    /** Saved membership constrains a marker's assembly; it does not trigger assembly itself. */
    public static Map<BlockPos, Set<BlockPos>> membership(
            com.eynny.npships.authoring.document.SchematicDocument document, BlockPos origin) {
        var groups = new LinkedHashMap<String, Set<BlockPos>>();
        for (var position : document.blocks().keySet()) {
            String id = document.blockMetadataString(position, SubLevelImport.GROUP);
            if (!id.isEmpty() && !document.blockName(position).equals("minecraft:air"))
                groups.computeIfAbsent(id, key -> new LinkedHashSet<>())
                        .add(origin.offset(position));
        }
        var result = new HashMap<BlockPos, Set<BlockPos>>();
        for (var group : groups.values()) {
            var immutable = Collections.unmodifiableSet(group);
            for (var position : group) result.put(position, immutable);
        }
        return Collections.unmodifiableMap(result);
    }

    public static void validate(CompoundTag source) {
        var ids = new HashSet<String>();
        for (var value : source.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            if (!block.contains(SubLevelImport.GROUP)) continue;
            if (!block.contains(SubLevelImport.GROUP, Tag.TAG_STRING))
                throw new IllegalArgumentException("Invalid physical group");
            String id = block.getString(SubLevelImport.GROUP);
            if (id.isEmpty() || id.length() > 128)
                throw new IllegalArgumentException("Invalid physical group identity");
            ids.add(id);
            if (ids.size() > 256) throw new IllegalArgumentException("Too many physical groups");
        }
    }
}
