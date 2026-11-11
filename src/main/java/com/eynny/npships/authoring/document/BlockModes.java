package com.eynny.npships.authoring.document;

import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.authoring.patches.PatchBlockControls;
import com.eynny.npships.authoring.patches.PatchCode;

import net.minecraft.core.*;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Provider-neutral bridge retaining the existing form DTOs and accepted edit owner. */
public final class BlockModes {
    private BlockModes() {}

    public record Option(int index, Component label, Component value) {}

    public record NumberOption(int index, Component label, int value, int min, int max) {}

    private static List<PatchBlockControls> code(
            SchematicDocument doc, BlockPos p, HolderLookup.Provider registry) {
        return PatchCode.applicable(
                        PatchBlockControls.class,
                        c -> c.applies(doc, p, registry),
                        PatchStage.AUTHOR_PREPARATION)
                .stream()
                .map(Map.Entry::getValue)
                .toList();
    }

    public static List<NumberOption> readNumbers(
            SchematicDocument doc, BlockPos p, HolderLookup.Provider registry) {
        var result = new ArrayList<NumberOption>();
        for (var code : code(doc, p, registry))
            for (var option : code.readNumbers(doc, p, registry))
                result.add(
                        new NumberOption(
                                result.size(),
                                option.label(),
                                option.value(),
                                option.min(),
                                option.max()));
        return List.copyOf(result);
    }

    public static Map<String, DataSuggestions.NativeField> fieldHelp(
            SchematicDocument doc, BlockPos p, HolderLookup.Provider registry) {
        var index = doc.blocks().get(p);
        if (index == null) return Map.of();
        var state =
                net.minecraft.nbt.NbtUtils.readBlockState(
                        registry.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        doc.paletteCopy().getCompound(index));
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock factory))
            return Map.of();
        return nativeFields(factory.newBlockEntity(p, state), registry);
    }

    private static Map<String, DataSuggestions.NativeField> nativeFields(
            net.minecraft.world.level.block.entity.BlockEntity entity,
            HolderLookup.Provider registry) {
        if (entity == null) return Map.of();
        var result = new LinkedHashMap<String, DataSuggestions.NativeField>();
        NativeIntegerBounds.of(entity.getClass())
                .forEach((key, value) -> result.put("data." + key, value));
        // Limits describe native input safety, independently of optional restoration policy.
        for (var code :
                com.eynny.npships.api.patches.PatchTypes.capabilities(PatchBlockControls.class)
                        .values())
            for (var entry : code.nativeFields(entity, registry).entrySet())
                if (result.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                    throw new IllegalArgumentException(
                            "Conflicting native field controls: " + entry.getKey());
        return Map.copyOf(result);
    }

    public static void validateData(
            SchematicDocument doc,
            BlockPos pos,
            net.minecraft.nbt.CompoundTag data,
            HolderLookup.Provider registry) {
        NativeDataFields.validate(data, fieldHelp(doc, pos, registry), "data");
    }

    public static void validateData(
            net.minecraft.world.level.block.entity.BlockEntity entity,
            net.minecraft.nbt.CompoundTag data,
            HolderLookup.Provider registry) {
        NativeDataFields.validate(data, nativeFields(entity, registry), "data");
    }

    public static List<Option> read(
            SchematicDocument doc, BlockPos p, HolderLookup.Provider registry) {
        var result = new ArrayList<Option>();
        for (var code : code(doc, p, registry))
            for (var option : code.read(doc, p, registry))
                result.add(new Option(result.size(), option.label(), option.value()));
        return List.copyOf(result);
    }

    public static SchematicDocument setNumber(
            SchematicDocument doc,
            BlockPos p,
            int index,
            int value,
            HolderLookup.Provider registry) {
        for (var code : code(doc, p, registry)) {
            int count = code.readNumbers(doc, p, registry).size();
            if (index >= 0 && index < count) return code.setNumber(doc, p, index, value, registry);
            index -= count;
        }
        throw new IllegalArgumentException("Native value control is unavailable");
    }

    public static SchematicDocument cycle(
            SchematicDocument doc, BlockPos p, int index, HolderLookup.Provider registry) {
        for (var code : code(doc, p, registry)) {
            int count = code.read(doc, p, registry).size();
            if (index >= 0 && index < count) return code.cycle(doc, p, index, registry);
            index -= count;
        }
        throw new IllegalArgumentException("Native option control is unavailable");
    }
}
