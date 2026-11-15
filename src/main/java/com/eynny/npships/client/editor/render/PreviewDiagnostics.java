package com.eynny.npships.client.editor.render;

import com.mojang.logging.LogUtils;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Preview-owned failures, invalidated with their targets; only four representative details. */
public final class PreviewDiagnostics {
    private final Set<Object> targets = new HashSet<>();
    private final LinkedHashMap<Object, Component> examples = new LinkedHashMap<>();
    private List<Component> lines = List.of();
    private int reported;

    public boolean contains(Object target) {
        return targets.contains(target);
    }

    public void clear() {
        targets.clear();
        examples.clear();
        lines = List.of();
        // Logging remains bounded for the whole preview lifetime, including reload retries.
    }

    public void remove(Object target) {
        if (!targets.remove(target)) return;
        examples.remove(target);
        rebuild();
    }

    public void add(Object target, Component detail) {
        if (!targets.add(target)) return;
        if (examples.size() < 4) examples.put(target, detail);
        if (reported++ < 4) LogUtils.getLogger().warn("[NPShips preview] {}", detail.getString());
        else if (reported == 5)
            LogUtils.getLogger()
                    .warn("[NPShips preview] Further details suppressed in this preview");
        rebuild();
    }

    private void rebuild() {
        var result = new ArrayList<>(examples.values());
        if (targets.size() > examples.size())
            result.add(
                    Component.translatable(
                            "npships.editor.preview_more", targets.size() - examples.size()));
        lines = List.copyOf(result);
    }

    public List<Component> lines() {
        return lines;
    }

    public static String bounded(Object value, int limit) {
        String text = String.valueOf(value);
        if (text.length() > limit) text = text.substring(0, limit) + "...";
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    public static String cause(Throwable failure) {
        rethrowFatal(failure);
        Throwable cause = failure;
        for (int i = 0; i < 16 && cause.getCause() != null && cause.getCause() != cause; i++)
            cause = cause.getCause();
        return bounded(cause.getClass().getSimpleName() + ": " + cause.getMessage(), 320);
    }

    private static void rethrowFatal(Throwable failure) {
        for (int i = 0; failure != null && i < 32; i++, failure = failure.getCause()) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (failure.getCause() == failure) break;
        }
    }
}
