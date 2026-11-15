package com.eynny.npships.client.editor;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.document.BlockSettingTemplates;
import com.eynny.npships.authoring.document.BlockSettings;
import com.eynny.npships.client.editor.widget.EditorPopupMenu;
import com.eynny.npships.client.editor.widget.RegistryEditBox;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Nested settings stay in the editor sidebar and commit through its existing history owner. */
final class ParameterControls {
    private final EditorToolsPanel panel;
    private String scope = "";
    private com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject subject =
            com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.unknown();
    private final Set<String> handled = new HashSet<>();
    private boolean building;
    final EditorParameterTree tree;
    private CompoundTag before, draft, observed;
    private Supplier<CompoundTag> current;
    private BiConsumer<CompoundTag, CompoundTag> accept;
    private final Map<net.minecraft.client.gui.components.AbstractWidget, EditorParameterTree.Path>
            fields = new LinkedHashMap<>();
    private Supplier<Boolean> compatible;

    void resetHandled() {
        handled.clear();
        tree.reset();
        fields.clear();
    }

    void handled(String path) {
        if (!building) handled.add("/" + path);
    }

    boolean covered(String path) {
        return handled.contains(path);
    }

    ParameterControls(EditorToolsPanel panel) {
        this.panel = panel;
        tree = new EditorParameterTree(panel);
    }

    void build(
            String owner,
            CompoundTag source,
            CompoundTag observed,
            Supplier<CompoundTag> current,
            Supplier<Boolean> compatible,
            BiConsumer<CompoundTag, CompoundTag> accept) {
        scope = owner;
        subject =
                panel.pos() != null && panel.doc().blocks().containsKey(panel.pos())
                        ? com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.block(
                                panel.doc().blockName(panel.pos()))
                        : com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.unknown();
        before = source.copy();
        draft = source.copy();
        this.observed = observed.copy();
        this.current = current;
        this.compatible = compatible;
        this.accept = accept;
        Component title =
                owner.startsWith("sign:")
                        ? panel.tr("sign_settings")
                        : Component.translatable(
                                owner.startsWith("entity:")
                                        ? "npships.entity_settings.title"
                                        : "npships.block_settings.title");
        tree.build(
                owner,
                title,
                observed.copy(),
                node -> group(node, draft, new EditorParameterTree.Path(List.of()), this::save, 0));
        // One group at the existing sign acceptance position, including descendants built later.
        panel.defer(
                () -> {
                    tree.saveFields();
                    save();
                });
    }

    void acceptedDataChanged() {
        if (tree.root() != null && !compatible.get()) {
            tree.expire();
            panel.layoutOnly(panel.layout());
        } else if (tree.root() != null) tree.acceptedLists(current.get());
    }

    void contextChanged() {
        if (tree.root() != null && !compatible.get()) tree.expire();
    }

    private void save() {
        if (tree.root() == null || !tree.root().active || draft.equals(before)) return;
        var now = current.get();
        var keys = new HashSet<>(before.getAllKeys());
        keys.addAll(draft.getAllKeys());
        var changed =
                keys.stream()
                        .filter(k -> !Objects.equals(before.get(k), draft.get(k)))
                        .sorted()
                        .toList();
        net.minecraft.client.gui.components.AbstractWidget acceptanceOwner = tree.root();
        for (var key : changed) {
            var field =
                    fields.entrySet().stream()
                            .filter(
                                    e ->
                                            !e.getValue().keys().isEmpty()
                                                    && e.getValue().keys().getFirst().equals(key))
                            .filter(
                                    e ->
                                            !Objects.equals(
                                                    tree.value(before, e.getValue()),
                                                    tree.value(draft, e.getValue())))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(tree.field(new EditorParameterTree.Path(List.of(key))));
            acceptanceOwner = field;
            panel.validateField(
                    field,
                    () -> {
                        if (!Objects.equals(now.get(key), observed.get(key))
                                && !Objects.equals(now.get(key), draft.get(key)))
                            throw invalid(
                                    "sign_compound_conflict",
                                    "Sign compound changed since this form was opened"
                                            + (tree.stale()
                                                    ? ". Pending sign form (copy before a semantic reload): "
                                                            + draft
                                                    : ""),
                                    key,
                                    scope);
                    });
        }
        panel.validateField(acceptanceOwner, () -> accept.accept(before.copy(), draft.copy()));
        // Subsequent native callbacks in the same run may edit again before the semantic rebuild.
        before = draft.copy();
        for (var key : changed) {
            if (draft.contains(key)) observed.put(key, draft.get(key).copy());
            else observed.remove(key);
        }
    }

