package com.eynny.npships.authoring.document;

import com.eynny.npships.api.patches.PatchConnectionFields;
import com.eynny.npships.api.patches.PatchConnectionFields.Field;
import com.eynny.npships.api.patches.PatchConnectionFields.Group;
import com.eynny.npships.api.patches.PatchConnectionFields.ValueKind;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.authoring.patches.PatchCode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/**
 * Declared connection fields share ordinary native authoring, never an inventory or live network.
 */
public final class ConnectionSettings {
    private ConnectionSettings() {}

    public record Section(ResourceLocation provider, Group group) {}

    public static List<Section> sections(
            SchematicDocument doc, BlockPos pos, HolderLookup.Provider registry) {
        return sections(
                doc, pos, registry, com.eynny.npships.config.NPShipsRuntimeConfig.patchPolicy());
    }

    public static List<Section> sections(
            SchematicDocument doc,
            BlockPos pos,
            HolderLookup.Provider registry,
            com.eynny.npships.api.patches.PatchPolicy policy) {
        if (pos == null || !doc.blocks().containsKey(pos)) return List.of();
        var state =
                NbtUtils.readBlockState(
                        registry.lookupOrThrow(Registries.BLOCK),
                        doc.paletteCopy().getCompound(doc.blocks().get(pos)));
        return sections(state, doc.blockEntityData(pos), registry, policy);
    }

    public static Set<List<String>> itemPaths(
            net.minecraft.world.level.block.state.BlockState state,
            CompoundTag data,
            HolderLookup.Provider registry) {
        var paths = new LinkedHashSet<List<String>>();
        for (var section :
                sections(
                        state,
                        data,
                        registry,
                        com.eynny.npships.config.NPShipsRuntimeConfig.patchPolicy())) {
            var group = section.group();
            for (var field : group.fields())
                if (field.kind() == ValueKind.ITEM) {
                    var path = new ArrayList<>(group.path());
                    if (group.repeated()) path.add("[]");
                    path.add(field.key());
                    paths.add(List.copyOf(path));
                }
        }
        return Set.copyOf(paths);
    }

    private static List<Section> sections(
            net.minecraft.world.level.block.state.BlockState state,
            CompoundTag data,
            HolderLookup.Provider registry,
            com.eynny.npships.api.patches.PatchPolicy policy) {
        var declarations = new IdentityHashMap<PatchConnectionFields, List<Group>>();
        final List<Map.Entry<ResourceLocation, PatchConnectionFields>> providers;
        try {
            providers =
                    PatchCode.applicable(
                            PatchConnectionFields.class,
                            code -> {
                                var groups =
                                        List.copyOf(code.describe(state, data.copy(), registry));
                                if (groups.size() > 32)
                                    throw new IllegalArgumentException(
                                            "Too many connection groups");
                                declarations.put(code, groups);
                                return !groups.isEmpty();
                            },
                            PatchStage.AUTHOR_PREPARATION,
                            policy);
        } catch (RuntimeException | LinkageError failure) {
            throw new IllegalArgumentException(
                    "Connection controls unavailable: " + failure.getMessage(), failure);
        }
        var result = new ArrayList<Section>();
        var claims = new ArrayList<List<String>>();
        for (var provider : providers) {
            // Locate by code identity: no second discovery callback or retained native object.
            var groups = declarations.get(provider.getValue());
            var ids = new HashSet<String>();
            for (var group : groups) {
                if (!ids.add(group.id()))
                    throw new IllegalArgumentException("Duplicate connection group");
                var owned = new ArrayList<List<String>>();
                if (group.repeated()) owned.add(group.path());
                else
                    for (var field : group.fields()) {
                        var path = new ArrayList<>(group.path());
                        path.add(field.key());
                        owned.add(List.copyOf(path));
                    }
                for (var path : owned) {
                    if (BlockSettings.IDENTITY.contains(path.getFirst())
                            || path.getFirst().equals("NeoForgeData")
                            || path.getFirst().startsWith("npships:"))
                        throw new IllegalArgumentException(
                                "Connection control cannot edit native identity");
                    for (var earlier : claims)
                        if (prefix(path, earlier) || prefix(earlier, path))
                            throw new IllegalArgumentException(
                                    "Overlapping connection fields: " + provider.getKey());
                    claims.add(path);
                }
                groupData(data, group);
                result.add(new Section(provider.getKey(), group));
            }
        }
        return List.copyOf(result);
    }

    private static boolean prefix(List<String> first, List<String> second) {
        return first.size() <= second.size() && first.equals(second.subList(0, first.size()));
    }

    private static Tag at(CompoundTag root, List<String> path, boolean create) {
        Tag current = root;
        for (int i = 0; i < path.size(); i++) {
            if (!(current instanceof CompoundTag parent))
                throw new IllegalArgumentException("Connection path is not a compound");
            String key = path.get(i);
            var next = parent.get(key);
            if (next == null) {
                if (!create) return null;
                next = new CompoundTag();
                parent.put(key, next);
            }
            current = next;
        }
        return current;
    }

