package com.eynny.npships.client.editor;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.patches.SchematicFluids;
import com.eynny.npships.authoring.patches.SchematicResources;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorSlider;
import com.eynny.npships.network.LootTableNetworking;
import com.google.gson.*;

import net.minecraft.client.gui.components.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;

import java.util.*;

/** Tool-specific controls; document commits and selection stay with the editor session. */
final class LootToolControls {
    private static final String LOCAL_PREFIX = "npships:local/";
    private final EditorToolsPanel panel;
    private EditBox localName;
    private final Map<Integer, AbstractWidget> retainedInventoryCells = new HashMap<>();
    private boolean inventoryLayout;
    private int inventoryTotalWidth;

    LootToolControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    void resetLayout() {
        localName = null;
        inventoryLayout = false;
        retainedInventoryCells.clear();
        panel.compactInventoryCells.clear();
        panel.inventoryCells.clear();
    }

    void lootTools() {
        panel.action("next_container", panel.host::nextContainer);
        boolean eligible = panel.pos() != null && panel.host.containers().contains(panel.pos());
        var selectedBlock =
                eligible
                        ? SchematicResources.detached(
                                panel.doc(), panel.pos(), panel.mc.level.registryAccess())
                        : null;
        boolean generated = selectedBlock instanceof net.minecraft.world.RandomizableContainer;
        if (eligible) panel.blockData();
        var catalog = LootTableNetworking.clientReply();
        if (catalog != null) panel.serverTables = catalog.tables();
        panel.sections.begin("tables", "section_loot_tables", true);
        panel.field(
                "search",
                panel.lootQuery,
                v -> {
                    panel.lootQuery = v;
                    panel.lootPage = 0;
                    panel.updateLootRows();
                });
        panel.lootRowsY = panel.cursor;
        panel.cursor += 5 * 24;
        panel.updateLootRows();
        for (var row : panel.lootRows) row.setY(row.getY() + panel.scroll);
        panel.sections.end();
        if (generated) {
            panel.sections.begin("table_authoring", "section_loot_authoring", true);
            panel.action("new_loot", () -> panel.editLocalTable("", null));
            var local = EmbeddedLoot.read(panel.cached.configCopy());
            for (String id : local.keySet())
                panel.button(
                        panel.tr("edit_loot").getString()
                                + ": "
                                + id.substring(LOCAL_PREFIX.length()),
                        () -> panel.editLocalTable(id, local.getAsJsonObject(id)));
            panel.sections.end();
        }
        if (!eligible) {
            panel.wrappedNote("loot_target_unavailable");
            return;
        }
        if (!generated) return;
        String[] table = {
            LootAssignments.read(panel.cached.configCopy()).stream()
                    .filter(v -> v.pos().equals(panel.pos()))
                    .map(v -> v.table().toString())
                    .findFirst()
                    .orElse(panel.cached.savedLootTable(panel.pos()))
        };
        BlockPos containerPos = panel.pos();
        String[] rolls = {
            ""
                    + LootAssignments.read(panel.cached.configCopy()).stream()
                            .filter(v -> v.pos().equals(containerPos))
                            .map(LootAssignments.Entry::rolls)
                            .findFirst()
                            .orElse(1)
        };
        panel.sections.begin("assignment", "section_generated_loot", true);
        var tableField =
                panel.field(
                        "loot_table",
                        table[0],
                        v -> {
                            table[0] = v;
                            panel.settingsDirty = true;
                        });
        var rollsField =
                panel.field(
                        "container_rolls",
                        rolls[0],
                        v -> {
                            rolls[0] = v;
                            panel.settingsDirty = true;
                        });
        panel.wrappedNote("container_rolls_hint");
        String initialTable = table[0], initialRolls = rolls[0];
        panel.pendingSettings =
                () -> {
                    if (table[0].equals(initialTable) && rolls[0].equals(initialRolls)) {
                        panel.host.validation.result(
                                tableField, panel.host.validation.generation(tableField), null);
                        panel.host.validation.result(
                                rollsField, panel.host.validation.generation(rollsField), null);
                        return;
                    }
                    ResourceLocation[] id = {null};
                    panel.validateField(
                            tableField,
                            () ->
                                    id[0] =
                                            table[0].isBlank()
                                                    ? null
                                                    : ResourceLocation.parse(table[0]));
                    panel.validateField(
                            rollsField,
                            () ->
                                    panel.commit(
                                            panel.doc()
                                                    .withConfig(
                                                            LootAssignments.assign(
                                                                    panel.doc().configCopy(),
                                                                    containerPos,
                                                                    id[0],
                                                                    Integer.parseInt(rolls[0])))));
                };
        panel.sections.end();
    }

