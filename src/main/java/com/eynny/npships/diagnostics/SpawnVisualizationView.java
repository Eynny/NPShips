package com.eynny.npships.diagnostics;

import com.eynny.npships.diagnostics.SpawnVisualizationData.Frame;
import com.eynny.npships.network.SpawnVisualizationNetworking;
import com.eynny.npships.network.SpawnVisualizationNetworking.State;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Client-world-owned sequence barrier, independent of rendering and Minecraft singletons. */
public final class SpawnVisualizationView {
    public static final int STALE_TICKS = 60;
    private UUID world;
    private ResourceLocation dimension;
    private long epoch, sequence, receivedAt, levelTick;
    private boolean enabled;
    private Frame frame = Frame.empty();

    public void bind(UUID world, ResourceLocation dimension) {
        this.world = world;
        this.dimension = dimension;
        epoch = sequence = receivedAt = levelTick = 0;
        enabled = false;
        frame = Frame.empty();
    }

    public boolean accept(State state, long localTick, long levelTick) {
        if (world == null || !world.equals(state.world()) || !dimension.equals(state.dimension()))
            return false;
        if (state.kind() == SpawnVisualizationNetworking.CLEAR) {
            if (state.epoch() < epoch) return false;
            epoch = state.epoch();
            sequence = state.sequence();
            enabled = false;
            frame = Frame.empty();
            return true;
        }
        if (state.kind() == SpawnVisualizationNetworking.START) {
            if (state.epoch() <= epoch) return false;
            epoch = state.epoch();
        } else if (!enabled || state.epoch() != epoch || state.sequence() <= sequence) return false;
        sequence = state.sequence();
        receivedAt = localTick;
        this.levelTick = levelTick;
        frame = state.frame();
        enabled = true;
        return true;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean fresh(long tick) {
        return enabled && tick - receivedAt <= STALE_TICKS;
    }

    public long age(long tick) {
        return Math.max(0, tick - receivedAt);
    }

    public long estimatedServerTick(long levelTick) {
        return frame.tick() + Math.max(0, levelTick - this.levelTick);
    }

    public Frame frame() {
        return frame;
    }

    public UUID world() {
        return world;
    }
}
