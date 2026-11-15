package com.eynny.npships.authoring.patches;

import net.minecraft.nbt.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Authored identity, never a world UUID. Allocation belongs to accepted authoring edits only. */
public final class EntityIdentity {
    public static final String KEY = "npships:entity_identity";

    private EntityIdentity() {}

    public static UUID read(CompoundTag nbt) {
        if (!nbt.contains(KEY)) return null;
        var id = nbt.getCompound(KEY);
        if (id.getInt("version") != 1 || !id.hasUUID("id"))
            throw new IllegalArgumentException("Invalid entity identity/version");
        return id.getUUID("id");
    }

    public static void put(CompoundTag nbt, UUID token) {
        var tag = nbt.getCompound(KEY).copy();
        tag.putInt("version", 1);
        tag.putUUID("id", token);
        nbt.put(KEY, tag);
    }

    public static UUID composed(UUID occurrence, UUID local) {
        return UUID.nameUUIDFromBytes(
                ("npships:entity/v1/" + occurrence + "/" + local).getBytes(StandardCharsets.UTF_8));
    }

    public static CompoundTag placementCopy(CompoundTag source) {
        var copy = source.copy();
        strip(copy);
        return copy;
    }

    private static void strip(CompoundTag nbt) {
        nbt.remove(KEY);
        var persistent = nbt.getCompound("NeoForgeData");
        if (persistent.contains("npships_initial_spawn", Tag.TAG_COMPOUND)) {
            var pending = persistent.getCompound("npships_initial_spawn");
            strip(pending);
            if (pending.getInt("npships:format") == 2
                    && pending.contains("overrides", Tag.TAG_COMPOUND)
                    && pending.contains("equipment", Tag.TAG_COMPOUND))
                strip(pending.getCompound("overrides"));
        }
        for (var child : nbt.getList("Passengers", Tag.TAG_COMPOUND)) strip((CompoundTag) child);
    }
}
