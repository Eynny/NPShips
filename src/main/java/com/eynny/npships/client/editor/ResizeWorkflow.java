package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicReframe;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorScrollBar;
import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/** A transaction over all affected variants; cancellation never changes the parent document. */
final class ResizeWorkflow extends Screen {
    private final SchematicEditorScreen parent;
    private final SchematicReframe.Proposal proposal;
    private SchematicDocument candidate;
    private final EditorScrollBar listBar = new EditorScrollBar();
    private int scroll;

    private ResizeWorkflow(SchematicEditorScreen parent, SchematicReframe.Proposal proposal) {
        super(Component.translatable("npships.tools.resize_confirm"));
        this.parent = parent;
        this.proposal = proposal;
        candidate = proposal.result();
    }

    static void open(SchematicEditorScreen parent, SchematicReframe.Proposal proposal) {
        Minecraft.getInstance().setScreen(new ResizeWorkflow(parent, proposal));
    }

    @Override
    protected void init() {
        scroll = Math.clamp(scroll, 0, scrollMaximum());
        int w = (width - 32) / 3;
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("npships.tools.resize_auto"),
                                b -> parent.acceptResize(candidate))
                        .bounds(8, height - 30, w, 20)
                        .build());
        addRenderableWidget(
                                EditorButton.flat(
                                                Component.translatable(
                                                        "npships.tools.resize_review"),
                                                b -> review(0))
                                        .bounds(16 + w, height - 30, w, 20)
                                        .build())
                        .active =
                !proposal.reviews().isEmpty();
        addRenderableWidget(
                EditorButton.flat(Component.translatable("gui.cancel"), b -> onClose())
                        .bounds(24 + w * 2, height - 30, w, 20)
                        .build());
    }

    private void review(int index) {
        if (index >= proposal.reviews().size()) {
            parent.acceptResize(candidate);
            return;
        }
        var original = proposal.reviews().get(index);
        var item =
                new SchematicReframe.Review(
                        original.id(),
                        (index + 1) + " / " + proposal.reviews().size() + " - " + original.label(),
                        original.before(),
                        original.origin(),
                        original.size());
        parent.reviewResize(
                item,
                edited -> {
                    var resized = SchematicReframe.plain(edited, item.origin(), item.size());
                    var tag = candidate.nbtCopy();
                    tag.getCompound(StructureSegments.VARIANTS).put(item.id(), resized.nbtCopy());
                    var c = candidate.configCopy();
                    for (var s : c.getAsJsonArray("segments"))
                        if (s.getAsJsonObject().has("pool"))
                            for (var v : s.getAsJsonObject().getAsJsonArray("pool")) {
                                var entry = v.getAsJsonObject();
                                if (!entry.get("variant").getAsString().equals(item.id())) continue;
                                entry.remove(LootAssignments.KEY);
                                for (var loot : LootAssignments.read(resized.configCopy())) {
                                    var updated =
                                            LootAssignments.assign(
                                                    entry, loot.pos(), loot.table(), loot.rolls());
                                    for (var e : updated.entrySet())
                                        entry.add(e.getKey(), e.getValue());
                                }
                            }
                    for (var table : EmbeddedLoot.read(resized.configCopy()).entrySet())
                        c =
                                EmbeddedLoot.assign(
                                        c, table.getKey(), table.getValue().getAsJsonObject());
                    StructureSegments.validate(tag, StructureSegments.read(c));
                    candidate = new SchematicDocument(tag, c);
                    review(index + 1);
                },
                parent::cancelResize);
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float t) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float t) {
        g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        g.drawCenteredString(font, title, width / 2, 12, EditorButtonTheme.text());
        g.enableScissor(8, 32, width - 8, height - 38);
        int at = 36 - scroll;
        for (var line :
                font.split(Component.translatable("npships.tools.resize_warning"), width - 24)) {
            g.drawString(font, line, 12, at, 0xffcf80);
            at += 12;
        }
        for (String change : proposal.changes())
            for (var line : font.split(Component.literal(change), width - 24)) {
                g.drawString(font, line, 12, at, EditorButtonTheme.text());
                at += 12;
            }
        g.disableScissor();
        int total = at + scroll - 36;
        listBar.configure(width - 8, 32, height - 70, height - 70, total, scroll, v -> scroll = v);
        listBar.render(g);
        super.render(g, x, y, t);
    }

    private int scrollMaximum() {
        int lines =
                font.split(Component.translatable("npships.tools.resize_warning"), width - 24)
                        .size();
        for (String change : proposal.changes())
            lines += font.split(Component.literal(change), width - 24).size();
        return Math.max(0, lines * 12 - Math.max(0, height - 70));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        scroll = (int) Math.clamp(scroll - v * 24, 0, scrollMaximum());
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return button == 0 && listBar.drag(y) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        listBar.release();
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return listBar.click(x, y, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        parent.cancelResize();
    }
}