    void updateLootRows() {
        panel.sections.forget(List.copyOf(panel.lootRows));
        panel.widgets.removeAll(panel.lootRows);
        panel.lootRows.clear();
        var available = new TreeSet<String>(panel.serverTables);
        available.addAll(EmbeddedLoot.read(panel.doc().configCopy()).keySet());
        var filtered =
                available.stream()
                        .filter(
                                id ->
                                        id.toLowerCase(Locale.ROOT)
                                                .contains(
                                                        panel.lootQuery
                                                                .trim()
                                                                .toLowerCase(Locale.ROOT)))
                        .toList();
        panel.lootMax = Math.max(0, filtered.size() - 5);
        panel.lootPage = Math.min(panel.lootPage, panel.lootMax);
        for (int i = 0; i < 5 && panel.lootPage + i < filtered.size(); i++) {
            String id = filtered.get(panel.lootPage + i);
            var b =
                    panel.buttonAt(
                            Component.literal(id),
                            panel.x,
                            panel.lootRowsY + i * 24 - panel.scroll,
                            EditorLayout.listWidth(panel.w),
                            () -> {
                                if (panel.pos() != null
                                        && panel.host.supportsLootTable(panel.pos()))
                                    panel.commit(
                                            panel.doc()
                                                    .withConfig(
                                                            LootAssignments.assign(
                                                                    panel.doc().configCopy(),
                                                                    panel.pos(),
                                                                    ResourceLocation.parse(id))));
                            });
            b.active = panel.pos() != null && panel.host.supportsLootTable(panel.pos());
            b.setTooltip(Tooltip.create(Component.literal(id)));
            panel.lootRows.add(b);
        }
        panel.sections.tableCells(panel.lootRows);
    }

