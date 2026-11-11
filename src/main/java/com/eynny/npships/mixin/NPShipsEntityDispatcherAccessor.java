package com.eynny.npships.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact restoration of native dispatcher ownership after a synchronous editor callback. */
@Mixin(EntityRenderDispatcher.class)
public interface NPShipsEntityDispatcherAccessor {
    @Accessor("level")
    Level npships$level();

    @Accessor("level")
    void npships$level(Level level);

    @Accessor("shouldRenderShadow")
    boolean npships$shadow();
}
