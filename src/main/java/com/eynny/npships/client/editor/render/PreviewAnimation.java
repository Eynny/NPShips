package com.eynny.npships.client.editor.render;

/** Render-thread scope: native renderers keep their saved pose but not a running clock. */
public final class PreviewAnimation implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private PreviewAnimation() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static PreviewAnimation freeze() {
        return new PreviewAnimation();
    }

    public static boolean frozen() {
        return DEPTH.get() > 0;
    }

    @Override
    public void close() {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) DEPTH.remove();
        else DEPTH.set(depth);
    }
}