    void editLocalTable(String original, JsonObject existing) {
        var document = panel.doc();
        var target = panel.pos();
        panel.focusWidget(null);
        panel.sections.reset();
        panel.rebuild = false;
        panel.widgets.clear();
        panel.resetTextWorkspace();
        panel.sections.reset("loot:local");
        panel.lootRows.clear();
        panel.itemCells.clear();
        panel.searchStart = panel.searchEnd = -1;
        panel.fieldSavers.clear();
        panel.pendingSettings = null;
        panel.cursor = panel.layout().inspectorContent().top();
        panel.scroll = 0;
        // These actions own the nested draft. Cancel deliberately bypasses run()/flushSettings().
        var apply =
                EditorButton.flat(
                                panel.tr("loot_apply_return"),
                                ignored -> {
                                    if (panel.doc() != document
                                            || !Objects.equals(target, panel.pos())) return;
                                    if (panel.flushEdits()) panel.build();
                                })
                        .bounds(panel.x, panel.cursor, panel.contentWidth(), 20)
                        .build();
        panel.widgets.add(apply);
        panel.cursor += 24;
        var cancel =
                EditorButton.flat(
                                panel.tr("cancel"),
                                ignored -> {
                                    if (panel.doc() != document
                                            || !Objects.equals(target, panel.pos())) return;
                                    panel.settingsDirty = false;
                                    panel.pendingSettings = null;
                                    panel.fieldSavers.clear();
                                    panel.build();
                                })
                        .bounds(panel.x, panel.cursor, panel.contentWidth(), 20)
                        .build();
        panel.widgets.add(cancel);
        panel.cursor += 24;
        if (!original.isEmpty()) {
            var remove =
                    EditorButton.flat(
                                    panel.tr("loot_delete_table"),
                                    ignored -> {
                                        if (panel.doc() != document
                                                || !Objects.equals(target, panel.pos())) return;
                                        var config =
                                                EmbeddedLoot.remove(
                                                        panel.doc().configCopy(), original);
                                        panel.settingsDirty = false;
                                        panel.pendingSettings = null;
                                        panel.fieldSavers.clear();
                                        panel.commit(panel.doc().withConfig(config));
                                        panel.build();
                                    })
                            .bounds(panel.x, panel.cursor, panel.contentWidth(), 20)
                            .tooltip(Tooltip.create(panel.tr("loot_delete_table_hint")))
                            .build();
            panel.widgets.add(remove);
            panel.cursor += 24;
        }
        String[] id = {
            original.isEmpty()
                    ? LOCAL_PREFIX
                            + "loot_"
                            + (EmbeddedLoot.read(panel.doc().configCopy()).size() + 1)
                    : original
        };
        var initial =
                existing == null
                        ? JsonParser.parseString(
                                        "{\"type\":\"minecraft:chest\",\"pools\":[{\"rolls\":1,\"entries\":[]}]}")
                                .getAsJsonObject()
                        : existing;
        String[] json = {new GsonBuilder().setPrettyPrinting().create().toJson(initial)};
        LuaEditBox[] jsonField = {null};
        EditBox[] rollsField = {null};
        String[] rollsDraft = {null};
        boolean[] syncingRolls = {false};
        if (original.isEmpty()) panel.settingsDirty = true;
        panel.sections.begin("table_editor", "section_local_entries", true);
        var idField =
                panel.field(
                        "loot_local_name",
                        id[0].substring(LOCAL_PREFIX.length()),
                        value -> id[0] = LOCAL_PREFIX + value);
        localName = idField;
        idField.validateWith(
                value ->
                        !value.isBlank()
                                && ResourceLocation.tryParse(LOCAL_PREFIX + value) != null);
        String[] item = {""}, count = {"1"}, weight = {"1"};
        var itemField = panel.field("item", item[0], v -> item[0] = v);
        var countField = panel.field("count", count[0], v -> count[0] = v);
        var weightField = panel.field("loot_weight", weight[0], v -> weight[0] = v);
        panel.draftAction(
                "add_loot_item",
                () -> {
                    JsonObject[] table = {null};
                    panel.validateField(
                            jsonField[0],
                            () -> {
                                table[0] = JsonStructure.parse(json[0]).getAsJsonObject();
                                var pools = table[0].getAsJsonArray("pools");
                                if (pools == null || pools.isEmpty())
                                    throw invalid("loot_pool", "The table needs a pool first.");
                            });
                    ResourceLocation[] parsed = {null};
                    int[] values = {0, 0};
                    panel.validateField(
                            itemField, () -> parsed[0] = ResourceLocation.parse(item[0]));
                    panel.validateField(countField, () -> values[0] = Integer.parseInt(count[0]));
                    panel.validateField(weightField, () -> values[1] = Integer.parseInt(weight[0]));
                    var resource = parsed[0];
                    int n = values[0], chance = values[1];
                    if (!BuiltInRegistries.ITEM.containsKey(resource)
                            || n < 1
                            || n > 99
                            || chance < 1
                            || chance > 1000000)
                        panel.validateField(
                                !BuiltInRegistries.ITEM.containsKey(resource)
                                        ? itemField
                                        : n < 1 || n > 99 ? countField : weightField,
                                () -> {
                                    throw invalid(
                                            "loot_inline",
                                            "Invalid item, count or weight.",
                                            resource,
                                            n,
                                            chance);
                                });
                    var entry = new JsonObject();
                    entry.addProperty("type", "minecraft:item");
                    entry.addProperty("name", resource.toString());
                    entry.addProperty("weight", chance);
                    var fn = new JsonObject();
                    fn.addProperty("function", "minecraft:set_count");
                    fn.addProperty("count", n);
                    var functions = new JsonArray();
                    functions.add(fn);
                    entry.add("functions", functions);
                    panel.validateField(
                            jsonField[0],
                            () ->
                                    table[0].getAsJsonArray("pools")
                                            .get(0)
                                            .getAsJsonObject()
                                            .getAsJsonArray("entries")
                                            .add(entry));
                    panel.editLocalTable(original, table[0]);
                    localName.setValue(id[0].substring(LOCAL_PREFIX.length()));
                });
        if (initial.has("pools") && !initial.getAsJsonArray("pools").isEmpty()) {
            var pool = initial.getAsJsonArray("pools").get(0).getAsJsonObject();
            rollsDraft[0] = simpleRolls(initial);
            if (rollsDraft[0] != null) {
                rollsField[0] =
                        panel.field(
                                "rolls",
                                rollsDraft[0],
                                v -> {
                                    rollsDraft[0] = v;
                                    if (syncingRolls[0]) return;
                                    try {
                                        int n = inlineRolls(v);
                                        var table = JsonStructure.parse(json[0]).getAsJsonObject();
                                        table.getAsJsonArray("pools")
                                                .get(0)
                                                .getAsJsonObject()
                                                .addProperty("rolls", n);
                                        jsonField[0].setValue(
                                                new GsonBuilder()
                                                        .setPrettyPrinting()
                                                        .create()
                                                        .toJson(table));
                                    } catch (RuntimeException error) {
                                        panel.host.validation.result(
                                                rollsField[0],
                                                panel.host.validation.generation(rollsField[0]),
                                                EditorErrorText.message(error));
                                    }
                                });
            }
            if (pool.has("entries"))
                for (int i = 0; i < pool.getAsJsonArray("entries").size(); i++) {
                    int index = i;
                    var entry = pool.getAsJsonArray("entries").get(i).getAsJsonObject();
                    String label =
                            entry.has("name")
                                    ? entry.get("name").getAsString()
                                    : entry.get("type").getAsString();
                    panel.draftAction(
                            panel.tr("remove_loot_item").getString() + ": " + label,
                            () -> {
                                JsonObject[] table = {null};
                                panel.validateField(
                                        jsonField[0],
                                        () -> {
                                            table[0] =
                                                    JsonStructure.parse(json[0]).getAsJsonObject();
                                            var entries =
                                                    table[0].getAsJsonArray("pools")
                                                            .get(0)
                                                            .getAsJsonObject()
                                                            .getAsJsonArray("entries");
                                            if (index >= entries.size()
                                                    || !entry.equals(entries.get(index)))
                                                throw invalid(
                                                        "loot_entry_changed",
                                                        "This loot entry changed. Edit the current JSON or reopen the table.");
                                            entries.remove(index);
                                        });
                                panel.editLocalTable(original, table[0]);
                                localName.setValue(id[0].substring(LOCAL_PREFIX.length()));
                            });
                }
        }
        // The complete standard table stays editable; unknown conditions/functions are preserved.
        jsonField[0] =
                panel.jsonDraftEditor(
                        json[0],
                        v -> {
                            json[0] = v;
                            if (rollsField[0] == null) return;
                            JsonObject parsed;
                            try {
                                parsed = JsonStructure.parse(v).getAsJsonObject();
                            } catch (RuntimeException invalidDraft) {
                                return;
                            }
                            String value = simpleRolls(parsed);
                            rollsField[0].active = value != null;
                            rollsDraft[0] = value;
                            if (value == null) return;
                            syncingRolls[0] = true;
                            try {
                                if (!rollsField[0].getValue().equals(value))
                                    rollsField[0].setValue(value);
                            } finally {
                                syncingRolls[0] = false;
                            }
                        });
        panel.pendingSettings =
                () -> {
                    if (panel.doc() != document || !Objects.equals(target, panel.pos())) {
                        panel.host.status(panel.tr("patch_external_stale").getString());
                        return;
                    }
                    panel.validateField(
                            idField,
                            () -> {
                                if (id[0].length() == LOCAL_PREFIX.length()
                                        || ResourceLocation.tryParse(id[0]) == null)
                                    throw invalid(
                                            "loot_local_name", "Enter a valid local table name.");
                            });
                    if (!original.equals(id[0])
                            && EmbeddedLoot.read(panel.doc().configCopy()).has(id[0]))
                        panel.validateField(
                                idField,
                                () -> {
                                    throw invalid(
                                            "loot_table_exists",
                                            "This table already exists. Choose another ID.",
                                            id[0]);
                                });
                    if (rollsField[0] != null && rollsDraft[0] != null)
                        panel.validateField(rollsField[0], () -> inlineRolls(rollsDraft[0]));
                    JsonObject[] parsed = {null};
                    panel.validateField(
                            jsonField[0],
                            () -> {
                                parsed[0] = JsonStructure.parse(json[0]).getAsJsonObject();
                                net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC
                                        .parse(
                                                panel.mc
                                                        .level
                                                        .registryAccess()
                                                        .createSerializationContext(
                                                                com.mojang.serialization.JsonOps
                                                                        .INSTANCE),
                                                parsed[0])
                                        .getOrThrow();
                            });
                    panel.validateField(
                            idField,
                            () -> {
                                var c =
                                        EmbeddedLoot.replace(
                                                panel.doc().configCopy(),
                                                original,
                                                id[0],
                                                parsed[0]);
                                c =
                                        LootAssignments.assign(
                                                c, target, ResourceLocation.parse(id[0]));
                                panel.commit(panel.doc().withConfig(c));
                            });
                };
        panel.sections.end();
        panel.contentHeight = panel.cursor - panel.y - panel.TOOLBAR_HEIGHT;
        panel.captureArrangement();
        panel.layoutOnly(panel.layout());
    }