    private static Tag groupData(CompoundTag data, Group group) {
        var value = at(data, group.path(), false);
        if (!group.repeated()) {
            if (value != null && !(value instanceof CompoundTag))
                throw new IllegalArgumentException("Invalid connection group");
        } else if (value != null
                && (!(value instanceof ListTag list)
                        || list.size() > 512
                        || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid connection channel list");
        return value;
    }

    public static List<CompoundTag> rows(CompoundTag data, Group group) {
        var value = groupData(data, group);
        if (!group.repeated())
            return List.of(value == null ? new CompoundTag() : ((CompoundTag) value).copy());
        if (value == null) return List.of();
        return ((ListTag) value).stream().map(tag -> ((CompoundTag) tag).copy()).toList();
    }

    /** A stale form cannot overwrite a subsequent SNBT, selection or provider-policy change. */
    public static SchematicDocument set(
            SchematicDocument current,
            SchematicDocument expected,
            BlockPos pos,
            Section section,
            int row,
            Field field,
            Tag value,
            HolderLookup.Provider registry) {
        return setAll(current, expected, pos, section, row, Map.of(field, value), registry);
    }

    public static SchematicDocument setAll(
            SchematicDocument current,
            SchematicDocument expected,
            BlockPos pos,
            Section section,
            int row,
            Map<Field, Tag> values,
            HolderLookup.Provider registry) {
        require(current, expected, pos, section, registry);
        var before = current.blockEntityData(pos);
        var after = before.copy();
        for (var entry : values.entrySet()) {
            var field = entry.getKey();
            var value = entry.getValue();
            if (!section.group().fields().contains(field))
                throw new IllegalArgumentException("Unknown connection field");
            if (field.kind() == ValueKind.ITEM) {
                if (!(value instanceof CompoundTag item))
                    throw new IllegalArgumentException("Invalid channel item");
                var parsed = ItemSettings.parse(item, registry);
                if (!parsed.isEmpty() && parsed.getCount() != 1)
                    throw new IllegalArgumentException("A channel uses one item");
            } else if (field.kind() == ValueKind.KEY) {
                if (!(value instanceof IntTag number)
                        || number.getAsInt() < 32
                        || number.getAsInt() > 348)
                    throw new IllegalArgumentException("Invalid keyboard key");
            } else if (BlockPos.CODEC.parse(NbtOps.INSTANCE, value).result().isEmpty())
                throw new IllegalArgumentException("Invalid connection target");
            var group = section.group();
            CompoundTag target;
            if (group.repeated()) {
                var list = (ListTag) at(after, group.path(), false);
                if (list == null || row < 0 || row >= list.size())
                    throw new IllegalArgumentException("Connection row changed");
                target = list.getCompound(row);
                if (field.kind() == ValueKind.KEY)
                    for (int i = 0; i < list.size(); i++)
                        if (i != row && Objects.equals(list.getCompound(i).get(field.key()), value))
                            throw new IllegalArgumentException("This key already has a channel");
            } else target = (CompoundTag) at(after, group.path(), true);
            target.put(field.key(), value.copy());
        }
        return BlockSettings.apply(current, pos, before, after, registry);
    }

    public static SchematicDocument add(
            SchematicDocument current,
            SchematicDocument expected,
            BlockPos pos,
            Section section,
            int key,
            HolderLookup.Provider registry) {
        require(current, expected, pos, section, registry);
        var group = section.group();
        if (!group.repeated() || key < 32 || key > 348)
            throw new IllegalArgumentException("Invalid keyboard channel");
        var before = current.blockEntityData(pos);
        var after = before.copy();
        var path = group.path();
        var parent = (CompoundTag) at(after, path.subList(0, path.size() - 1), true);
        var list = parent.getList(path.getLast(), Tag.TAG_COMPOUND);
        if (list.size() >= 512) throw new IllegalArgumentException("Too many connection channels");
        var keyField =
                group.fields().stream()
                        .filter(f -> f.kind() == ValueKind.KEY)
                        .findFirst()
                        .orElseThrow();
        for (var tag : list)
            if (((CompoundTag) tag).getInt(keyField.key()) == key)
                throw new IllegalArgumentException("This key already has a channel");
        var row = new CompoundTag();
        for (var field : group.fields()) {
            if (field.kind() == ValueKind.KEY) row.putInt(field.key(), key);
            else if (field.kind() == ValueKind.ITEM) row.put(field.key(), new CompoundTag());
            else row.put(field.key(), NbtUtils.writeBlockPos(BlockPos.ZERO));
        }
        list.add(row);
        parent.put(path.getLast(), list);
        return BlockSettings.apply(current, pos, before, after, registry);
    }

    public static SchematicDocument remove(
            SchematicDocument current,
            SchematicDocument expected,
            BlockPos pos,
            Section section,
            int row,
            HolderLookup.Provider registry) {
        require(current, expected, pos, section, registry);
        if (!section.group().repeated()) throw new IllegalArgumentException("Not a channel table");
        var before = current.blockEntityData(pos);
        var after = before.copy();
        var list = (ListTag) at(after, section.group().path(), false);
        if (list == null || row < 0 || row >= list.size())
            throw new IllegalArgumentException("Connection row changed");
        list.remove(row);
        return BlockSettings.apply(current, pos, before, after, registry);
    }

    private static void require(
            SchematicDocument current,
            SchematicDocument expected,
            BlockPos pos,
            Section section,
            HolderLookup.Provider registry) {
        if (current != expected || !sections(current, pos, registry).contains(section))
            throw new IllegalArgumentException("Connection settings changed; reopen the controls");
    }
}
