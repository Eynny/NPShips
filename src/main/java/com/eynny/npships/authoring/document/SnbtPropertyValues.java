package com.eynny.npships.authoring.document;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.*;

/**
 * Explicit, exhaustive property choices from current block SNBT, independent of completion hints.
 */
public final class SnbtPropertyValues {
    private SnbtPropertyValues() {}

    public record Result(
            String source,
            int start,
            int end,
            String property,
            List<DataSuggestions.Entry> entries) {}

    public static Result at(String source, int offset, Level level) {
        if (offset < 0 || offset >= source.length()) return null;
        try {
            // The native parser establishes syntax; the scanner only records unambiguous ranges.
            var token = SnbtText.token(source, offset);
            if (token == null
                    || token.parentState() == null
                    || token.path().size() < 2
                    || token.path().stream()
                            .anyMatch(
                                    p ->
                                            p.equals(SchematicExtensions.KEY)
                                                    || p.equals(
                                                            com.eynny.npships.authoring.patches
                                                                    .EntityPatches.EMBEDDED)
                                                    || p.equals("components"))) return null;
            var state = token.parentState();
            if (!state.contains("Name", Tag.TAG_STRING)
                    || !state.contains("Properties", Tag.TAG_COMPOUND)) return null;
            var id = ResourceLocation.tryParse(state.getString("Name"));
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
            BlockState draft = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
            var definition = draft.getBlock().getStateDefinition();
            String key = token.path().getLast();
            var property = definition.getProperty(key);
            if (property == null
                    || property.getPossibleValues().isEmpty()
                    || property.getPossibleValues().size() > 64) return null;
            var properties = state.getCompound("Properties");
            for (String other : properties.getAllKeys()) {
                if (other.equals(key)) continue;
                var p = definition.getProperty(other);
                if (p == null || !properties.contains(other, Tag.TAG_STRING)) return null;
                draft = set(draft, p, properties.getString(other));
            }
            // Invalid current values may be repaired; other property values must resolve reliably.
            if (properties.contains(key, Tag.TAG_STRING)
                    && property.getValue(properties.getString(key)).isPresent())
                draft = set(draft, property, properties.getString(key));
            final BlockState current = draft;
            var allowed =
                    SchematicRotations.orientation(property)
                            ? SchematicRotations.allowed(current, level)
                            : null;
            char quote = source.charAt(token.start());
            var entries = new ArrayList<DataSuggestions.Entry>();
            for (var value : property.getPossibleValues()) {
                if (allowed != null && !allowed.contains(with(current, property, value))) continue;
                String name = name(property, value);
                entries.add(new DataSuggestions.Entry(literal(name, quote), id + " / " + key));
            }
            if (entries.isEmpty()) return null;
            return new Result(source, token.start(), token.end(), key, List.copyOf(entries));
        } catch (CommandSyntaxException | IllegalArgumentException uncertainContext) {
            return null;
        }
    }

    /**
     * Explicit native enum or bounded numeric domains; preserve the serialized numeric type and
     * exact token range.
     */
    public static Result nativeValues(
            String source, int offset, Map<String, DataSuggestions.NativeField> fields) {
        if (fields.isEmpty() || offset < 0 || offset >= source.length()) return null;
        try {
            var token = SnbtText.token(source, offset);
            if (token == null) return null;
            var path = canonicalPath(source, token.path());
            var field = fields.get(path);
            if (field == null || field.options().size() > 4096) return null;
            Tag value = token.value();
            if (!(value instanceof net.minecraft.nbt.NumericTag numeric)) return null;
            var entries = new ArrayList<DataSuggestions.Entry>();
            var options = new TreeMap<>(field.options());
            if (options.isEmpty()) {
                long count = (long) field.max() - field.min() + 1;
                if (count <= 0 || count > 64) return null;
                for (long i = field.min(); i <= field.max(); i++)
                    options.put((int) i, Long.toString(i));
            }
            for (var entry : options.entrySet()) {
                var literal =
                        BlockSettings.scalar(numeric, Integer.toString(entry.getKey())).toString();
                entries.add(new DataSuggestions.Entry(literal, path + " / " + entry.getValue()));
            }
            return new Result(source, token.start(), token.end(), path, List.copyOf(entries));
        } catch (CommandSyntaxException | IllegalArgumentException uncertainContext) {
            return null;
        }
    }

