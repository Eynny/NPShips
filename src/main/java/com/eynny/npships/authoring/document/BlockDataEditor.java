package com.eynny.npships.authoring.document;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.*;

/** Shared textual state/NBT editor, using real registered properties and native placement rules. */
public final class BlockDataEditor {
    private BlockDataEditor() {}

    public static CompoundTag read(SchematicDocument doc, BlockPos pos) {
        return read(doc, pos, null);
    }

    public static CompoundTag read(SchematicDocument doc, BlockPos pos, Level context) {
        var result = new CompoundTag();
        result.put("state", doc.paletteCopy().getCompound(doc.blocks().get(pos)).copy());
        var data =
                context == null
                        ? doc.blockEntityData(pos).copy()
                        : com.eynny.npships.authoring.patches.SchematicResources.projectedData(
                                doc, pos, context);
        BlockSettings.IDENTITY.forEach(data::remove);
        result.put("data", data);
        return result;
    }

    public static DataSuggestions suggestions(SchematicDocument doc, BlockPos pos, Level level) {
        return suggestions(doc, pos, level, null);
    }

    public static DataSuggestions suggestions(
            SchematicDocument doc, BlockPos pos, Level level, DataSuggestions previous) {
        var stored = read(doc, pos, level);
        var defaults = new CompoundTag();
        try {
            defaults.put("data", BlockSettings.typeDefaults(doc, pos, level.registryAccess()));
        } catch (RuntimeException unsupportedDefaults) {
            /* Stored fields remain available for unusual addon entities. */
        }
        var state =
                NbtUtils.readBlockState(
                        level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                        stored.getCompound("state"));
        defaults.put("state", NbtUtils.writeBlockState(state.getBlock().defaultBlockState()));
        Map<String, List<String>> domains = new HashMap<>();
        for (var property : state.getProperties()) {
            var values = new TreeSet<String>();
            if (SchematicRotations.orientation(property)) {
                for (var allowed : SchematicRotations.allowed(state, level))
                    values.add(StringTag.valueOf(name(allowed, property)).toString());
            } else
                for (var value : property.getPossibleValues())
                    values.add(StringTag.valueOf(propertyName(property, value)).toString());
            domains.put("state.Properties." + property.getName(), List.copyOf(values));
        }
        var suggestions =
                new DataSuggestions(
                        defaults,
                        stored,
                        domains,
                        com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.block(
                                doc.blockName(pos)),
                        "data");
        try {
            suggestions.withNativeFields(BlockModes.fieldHelp(doc, pos, level.registryAccess()));
        } catch (RuntimeException unsupportedNativeControl) {
            /* Serialized-field help remains available. */
        }
        try {
            suggestions.withConnections(
                    ConnectionSettings.sections(doc, pos, level.registryAccess()).stream()
                            .map(section -> section.group())
                            .toList());
        } catch (RuntimeException unavailableConnections) {
            // Known stored fields remain editable when an unavailable/conflicting provider cannot
            // describe them.
        }
        try {
            if (state.getBlock() instanceof EntityBlock factory) {
                var detached = factory.newBlockEntity(BlockPos.ZERO, state);
                if (detached != null)
                    suggestions.withNativeSchema(NativeSerializationFields.of(detached.getClass()));
            }
        } catch (RuntimeException | LinkageError unavailableNativeFactory) {
            // A hint factory must never prevent editing retained source data.
        }
        return suggestions.withNativeValues(level).withItems(level.registryAccess(), previous);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyName(Property p, Comparable v) {
        return p.getName(v);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String name(BlockState s, Property p) {
        return p.getName(s.getValue(p));
    }

    public static SchematicDocument apply(
            SchematicDocument doc, BlockPos pos, CompoundTag text, Level level) {
        for (String key : text.getAllKeys())
            if (!Set.of("state", "data").contains(key))
                throw invalid(
                        "block_section", "Unknown editor section: " + key + " (state / data)", key);
        if (!text.contains("state", Tag.TAG_COMPOUND))
            throw invalid("block_state_missing", "Missing state compound");
        if (text.contains("data") && !text.contains("data", Tag.TAG_COMPOUND))
            throw invalid("block_data_compound", "data must be a compound");
        var stateTag = text.getCompound("state");
        for (String key : stateTag.getAllKeys())
            if (!Set.of("Name", "Properties").contains(key))
                throw invalid("block_state_field", "Unknown state field: " + key, key);
        if (!stateTag.contains("Name", Tag.TAG_STRING))
            throw invalid("block_name_string", "state.Name must be a block id string");
        if (stateTag.contains("Properties") && !stateTag.contains("Properties", Tag.TAG_COMPOUND))
            throw invalid("block_properties_compound", "state.Properties must be a compound");
        var id = ResourceLocation.tryParse(stateTag.getString("Name"));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
            throw invalid("unknown_block", "Unknown block: " + id, stateTag.getString("Name"));
        var next = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        var props = stateTag.getCompound("Properties");
        for (String key : props.getAllKeys()) {
            var property = next.getBlock().getStateDefinition().getProperty(key);
            if (property == null)
                throw invalid(
                        "block_property",
                        "Unknown block property: " + key,
                        stateTag.getString("Name"),
                        key);
            if (!props.contains(key, Tag.TAG_STRING))
                throw invalid(
                        "block_property_string",
                        "Block property " + key + " must be a string",
                        key);
            next = set(next, property, props.getString(key));
        }
        var old =
                NbtUtils.readBlockState(
                        level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                        doc.paletteCopy().getCompound(doc.blocks().get(pos)));
        {
            final var candidate = next;
            boolean changed =
                    next.getBlock() != old.getBlock()
                            || old.getProperties().stream()
                                    .anyMatch(
                                            p ->
                                                    SchematicRotations.orientation(p)
                                                            && !old.getValue(p)
                                                                    .equals(candidate.getValue(p)));
            if (changed
                    && SchematicRotations.allowed(next, level).stream()
                            .noneMatch(s -> s.equals(candidate)))
                throw invalid(
                        "block_orientation",
                        "This orientation is not supported by the block's native placement rules",
                        stateTag.toString());
        }
        var data = text.getCompound("data").copy();
        BlockSettings.IDENTITY.forEach(data::remove);
        var subject =
                com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.block(
                        id.toString());
        var itemPaths =
                new java.util.LinkedHashSet<>(
                        ItemSettings.nativeItemPaths(subject, level.registryAccess()));
        itemPaths.addAll(ConnectionSettings.itemPaths(next, data, level.registryAccess()));
        ItemSettings.validateChanges(
                doc.blockEntityData(pos), data, subject, level.registryAccess(), itemPaths);
        if (!(next.getBlock() instanceof EntityBlock) && !data.isEmpty())
            throw invalid(
                    "block_no_data",
                    "This block has no BlockEntity data; edit state.Properties",
                    stateTag.getString("Name"));
        if (old.equals(next)) return SchematicEdits.blockData(doc, pos, data, level);
        // The put argument is copycat material; universal data replaces the complete NBT payload.
        final var replacement = next;
        var changed =
                SchematicExtensions.intermediate(
                        () -> {
                            var stateChanged =
                                    SchematicBlocks.put(doc, pos, pos, replacement, null);
                            return replacement.getBlock() instanceof EntityBlock
                                    ? SchematicEdits.blockData(stateChanged, pos, data, level)
                                    : stateChanged;
                        });
        return SchematicExtensions.apply(
                doc,
                changed,
                SchematicExtensions.operation(
                        com.eynny.npships.api.patches.PatchAuthoring.Kind.EDIT,
                        p ->
                                p.equals(pos)
                                                && (old.getBlock() != replacement.getBlock()
                                                        || replacement.isAir())
                                        ? List.of()
                                        : List.of(p),
                        p -> List.of(p),
                        java.util.function.UnaryOperator.identity(),
                        Optional::of));
    }

    private static <T extends Comparable<T>> BlockState set(
            BlockState state, Property<T> property, String text) {
        var value =
                property.getValue(text)
                        .orElseThrow(
                                () ->
                                        invalid(
                                                "block_property_value",
                                                "Invalid "
                                                        + property.getName()
                                                        + ": "
                                                        + text
                                                        + "; allowed: "
                                                        + property.getPossibleValues(),
                                                property.getName(),
                                                text,
                                                property.getPossibleValues()));
        return state.setValue(property, value);
    }
}
