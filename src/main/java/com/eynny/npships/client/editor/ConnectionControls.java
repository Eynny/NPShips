package com.eynny.npships.client.editor;

import com.eynny.npships.api.patches.PatchConnectionFields.Field;
import com.eynny.npships.api.patches.PatchConnectionFields.ValueKind;
import com.eynny.npships.authoring.document.ConnectionSettings;
import com.eynny.npships.authoring.document.ConnectionSettings.Section;
import com.eynny.npships.client.editor.widget.EditorPopupMenu;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Thin connection forms; native semantics and exact field ownership come from patch declarations.
 */
final class ConnectionControls {
    private final EditorToolsPanel panel;
    private final Map<String, Integer> selected = new HashMap<>();
    private BlockPos owner;

    ConnectionControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    void build() {
        var pos = panel.pos();
        if (!Objects.equals(owner, pos)) {
            owner = pos;
            selected.clear();
        }
        for (var section :
                ConnectionSettings.sections(panel.cached, pos, panel.mc.level.registryAccess()))
            group(pos, section);
    }

    private void group(BlockPos position, Section section) {
        var expected = panel.doc();
        var group = section.group();
        String id = section.provider() + "/" + group.id();
        var rows = ConnectionSettings.rows(expected.blockEntityData(position), group);
        int index = Math.clamp(selected.getOrDefault(id, 0), 0, Math.max(0, rows.size() - 1));
        selected.put(id, index);
        panel.sections.begin("connections." + id, "section_connections", true);
        panel.wrappedNote(group.title());
        if (group.repeated()) {
            int menuY = panel.cursor;
            var keyField =
                    group.fields().stream()
                            .filter(f -> f.kind() == ValueKind.KEY)
                            .findFirst()
                            .orElseThrow();
            if (!rows.isEmpty())
                panel.button(
                        keyName(rows.get(index).getInt(keyField.key())).getString(),
                        () -> {
                            var entries = new ArrayList<EditorPopupMenu.Entry>();
                            for (int i = 0; i < rows.size(); i++) {
                                final int choice = i;
                                entries.add(
                                        new EditorPopupMenu.Entry(
                                                keyName(rows.get(i).getInt(keyField.key())),
                                                () -> selected.getOrDefault(id, 0) == choice,
                                                () -> {
                                                    selected.put(id, choice);
                                                    panel.build();
                                                }));
                            }
                            panel.host.toolMenu(panel.x, menuY - panel.scroll, panel.w, entries);
                        });
            String[] newKey = {"a"};
            var keyBox = panel.field("connection_new_key", newKey[0], value -> newKey[0] = value);
            panel.action(
                    "connection_add_key",
                    () ->
                            panel.validateField(
                                    keyBox,
                                    () -> {
                                        check(position);
                                        var next =
                                                ConnectionSettings.add(
                                                        panel.doc(),
                                                        expected,
                                                        position,
                                                        section,
                                                        parseKey(newKey[0]),
                                                        panel.mc.level.registryAccess());
                                        selected.put(id, rows.size());
                                        panel.commit(next);
                                    }));
        }
        if (!rows.isEmpty()) fields(position, section, index, rows.get(index), expected);
        if (group.repeated() && !rows.isEmpty())
            panel.action(
                    "connection_remove_key",
                    () -> {
                        check(position);
                        panel.commit(
                                ConnectionSettings.remove(
                                        panel.doc(),
                                        expected,
                                        position,
                                        section,
                                        index,
                                        panel.mc.level.registryAccess()));
                    });
        panel.sections.end();
    }

