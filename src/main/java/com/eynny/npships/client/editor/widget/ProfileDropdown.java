package com.eynny.npships.client.editor.widget;

import com.eynny.npships.authoring.io.ExportProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class ProfileDropdown extends EditorButton {
    private ExportProfile value;
    private boolean expanded;
    private final java.util.function.Consumer<String> changed;

    public ProfileDropdown(
            int x, int y, int width, String initial, java.util.function.Consumer<String> changed) {
        super(x, y, width, 20, ExportProfile.valueOf(initial).label(), b -> {}, DEFAULT_NARRATION);
        value = ExportProfile.valueOf(initial);
        this.changed = changed;
        selected(() -> expanded);
    }

    @Override
    public void onPress() {
        expanded = !expanded;
    }

    public boolean expanded() {
        return expanded;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (expanded && button == 0) {
            int row = (int) (y - getY() - 20) / 20;
            if (x >= getX()
                    && x < getX() + width
                    && y >= getY() + 20
                    && row >= 0
                    && row < ExportProfile.values().length) {
                value = ExportProfile.values()[row];
                setMessage(value.label());
                changed.accept(value.name());
                expanded = false;
                return true;
            }
            if (!isMouseOver(x, y)) expanded = false;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int x, int y, float tick) {
        super.renderWidget(g, x, y, tick);
        EditorButton.arrow(
                g, getX() + width - 8, getY() + height / 2, EditorButtonTheme.text(), false);
        if (expanded) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            int row = 0;
            g.fill(
                    getX(),
                    getY() + 18,
                    getX() + width,
                    getY() + 22 + 20 * ExportProfile.values().length,
                    EditorButtonTheme.popupEdge());
            for (var profile : ExportProfile.values()) {
                int top = getY() + 20 + 20 * row++;
                g.fill(
                        getX() + 1,
                        top,
                        getX() + width - 1,
                        top + 20,
                        x >= getX() && x < getX() + width && y >= top && y < top + 20
                                ? EditorButtonTheme.surface(0xff466078)
                                : EditorButtonTheme.surface(0xff253343));
                g.fill(
                        getX() + 1,
                        top + 19,
                        getX() + width - 1,
                        top + 20,
                        EditorButtonTheme.surface(0xff53657a));
                g.drawString(
                        Minecraft.getInstance().font,
                        profile.label(),
                        getX() + 5,
                        top + 6,
                        EditorButtonTheme.text(),
                        false);
            }
            g.pose().popPose();
        }
    }
}
