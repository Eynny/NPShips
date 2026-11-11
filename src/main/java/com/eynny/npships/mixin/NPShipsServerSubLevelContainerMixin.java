package com.eynny.npships.mixin;

import com.eynny.npships.lifecycle.NPShipsLifecycleManager;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerSubLevelContainer.class, remap = false)
public abstract class NPShipsServerSubLevelContainerMixin {

    /**
     * Observe bodies restored inside initialize(), using this container directly: it need not yet
     * be available through the level's global lookup.
     */
    @Inject(method = "initialize", at = @At("HEAD"), remap = false)
    private void npships$attachLifecycleManager(final CallbackInfo ci) {
        ServerSubLevelContainer container = (ServerSubLevelContainer) (Object) this;

        NPShipsLifecycleManager.attach(container);
    }

    @Inject(method = "initialize", at = @At("TAIL"), remap = false)
    private void npships$reconcileLifecycle(final CallbackInfo ci) {
        NPShipsLifecycleManager.reconcile((ServerSubLevelContainer) (Object) this);
    }
}
