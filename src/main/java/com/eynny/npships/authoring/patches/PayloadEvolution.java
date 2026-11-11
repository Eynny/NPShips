package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchPayloads;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.resources.PreparedTemplateResources;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Detached host reads only. No method here writes an author document or invokes native placement.
 */
public final class PayloadEvolution {
    private static final ThreadLocal<Boolean> CALLBACK = ThreadLocal.withInitial(() -> false);

    private PayloadEvolution() {}

    /** Existing document acceptance owners also refuse reentry from a pure version callback. */
    public static boolean inCallback() {
        return CALLBACK.get();
    }

    private static <T> T callback(Supplier<T> action) {
        if (CALLBACK.get())
            throw new IllegalStateException("Reentrant provider payload conversion");
        CALLBACK.set(true);
        try {
            return action.get();
        } finally {
            CALLBACK.remove();
        }
    }

    public static boolean readableTarget(ResourceLocation id, int version) {
        var type = PatchTypes.type(id);
        if (type == null) return false;
        var definitions = PatchTypes.payloads(id);
        var schema = definitions == null ? null : definitions.target();
        return schema == null ? version == type.version() : schema.readable(version);
    }

    public static JsonObject target(ResourceLocation id, int version, JsonObject source) {
        Objects.requireNonNull(source);
        var type = PatchTypes.type(id);
        if (type == null) throw new NullPointerException("Missing target provider " + id);
        var definitions = PatchTypes.payloads(id);
        var schema = definitions == null ? null : definitions.target();
        if (schema == null) {
            if (version != type.version())
                throw new PatchPayloads.UnsupportedVersionException(
                        id.toString(), version, type.version(), Set.of(type.version()));
            return source.deepCopy();
        }
        checkJson(source);
        if (!schema.readable(version)) throw unsupported(id.toString(), version, schema);
        JsonObject canonical;
        if (version == schema.currentVersion()) canonical = source.deepCopy();
        else {
            var reader = schema.older().get(version);
            callback(
                    () -> {
                        reader.validator().accept(source.deepCopy());
                        return null;
                    });
            canonical =
                    Objects.requireNonNull(
                            callback(() -> reader.converter().apply(source.deepCopy())),
                            "Payload converter returned null");
            checkJson(canonical);
        }
        callback(
                () -> {
                    schema.currentValidator().accept(canonical.deepCopy());
                    return null;
                });
        // Neither validation's private mutations nor a converter-owned retained alias escapes.
        return canonical.deepCopy();
    }

    public static CompoundTag nbt(
            ResourceLocation id,
            String name,
            int version,
            CompoundTag source,
            Consumer<CompoundTag> beforeCopy) {
        return nbt(id, name, version, source, beforeCopy, ignored -> {});
    }

    public static CompoundTag nbt(
            ResourceLocation id,
            String name,
            int version,
            CompoundTag source,
            Consumer<CompoundTag> beforeCopy,
            Consumer<CompoundTag> beforeGrowth) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(beforeCopy);
        Objects.requireNonNull(beforeGrowth);
        checkNbt(source);
        var definitions = PatchTypes.payloads(id);
        var schema = definitions == null ? null : definitions.authorData().get(name);
        if (schema == null) return copy(source, beforeCopy);
        if (!schema.readable(version)) throw unsupported(id + "/" + name, version, schema);
        CompoundTag canonical;
        if (version == schema.currentVersion()) canonical = source;
        else {
            var reader = schema.older().get(version);
            callback(
                    () -> {
                        reader.validator().accept(copy(source, beforeCopy));
                        return null;
                    });
            canonical =
                    Objects.requireNonNull(
                            callback(() -> reader.converter().apply(copy(source, beforeCopy))),
                            "Payload converter returned null");
            checkNbt(canonical);
            // The provider may already have allocated it; reserve known host-owned growth before
            // taking a defensive copy. This cannot preempt or sandbox foreign Java allocation.
            beforeGrowth.accept(canonical);
        }
        callback(
                () -> {
                    schema.currentValidator().accept(copy(canonical, beforeCopy));
                    return null;
                });
        return copy(canonical, beforeCopy);
    }

    private static PatchPayloads.UnsupportedVersionException unsupported(
            String subject, int version, PatchPayloads.Schema<?> schema) {
        return new PatchPayloads.UnsupportedVersionException(
                subject, version, schema.currentVersion(), schema.readableVersions());
    }

    private static CompoundTag copy(CompoundTag source, Consumer<CompoundTag> beforeCopy) {
        beforeCopy.accept(source);
        return source.copy();
    }

    private static void checkNbt(CompoundTag value) {
        // Checked native tag graph/depth/capacity, not NbtAccounter bytes or a Java-heap estimate.
        PreparedTemplateResources.measureSupplied(value);
    }

    private static void checkJson(JsonObject value) {
        jsonShape(value, 0, new int[2]);
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("Converted external payload exceeds 64 KiB");
    }

    private static void jsonShape(JsonElement value, int depth, int[] count) {
        if (value == null || depth > 32 || ++count[0] > 8192)
            throw new IllegalArgumentException(
                    "Converted external payload nesting/node limit exceeded");
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                text(entry.getKey(), count);
                jsonShape(entry.getValue(), depth + 1, count);
            }
        } else if (value.isJsonArray()) {
            for (var item : value.getAsJsonArray()) jsonShape(item, depth + 1, count);
        } else if (value.isJsonPrimitive()) text(value.getAsString(), count);
    }

    private static void text(String value, int[] count) {
        // A cheap lower bound stops giant provider strings before serialization/deepCopy. The
        // exact UTF-8 check above then applies the unchanged 64-KiB target envelope contract.
        if (value.length() > 65536 - count[1])
            throw new IllegalArgumentException("Converted external payload exceeds 64 KiB");
        count[1] += value.length();
    }
}
