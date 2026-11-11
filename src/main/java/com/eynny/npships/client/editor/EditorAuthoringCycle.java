package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.BlockAreaLayers;
import com.eynny.npships.authoring.document.SchematicDocument;

import org.joml.Matrix4f;

import java.util.List;

/** Session-only cycle intent, committed only after validated activation; no document ownership. */
final class EditorAuthoringCycle {
    record Context(
            SchematicDocument document,
            BlockAreaLayers.State layers,
            Matrix4f camera,
            int ceiling,
            int slice,
            int reach,
            boolean firstPerson) {
        Context {
            camera = new Matrix4f(camera);
        }
    }

    record Plan(
            Context context,
            List<EditorInspection.Target> targets,
            EditorInspection.Candidate candidate,
            int index,
            double x,
            double y) {}

    private Plan previous;

    void reset() {
        previous = null;
    }

    void pointer(double x, double y) {
        if (previous != null && Math.hypot(x - previous.x(), y - previous.y()) > 4) reset();
    }

    void context(Context current) {
        if (previous != null && !previous.context().equals(current)) reset();
    }

    Plan propose(Context context, List<EditorInspection.Candidate> candidates, double x, double y) {
        pointer(x, y);
        context(context);
        if (candidates.isEmpty()) {
            reset();
            return null;
        }
        var targets = candidates.stream().map(EditorInspection.Candidate::target).toList();
        boolean same = previous != null && previous.targets().equals(targets);
        int next = same ? (previous.index() + 1) % targets.size() : 0;
        return new Plan(
                context,
                targets,
                candidates.get(next),
                next,
                same ? previous.x() : x,
                same ? previous.y() : y);
    }

    void accepted(Plan plan) {
        previous = plan;
    }
}