    private static int inlineRolls(String value) {
        int rolls;
        try {
            rolls = Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            throw invalid("loot_rolls", "Rolls: 0..256.", value);
        }
        if (rolls < 0 || rolls > 256) throw invalid("loot_rolls", "Rolls: 0..256.", rolls);
        return rolls;
    }

    private static String simpleRolls(JsonObject table) {
        try {
            var value = table.getAsJsonArray("pools").get(0).getAsJsonObject().get("rolls");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
            int rolls = value.getAsBigDecimal().intValueExact();
            return rolls >= 0 && rolls <= 256 ? Integer.toString(rolls) : null;
        } catch (RuntimeException notSimple) {
            return null;
        }
    }

    void inventory() {
        resetLayout();
        boolean ownerChanged = !Objects.equals(panel.resourceOwner, panel.pos());
        if (ownerChanged) {
            panel.resourceOwner = panel.pos();
            panel.resourceSlot = 0;
            panel.inventoryOffsetX = panel.inventoryOffsetY = 0;
            panel.itemPage = 0;
            panel.itemRemainder = 0;
            panel.itemQuery = "";
        }
        panel.resourceInventory =
                SchematicResources.open(panel.cached, panel.pos(), panel.mc.level);
        panel.resourceFluids = new SchematicFluids(panel.cached, panel.pos(), panel.mc.level);
        if (!panel.resourceInventory.available()) {
            panel.wrappedNote("resource_missing_items");
            return;
        }
        if (panel.resourceInventory.size() == 0 && panel.resourceFluids.size() == 0) return;
        inventoryLayout = true;
        if (panel.resourceFluids.size() == 0) panel.fluidsMode = false;
        else if (panel.resourceInventory.size() == 0) panel.fluidsMode = true;
        if (panel.resourceInventory.size() > 0 && panel.resourceFluids.size() > 0)
            panel.action(
                    panel.fluidsMode ? "show_items" : "show_fluids",
                    () -> {
                        panel.fluidsMode = !panel.fluidsMode;
                        panel.resourceSlot = 0;
                        panel.build();
                    });
        int size = panel.fluidsMode ? panel.resourceFluids.size() : panel.resourceInventory.size();
        panel.resourceSlot = Math.min(panel.resourceSlot, size - 1);
        int columns = panel.fluidsMode ? Math.min(9, size) : panel.resourceInventory.columns();
        int totalWidth = columns * 18;
        if (!panel.fluidsMode)
            for (int slot = 0; slot < size; slot++)
                totalWidth = Math.max(totalWidth, panel.resourceInventory.slotX(slot) + 18);
        inventoryTotalWidth = totalWidth;
        // A small new target owns a compact native grid, not the previous target's large workspace.
        // The same target keeps its explicit workspace, scroll, drafts and retained slot identity.
        if (ownerChanged && panel.inventoryExpanded && !inventoryNeedsExpansion(panel.layout()))
            panel.inventoryExpanded = false;
        boolean populated = panel.inventoryExpanded || panel.tool == EditorToolsPanel.Tool.LOOT;
        for (int slot = 0; slot < size && !populated; slot++)
            populated =
                    panel.fluidsMode
                            ? !panel.resourceFluids.get(slot).isEmpty()
                            : !panel.resourceInventory.get(slot).isEmpty();
        panel.sections.begin("contents", "section_contents", populated);
        panel.sections.contents();
        panel.action(
                panel.inventoryExpanded ? "inventory_dock" : "inventory_expand",
                panel::acceptInventoryWorkspaceToggle);
        panel.inventoryWorkspaceAction = panel.widgets.getLast();
        ((com.eynny.npships.client.editor.widget.EditorButton) panel.inventoryWorkspaceAction)
                .tooltip(
                        () ->
                                EditorShortcutDispatcher.hint(
                                        panel.tr("inventory_expand_hint"),
                                        EditorShortcutDispatcher.Action.WORKSPACE));
        panel.sections.workspaceAction(panel.widgets.getLast());
        int gridWidth =
                panel.inventoryExpanded
                        ? panel.layout().inventoryGrid().width()
                        : panel.contentWidth();
        int maxOffset = Math.max(0, totalWidth - gridWidth);
        panel.inventoryOffsetX = Math.min(panel.inventoryOffsetX, maxOffset);
        if (maxOffset > 0) {
            panel.widgets.add(
                    new EditorSlider(
                            panel.x,
                            panel.cursor,
                            panel.contentWidth(),
                            14,
                            Component.empty(),
                            panel.inventoryOffsetX / (double) maxOffset) {
                        protected void updateMessage() {}

                        @Override
                        public void renderWidget(
                                net.minecraft.client.gui.GuiGraphics g,
                                int mx,
                                int my,
                                float tick) {
                            int maximum = horizontalInventoryMaximum();
                            active = maximum > 0;
                            value = maximum == 0 ? 0 : panel.inventoryOffsetX / (double) maximum;
                            super.renderWidget(g, mx, my, tick);
                        }

                        protected void applyValue() {
                            panel.inventorySliding = true;
                            panel.inventoryOffsetX =
                                    (int) Math.round(value * horizontalInventoryMaximum());
                            panel.rebuild = true;
                        }
                    });
            panel.cursor += 18;
        }
        int gridHeight =
                panel.fluidsMode
                        ? ((size + columns - 1) / columns) * 18
                        : panel.resourceInventory.gridHeight();
        int visibleHeight =
                panel.inventoryExpanded ? panel.layout().inventoryGrid().height() : gridHeight;
        panel.inventoryOffsetY =
                Math.max(
                        0,
                        Math.min(panel.inventoryOffsetY, Math.max(0, gridHeight - visibleHeight)));
        if (panel.inventoryExpanded)
            panel.inventoryBar.configure(
                    panel.layout().inventoryGrid().right() - 1,
                    panel.layout().inventoryGrid().top(),
                    visibleHeight,
                    visibleHeight,
                    gridHeight,
                    panel.inventoryOffsetY,
                    next -> {
                        panel.inventoryOffsetY = next;
                        panel.build();
                    });
        panel.inventoryRowsY = panel.cursor;
        arrangeInventoryCells(size, columns, gridWidth, visibleHeight);
        panel.cursor +=
                panel.inventoryExpanded
                        ? 4
                        : (panel.fluidsMode
                                        ? ((size + columns - 1) / columns) * 18
                                        : panel.resourceInventory.gridHeight())
                                + 4;
        var slotLabel =
                new net.minecraft.client.gui.components.StringWidget(
                                panel.x,
                                panel.cursor,
                                panel.contentWidth(),
                                16,
                                panel.tr(
                                                panel.fluidsMode
                                                        ? panel.resourceFluids.label(
                                                                panel.resourceSlot)
                                                        : panel.resourceInventory.label(
                                                                panel.resourceSlot))
                                        .copy()
                                        .append(" #" + (panel.resourceSlot + 1)),
                                panel.mc.font)
                        .alignLeft();
        slotLabel.setTooltip(
                Tooltip.create(
                        slotLabel
                                .getMessage()
                                .copy()
                                .append("\n")
                                .append(panel.tr("slot_display_hint"))));
        panel.widgets.add(slotLabel);
        panel.cursor += 20;
        if (panel.fluidsMode) {
            int initial = panel.resourceFluids.get(panel.resourceSlot).getAmount();
            String[] amount = {"" + (initial == 0 ? 1000 : initial)};
            panel.itemCount = amount[0];
            if (!panel.resourceFluids.creative()) {
                panel.field(
                        "fluid_amount",
                        amount[0],
                        v -> {
                            amount[0] = v;
                            panel.itemCount = v;
                        });
                panel.defer(
                        () -> {
                            var inv = new SchematicFluids(panel.doc(), panel.pos(), panel.mc.level);
                            var stack = inv.get(panel.resourceSlot);
                            if (!stack.isEmpty() && Integer.parseInt(amount[0]) != initial) {
                                stack.setAmount(Integer.parseInt(amount[0]));
                                panel.commit(inv.put(panel.resourceSlot, stack));
                            }
                        });
            }
            panel.button(panel.resourceFluids.capacity(panel.resourceSlot) + " mB", () -> {});
        } else {
            var initial = panel.resourceInventory.get(panel.resourceSlot);
            String[] count = {"" + (initial.isEmpty() ? 1 : initial.getCount())};
            panel.itemCount = count[0];
            panel.field(
                    "resource_count",
                    count[0],
                    v -> {
                        count[0] = v;
                        panel.itemCount = v;
                    });
            panel.defer(
                    () -> {
                        if (initial.isEmpty() || Integer.parseInt(count[0]) == initial.getCount())
                            return;
                        var inv = SchematicResources.open(panel.doc(), panel.pos(), panel.mc.level);
                        panel.commit(
                                inv.put(
                                        panel.resourceSlot,
                                        inv.get(panel.resourceSlot)
                                                .copyWithCount(Integer.parseInt(count[0]))));
                    });
            final int selectedSlot = panel.resourceSlot;
            final var selectedTarget = panel.pos();
            panel.action(
                    "item_clear",
                    () ->
                            panel.commit(
                                    SchematicResources.open(
                                                    panel.doc(), selectedTarget, panel.mc.level)
                                            .put(selectedSlot, ItemStack.EMPTY)));
            if (!panel.sections.enabled()) resourceSettings();
        }
        panel.field(
                "search",
                panel.itemQuery,
                v -> {
                    panel.itemQuery = v;
                    panel.itemPage = 0;
                    panel.itemRemainder = 0;
                    panel.updateItemRows();
                });
        panel.itemRowsY = panel.cursor;
        panel.itemCells.clear();
        panel.updateItemRows();
        panel.cursor += 94;
        panel.wrappedNote("resource_hint");
        panel.sections.end();
        if (panel.sections.enabled() && !panel.fluidsMode) resourceSettings();
    }

