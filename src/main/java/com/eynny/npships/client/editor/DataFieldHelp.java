package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.DataSuggestions;

import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Describes verified serialized facts; never infers semantics from an arbitrary field name. */
final class DataFieldHelp {
    private DataFieldHelp() {}

    static List<Component> structure(
            DataSuggestions source, DataSuggestions.Help selected, String text) {
        var lines = new ArrayList<Component>();
        if (selected == null) {
            if (source.jsonMode()) lines.add(Component.translatable("npships.loot.help.root"));
            lines.add(tr("example_short"));
            var example = source.example(text);
            String code = source.exampleText(text);
            for (String line : code.split("\\n", -1)) lines.add(SnbtCodeSyntax.styled(line));

            return List.copyOf(lines);
        }
        if (source.jsonMode()) {
            lines.add(Component.literal(selected.path()));
            String key = source.descriptionKey(selected.path());
            if (key != null
                    && !key.isEmpty()
                    && net.minecraft.client.resources.language.I18n.exists(key))
                lines.add(Component.translatable(key));
            else lines.add(tr("json_field"));
            if (!selected.allowed().isEmpty()) {
                lines.add(
                        tr(
                                "allowed",
                                String.join(", ", selected.allowed().stream().limit(8).toList())));
                if (selected.allowed().size() > 8)
                    lines.add(tr("more_choices", selected.allowed().size()));
            }
            return List.copyOf(lines);
        }
        String connection = source.connectionLabel(selected.path());
        if (!connection.isEmpty()) lines.add(Component.literal(connection));
        String role = source.itemPart(selected.path(), text);
        if (role.equals("item_components_optional")) {
            lines.add(Component.literal(selected.path()));
            lines.add(tr("item_components_optional"));
            lines.add(tr("compound_entries"));
            lines.add(tr("item_enchantments"));
            lines.add(tr("item_complete"));
            return List.copyOf(lines);
        }
        if (role.equals("item_id")) lines.add(tr("item_edit"));
        var structure = source.hoverStructure(selected, text);
        if (structure.fields().isEmpty()) lines.add(tr("unknown"));
        for (var field : structure.fields()) {
            Tag value = field.value();
            String path = field.path().isEmpty() ? "{ }" : field.path();
            String sample =
                    value instanceof net.minecraft.nbt.CompoundTag
                            ? "{...}"
                            : value instanceof net.minecraft.nbt.ListTag
                                    ? "[...]"
                                    : value.toString();
            if (sample.length() > 80) sample = sample.substring(0, 80) + "...";
            lines.add(
                    Component.literal(path + " = ")
                            .withStyle(
                                    style ->
                                            style.withColor(
                                                    com.eynny.npships.client.editor.widget
                                                            .EditorButtonTheme.accent()))
                            .append(SnbtCodeSyntax.styled(sample)));
            // Child rows summarize actual data; descriptions belong to the hovered field only.
            if (field != structure.fields().getFirst()) continue;
            lines.add(tr("compact." + value.getId()));
            String descriptionKey =
                    "data_help." + field.owner().replace(':', '.') + "." + field.path();
            if (net.minecraft.client.resources.language.I18n.exists(descriptionKey))
                lines.add(Component.translatable(descriptionKey));
            if ((value instanceof net.minecraft.nbt.ListTag list && list.isEmpty()
                            || value instanceof net.minecraft.nbt.CompoundTag compound
                                    && compound.isEmpty())
                    && source.itemExamples(field.path()).isEmpty())
                lines.add(tr("unknown_structure", field.path()));
            String contract = source.itemPart(field.path(), text);
            if (!contract.isEmpty()) lines.add(tr(contract));
            if (field.path().equals("Rotation")) lines.add(tr("entity_rotation"));
            if (field.path().equals("NeoForgeData.npships_head_yaw")) lines.add(tr("entity_head"));
            if (!field.allowed().isEmpty()) {
                lines.add(
                        tr(
                                "allowed",
                                String.join(", ", field.allowed().stream().limit(8).toList())));
                if (field.allowed().size() > 8)
                    lines.add(tr("more_choices", field.allowed().size()));
            }
            var nativeField = field.nativeField();
            if (nativeField != null) {
                if (nativeField.label().equals("PersistenceRequired")) {
                    lines.add(tr("entity_persistence"));
                    continue;
                }
                if (nativeField.label().equals("EffectDuration")) lines.add(tr("effect_duration"));
                if (nativeField.label().equals("EffectAmplifier"))
                    lines.add(tr("effect_amplifier"));
                lines.add(tr("native", nativeField.label()));
                if (nativeField.options().isEmpty())
                    lines.add(tr("range", nativeField.min(), nativeField.max()));
                else
                    nativeField.options().entrySet().stream()
                            .sorted(
                                    java.util.Comparator
                                            .<java.util.Map.Entry<Integer, String>>comparingInt(
                                                    e ->
                                                            value
                                                                                    instanceof
                                                                                    net.minecraft
                                                                                                            .nbt
                                                                                                            .NumericTag
                                                                                                    number
                                                                            && e.getKey()
                                                                                    == number
                                                                                            .getAsInt()
                                                                    ? 0
                                                                    : 1)
                                            .thenComparingInt(java.util.Map.Entry::getKey))
                            .limit(8)
                            .forEach(
                                    e -> {
                                        var name = Component.literal(e.getValue());
                                        if (nativeField.label().equals("DyeColor")) {
                                            int rgb =
                                                    net.minecraft.world.item.DyeColor.byId(
                                                                    e.getKey())
                                                            .getTextColor();
                                            name.withStyle(
                                                    style ->
                                                            style.withColor(
                                                                    SnbtCodeSyntax.readableColor(
                                                                            rgb)));
                                        }
                                        lines.add(
                                                Component.literal(
                                                                DataSuggestions.numericLiteral(
                                                                                value, e.getKey())
                                                                        + " = ")
                                                        .append(name));
                                    });
            } else if (value instanceof net.minecraft.nbt.NumericTag && field.allowed().isEmpty())
                lines.add(tr("unknown_range"));
        }
        if (structure.truncated()) lines.add(tr("hover_more"));
        if (selected == null) lines.add(tr("compact_scope"));
        return List.copyOf(lines);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("npships.data_help." + key, args);
    }
}
