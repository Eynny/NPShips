package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.patches.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.*;

import java.util.*;

/** Host transaction bridge. Native serialized volume semantics belong to their registered codec. */
public final class SchematicGlue {
    private SchematicGlue() {}

    private static final class Code {
        static final Map<
                        String,
                        Map.Entry<net.minecraft.resources.ResourceLocation, PatchVolumeFormat>>
                VALUES = build();

        static Map<String, Map.Entry<net.minecraft.resources.ResourceLocation, PatchVolumeFormat>>
                build() {
            var result =
                    new LinkedHashMap<
                            String,
                            Map.Entry<
                                    net.minecraft.resources.ResourceLocation, PatchVolumeFormat>>();
            PatchCode.sourceFormats(PatchVolumeFormat.class).entrySet().stream()
                    .sorted(Comparator.comparingInt(e -> e.getValue().order()))
                    .forEach(
                            e -> {
                                if (result.putIfAbsent(e.getValue().type(), e) != null)
                                    throw new IllegalArgumentException(
                                            "Competing native volume codecs: "
                                                    + e.getValue().type());
                            });
            return Collections.unmodifiableMap(result);
        }
    }

    public static void captureVolumes(
            net.minecraft.server.level.ServerLevel level, AABB selection, CompoundTag template) {
        for (var format : Code.VALUES.values())
            format.getValue().capture(level, selection, template);
    }

    public static AABB bounds(CompoundTag data, Vec3 position) {
        var value = Code.VALUES.get(data.getString("id"));
        return value == null ? null : value.getValue().bounds(data, position);
    }

    public static List<PatchVolumeFormat> formats() {
        var available = PatchCode.creationFormats(PatchVolumeFormat.class);
        return Code.VALUES.values().stream()
                .filter(entry -> available.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(
                        format ->
                                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                        .containsKey(
                                                net.minecraft.resources.ResourceLocation.parse(
                                                        format.type())))
                .toList();
    }

    public static boolean supported(String id) {
        return Code.VALUES.containsKey(id);
    }

    public static int color(String id) {
        var value = Code.VALUES.get(id);
        return value == null ? 0xffffff : value.getValue().color();
    }

    public static SchematicDocument put(
            SchematicDocument source, int index, String type, AABB bounds) {
        if (index < 0 && formats().stream().noneMatch(format -> format.type().equals(type)))
            throw new IllegalArgumentException("Glue type is not available for creation: " + type);
        var value = Code.VALUES.get(type);
        if (value == null) throw new IllegalArgumentException("Unsupported glue type");
        return PatchCode.authorControl(PatchVolumeFormat.class, value.getKey())
                .put(source, index, bounds);
    }

    public static Vec3 transformVolume(
            CompoundTag data, Vec3 position, java.util.function.Function<Vec3, Vec3> forward) {
        var value = Code.VALUES.get(data.getString("id"));
        if (value == null) throw new IllegalArgumentException("Unsupported native volume");
        return value.getValue().transform(data, position, forward);
    }
}
