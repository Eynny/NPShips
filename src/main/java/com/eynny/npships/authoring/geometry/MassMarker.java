package com.eynny.npships.authoring.geometry;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Normalize in 3D: an axis viewed end-on must shrink on screen, never spin at full length. */
public final class MassMarker {
    private MassMarker() {}

    public static Vector3f axis(Matrix4f pose, int axis) {
        var direction = new Vector3f();
        direction.setComponent(axis, 1);
        pose.transformDirection(direction);
        float length = direction.length();
        return length < 1e-6f ? direction.zero() : direction.mul(6 / length);
    }
}