    private void resourceSettings() {
        var index = panel.cached.blocks().get(panel.pos());
        if (index == null) return;
        var state =
                NbtUtils.readBlockState(
                        panel.mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                        panel.cached.paletteCopy().getCompound(index));
        BlockInspectorContributions.build(
                BlockInspectorContribution.Slot.RESOURCE_SETTINGS,
                panel,
                state,
                panel.cached.blockEntityData(panel.pos()),
                null);
    }

    private void arrangeInventoryCells(int size, int columns, int gridWidth, int visibleHeight) {
        for (int i = 0; i < size; i++) {
            int slot = i;
            int
                    bx =
                            (panel.inventoryExpanded
                                            ? panel.layout().inventoryGrid().left()
                                            : panel.x)
                                    - panel.inventoryOffsetX
                                    + (panel.fluidsMode
                                            ? i % columns * 18
                                            : panel.resourceInventory.slotX(i)),
                    by =
                            (panel.inventoryExpanded
                                            ? panel.layout().inventoryGrid().top()
                                                    - panel.inventoryOffsetY
                                            : panel.inventoryRowsY
                                                    - (panel.building ? 0 : panel.scroll))
                                    + (panel.fluidsMode
                                            ? i / columns * 18
                                            : panel.resourceInventory.slotY(i));
            int gridLeft =
                    panel.inventoryExpanded ? panel.layout().inventoryGrid().left() : panel.x;
            int gridTop =
                    panel.inventoryExpanded
                            ? panel.layout().inventoryGrid().top()
                            : panel.inventoryRowsY - (panel.building ? 0 : panel.scroll);
            if (bx < gridLeft
                    || bx + 18 > gridLeft + gridWidth
                    || by + 18 < gridTop
                    || by >= gridTop + visibleHeight) continue;
            AbstractWidget cell = retainedInventoryCells.get(slot);
            if (cell == null) {
                if (panel.fluidsMode)
                    panel.widgets.add(
                            panel.fluidCell(
                                    panel.resourceFluids.get(i),
                                    bx,
                                    by,
                                    () -> {
                                        panel.resourceSlot = slot;
                                        panel.build();
                                    },
                                    () ->
                                            panel.commit(
                                                    new SchematicFluids(
                                                                    panel.doc(),
                                                                    panel.pos(),
                                                                    panel.mc.level)
                                                            .put(
                                                                    slot,
                                                                    net.neoforged.neoforge.fluids
                                                                            .FluidStack.EMPTY)),
                                    () -> panel.resourceSlot == slot));
                else
                    panel.widgets.add(
                            panel.itemCell(
                                    panel.resourceInventory.get(i),
                                    bx,
                                    by,
                                    () -> {
                                        panel.resourceSlot = slot;
                                        panel.build();
                                    },
                                    () ->
                                            panel.commit(
                                                    SchematicResources.open(
                                                                    panel.doc(),
                                                                    panel.pos(),
                                                                    panel.mc.level)
                                                            .put(slot, ItemStack.EMPTY)),
                                    () -> panel.resourceSlot == slot));
                if (!panel.fluidsMode) {
                    var tip =
                            panel.tr(panel.resourceInventory.label(slot))
                                    .copy()
                                    .append(" #" + (slot + 1));
                    var stack = panel.resourceInventory.get(slot);
                    if (!stack.isEmpty())
                        tip.append("\n")
                                .append(stack.getHoverName())
                                .append(
                                        "\n"
                                                + net.minecraft.core.registries.BuiltInRegistries
                                                        .ITEM
                                                        .getKey(stack.getItem()));
                    panel.widgets
                            .getLast()
                            .setTooltip(net.minecraft.client.gui.components.Tooltip.create(tip));
                }
                cell = panel.widgets.removeLast();
                retainedInventoryCells.put(slot, cell);
            }
            cell.setX(bx);
            cell.setY(by);
            cell.setWidth(18);
            if (panel.inventoryExpanded) panel.inventoryCells.add(cell);
            else {
                panel.compactInventoryCells.add(cell);
                panel.widgets.add(cell);
            }
        }
    }

