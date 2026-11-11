package com.eynny.npships.authoring.document;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read enum metadata from matching native accessors; never invoke setters or tick the entity. */
public final class NativeDataFields {
    private NativeDataFields() {}

    /** Reject known invalid values before any native reader can truncate them or index an enum. */
    public static void validate(
            CompoundTag data, Map<String, DataSuggestions.NativeField> fields, String root) {
        for (var entry : fields.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith(root + ".")) continue;
            net.minecraft.nbt.Tag value = data;
            for (String key : path.substring(root.length() + 1).split("\\.")) {
                if (value instanceof CompoundTag compound) value = compound.get(key);
                else
                    throw new IllegalArgumentException("Expected a compound while reading " + path);
                if (value == null) break;
            }
            if (value == null) continue;
            var domain = entry.getValue();
            double number = value instanceof NumericTag n ? n.getAsDouble() : Double.NaN;
            if (!Double.isFinite(number)
                    || number != Math.rint(number)
                    || number < domain.min()
                    || number > domain.max())
                throw com.eynny.npships.authoring.validation.ValidationProblem.invalid(
                        "native_integer_range",
                        path
                                + " must be a whole number from "
                                + domain.min()
                                + " to "
                                + domain.max()
                                + ".",
                        path,
                        domain.min(),
                        domain.max());
            if (!domain.options().isEmpty() && !domain.options().containsKey((int) number))
                throw com.eynny.npships.authoring.validation.ValidationProblem.invalid(
                        "native_integer_options",
                        path
                                + " accepts these values: "
                                + new java.util.TreeSet<>(domain.options().keySet())
                                + ".",
                        path,
                        new java.util.TreeSet<>(domain.options().keySet()));
        }
    }

    public static Map<String, DataSuggestions.NativeField> enums(
            Object subject, CompoundTag nativeData) {
        var result = new LinkedHashMap<String, DataSuggestions.NativeField>();
        for (var getter : subject.getClass().getMethods()) {
            if (Modifier.isStatic(getter.getModifiers())
                    || getter.getParameterCount() != 0
                    || !getter.getName().startsWith("get")
                    || !getter.getReturnType().isEnum()) continue;
            String name = getter.getName().substring(3);
            var keys =
                    nativeData.getAllKeys().stream().filter(k -> k.equalsIgnoreCase(name)).toList();
            if (keys.size() != 1 || !(nativeData.get(keys.getFirst()) instanceof NumericTag stored))
                continue;
            try {
                var id = getter.getReturnType().getMethod("getId");
                if (id.getReturnType() != int.class || id.getParameterCount() != 0) continue;
                var current = getter.invoke(subject);
                if ((int) id.invoke(current) != stored.getAsDouble()) continue;
                var options = new LinkedHashMap<Integer, String>();
                for (Object constant : getter.getReturnType().getEnumConstants()) {
                    int value = (int) id.invoke(constant);
                    BlockSettings.scalar(stored, Integer.toString(value));
                    String label =
                            constant instanceof net.minecraft.util.StringRepresentable named
                                    ? named.getSerializedName()
                                    : ((Enum<?>) constant).name();
                    if (constant instanceof net.minecraft.world.item.DyeColor color)
                        label =
                                Component.translatable("color.minecraft." + color.getName())
                                        .getString();
                    if (options.put(value, label) != null) {
                        options.clear();
                        break;
                    }
                }
                if (options.isEmpty()) continue;
                result.put(
                        keys.getFirst(),
                        new DataSuggestions.NativeField(
                                getter.getReturnType().getSimpleName(),
                                options.keySet().stream()
                                        .mapToInt(Integer::intValue)
                                        .min()
                                        .orElseThrow(),
                                options.keySet().stream()
                                        .mapToInt(Integer::intValue)
                                        .max()
                                        .orElseThrow(),
                                Map.copyOf(options)));
            } catch (ReflectiveOperationException | RuntimeException unsupportedAccessor) {
            }
        }
        return Map.copyOf(result);
    }
}
