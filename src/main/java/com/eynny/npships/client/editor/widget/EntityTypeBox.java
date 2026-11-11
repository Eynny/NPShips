package com.eynny.npships.client.editor.widget;

import net.minecraft.network.chat.Component;

public final class EntityTypeBox extends RegistryEditBox {
    public EntityTypeBox(
            int x, int y, int w, String initial, java.util.function.Consumer<String> changed) {
        super(
                x,
                y,
                w,
                Component.translatable("npships.tools.entity_type_tag"),
                () -> RegistryEditBox.names("new_pool_type"));
        setMaxLength(256);
        setValue(initial);
        updateHint(initial);
        setResponder(
                value -> {
                    changed.accept(value);
                    updateHint(value);
                });
    }

    private void updateHint(String value) {
        var hint = Component.translatable("npships.tools.entity_type_tag_hint");
        if (value.startsWith("#")) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(value.substring(1));
            if (id != null) {
                var tag =
                        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getTag(
                                net.minecraft.tags.TagKey.create(
                                        net.minecraft.core.registries.Registries.ENTITY_TYPE, id));
                if (tag.isPresent()) {
                    var names =
                            tag.get().stream()
                                    .map(h -> h.value().getDescription().getString())
                                    .sorted()
                                    .toList();
                    hint.append(
                            "\n"
                                    + String.join(", ", names.stream().limit(12).toList())
                                    + (names.size() > 12
                                            ? " \u2026 (+" + (names.size() - 12) + ")"
                                            : ""));
                }
            }
        }
        setTooltip(net.minecraft.client.gui.components.Tooltip.create(hint));
    }
}
