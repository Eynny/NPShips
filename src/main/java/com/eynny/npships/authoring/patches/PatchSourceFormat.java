package com.eynny.npships.authoring.patches;

/**
 * Internal pure source grammar capability. Unlike native execution it must remain available for
 * lossless read/validation when an optional mod is absent. Implementations must not load optional
 * mod classes, inspect a world, acquire resources or perform restoration in these pure methods.
 * Mutation/execution methods of derived contracts require separate policy/availability admission.
 */
public interface PatchSourceFormat {}
