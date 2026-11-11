package com.eynny.npships.api.client.editor;

import com.eynny.npships.api.patches.PatchTypes;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

import java.util.*;
import java.util.function.Supplier;

/** CLIENT facets of existing provider identities. No COMMON owner loads this class. */
@EventBusSubscriber(modid = "npships", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class EditorExtensions {
    /**
     * Frozen provider identity, copied title and fresh contribution-owner factory.
     *
     * @param provider already registered COMMON provider ID
     * @param id namespaced identity
     * @param title display title, copied on construction
     * @param factory fresh per-owner factory
     */
    public record Definition(
            ResourceLocation provider,
            ResourceLocation id,
            Component title,
            Supplier<? extends EditorExtension> factory) {
        /**
         * Checks identities and factory and copies the display title.
         *
         * @param provider already registered COMMON provider ID
         * @param id namespaced identity
         * @param title display title, copied on construction
         * @param factory fresh per-owner factory
         */
        public Definition {
            Objects.requireNonNull(provider);
            Objects.requireNonNull(id);
            title = Objects.requireNonNull(title).copy();
            Objects.requireNonNull(factory);
        }

        /**
         * Copies the display title.
         *
         * @return a fresh display component
         */
        @Override
        public Component title() {
            return title.copy();
        }
    }

    private static final Map<ResourceLocation, Definition> ENTRIES = new TreeMap<>();
    private static volatile List<Definition> frozen;

    private EditorExtensions() {}

    /**
     * Register during CLIENT setup. Factory must return a fresh UI/resource owner on every call.
     *
     * @param provider already registered COMMON provider ID
     * @param id namespaced identity
     * @param title display title, copied on construction
     * @param factory fresh per-owner factory
     */
    public static synchronized void register(
            ResourceLocation provider,
            ResourceLocation id,
            Component title,
            Supplier<? extends EditorExtension> factory) {
        if (frozen != null) throw new IllegalStateException("Editor extensions are frozen");
        if (PatchTypes.provider(provider) == null)
            throw new IllegalArgumentException("Unknown provider: " + provider);
        if (!provider.getNamespace().equals(id.getNamespace()) || id.toString().length() > 256)
            throw new IllegalArgumentException("Editor ID must use its provider namespace: " + id);
        if (ENTRIES.size() >= 256)
            throw new IllegalStateException("Editor contribution limit is 256");
        if (ENTRIES.containsKey(id))
            throw new IllegalArgumentException("Duplicate editor ID: " + id);
        ENTRIES.put(id, new Definition(provider, id, title, factory));
    }

    /**
     * Snapshot once per editor activation, never scan the registry during frames/ticks.
     *
     * @return immutable frozen registry view
     */
    public static List<Definition> registered() {
        var result = frozen;
        if (result == null) throw new IllegalStateException("Editor extensions are not frozen");
        return result;
    }

    /**
     * Queues the CLIENT contribution freeze at the existing load-complete boundary.
     *
     * @param event NeoForge load-complete event
     */
    @SubscribeEvent
    public static void loadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(
                () -> {
                    synchronized (EditorExtensions.class) {
                        frozen = List.copyOf(ENTRIES.values());
                    }
                });
    }
}
