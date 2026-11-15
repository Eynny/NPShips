package com.eynny.npships.block;

import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.lifecycle.NPShipsSavedData;
import com.eynny.npships.network.ClaimNetworking;
import com.eynny.npships.registry.NPShipsRegistry;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Cached display state only; the containing physical body's lifecycle record owns retention. */
public final class ClaimBlockEntity extends BlockEntity {
    public static final int IDLE = 0, STAMP = 1, BLINK = 2;
    private int animation;
    private long started;
    private UUID pendingBody;
    private boolean retained, physical, denied;
    private boolean hostilesDirty;
    private ServerSubLevel cachedBody;
    private boolean statusDirty = true;
    // Server-thread-only weak registrations: never keep unloaded blocks/worlds alive.
    private static final java.util.Set<ClaimBlockEntity> LOADED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Server-thread movement observers need no native tracking state without any claims. */
    public static boolean hasLoadedClaims() {
        return !LOADED.isEmpty();
    }

    /**
     * Relevance for a native transfer, using existing loaded registrations rather than a world
     * scan.
     */
    public static boolean observesTransfer(
            ServerLevel level,
            dev.ryanhcode.sable.sublevel.SubLevel source,
            dev.ryanhcode.sable.sublevel.SubLevel target) {
        for (var be : LOADED) {
            if (be.isRemoved() || be.level != level) continue;
            var found = Sable.HELPER.getContaining(level, be.worldPosition);
            if ((found != source && found != target)
                    || !(found instanceof ServerSubLevel body)
                    || !NativeBlockAccess.current(level, body)) continue;
            var chunk =
                    NativeBlockAccess.chunk(
                            level,
                            body,
                            be.worldPosition.getX() >> 4,
                            be.worldPosition.getZ() >> 4);
            if (chunk.getBlockEntities().get(be.worldPosition) == be
                    && be.getType().isValid(chunk.getBlockState(be.worldPosition))) return true;
        }
        return false;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        invalidateStatus();
    }

