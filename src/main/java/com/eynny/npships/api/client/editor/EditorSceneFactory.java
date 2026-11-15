package com.eynny.npships.api.client.editor;

/**
 * Optional CLIENT scene capability of an existing registered patch provider. Return this factory
 * from the provider's capability method only on CLIENT; COMMON signatures must not eagerly link
 * CLIENT code. The host snapshots factories once per editor, applies the captured SERVER policy and
 * owns their callback, isolated-render and exactly-once normal close lifetimes.
 *
 * <p>A fresh contribution supplies authored visuals independently of the selected tab. The same
 * scene output participates in normal preview, read-only preview and PNG capture. The host calls
 * opened, document/resource invalidation, preview and close; it does not call layout, selected or
 * input. Scene sessions are read-only and cannot submit history edits or acquire tool/modal input.
 * Use the ordinary editor extension registration for interactive controls.
 *
 * <p>The host samples hasPreview after opened and document/resource invalidation, never by scanning
 * the registry every frame. Return false while no data applies. No native world ticking, retained
 * Screen/world reference, arbitrary shader compatibility or sandboxing of Java code is promised.
 */
@FunctionalInterface
public interface EditorSceneFactory {
    /**
     * Creates a fresh owner for this editor activation, without retaining a world or screen.
     *
     * @return non-null fresh scene contribution
     */
    EditorExtension create();
}
