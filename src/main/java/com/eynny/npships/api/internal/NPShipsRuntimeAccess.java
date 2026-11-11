package com.eynny.npships.api.internal;

import com.eynny.npships.api.NPShipsCatalogueApi;
import com.eynny.npships.api.NPShipsSpawnApi;
import com.eynny.npships.api.SpawnResult;
import com.eynny.npships.api.patches.PatchAdapter;
import com.eynny.npships.api.patches.PatchRegistration;
import com.eynny.npships.config.RarityParameters;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Internal binary binding between compile-time contracts and the installed normal mod. Not an addon
 * extension point. Exactly one stateless provider belongs to the NPShips runtime; no world,
 * handler, replacement registry or per-tick work is owned here.
 *
 * @hidden
 */
public interface NPShipsRuntimeAccess {
    NPShipsCatalogueApi.Snapshot snapshot(MinecraftServer server);

    RarityParameters rarityParameters(MinecraftServer server);

    boolean automaticAllowedByAdmin(MinecraftServer server, ResourceLocation id);

    NPShipsSpawnApi.ExactResult spawnExact(ServerLevel level, ResourceLocation id, BlockPos origin);

    NPShipsSpawnApi.StartResult request(
            ServerLevel level,
            ResourceLocation id,
            BlockPos reference,
            @Nullable Consumer<SpawnResult> observer);

    NPShipsSpawnApi.AutomaticStartResult requestAutomatic(
            MinecraftServer server,
            NPShipsSpawnApi.AutomaticOptions options,
            @Nullable Consumer<NPShipsSpawnApi.AutomaticResult> observer);

    NPShipsSpawnApi.AutomaticStartResult requestAutomatic(
            MinecraftServer server,
            NPShipsSpawnApi.AutomaticOptions options,
            NPShipsSpawnApi.AutomaticEffects effects,
            @Nullable Consumer<NPShipsSpawnApi.AutomaticResult> observer);

    void registerBuiltIns(BuiltInRegistrar registrar);

    /**
     * Bootstrap-only callback supplied by PatchTypes; never exposes its private reserved mutator.
     *
     * @hidden
     */
    @FunctionalInterface
    interface BuiltInRegistrar {
        /**
         * Registers one existing native adapter during class initialization.
         *
         * @param id reserved native provider identifier
         * @param adapter existing specialized implementation
         * @param registration immutable native policy/ordering metadata
         */
        void register(ResourceLocation id, PatchAdapter adapter, PatchRegistration registration);

        /**
         * Installs real scoped code without inventing a runtime binding or handler factory.
         *
         * @param id reserved provider identifier
         * @param authoring native authoring/capability implementation
         * @param registration immutable provider metadata
         */
        default void registerAuthoring(
                ResourceLocation id,
                com.eynny.npships.api.patches.PatchAuthoring authoring,
                PatchRegistration registration) {
            throw new UnsupportedOperationException("Native authoring bootstrap is unavailable");
        }
    }

    static NPShipsRuntimeAccess get() {
        return Holder.INSTANCE;
    }

    /**
     * One stateless process-lifetime service binding; no worlds or active handlers are retained.
     *
     * @hidden
     */
    final class Holder {
        private static final NPShipsRuntimeAccess INSTANCE = load();

        private Holder() {}

        private static NPShipsRuntimeAccess load() {
            var providers =
                    ServiceLoader.load(
                                    NPShipsRuntimeAccess.class,
                                    NPShipsRuntimeAccess.class.getClassLoader())
                            .iterator();
            if (!providers.hasNext())
                throw new IllegalStateException(
                        "Install the normal NPShips mod at runtime; npships-api is a compile-time dependency only.");
            var provider = providers.next();
            if (providers.hasNext())
                throw new IllegalStateException(
                        "Multiple NPShips runtime bindings: do not install or shade a second API/runtime copy.");
            return provider;
        }
    }
}