    private static String canonicalPath(String source, java.util.List<String> parts) {
        try {
            net.minecraft.nbt.Tag value =
                    com.eynny.npships.authoring.document.SnbtText.nativeCompound(source);
            var path = new StringBuilder();
            for (String part : parts) {
                if (value instanceof net.minecraft.nbt.ListTag list) {
                    int index = Integer.parseInt(part);
                    if (index < 0 || index >= list.size()) return String.join(".", parts);
                    path.append("[]");
                    value = list.get(index);
                } else if (value instanceof net.minecraft.nbt.CompoundTag object) {
                    if (!path.isEmpty()) path.append('.');
                    path.append(part);
                    value = object.get(part);
                } else return String.join(".", parts);
            }
            return path.toString();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException
                | NumberFormatException incomplete) {
            return String.join(".", parts);
        }
    }

    /** Explicit, already bounded registry choices at an exact native scalar token. */
    public static Result declaredValues(
            String source, int offset, Map<String, List<String>> domains) {
        if (domains.isEmpty() || offset < 0 || offset >= source.length()) return null;
        try {
            var token = SnbtText.token(source, offset);
            if (token == null) return null;
            String path = canonicalPath(source, token.path());
            var values = domains.getOrDefault(path, List.of());
            if (values.isEmpty()) return null;
            var entries = new ArrayList<DataSuggestions.Entry>();
            for (String value : values) {
                // Domains already contain serialized SNBT; never wrap numeric/compound values as
                // strings.
                var parsed =
                        com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                        "{v:" + value + "}")
                                .get("v");
                if (parsed == null) return null;
                String insert =
                        parsed instanceof StringTag text
                                ? literal(text.getAsString(), source.charAt(token.start()))
                                : value;
                entries.add(new DataSuggestions.Entry(insert, path));
            }
            return new Result(source, token.start(), token.end(), path, List.copyOf(entries));
        } catch (CommandSyntaxException | IllegalArgumentException uncertainContext) {
            return null;
        }
    }

    /**
     * Ctrl-Space filters an incomplete string within its admitted native domain. Ctrl-click
     * continues to expose the full domain, including valid orientation choices.
     */
    public static Result forCompletion(Result result, int cursor) {
        try {
            String token = result.source().substring(result.start(), result.end());
            Tag current =
                    com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                    "{v:" + token + "}")
                            .get("v");
            if (!(current instanceof StringTag)) return result;
            for (var entry : result.entries()) {
                Tag candidate =
                        com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                        "{v:" + entry.insert() + "}")
                                .get("v");
                if (current.equals(candidate)) return result;
            }
            int end = Math.clamp(cursor - result.start(), 0, token.length());
            String prefix = token.substring(0, end);
            if (!prefix.isEmpty()
                    && (prefix.startsWith("'") || prefix.charAt(0) == '"')
                    && end < token.length()) prefix += prefix.charAt(0);
            Tag parsed =
                    com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                    "{v:" + prefix + "}")
                            .get("v");
            if (!(parsed instanceof StringTag query)) return result;
            var entries = new ArrayList<DataSuggestions.Entry>();
            for (var entry : result.entries()) {
                Tag candidate =
                        com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                        "{v:" + entry.insert() + "}")
                                .get("v");
                if (candidate instanceof StringTag text
                        && DataSuggestions.matches(text.getAsString(), query.getAsString()))
                    entries.add(entry);
            }
            return new Result(
                    result.source(),
                    result.start(),
                    result.end(),
                    result.property(),
                    List.copyOf(entries));
        } catch (CommandSyntaxException incomplete) {
            return result;
        }
    }

    private static String literal(String name, char quote) throws CommandSyntaxException {
        if (quote != '\'' && quote != '"') {
            // BlockState properties require strings: bare booleans/numbers are native numeric tags.
            if (com.eynny.npships.authoring.document.SnbtText.nativeCompound("{v:" + name + "}")
                            .get("v")
                    instanceof StringTag) return name;
            quote = '"';
        }
        String q = String.valueOf(quote);
        return q + name.replace("\\", "\\\\").replace(q, "\\" + q) + q;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String name(Property p, Comparable value) {
        return p.getName(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState with(BlockState state, Property p, Comparable value) {
        return state.setValue(p, value);
    }

    private static <T extends Comparable<T>> BlockState set(
            BlockState state, Property<T> p, String value) {
        return state.setValue(p, p.getValue(value).orElseThrow(IllegalArgumentException::new));
    }
}
