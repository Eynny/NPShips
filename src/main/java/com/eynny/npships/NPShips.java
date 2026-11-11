package com.eynny.npships;

import com.eynny.npships.authoring.io.AddonModResources;
import com.eynny.npships.commands.NPShipsCommands;
import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.naturalspawn.NPShipsNaturalSpawnManager;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager;
import com.eynny.npships.network.CatalogRefreshNetworking;
import com.eynny.npships.network.LootTableNetworking;
import com.eynny.npships.network.WeatherNetworking;
import com.eynny.npships.registry.NPShipsRegistry;
import com.eynny.npships.ship.NPShipsSchematicManager;
import com.eynny.npships.ship.ShipKineticRepair;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(NPShips.MOD_ID)
public final class NPShips {

    public static final String MOD_ID = "npships";

    public NPShips(final IEventBus eventBus, final ModContainer container) {
        container.registerConfig(
                ModConfig.Type.SERVER,
                com.eynny.npships.config.NPShipsRuntimeConfig.SPEC,
                "npships-server.toml");
        container.registerConfig(
                ModConfig.Type.CLIENT,
                com.eynny.npships.config.NPShipsEditorConfig.SPEC,
                "npships-editor.toml");

        eventBus.addListener(com.eynny.npships.network.RuntimeSettingsNetworking::register);
        eventBus.addListener(LootTableNetworking::register);
        eventBus.addListener(WeatherNetworking::register);
        eventBus.addListener(CatalogRefreshNetworking::register);
        eventBus.addListener(com.eynny.npships.network.ClaimNetworking::register);
        eventBus.addListener(com.eynny.npships.network.ClaimNetworking::onReload);
        eventBus.addListener(com.eynny.npships.network.SpawnVisualizationNetworking::register);
        eventBus.addListener(com.eynny.npships.network.DevelopmentModeNetworking::register);
        eventBus.addListener(com.eynny.npships.network.InfoShipNetworking::register);
        eventBus.addListener(com.eynny.npships.network.DevelopmentModeNetworking::onReload);
        eventBus.addListener(AddonModResources::addPackFinders);
        NPShipsRegistry.register(eventBus);

        NeoForge.EVENT_BUS.register(ShipKineticRepair.class);

        NeoForge.EVENT_BUS.register(NPShipsNaturalSpawnManager.class);

        NaturalSpawnPhysicalChunkManager.initialize();
        NeoForge.EVENT_BUS.register(NaturalSpawnPhysicalChunkManager.class);

        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerCommands(final RegisterCommandsEvent event) {
        NPShipsCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(final ServerStoppingEvent event) {
        NPShipsSchematicManager.clear(event.getServer());
    }

    @SubscribeEvent
    public void onServerStarted(final ServerStartedEvent event) {
        NPShipsSchematicManager.refresh(event.getServer());
    }

    @SubscribeEvent
    public void onDatapackSync(final net.neoforged.neoforge.event.OnDatapackSyncEvent event) {
        if (event.getPlayer() == null)
            NPShipsSchematicManager.refresh(event.getPlayerList().getServer());
    }

    /**
     * Handles dimensions loaded/created after the initial server startup.
     *
     * <p>This keeps NPShips compatible with custom dimensions.
     */
    @SubscribeEvent
    public void onLevelLoad(final LevelEvent.Load event) {
        if (!(event.getLevel() instanceof final ServerLevel level)) {
            return;
        }

        NPShipsLifecycleManager.attach(level);
    }
}