    public void invalidateStatus() {
        if (!(level instanceof ServerLevel)) return;
        LOADED.add(this);
        statusDirty = true;
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel) LOADED.remove(this);
        cachedBody = null;
        super.setRemoved();
    }

    public static void retentionChanged(NPShipsSavedData data, UUID id) {
        for (var be : LOADED) {
            if (!be.isRemoved()
                    && be.cachedBody != null
                    && be.cachedBody.getUniqueId().equals(id)
                    && be.level instanceof ServerLevel server
                    && NPShipsSavedData.peek(server) == data) be.statusDirty = true;
        }
    }

    public static void hostileChanged(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.Mob mob)
                || !(mob instanceof net.minecraft.world.entity.monster.Enemy)
                || !(mob.level() instanceof ServerLevel)) return;
        for (var be : LOADED) {
            if (!be.isRemoved()
                    && !be.retained
                    && be.cachedBody != null
                    && be.level == mob.level()
                    && ClaimHostiles.belongs(be.cachedBody, mob)) be.hostilesDirty = true;
        }
    }

    public static void hostileMoved(
            net.minecraft.world.entity.Entity entity,
            Vec3 before,
            dev.ryanhcode.sable.sublevel.SubLevel previousBody) {
        if (!(entity instanceof net.minecraft.world.entity.Mob mob)
                || !(mob instanceof net.minecraft.world.entity.monster.Enemy)
                || !(mob.level() instanceof ServerLevel)) return;
        if (LOADED.isEmpty()) return;
        var currentBody = Sable.HELPER.getTrackingOrVehicleSubLevel(mob);
        if (previousBody == currentBody && BlockPos.containing(before).equals(mob.blockPosition()))
            return;
        ServerSubLevel checkedBody = null;
        boolean changed = false;
        for (var be : LOADED) {
            if (be.isRemoved() || be.retained || be.cachedBody == null || be.level != mob.level())
                continue;
            if (be.cachedBody != checkedBody) {
                checkedBody = be.cachedBody;
                // Adjacent registrations may observe the same body. Reuse only within
                // this notification; never cache a pose or membership across movement.
                boolean was = ClaimHostiles.belongsAt(checkedBody, before, previousBody);
                boolean now = ClaimHostiles.belongsAt(checkedBody, mob.position(), currentBody);
                changed = was != now;
            }
            if (changed) be.hostilesDirty = true;
        }
    }

    public static void hostileTrackingChanged(
            net.minecraft.world.entity.Entity entity,
            dev.ryanhcode.sable.sublevel.SubLevel previous,
            dev.ryanhcode.sable.sublevel.SubLevel next) {
        if (previous == next
                || !(entity instanceof net.minecraft.world.entity.Mob)
                || !(entity instanceof net.minecraft.world.entity.monster.Enemy)
                || !(entity.level() instanceof ServerLevel)) return;
        for (var be : LOADED)
            if (!be.isRemoved()
                    && !be.retained
                    && be.level == entity.level()
                    && be.cachedBody != null
                    && (be.cachedBody == previous || be.cachedBody == next))
                be.hostilesDirty = true;
    }

    private void resolveStatus() {
        invalidateStatus();
        cachedBody = body();
        statusDirty = false;
        hostilesDirty = false;
        refresh(cachedBody);
    }

    public boolean denied() {
        return denied;
    }

    public ClaimBlockEntity(BlockPos pos, BlockState state) {
        super(NPShipsRegistry.CLAIM_BLOCK_ENTITY.get(), pos, state);
    }

    public int animation() {
        return animation;
    }

    public long started() {
        return started;
    }

    public boolean retained() {
        return retained;
    }

    public boolean physical() {
        return physical;
    }

    private ServerSubLevel body() {
        var found = Sable.HELPER.getContaining(level, worldPosition);
        return level instanceof ServerLevel server
                        && found instanceof ServerSubLevel body
                        && NativeBlockAccess.current(server, body)
                        && NativeBlockAccess.at(server, worldPosition) == this
                ? body
                : null;
    }

    public void use(Player player) {
        if (!(level instanceof ServerLevel) || player.isSpectator()) return;
        resolveStatus();
        var body = cachedBody;
        if (body == null) return;
        if (animation == STAMP) return;
        started = level.getGameTime();
        if (retained || denied) {
            animation = BLINK; // A repeated click restarts the complete two-blink confirmation.
        } else {
            animation = STAMP;
            pendingBody = body.getUniqueId();
            // Functional retention ends now. Animation/network/sound are feedback, not commit.
            NPShipsLifecycleManager.untrack((ServerLevel) level, pendingBody);
            if (isRemoved() || body.isRemoved()) {
                animation = IDLE;
                pendingBody = null;
                return;
            }
            refresh(body);
            ClaimNetworking.send(
                    body,
                    ClaimBlock.support(worldPosition, getBlockState()),
                    ClaimNetworking.PREPARE);
        }
        sync();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ClaimBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        if (be.statusDirty || be.cachedBody != null && be.cachedBody.isRemoved())
            be.resolveStatus();
        var body = be.cachedBody;
        if (be.hostilesDirty && body != null && !be.retained) be.scanHostiles(body);
        if (be.animation == IDLE) return;
        long elapsed = level.getGameTime() - be.started;
        if (be.animation == STAMP && be.pendingBody != null) {
            if (elapsed >= ClaimAnimation.IMPACT) {
                be.resolveStatus();
                body = be.cachedBody;
            }
            if (body == null || !body.getUniqueId().equals(be.pendingBody)) {
                ClaimNetworking.cancel(server, be.pendingBody, ClaimBlock.support(pos, state));
                be.reset();
            } else if (elapsed >= ClaimAnimation.IMPACT) {
                be.pendingBody = null;
                be.refresh(body);
                var support = ClaimBlock.support(pos, state);
                ClaimNetworking.send(body, support, ClaimNetworking.IMPACT);
                var hit = body.logicalPose().transformPosition(Vec3.atCenterOf(support));
                server.playSound(
                        null,
                        hit.x,
                        hit.y,
                        hit.z,
                        SoundEvents.BEACON_ACTIVATE,
                        SoundSource.BLOCKS,
                        .7f,
                        1f);
                be.sync();
            }
        }
        if (be.animation == STAMP && elapsed >= ClaimAnimation.FINISH
                || be.animation == BLINK && elapsed >= ClaimAnimation.BLINK_FINISH) be.reset();
    }

    private void refresh(ServerSubLevel body) {
        boolean present = body != null;
        boolean next =
                present && !NPShipsSavedData.get(body.getLevel()).isTracked(body.getUniqueId());
        boolean blocked = false;
        if (present && !next) {
            blocked = ClaimHostiles.present(body);
            hostilesDirty = false;
        }
        if (physical != present || retained != next || denied != blocked) {
            physical = present;
            retained = next;
            denied = blocked;
            sync();
        }
    }

    private void scanHostiles(ServerSubLevel body) {
        boolean blocked = ClaimHostiles.present(body);
        hostilesDirty = false;
        if (denied != blocked) {
            denied = blocked;
            sync();
        }
    }

    private void reset() {
        animation = IDLE;
        pendingBody = null;
        sync();
    }

    private void sync() {
        if (level != null)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        var tag = super.getUpdateTag(lookup);
        tag.putBoolean("Retained", retained);
        tag.putBoolean("Physical", physical);
        tag.putBoolean("Denied", denied);
        tag.putInt("Animation", animation);
        tag.putLong("Started", started);
        return tag;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.loadAdditional(tag, lookup);
        retained = tag.getBoolean("Retained");
        physical = tag.getBoolean("Physical");
        denied = tag.getBoolean("Denied");
        animation = tag.getInt("Animation");
        started = tag.getLong("Started");
        pendingBody = null;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
