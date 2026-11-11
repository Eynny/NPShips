package com.eynny.npships.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Internal read-only status view; loading data and publishing tracking are distinct boundaries. */
@Mixin(PersistentEntitySectionManager.class)
public interface NPShipsEntityVisibilityAccessor {
    @Accessor("chunkVisibility")
    Long2ObjectMap<Visibility> npships$chunkVisibility();
}
