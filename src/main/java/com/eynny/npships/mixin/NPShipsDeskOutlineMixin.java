package com.eynny.npships.mixin;

import com.eynny.npships.client.editor.render.SchematicEditorOutline;
import com.eynny.npships.registry.NPShipsRegistry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keep native shape drawing and physical-owner transforms; change only the desk's line material.
 */
@Mixin(LevelRenderer.class)
public abstract class NPShipsDeskOutlineMixin {
    @WrapOperation(
            method = "renderLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void npships$deskOutline(
            LevelRenderer renderer,
            PoseStack pose,
            VertexConsumer lines,
            Entity viewer,
            double x,
            double y,
            double z,
            BlockPos pos,
            BlockState state,
            Operation<Void> original,
            @Local MultiBufferSource.BufferSource buffers) {
        original.call(
                renderer,
                pose,
                state.is(NPShipsRegistry.SCHEMATIC_EDITOR.get())
                        ? buffers.getBuffer(SchematicEditorOutline.type())
                        : lines,
                viewer,
                x,
                y,
                z,
                pos,
                state);
    }
}