    private static String label(String key) {
        String translation = "npships.block_setting." + key;
        return net.minecraft.client.resources.language.I18n.exists(translation)
                ? Component.translatable(translation).getString()
                : key.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private Tag get(Tag group, String key) {
        return group instanceof CompoundTag c
                ? c.get(key)
                : ((CollectionTag<?>) group).get(Integer.parseInt(key));
    }

    private void put(Tag group, String key, Tag value) {
        if (group instanceof CompoundTag c) c.put(key, value);
        else if (!((CollectionTag<?>) group).setTag(Integer.parseInt(key), value))
            throw invalid("list_type", "List entries must have the same type");
    }

    private void group(
            EditorParameterTree.Node node,
            Tag current,
            EditorParameterTree.Path identity,
            Runnable save,
            int depth) {
        if (depth > 16) return;
        tree.structure(node, current);
        if (node.stale) return;
        String path = identity.display();
        var keys =
                current instanceof CompoundTag c
                        ? c.getAllKeys().stream().sorted().toList()
                        : java.util.stream.IntStream.range(0, ((CollectionTag<?>) current).size())
                                .mapToObj(Integer::toString)
                                .toList();
        for (var key : keys) {
            if (key.equals("npships_initial_spawn")) continue;
            if (path.isEmpty()
                    && Set.of("id", "UUID", "Pos", "Motion", "x", "y", "z", "RawCustomText")
                            .contains(key)) continue;
            Tag value = get(current, key);
            var childIdentity = identity.child(key);
            String child = childIdentity.display();
            if (covered(child)) continue;
            if (value instanceof CompoundTag || value instanceof CollectionTag<?>) {
                var branch =
                        tree.node(
                                node,
                                childIdentity,
                                EditorParameterTree.Kind.GROUP,
                                Component.literal(label(key)),
                                childNode ->
                                        group(childNode, value, childIdentity, save, depth + 1));
                tree.structure(branch, value);
            } else {
                if (value instanceof StringTag
                        && (key.equals("color") || key.matches("Dye[0-9]*"))) {
                    tree.node(
                            node,
                            childIdentity,
                            EditorParameterTree.Kind.PALETTE,
                            Component.literal(label(key) + ": " + value.getAsString()),
                            branch ->
                                    row(
                                            branch,
                                            () ->
                                                    panel.banners.colors(
                                                            color -> {
                                                                put(
                                                                        current,
                                                                        key,
                                                                        StringTag.valueOf(
                                                                                key.startsWith(
                                                                                                "Dye")
                                                                                        ? color
                                                                                                .name()
                                                                                        : color
                                                                                                .getName()));
                                                                save.run();
                                                            })));
                } else row(node, () -> scalar(current, key, value, identity, save));
            }
            if (current instanceof CollectionTag<?>) {
                row(
                        node,
                        () ->
                                panel.button(
                                        "npships.block_settings.remove_entry",
                                        () -> {
                                            ((CollectionTag<?>) current)
                                                    .remove(Integer.parseInt(key));
                                            save.run();
                                            tree.expire();
                                        }));
            }
        }
        if (current instanceof CollectionTag<?> list) {
            row(
                    node,
                    () ->
                            panel.button(
                                    "npships.block_settings.add_entry",
                                    () -> {
                                        Tag next =
                                                list.isEmpty()
                                                        ? BlockSettingTemplates.entry(subject, path)
                                                        : list.get(0).copy();
                                        if (next == null) next = new CompoundTag();
                                        if (current instanceof ByteArrayTag) next = ByteTag.ZERO;
                                        if (current instanceof IntArrayTag)
                                            next = IntTag.valueOf(0);
                                        if (current instanceof LongArrayTag)
                                            next = LongTag.valueOf(0);
                                        if (!list.addTag(list.size(), next))
                                            throw invalid("list_type", "Incompatible list type");
                                        save.run();
                                        tree.expire();
                                    }));
        }
        if (current instanceof CompoundTag compound) {
            tree.node(
                    node,
                    identity,
                    EditorParameterTree.Kind.FIELDS,
                    Component.translatable("npships.block_settings.fields"),
                    branch ->
                            row(
                                    branch,
                                    () -> {
                                        String[] name = {""};
                                        var nameField =
                                                panel.field("parameter_name", "", v -> name[0] = v);
                                        panel.host.validation.register(
                                                nameField, tree.section(identity) + ":fields_name");
                                        int[] type = {Tag.TAG_STRING};
                                        var typeButton =
                                                panel.button(
                                                        "npships.block_settings.type.string",
                                                        () -> {});
                                        typeButton.active = false;
                                        panel.button(
                                                "npships.block_settings.next_type",
                                                () -> {
                                                    type[0] = type[0] % Tag.TAG_LONG_ARRAY + 1;
                                                    typeButton.setMessage(
                                                            Component.literal(
                                                                    TagTypes.getType(type[0])
                                                                            .getPrettyName()));
                                                });
                                        panel.button(
                                                "npships.block_settings.add_entry",
                                                () ->
                                                        panel.validateField(
                                                                nameField,
                                                                () -> {
                                                                    if (name[0].isBlank()
                                                                            || compound.contains(
                                                                                    name[0])
                                                                            || path.isEmpty()
                                                                                    && BlockSettings
                                                                                            .IDENTITY
                                                                                            .contains(
                                                                                                    name[
                                                                                                            0]))
                                                                        throw invalid(
                                                                                "parameter_name",
                                                                                "Choose a new parameter name",
                                                                                name[0]);
                                                                    Tag entry =
                                                                            switch (type[0]) {
                                                                                case Tag.TAG_BYTE ->
                                                                                        ByteTag
                                                                                                .ZERO;
                                                                                case Tag
                                                                                                .TAG_SHORT ->
                                                                                        ShortTag
                                                                                                .valueOf(
                                                                                                        (short)
                                                                                                                0);
                                                                                case Tag.TAG_INT ->
                                                                                        IntTag
                                                                                                .valueOf(
                                                                                                        0);
                                                                                case Tag.TAG_LONG ->
                                                                                        LongTag
                                                                                                .valueOf(
                                                                                                        0);
                                                                                case Tag
                                                                                                .TAG_FLOAT ->
                                                                                        FloatTag
                                                                                                .valueOf(
                                                                                                        0);
                                                                                case Tag
                                                                                                .TAG_DOUBLE ->
                                                                                        DoubleTag
                                                                                                .valueOf(
                                                                                                        0);
                                                                                case Tag
                                                                                                .TAG_BYTE_ARRAY ->
                                                                                        new ByteArrayTag(
                                                                                                new byte
                                                                                                        [0]);
                                                                                case Tag.TAG_LIST ->
                                                                                        new ListTag();
                                                                                case Tag
                                                                                                .TAG_COMPOUND ->
                                                                                        new CompoundTag();
                                                                                case Tag
                                                                                                .TAG_INT_ARRAY ->
                                                                                        new IntArrayTag(
                                                                                                new int
                                                                                                        [0]);
                                                                                case Tag
                                                                                                .TAG_LONG_ARRAY ->
                                                                                        new LongArrayTag(
                                                                                                new long
                                                                                                        [0]);
                                                                                default ->
                                                                                        StringTag
                                                                                                .valueOf(
                                                                                                        "");
                                                                            };
                                                                    compound.put(name[0], entry);
                                                                    save.run();
                                                                    tree.expire();
                                                                }));
                                        panel.button(
                                                "npships.block_settings.remove_entry",
                                                () -> {
                                                    if (path.isEmpty()
                                                            && BlockSettings.IDENTITY.contains(
                                                                    name[0])) return;
                                                    boolean removed = compound.contains(name[0]);
                                                    compound.remove(name[0]);
                                                    save.run();
                                                    if (removed) tree.expire();
                                                });
                                    }));
        }
    }

    private void row(EditorParameterTree.Node node, Runnable build) {
        boolean previous = building;
        building = true;
        try {
            tree.row(node, build);
        } finally {
            building = previous;
        }
    }

    private void scalar(
            Tag current, String key, Tag value, EditorParameterTree.Path identity, Runnable save) {
        String path = identity.display();
        String title = label(key);
        var fieldPath = identity.child(key);
        String fullPath = fieldPath.display();
        String hintKey = "npships.block_setting_hint." + key;
        Component hint =
                net.minecraft.client.resources.language.I18n.exists(hintKey)
                        ? Component.translatable(hintKey)
                        : Component.literal(title + "\n" + fullPath);
        if (value instanceof ByteTag n
                && (n.getAsByte() == 0 || n.getAsByte() == 1)
                && !Set.of("Slot", "Count", "Fuel", "color").contains(key)) {
            panel.button(
                            title
                                    + ": "
                                    + Component.translatable(
                                                    n.getAsByte() == 0
                                                            ? "options.off"
                                                            : "options.on")
                                            .getString(),
                            () -> {
                                put(current, key, ByteTag.valueOf(n.getAsByte() == 0));
                                save.run();
                            })
                    .selected(() -> n.getAsByte() != 0)
                    .setTooltip(Tooltip.create(hint));
            return;
        }
        var choices = BlockSettingTemplates.choices(subject, path, key);
        if (value instanceof StringTag && choices.contains(value.getAsString())) {
            panel.button(
                            title + ": " + value.getAsString(),
                            () -> {
                                int y = panel.cursor - panel.scroll;
                                var entries =
                                        choices.stream()
                                                .map(
                                                        choice ->
                                                                new EditorPopupMenu.Entry(
                                                                        Component.literal(choice),
                                                                        () ->
                                                                                value.getAsString()
                                                                                        .equals(
                                                                                                choice),
                                                                        () -> {
                                                                            put(
                                                                                    current,
                                                                                    key,
                                                                                    StringTag
                                                                                            .valueOf(
                                                                                                    choice));
                                                                            save.run();
                                                                        }))
                                                .toList();
                                panel.host.toolMenu(
                                        panel.x,
                                        Math.min(y, panel.y + panel.h - 30),
                                        panel.w,
                                        entries);
                            })
                    .setTooltip(Tooltip.create(hint));
            return;
        }
        boolean text =
                value instanceof StringTag
                        && (Set.of("CustomName", "CustomText", "Text").contains(key)
                                || path.endsWith("/messages")
                                || path.endsWith("/filtered_messages"));
        String initial =
                value instanceof NumericTag n ? n.getAsNumber().toString() : value.getAsString();
        if (text)
            try {
                initial =
                        Component.Serializer.fromJson(initial, panel.mc.level.registryAccess())
                                .getString();
            } catch (Exception ignored) {
            }
        String original = initial;
        panel.widgets.add(
                new net.minecraft.client.gui.components.StringWidget(
                                panel.x,
                                panel.cursor,
                                panel.w - 12,
                                12,
                                Component.literal(
                                        panel.mc.font.plainSubstrByWidth(title, panel.w - 12)),
                                panel.mc.font)
                        .alignLeft());
        panel.cursor += 14;
        if (key.equals("Command")) {
            String[] changed = {initial};
            var editor = panel.multiline(initial, v -> changed[0] = v);
            fields.put(editor, fieldPath);
            panel.host.validation.register(editor, tree.section(fieldPath));
            panel.defer(
                    editor,
                    () -> {
                        put(
                                current,
                                key,
                                changed[0].equals(original)
                                        ? value.copy()
                                        : StringTag.valueOf(changed[0]));
                    });
            return;
        }
        var box =
                new RegistryEditBox(
                        panel.x,
                        panel.cursor,
                        panel.w - (value instanceof NumericTag ? 64 : 12),
                        Component.literal(title),
                        () -> registry(path, key));
        box.setMaxLength(32767);
        box.setValue(initial);
        box.setTooltip(Tooltip.create(hint));
        panel.host.validation.register(box, tree.section(fieldPath));
        fields.put(box, fieldPath);
        box.setResponder(
                v -> {
                    panel.settingsDirty = true;
                    panel.host.validation.changed(box);
                });
        panel.widgets.add(box);
        if (value instanceof NumericTag) panel.stretch(box);
        panel.defer(
                box,
                () -> {
                    put(
                            current,
                            key,
                            box.getValue().equals(original)
                                    ? value.copy()
                                    : text
                                            ? StringTag.valueOf(
                                                    Component.Serializer.toJson(
                                                            Component.literal(box.getValue()),
                                                            panel.mc.level.registryAccess()))
                                            : BlockSettings.scalar(value, box.getValue()));
                });
        if (value instanceof NumericTag n) {
            for (int direction : new int[] {-1, 1})
                panel.anchorRight(
                        panel.buttonAt(
                                Component.literal(direction < 0 ? "−" : "+"),
                                panel.x + panel.w - (direction < 0 ? 60 : 36),
                                panel.cursor,
                                22,
                                () -> {
                                    var number = (NumericTag) get(current, key);
                                    String changed =
                                            value instanceof FloatTag || value instanceof DoubleTag
                                                    ? Double.toString(
                                                            number.getAsDouble() + direction)
                                                    : Long.toString(
                                                            Math.addExact(
                                                                    number.getAsLong(), direction));
                                    put(current, key, BlockSettings.scalar(value, changed));
                                    save.run();
                                }));
        }
        panel.cursor += 24;
    }

    private List<String> registry(String path, String key) {
        if (key.equals("id") && (path.endsWith("/entity") || path.endsWith("/entity_data")))
            return BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                    .map(Object::toString)
                    .sorted()
                    .toList();
        if (key.equals("primary_effect") || key.equals("secondary_effect"))
            return BuiltInRegistries.MOB_EFFECT.keySet().stream()
                    .map(Object::toString)
                    .sorted()
                    .toList();
        if (key.equals("pattern"))
            return panel
                    .mc
                    .level
                    .registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN)
                    .keySet()
                    .stream()
                    .map(Object::toString)
                    .sorted()
                    .toList();
        if (key.equals("id"))
            return BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).sorted().toList();
        if (key.equals("note_block_sound"))
            return BuiltInRegistries.SOUND_EVENT.keySet().stream()
                    .map(Object::toString)
                    .sorted()
                    .toList();
        if (key.equals("color") || key.equals("Dye"))
            return Arrays.stream(DyeColor.values()).map(DyeColor::getName).toList();
        return List.of();
    }
}
