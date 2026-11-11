package com.eynny.npships.client.editor;

import com.eynny.npships.mixin.NPShipsMultilineAccessor;
import com.eynny.npships.mixin.NPShipsMultilineWidthAccessor;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;

/** Retained row geometry; changing bounds never invokes widget value/edit callbacks. */
final class EditorWidgetArrangement {
    private EditorWidgetArrangement() {}

    enum Horizontal {
        AUTOMATIC,
        FILL,
        RIGHT
    }

    static void move(
            AbstractWidget widget,
            EditorLayout.Rect oldBounds,
            EditorLayout.Rect newBounds,
            int verticalOffset,
            boolean flexible) {
        arrange(
                widget,
                new EditorLayout.Rect(
                        widget.getX(),
                        widget.getY(),
                        widget.getX() + widget.getWidth(),
                        widget.getY() + widget.getHeight()),
                oldBounds,
                newBounds,
                verticalOffset,
                flexible,
                padding(widget),
                Horizontal.AUTOMATIC);
    }

    record Binding(
            AbstractWidget widget,
            EditorLayout.Rect bounds,
            EditorLayout.Rect owner,
            int textPadding,
            Horizontal horizontal) {
        static Binding capture(AbstractWidget widget, EditorLayout.Rect owner, int scroll) {
            return capture(widget, owner, scroll, Horizontal.AUTOMATIC);
        }

        static Binding capture(
                AbstractWidget widget, EditorLayout.Rect owner, int scroll, Horizontal horizontal) {
            return new Binding(
                    widget,
                    new EditorLayout.Rect(
                            widget.getX(),
                            widget.getY() + scroll,
                            widget.getX() + widget.getWidth(),
                            widget.getY() + scroll + widget.getHeight()),
                    owner,
                    padding(widget),
                    horizontal);
        }

        void apply(EditorLayout.Rect next, int offset, boolean flexible) {
            arrange(widget, bounds, owner, next, offset, flexible, textPadding, horizontal);
        }
    }

    private static int padding(AbstractWidget widget) {
        return widget instanceof MultiLineEditBox box
                ? widget.getWidth()
                        - ((NPShipsMultilineWidthAccessor)
                                        (Object)
                                                ((NPShipsMultilineAccessor) (Object) box)
                                                        .npships$textField())
                                .npships$width()
                : 0;
    }

    private static void arrange(
            AbstractWidget widget,
            EditorLayout.Rect bounds,
            EditorLayout.Rect oldBounds,
            EditorLayout.Rect newBounds,
            int verticalOffset,
            boolean flexible,
            int textPadding,
            Horizontal horizontal) {
        int offset = bounds.left() - oldBounds.left();
        int width = bounds.width();
        int gap = oldBounds.right() - bounds.left() - width;
        int nextX = newBounds.left() + offset;
        int nextWidth = width;
        if (horizontal == Horizontal.RIGHT) nextX = newBounds.right() - gap - width;
        else if (horizontal == Horizontal.FILL) nextWidth = newBounds.right() - gap - nextX;
        else if (offset > 0 && gap >= 0 && gap <= 16 && (!flexible || width <= 24))
            nextX = newBounds.right() - gap - width;
        else if (flexible && width > 24) {
            if (offset == 0 && gap <= 16) nextWidth = newBounds.width() - gap;
            else if (gap >= 0 && EditorLayout.contentWidth(oldBounds.width()) > 0) {
                double scale =
                        (double) EditorLayout.contentWidth(newBounds.width())
                                / EditorLayout.contentWidth(oldBounds.width());
                nextX = newBounds.left() + (int) Math.round(offset * scale);
                nextWidth = (int) Math.round(width * scale);
            }
        }
        int nextY = bounds.top() + newBounds.top() - oldBounds.top() + verticalOffset;
        if (widget instanceof LuaEditBox text)
            text.bounds(nextX, nextY, Math.max(1, nextWidth), bounds.height());
        else {
            if (widget instanceof MultiLineEditBox multiline && widget.getWidth() != nextWidth) {
                var text =
                        (NPShipsMultilineWidthAccessor)
                                (Object)
                                        ((NPShipsMultilineAccessor) (Object) multiline)
                                                .npships$textField();
                text.npships$width(Math.max(24, nextWidth - textPadding));
                text.npships$reflow();
            }
            widget.setX(nextX);
            widget.setY(nextY);
            widget.setWidth(Math.max(1, nextWidth));
        }
    }
}
