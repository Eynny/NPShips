package com.eynny.npships.api.client.patches;

import com.eynny.npships.api.patches.PatchTypes;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * CLIENT only. Register during FMLClientSetupEvent.enqueueWork, after COMMON providers register.
 * Frozen in load-complete work. Missing presentations leave common execution fully usable.
 */
@EventBusSubscriber(modid = "npships", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PatchPresentations {
    private static final Map<ResourceLocation, PatchPresentation> REGISTERED = new TreeMap<>();
    private static volatile Map<ResourceLocation, PatchPresentation> frozen;

    private PatchPresentations() {}

    /**
     * Registers one CLIENT form for an existing COMMON target provider.
     *
     * @param id namespaced identity
     * @param presentation stateless CLIENT presentation descriptor
     */
    public static synchronized void register(ResourceLocation id, PatchPresentation presentation) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(presentation);
        if (frozen != null)
            throw new IllegalStateException("Patch presentations are frozen: " + id);
        if (PatchTypes.type(id) == null)
            throw new IllegalArgumentException("No COMMON patch provider: " + id);
        if (REGISTERED.containsKey(id))
            throw new IllegalArgumentException("Duplicate patch presentation: " + id);
        if (REGISTERED.size() >= PatchTypes.MAX_TYPES)
            throw new IllegalStateException("Patch presentation limit is " + PatchTypes.MAX_TYPES);
        REGISTERED.put(id, presentation);
    }

    /**
     * Tests whether the immutable CLIENT view has been published.
     *
     * @return whether the immutable registry view has been published
     */
    public static boolean isFrozen() {
        return frozen != null;
    }

    /**
     * Reads the immutable frozen presentation registry.
     *
     * @return immutable frozen registry view
     */
    public static Map<ResourceLocation, PatchPresentation> registered() {
        var result = frozen;
        if (result == null)
            throw new IllegalStateException("Patch presentations are not frozen yet");
        return result;
    }

    /**
     * Queues the existing CLIENT load-complete freeze.
     *
     * @param event NeoForge load-complete event
     */
    @SubscribeEvent
    public static void loadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(
                () -> {
                    synchronized (PatchPresentations.class) {
                        frozen = Collections.unmodifiableMap(new TreeMap<>(REGISTERED));
                    }
                });
    }
}
