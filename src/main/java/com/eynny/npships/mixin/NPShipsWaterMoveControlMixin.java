package com.eynny.npships.mixin;

import com.eynny.npships.integration.sable.SableNavigationFix;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/**
 * A land JUMPING latch waits for onGround and cannot complete while swimming. Let the native setter
 * accept its next explicit water-route intent, without touching velocity.
 */
@Mixin(MoveControl.class)
public abstract class NPShipsWaterMoveControlMixin {
    @Shadow @Final protected Mob mob;

    @Coerce
    @ModifyExpressionValue(
            method = "setWantedPosition",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/world/entity/ai/control/MoveControl;operation:Lnet/minecraft/world/entity/ai/control/MoveControl$Operation;",
                            opcode = org.objectweb.asm.Opcodes.GETFIELD),
            require = 0)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object npships$fluidIntent(@Coerce Object operation) {
        if (operation instanceof Enum<?> value
                && value.name().equals("JUMPING")
                && SableNavigationFix.acceptWaterIntent(mob)) {
            try {
                return Enum.valueOf((Class) value.getDeclaringClass(), "MOVE_TO");
            } catch (IllegalArgumentException changedNativeContract) {
                return operation;
            }
        }
        return operation;
    }
}
