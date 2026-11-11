package com.eynny.npships.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only native owner access for pre-placement entity readiness; never changes visibility. */
@Mixin(ServerLevel.class)
public interface NPShipsLevelEntityManagerAccessor {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> npships$entityManager();
}
