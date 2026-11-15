package com.eynny.npships.authoring.document;

import java.util.ArrayDeque;

/** Bounded document history. Immutable snapshots include both NBT and companion JSON. */
public final class SchematicHistory {
    private static final int MAX_STEPS = 96;
    private static final long MAX_BLOCK_ENTRIES = 1_000_000;
    private final ArrayDeque<SchematicDocument> undo = new ArrayDeque<>();
    private final ArrayDeque<SchematicDocument> redo = new ArrayDeque<>();
    private long blockEntries;

    public void record(SchematicDocument previous) {
        for (var document : redo) blockEntries -= document.blocks().size();
        redo.clear();
        undo.addLast(previous);
        blockEntries += previous.blocks().size();
        trim(undo);
    }

    public boolean retains(SchematicDocument document) {
        return undo.contains(document) || redo.contains(document);
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public SchematicDocument undo(SchematicDocument current) {
        return move(undo, redo, current);
    }

    public SchematicDocument redo(SchematicDocument current) {
        return move(redo, undo, current);
    }

    private SchematicDocument move(
            ArrayDeque<SchematicDocument> from,
            ArrayDeque<SchematicDocument> to,
            SchematicDocument current) {
        if (from.isEmpty()) return current;
        var result = from.removeLast();
        blockEntries -= result.blocks().size();
        to.addLast(current);
        blockEntries += current.blocks().size();
        // Drop the most distant states first, preserving the next undo/redo step.
        trim(from.isEmpty() ? to : from);
        return result;
    }

    private void trim(ArrayDeque<SchematicDocument> distant) {
        while (undo.size() + redo.size() > MAX_STEPS
                || blockEntries > MAX_BLOCK_ENTRIES && undo.size() + redo.size() > 1) {
            if (distant.isEmpty()) distant = distant == undo ? redo : undo;
            if (distant.isEmpty()) break;
            blockEntries -= distant.removeFirst().blocks().size();
        }
    }
}