    private int horizontalInventoryMaximum() {
        return Math.max(
                0,
                inventoryTotalWidth
                        - (panel.inventoryExpanded
                                ? panel.layout().inventoryGrid().width()
                                : panel.contentWidth()));
    }

    boolean inventoryNeedsExpansion(EditorLayout next) {
        if (!inventoryLayout) return false;
        int size = panel.fluidsMode ? panel.resourceFluids.size() : panel.resourceInventory.size();
        int columns = panel.fluidsMode ? Math.min(9, size) : panel.resourceInventory.columns();
        int height =
                panel.fluidsMode
                        ? ((size + columns - 1) / columns) * 18
                        : panel.resourceInventory.gridHeight();
        // Compare the grid's own required extent, not its current scroll position or unrelated
        // inspector sections. The heading and native grid-to-details gap remain visible.
        int available =
                Math.max(
                        0,
                        next.inspectorContent().height()
                                - EditorInspectorSections.HEADER_HEIGHT
                                - 4);
        return inventoryTotalWidth > next.contentWidth() || height > available;
    }

    void layoutInventory(EditorLayout next) {
        if (!inventoryLayout) return;
        int size = panel.fluidsMode ? panel.resourceFluids.size() : panel.resourceInventory.size();
        if (size == 0) return;
        int columns = panel.fluidsMode ? Math.min(9, size) : panel.resourceInventory.columns();
        int gridHeight =
                panel.fluidsMode
                        ? ((size + columns - 1) / columns) * 18
                        : panel.resourceInventory.gridHeight();
        panel.inventoryOffsetX =
                Math.min(
                        panel.inventoryOffsetX,
                        Math.max(
                                0,
                                inventoryTotalWidth
                                        - (panel.inventoryExpanded
                                                ? next.inventoryGrid().width()
                                                : panel.contentWidth())));
        panel.inventoryOffsetY =
                Math.min(
                        panel.inventoryOffsetY,
                        Math.max(
                                0,
                                gridHeight
                                        - (panel.inventoryExpanded
                                                ? next.inventoryGrid().height()
                                                : gridHeight)));
        panel.inventoryCells.clear();
        panel.widgets.removeAll(panel.compactInventoryCells);
        panel.compactInventoryCells.clear();
        arrangeInventoryCells(
                size,
                columns,
                panel.inventoryExpanded ? next.inventoryGrid().width() : panel.contentWidth(),
                panel.inventoryExpanded ? next.inventoryGrid().height() : gridHeight);
        if (!panel.inventoryExpanded) return;
        panel.inventoryBar.configure(
                next.inventoryGrid().right() - 1,
                next.inventoryGrid().top(),
                next.inventoryGrid().height(),
                next.inventoryGrid().height(),
                gridHeight,
                panel.inventoryOffsetY,
                value -> {
                    panel.inventoryOffsetY = value;
                    layoutInventory(panel.layout());
                });
    }

