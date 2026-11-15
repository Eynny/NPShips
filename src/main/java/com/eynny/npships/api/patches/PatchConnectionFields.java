package com.eynny.npships.api.patches;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;

/**
 * Optional stateless declaration of native connection controls from a registered patch provider.
 * The host edits only these declared fields in a detached copy, then uses its ordinary native
 * validation/history transaction. No live level, mutable document or runtime connection is exposed.
 * Providers must describe only native data whose semantics they own; arbitrary matching NBT names
 * do not establish support. Full SNBT remains available independently of these convenience
 * controls.
 */
public interface PatchConnectionFields {
    /**
     * Describes controls for one detached selected block. Do not mutate or retain the inputs.
     * Overlapping ownership between providers is refused; declarations are not applied in order.
     *
     * @param state actual selected block state
     * @param data detached saved native block-entity data
     * @param registries current registry lookup, borrowed only for this callback
     * @return at most 32 groups, or an empty list for an unsupported block; native identity,
     *     NeoForgeData and host-owned npships: roots cannot be claimed
     */
    List<Group> describe(BlockState state, CompoundTag data, HolderLookup.Provider registries);

    /** Native value semantics; item channels are not storage inventories. */
    enum ValueKind {
        /** One frequency item, with its components; an empty compound clears the frequency. */
        ITEM,
        /** Native GLFW keyboard key code, used by a repeated keyboard-channel table. */
        KEY,
        /** Native block-position tag storing a displacement from the selected block. */
        RELATIVE_POSITION
    }

    /**
     * One named field in a group's compound or repeated row. Position values use Minecraft's native
     * block-position NBT codec. The host presents them as schematic-local target coordinates.
     *
     * @param key exact native compound key
     * @param title concise player-facing name
     * @param kind native value semantics
     */
    record Field(String key, Component title, ValueKind kind) {
        /**
         * Validates the declaration and copies the title.
         *
         * @param key exact native compound key
         * @param title concise player-facing name
         * @param kind native value semantics
         */
        public Field {
            validKey(key);
            title = Objects.requireNonNull(title).copy();
            Objects.requireNonNull(kind);
        }
    }

    /**
     * A compound's fields, or a list of independent keyboard-channel rows. Compound paths have at
     * most 16 exact keys; no wildcard, recursive search or guessed field recognition is performed.
     * Repeated groups require exactly one KEY field and at most 512 rows. Unknown sibling fields
     * survive updates. Removing a repeated row explicitly removes that one channel and its data.
     *
     * @param id stable provider-local UI identity
     * @param title concise player-facing group name
     * @param path exact compound path; empty means native BE root
     * @param repeated whether the final path identifies a list of row compounds
     * @param fields at most 16 independently editable fields
     */
    record Group(
            String id, Component title, List<String> path, boolean repeated, List<Field> fields) {
        /**
         * Validates limits and snapshots the declaration.
         *
         * @param id stable provider-local UI identity
         * @param title concise player-facing group name
         * @param path exact compound path
         * @param repeated whether the final path identifies a list of row compounds
         * @param fields independently editable fields
         */
        public Group {
            validKey(id);
            title = Objects.requireNonNull(title).copy();
            path = List.copyOf(path);
            fields = List.copyOf(fields);
            if (path.size() > 16 || fields.isEmpty() || fields.size() > 16)
                throw new IllegalArgumentException("Invalid connection field limits");
            path.forEach(PatchConnectionFields::validKey);
            if (fields.stream().map(Field::key).distinct().count() != fields.size())
                throw new IllegalArgumentException("Duplicate connection field");
            long keys = fields.stream().filter(f -> f.kind() == ValueKind.KEY).count();
            if (repeated ? path.isEmpty() || keys != 1 : keys != 0)
                throw new IllegalArgumentException("Invalid connection row key");
        }
    }

    private static void validKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 256)
            throw new IllegalArgumentException("Invalid connection field key");
    }
}
