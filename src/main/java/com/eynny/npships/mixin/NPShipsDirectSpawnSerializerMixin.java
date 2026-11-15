package com.eynny.npships.mixin;

import com.eynny.npships.ship.DirectSpawnJournal;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

/** Stamp the native plot snapshot itself, independent of SavedData write ordering. */
@Mixin(value = SubLevelSerializer.class, remap = false)
public abstract class NPShipsDirectSpawnSerializerMixin {
    @Inject(method = "fullyLoad", at = @At("HEAD"), cancellable = true, remap = false)
    private static void npships$observeConstruction(
            ServerLevel level, SubLevelData saved, CallbackInfoReturnable<ServerSubLevel> cir) {
        if (!DirectSpawnJournal.loading(level, saved)) cir.setReturnValue(null);
    }

    @Inject(method = "fullyLoad", at = @At("RETURN"), remap = false)
    private static void npships$captureConstruction(
            ServerLevel level, SubLevelData saved, CallbackInfoReturnable<ServerSubLevel> cir) {
        DirectSpawnJournal.loadedRecord(level, saved, cir.getReturnValue());
    }

    @Inject(method = "toData", at = @At("RETURN"), remap = false)
    private static void npships$stampConstruction(
            ServerSubLevel body,
            List<UUID> dependencies,
            CallbackInfoReturnable<SubLevelData> cir) {
        DirectSpawnJournal.stamp(body, cir.getReturnValue());
    }
}