    void updateItemRows() {
        panel.sections.forget(List.copyOf(panel.itemCells));
        panel.widgets.removeAll(panel.itemCells);
        panel.itemCells.clear();
        int columns = panel.quickColumns();
        String q = panel.itemQuery.toLowerCase(Locale.ROOT);
        if (panel.fluidsMode) {
            var fluids =
                    BuiltInRegistries.FLUID.stream()
                            .filter(
                                    f ->
                                            f != net.minecraft.world.level.material.Fluids.EMPTY
                                                    && f.defaultFluidState().isSource())
                            .map(f -> new net.neoforged.neoforge.fluids.FluidStack(f, 1000))
                            .filter(f -> panel.resourceFluids.accepts(panel.resourceSlot, f))
                            .filter(
                                    f ->
                                            BuiltInRegistries.FLUID
                                                            .getKey(f.getFluid())
                                                            .toString()
                                                            .contains(q)
                                                    || f.getHoverName()
                                                            .getString()
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains(q))
                            .toList();
            panel.itemMax = Math.max(0, (fluids.size() + columns - 1) / columns - 5);
            panel.itemPage = Math.min(panel.itemPage, panel.itemMax);
            for (int i = 0;
                    i < columns * 5
                            && panel.itemPage * columns + panel.itemRemainder + i < fluids.size();
                    i++) {
                var stack = fluids.get(panel.itemPage * columns + panel.itemRemainder + i);
                Runnable put =
                        () -> {
                            var inv = new SchematicFluids(panel.doc(), panel.pos(), panel.mc.level);
                            var chosen = stack.copy();
                            chosen.setAmount(
                                    inv.creative()
                                            ? inv.capacity(panel.resourceSlot)
                                            : Integer.parseInt(panel.itemCount));
                            panel.commit(inv.put(panel.resourceSlot, chosen));
                        };
                var b =
                        panel.fluidCell(
                                stack,
                                panel.x + i % columns * 18,
                                panel.itemRowsY
                                        + i / columns * 18
                                        - (panel.building ? 0 : panel.scroll),
                                put,
                                put);
                panel.itemCells.add(b);
                panel.widgets.add(b);
            }
            panel.sections.contentCells(panel.itemCells);
            return;
        }
        if (panel.resourceCatalog == null) {
            panel.resourcePresentations =
                    com.eynny.npships.authoring.patches.PatchItemPresentations.capture();
            panel.resourceCatalog =
                    EditorResourceCatalog.items(panel.mc.level, panel.resourcePresentations);
        }
        var stacks =
                panel.resourceCatalog.stream()
                        .filter(s -> panel.resourceInventory.accepts(panel.resourceSlot, s))
                        .filter(
                                s ->
                                        BuiltInRegistries.ITEM
                                                        .getKey(s.getItem())
                                                        .toString()
                                                        .contains(q)
                                                || EditorResourceCatalog.description(
                                                                s, panel.resourcePresentations)
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains(q))
                        .toList();
        panel.itemMax = Math.max(0, (stacks.size() + columns - 1) / columns - 5);
        panel.itemPage = Math.min(panel.itemPage, panel.itemMax);
        for (int i = 0;
                i < columns * 5
                        && panel.itemPage * columns + panel.itemRemainder + i < stacks.size();
                i++) {
            var stack = stacks.get(panel.itemPage * columns + panel.itemRemainder + i);
            Runnable put =
                    () -> {
                        var inv = SchematicResources.open(panel.doc(), panel.pos(), panel.mc.level);
                        int requested = Integer.parseInt(panel.itemCount);
                        if (requested < 1)
                            throw new IllegalArgumentException(
                                    panel.tr("invalid_count").getString());
                        int count = Math.min(requested, inv.limit(panel.resourceSlot, stack));
                        if (count < 1)
                            throw new IllegalArgumentException(
                                    panel.tr("invalid_count").getString());
                        panel.commit(inv.put(panel.resourceSlot, stack.copyWithCount(count)));
                    };
            var b =
                    panel.itemCell(
                            stack,
                            panel.x + i % columns * 18,
                            panel.itemRowsY
                                    + i / columns * 18
                                    - (panel.building ? 0 : panel.scroll),
                            put,
                            put);
            b.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    EditorResourceCatalog.description(
                                                    stack, panel.resourcePresentations)
                                            + "\n"
                                            + BuiltInRegistries.ITEM.getKey(stack.getItem()))));
            panel.itemCells.add(b);
            panel.widgets.add(b);
        }
        panel.sections.contentCells(panel.itemCells);
    }
}
