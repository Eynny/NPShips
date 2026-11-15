package com.eynny.npships.mixin;

import com.eynny.npships.config.WorldRuntimeConfig;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge's ServerAboutToStartEvent fires after SERVER config loading; the file must exist first.
 */
@Mixin(value = ServerLifecycleHooks.class, remap = false)
public abstract class NPShipsWorldConfigMixin {
    @Inject(method = "handleServerAboutToStart", at = @At("HEAD"), require = 1)
    private static void npships$worldConfiguration(MinecraftServer server, CallbackInfo ci) {
        WorldRuntimeConfig.initialize(server);
    }
}
