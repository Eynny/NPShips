package com.eynny.npships.mixin;

import com.eynny.npships.ship.EntityPlacementScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/** Keep native placement; repair known painting anchors only inside our active placement scope. */
@Mixin(StructureTemplate.class)
public abstract class NPShipsEntityFactoryMixin {
    @WrapOperation(
            method = "createEntityIgnoreException",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;"),
            require = 1)
    private static Optional<Entity> npships$entity(
            CompoundTag nbt, Level level, Operation<Optional<Entity>> original) {
        return EntityPlacementScope.create(nbt, level, copy -> original.call(copy, level));
    }

    @WrapOperation(
            method = "*",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"),
            require = 1,
            allow = 1)
    private static void npships$attachment(
            net.minecraft.world.level.ServerLevelAccessor world,
            Entity entity,
            Operation<Void> original) {
        EntityPlacementScope.beforeInsertion(entity);
        original.call(world, entity);
    }
}