    private void fields(
            BlockPos position,
            Section section,
            int row,
            CompoundTag data,
            com.eynny.npships.authoring.document.SchematicDocument expected) {
        var edits = new LinkedHashMap<Field, java.util.function.Supplier<Tag>>();
        var inputs = new ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        for (var field : section.group().fields()) {
            panel.wrappedNote(field.title());
            String initial;
            if (field.kind() == ValueKind.ITEM)
                initial = data.getCompound(field.key()).getString("id");
            else if (field.kind() == ValueKind.KEY)
                initial =
                        InputConstants.Type.KEYSYM
                                .getOrCreate(data.getInt(field.key()))
                                .getName()
                                .replace("key.keyboard.", "");
            else {
                var offset = NbtUtils.readBlockPos(data, field.key()).orElse(BlockPos.ZERO);
                initial = position.offset(offset).toShortString().replace(",", "");
            }
            String[] value = {initial};
            var input =
                    panel.field(
                            field.kind() == ValueKind.ITEM
                                    ? "item"
                                    : field.kind() == ValueKind.KEY
                                            ? "connection_key"
                                            : "connection_target",
                            initial,
                            changed -> value[0] = changed,
                            false);
            input.setTooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                            panel.tr(
                                    field.kind() == ValueKind.ITEM
                                            ? "connection_item_hint"
                                            : field.kind() == ValueKind.KEY
                                                    ? "connection_key_hint"
                                                    : "connection_position_hint")));
            inputs.add(input);
            edits.put(
                    field,
                    () -> {
                        if (value[0].trim().equals(initial)) return null;
                        if (field.kind() == ValueKind.ITEM) {
                            if (value[0].isBlank()) return new CompoundTag();
                            var item = data.getCompound(field.key()).copy();
                            item.putString(
                                    "id", ResourceLocation.parse(value[0].trim()).toString());
                            item.putInt("count", 1);
                            return item;
                        }
                        if (field.kind() == ValueKind.KEY)
                            return IntTag.valueOf(parseKey(value[0]));
                        var parts = value[0].trim().split("\\s+");
                        if (parts.length != 3)
                            throw new IllegalArgumentException(
                                    panel.tr("connection_position_hint").getString());
                        var target =
                                new BlockPos(
                                        Integer.parseInt(parts[0]),
                                        Integer.parseInt(parts[1]),
                                        Integer.parseInt(parts[2]));
                        return NbtUtils.writeBlockPos(
                                new BlockPos(
                                        Math.subtractExact(target.getX(), position.getX()),
                                        Math.subtractExact(target.getY(), position.getY()),
                                        Math.subtractExact(target.getZ(), position.getZ())));
                    });
            if (field.kind() == ValueKind.ITEM) {
                var raw = data.getCompound(field.key());
                var stack = ItemStack.parseOptional(panel.mc.level.registryAccess(), raw);
                var cell =
                        panel.itemCell(
                                stack,
                                panel.x,
                                panel.cursor,
                                () -> {
                                    panel.focusWidget(input);
                                    input.moveCursorToEnd(false);
                                    input.setHighlightPos(0);
                                },
                                () -> {
                                    check(position);
                                    panel.commit(
                                            ConnectionSettings.set(
                                                    panel.doc(),
                                                    expected,
                                                    position,
                                                    section,
                                                    row,
                                                    field,
                                                    new CompoundTag(),
                                                    panel.mc.level.registryAccess()));
                                });
                panel.widgets.add(cell);
                panel.cursor += 24;
            }
        }
        panel.action(
                "connection_apply",
                () -> {
                    check(position);
                    var changes = new LinkedHashMap<Field, Tag>();
                    int i = 0;
                    for (var entry : edits.entrySet()) {
                        var input = inputs.get(i++);
                        panel.validateField(
                                input,
                                () -> {
                                    var tag = entry.getValue().get();
                                    if (tag != null) changes.put(entry.getKey(), tag);
                                });
                    }
                    panel.commit(
                            ConnectionSettings.setAll(
                                    panel.doc(),
                                    expected,
                                    position,
                                    section,
                                    row,
                                    changes,
                                    panel.mc.level.registryAccess()));
                });
    }

    private void check(BlockPos position) {
        if (!Objects.equals(position, panel.pos()))
            throw new IllegalArgumentException(panel.tr("patch_external_stale").getString());
    }

    static int parseKey(String value) {
        String name = value.trim().toLowerCase(Locale.ROOT);
        if (!name.startsWith("key.keyboard.")) name = "key.keyboard." + name;
        int key = InputConstants.getKey(name).getValue();
        if (key < 32 || key > 348)
            throw new IllegalArgumentException("Unknown keyboard key: " + value);
        return key;
    }

    private static Component keyName(int key) {
        return EditorShortcutDispatcher.currentKeyName(InputConstants.Type.KEYSYM.getOrCreate(key));
    }
}
