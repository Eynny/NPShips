package com.eynny.npships.mixin;

import com.eynny.npships.lifecycle.NPShipsEntityCleaner;
import com.eynny.npships.lifecycle.NPShipsLifecycleManager;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.UUID;

/** Observe native holding transitions and cancel loading of expired tracked structures. */
@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class NPShipsSableMixin {

    @Shadow @Final private ServerLevel level;

    /** Native UUID-to-holding-object lookup; foreign structures remain untouched. */
    @Shadow @Final private Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels;

    /** Currently loaded holding chunks. */
    @Shadow @Final private Long2ObjectMap<SubLevelHoldingChunk> loadedHoldingChunks;

    /** Native private lookup; false preserves the no-create contract. */
    @Invoker("getOrLoadHoldingChunk")
    public abstract SubLevelHoldingChunk npships$getOrLoadHoldingChunk(
            ChunkPos chunkPos, boolean create);

    /** Mark changes to the native pointer list for persistence. */
    @Invoker("setDirty")
    public abstract void npships$setDirty(ChunkPos chunkPos);

    /** Native storage owner. */
    @Shadow
    public abstract SubLevelStorage getStorage();

    /** Observe the explicit unload route as well as the normal processUnload path below. */
    @Inject(method = "moveToUnloaded", at = @At("HEAD"), remap = false)
    private void npships$onMoveToUnloaded(
            final ServerSubLevel subLevel, final ChunkPos pos, final CallbackInfo ci) {

        final UUID uuid = subLevel.getUniqueId();

        if (!NPShipsLifecycleManager.isTracked(this.level, uuid)) {
            return;
        }

        NPShipsLifecycleManager.markUnloaded(this.level, uuid);
    }

    /** The normal unload route can bypass moveToUnloaded and early lifecycle observers. */
    @Redirect(
            method = "processUnload",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;"
                                            + "removeSubLevel("
                                            + "Ldev/ryanhcode/sable/sublevel/SubLevel;"
                                            + "Ldev/ryanhcode/sable/sublevel/storage/SubLevelRemovalReason;"
                                            + ")V"),
            remap = false)
    private void npships$onProcessUnloadRemove(
            final SubLevelContainer container,
            final SubLevel subLevel,
            final SubLevelRemovalReason reason) {

        if (reason == SubLevelRemovalReason.UNLOADED
                && subLevel instanceof ServerSubLevel serverSubLevel) {

            final UUID uuid = serverSubLevel.getUniqueId();

            if (NPShipsLifecycleManager.isTracked(this.level, uuid)) {

                NPShipsLifecycleManager.markUnloaded(this.level, uuid);
            }
        }

        container.removeSubLevel(subLevel, reason);
    }

    /** Cancel before fullyLoad: expired tracked objects must not be recreated. */
    @Inject(method = "loadHoldingSubLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private void npships$preventExpiredLoad(
            final HoldingSubLevel holdingSubLevel, final CallbackInfo ci) {

        final UUID uuid = holdingSubLevel.data().uuid();

        if (!NPShipsLifecycleManager.isTracked(this.level, uuid)) {
            return;
        }

        // Existing unload deadlines survive startup reconciliation.
        if (!NPShipsLifecycleManager.isExpired(this.level, uuid)) {
            return;
        }

        // The cleaner preserves players while removing ordinary entities in serialized bounds.
        com.eynny.npships.lifecycle.NPShipsRemovalDiagnostics.log(
                this.level,
                uuid,
                "HOLDING_DELETE_REQUEST",
                "NPShips expired unload deadline="
                        + NPShipsLifecycleManager.getUnloadDeadline(this.level, uuid));
        NPShipsEntityCleaner.deleteEntitiesInBounds(this.level, holdingSubLevel.data().bounds());

        this.allHoldingSubLevels.remove(uuid);

        final GlobalSavedSubLevelPointer pointer = holdingSubLevel.pointer();

        if (pointer != null) {

            final ChunkPos chunkPos = pointer.chunkPos();

            final SubLevelHoldingChunk holdingChunk =
                    this.npships$getOrLoadHoldingChunk(chunkPos, false);

            if (holdingChunk != null) {

                // This is the native values view: Iterator.remove updates the owning map.
                final Iterator<HoldingSubLevel> iterator =
                        holdingChunk.getLoadedHoldingSubLevels().iterator();

                while (iterator.hasNext()) {

                    final HoldingSubLevel current = iterator.next();

                    if (current.data().uuid().equals(uuid)) {

                        iterator.remove();
                        break;
                    }
                }

                holdingChunk.getSubLevelPointers().remove(pointer.local());

                // Persist the pointer removal so the next save cannot restore it.
                this.npships$setDirty(chunkPos);
            }

            /*
             * Installed Sable logs IO failures without a success result. Returning here
             * proves submission, not durable deletion.
             */
            this.getStorage().attemptSaveSubLevel(pointer, null);
        }

        // Forget tracking directly; remove() is reserved for deleting a loaded physical object.
        String removedSource =
                com.eynny.npships.lifecycle.NPShipsSavedData.get(this.level).source(uuid);
        long removedDeadline = NPShipsLifecycleManager.getUnloadDeadline(this.level, uuid);
        NPShipsLifecycleManager.forget(this.level, uuid);
        if (pointer != null)
            NPShipsLifecycleManager.holdingDeletionSubmitted(
                    this.level, uuid, removedSource, removedDeadline);
        com.eynny.npships.lifecycle.NPShipsRemovalDiagnostics.log(
                this.level,
                uuid,
                "HOLDING_DELETE_SUBMITTED",
                "NPShips removed holding references and submitted storage deletion; reload cancelled");

        ci.cancel();
    }
}
