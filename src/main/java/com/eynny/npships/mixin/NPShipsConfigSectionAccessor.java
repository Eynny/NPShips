package com.eynny.npships.mixin;

import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Identify NPShips-owned native configuration fields without retaining a screen. */
@Mixin(ConfigurationSectionScreen.class)
public interface NPShipsConfigSectionAccessor {
    @Accessor("context")
    ConfigurationSectionScreen.Context npships$context();
}
