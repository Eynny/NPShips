package com.eynny.npships.client.editor.render;

import com.eynny.npships.mixin.NPShipsEntityDispatcherAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Per-preview native dispatcher context; no mutation of Minecraft's real world camera. */
public final class PreviewCameraContext implements AutoCloseable {
    private final LocalCamera local = new LocalCamera();
    private final Matrix4f view = new Matrix4f(), inverse = new Matrix4f();
    private final Quaternionf rotation = new Quaternionf();
    private final Vector3f angles = new Vector3f(), scale = new Vector3f();
    private final Vector4f eye = new Vector4f();
    private EntityRenderDispatcher entities;
    private BlockEntityRenderDispatcher blocks;
    private Level entityLevel, blockLevel;
    private Camera entityCamera, blockCamera;
    private Quaternionf entityOrientation;
    private Entity picked;
    private HitResult hit;
    private boolean shadow;

    public PreviewCameraContext open(Level level, PoseStack pose) {
        return open(level, pose, Vec3.ZERO);
    }

    public PreviewCameraContext open(Level level, PoseStack pose, Vec3 offset) {
        if (entities != null) throw new IllegalStateException("Reentrant preview camera scope");
        view.set(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
        view.translate((float) offset.x, (float) offset.y, (float) offset.z);
        inverse.set(RenderSystem.getProjectionMatrix()).mul(view).invert();
        inverse.transform(eye.set(0, 0, 1, 0));
        // A projective eye is a finite homogeneous point; an orthographic camera uses
        // the centre of its near clipping plane as its finite distance reference.
        if (Math.abs(eye.w) < 1e-6f) inverse.transform(eye.set(0, 0, -1, 1));
        eye.div(eye.w);
        if (!eye.isFinite()) throw new IllegalArgumentException("Invalid preview camera matrix");
        view.getScale(scale);
        view.scale(1 / scale.x, 1 / scale.y, 1 / scale.z);
        // The editor GUI mirrors Y. Keep that reflection in the caller transform,
        // not in the proper rotation supplied to native billboard renderers.
        if (view.determinant3x3() < 0) view.m01(-view.m01()).m11(-view.m11()).m21(-view.m21());
        view.getNormalizedRotation(rotation).conjugate().normalize();
        rotation.getEulerAnglesYXZ(angles);
        local.configure(level, new Vec3(eye.x, eye.y, eye.z), angles);
        var mc = Minecraft.getInstance();
        var ed = mc.getEntityRenderDispatcher();
        var bd = mc.getBlockEntityRenderDispatcher();
        entityLevel = ((NPShipsEntityDispatcherAccessor) ed).npships$level();
        shadow = ((NPShipsEntityDispatcherAccessor) ed).npships$shadow();
        entityCamera = ed.camera;
        entityOrientation = ed.cameraOrientation();
        picked = ed.crosshairPickEntity;
        blockLevel = bd.level;
        blockCamera = bd.camera;
        hit = bd.cameraHitResult;
        entities = ed;
        blocks = bd;
        try {
            ed.prepare(level, local, null);
            // Catnip's dummy chunks contain VOID_AIR. Native shadow projection cannot
            // read the schematic floor and would allocate an empty chunk for each sample.
            ed.setRenderShadow(false);
            bd.prepare(level, local, null);
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return this;
    }

    @Override
    public void close() {
        if (entities == null) return;
        ((NPShipsEntityDispatcherAccessor) entities).npships$level(entityLevel);
        entities.camera = entityCamera;
        entities.overrideCameraOrientation(entityOrientation);
        entities.crosshairPickEntity = picked;
        entities.setRenderShadow(shadow);
        blocks.level = blockLevel;
        blocks.camera = blockCamera;
        blocks.cameraHitResult = hit;
        entities = null;
        blocks = null;
        entityLevel = blockLevel = null;
        entityCamera = blockCamera = null;
        entityOrientation = null;
        picked = null;
        hit = null;
    }

    public void release() {
        close();
        local.anchor = null;
    }

    private static final class LocalCamera extends Camera {
        private Entity anchor;

        void configure(Level level, Vec3 eye, Vector3f rotation) {
            if (anchor == null || anchor.level() != level) anchor = EntityType.MARKER.create(level);
            if (anchor == null)
                throw new IllegalStateException("Native preview camera anchor unavailable");
            anchor.setPos(eye);
            setPosition(eye);
            float degrees = 180 / (float) Math.PI;
            setRotation(180 - rotation.y * degrees, -rotation.x * degrees, -rotation.z * degrees);
        }

        @Override
        public Entity getEntity() {
            return anchor;
        }

        @Override
        public boolean isInitialized() {
            return anchor != null;
        }

        @Override
        public boolean isDetached() {
            return true;
        }

        @Override
        public BlockState getBlockAtCamera() {
            return anchor.level().getBlockState(BlockPos.containing(getPosition()));
        }
    }
}
