package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.document.*;
import com.eynny.npships.authoring.document.BlockModes.NumberOption;
import com.eynny.npships.authoring.document.BlockModes.Option;
import com.eynny.npships.authoring.patches.PatchBlockControls;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.core.*;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Uses Create's native option behaviours without ticking or calling world callbacks. */
public final class CreateBlockControls implements PatchBlockControls {
    @Override
    public boolean applies(SchematicDocument doc, BlockPos pos, HolderLookup.Provider registry) {
        var entity = detached(doc, pos, registry);
        return entity != null
                && entity.getAllBehaviours().stream()
                        .anyMatch(ScrollValueBehaviour.class::isInstance);
    }

    private static SmartBlockEntity detached(
            SchematicDocument doc, BlockPos p, HolderLookup.Provider registry) {
        var index = doc.blocks().get(p);
        if (index == null) return null;
        var state =
                net.minecraft.nbt.NbtUtils.readBlockState(
                        registry.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        doc.paletteCopy().getCompound(index));
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock block))
            return null;
        if (!(block.newBlockEntity(p, state) instanceof SmartBlockEntity smart)) return null;
        smart.markVirtual();
        var data = smart.saveWithoutMetadata(registry);
        data.merge(doc.blockEntityData(p));
        smart.loadWithComponents(data, registry);
        return smart;
    }

    private static List<ScrollValueBehaviour> numbers(
            SchematicDocument doc, BlockPos p, HolderLookup.Provider registry) {
        var smart = detached(doc, p, registry);
        if (smart == null) return List.of();
        return smart.getAllBehaviours().stream()
                .filter(
                        b ->
                                b instanceof ScrollValueBehaviour
                                        && !(b instanceof ScrollOptionBehaviour<?>))
                .map(b -> (ScrollValueBehaviour) b)
                .toList();
    }

    private static int bound(ScrollValueBehaviour behaviour, String name) {
        // Create exposes value but not the native configured bounds; read these without callbacks.
        try {
            var field = ScrollValueBehaviour.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(behaviour);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot inspect Create value bounds", e);
        }
    }

    public List<NumberOption> readNumbers(
            SchematicDocument doc, BlockPos p, HolderLookup.Provider registry) {
        var modes = numbers(doc, p, registry);
        var result = new ArrayList<NumberOption>();
        for (int i = 0; i < modes.size(); i++) {
            var mode = modes.get(i);
            result.add(
                    new NumberOption(
                            i,
                            mode.label,
                            mode.getValue(),
                            bound(mode, "min"),
                            bound(mode, "max")));
        }
        return result;
    }

    /** Map native value controls back to serialized fields without invoking callbacks. */
    @Override
    public Map<String, DataSuggestions.NativeField> nativeFields(
            net.minecraft.world.level.block.entity.BlockEntity entity,
            HolderLookup.Provider registry) {
        if (!(entity instanceof SmartBlockEntity smart)) return Map.of();
        var result = new LinkedHashMap<String, DataSuggestions.NativeField>();
        for (var behavior : smart.getAllBehaviours()) {
            if (!(behavior instanceof ScrollValueBehaviour mode)) continue;
            int original = mode.value, min = bound(mode, "min"), max = bound(mode, "max");
            if (min == max || min > max || original < min || original > max) continue;
            var first = new net.minecraft.nbt.CompoundTag();
            var second = new net.minecraft.nbt.CompoundTag();
            var options = new LinkedHashMap<Integer, String>();
            try {
                mode.value = min;
                mode.write(first, registry, false);
                mode.value = min + 1;
                mode.write(second, registry, false);
                if (mode instanceof ScrollOptionBehaviour<?> option) {
                    for (int value = min; value <= max && value - min < 1024; value++) {
                        mode.value = value;
                        options.put(
                                value,
                                Component.translatable(option.get().getTranslationKey())
                                        .getString());
                    }
                }
            } finally {
                mode.value = original;
            }
            collectFields(
                    first,
                    second,
                    "data",
                    min,
                    min + 1,
                    new DataSuggestions.NativeField(
                            mode.label.getString(), min, max, Map.copyOf(options)),
                    result);
        }
        return Map.copyOf(result);
    }

    private static void collectFields(
            net.minecraft.nbt.CompoundTag first,
            net.minecraft.nbt.CompoundTag second,
            String path,
            int a,
            int b,
            DataSuggestions.NativeField help,
            Map<String, DataSuggestions.NativeField> result) {
        for (String key : first.getAllKeys()) {
            var left = first.get(key);
            var right = second.get(key);
            if (left instanceof net.minecraft.nbt.CompoundTag l
                    && right instanceof net.minecraft.nbt.CompoundTag r)
                collectFields(l, r, path + "." + key, a, b, help, result);
            else if (left instanceof net.minecraft.nbt.IntTag l
                    && right instanceof net.minecraft.nbt.IntTag r
                    && l.getAsInt() == a
                    && r.getAsInt() == b) result.put(path + "." + key, help);
        }
    }

    public SchematicDocument setNumber(
            SchematicDocument doc,
            BlockPos p,
            int index,
            int value,
            HolderLookup.Provider registry) {
        var mode = numbers(doc, p, registry).get(index);
        if (value < bound(mode, "min") || value > bound(mode, "max"))
            throw new IllegalArgumentException(
                    "Value outside " + bound(mode, "min") + " .. " + bound(mode, "max"));
        mode.value = value;
        var data = BlockSettings.nativeData(doc, p, registry);
        mode.write(data, registry, false);
        return SchematicEdits.blockData(doc, p, data);
    }

    private static List<ScrollOptionBehaviour<?>> options(
            SchematicDocument doc, BlockPos p, HolderLookup.Provider registry) {
        var smart = detached(doc, p, registry);
        if (smart == null) return List.of();
        var out = new ArrayList<ScrollOptionBehaviour<?>>();
        for (var b : smart.getAllBehaviours())
            if (b instanceof ScrollOptionBehaviour<?> option) out.add(option);
        return out;
    }

    public List<Option> read(SchematicDocument doc, BlockPos p, HolderLookup.Provider registry) {
        var out = new ArrayList<Option>();
        var modes = options(doc, p, registry);
        for (int i = 0; i < modes.size(); i++) {
            var mode = modes.get(i);
            out.add(
                    new Option(
                            i,
                            mode.label,
                            mode.value < bound(mode, "min") || mode.value > bound(mode, "max")
                                    ? Component.literal(Integer.toString(mode.value))
                                    : Component.translatable(mode.get().getTranslationKey())));
        }
        return out;
    }

    public SchematicDocument cycle(
            SchematicDocument doc, BlockPos p, int index, HolderLookup.Provider registry) {
        var modes = options(doc, p, registry);
        var mode = modes.get(index);
        int min = bound(mode, "min"), max = bound(mode, "max");
        mode.value = mode.value < min || mode.value >= max ? min : mode.value + 1;
        var data = BlockSettings.nativeData(doc, p, registry);
        mode.write(data, registry, false);
        return SchematicEdits.blockData(doc, p, data);
    }
}
