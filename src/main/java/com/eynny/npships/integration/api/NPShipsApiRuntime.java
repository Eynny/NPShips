package com.eynny.npships.integration.api;

import com.eynny.npships.api.NPShipsCatalogueApi.Snapshot;
import com.eynny.npships.api.NPShipsSpawnApi;
import com.eynny.npships.api.NPShipsSpawnApi.ExactResult;
import com.eynny.npships.api.NPShipsSpawnApi.StartResult;
import com.eynny.npships.api.NPShipsSpawnApi.StartStatus;
import com.eynny.npships.api.SpawnResult;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.Route;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.SpawnPhase;
import com.eynny.npships.api.internal.NPShipsRuntimeAccess;
import com.eynny.npships.authoring.patches.BuiltInPatchAdapters;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.config.RarityParameters;
import com.eynny.npships.integration.events.NPShipsEventBridge;
import com.eynny.npships.naturalspawn.NPShipsNaturalSpawnManager;
import com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate;
import com.eynny.npships.ship.ExactStructureSpawner;
import com.eynny.npships.ship.NPShipsSchematicManager;
import com.eynny.npships.ship.SchematicNames;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Consumer;

/** Stateless runtime binding. Construction must not initialize API registries or optional mods. */
public final class NPShipsApiRuntime implements NPShipsRuntimeAccess {
    public NPShipsApiRuntime() {}

    @Override
    public NPShipsSpawnApi.AutomaticStartResult requestAutomatic(
            MinecraftServer server,
            NPShipsSpawnApi.AutomaticOptions options,
            @Nullable Consumer<NPShipsSpawnApi.AutomaticResult> observer) {
        return NPShipsNaturalSpawnManager.requestAutomatic(server, options, observer);
    }

    @Override
    public NPShipsSpawnApi.AutomaticStartResult requestAutomatic(
            MinecraftServer server,
            NPShipsSpawnApi.AutomaticOptions options,
            NPShipsSpawnApi.AutomaticEffects effects,
            @Nullable Consumer<NPShipsSpawnApi.AutomaticResult> observer) {
        return NPShipsNaturalSpawnManager.requestAutomatic(server, options, effects, observer);
    }

    @Override
    public void registerBuiltIns(BuiltInRegistrar registrar) {
        BuiltInPatchAdapters.register(registrar::register);
        registrar.registerAuthoring(
                net.minecraft.resources.ResourceLocation.parse("npships:simulated_devices"),
                new com.eynny.npships.integration.aeronautics.SimulatedDeviceAuthoring(),
                com.eynny.npships.api.patches.PatchRegistration.legacy("npships")
                        .withCapabilities(
                                java.util.Set.of(
                                        com.eynny.npships.api.patches.PatchRegistration.Capability
                                                .CLIENT_PRESENTATION)));
        registrar.registerAuthoring(
                net.minecraft.resources.ResourceLocation.parse("npships:create_devices"),
                new com.eynny.npships.integration.create.CreateDeviceAuthoring(),
                com.eynny.npships.api.patches.PatchRegistration.legacy("npships")
                        .withCapabilities(
                                java.util.Set.of(
                                        com.eynny.npships.api.patches.PatchRegistration.Capability
                                                .AUTHORED_TRANSFORM,
                                        com.eynny.npships.api.patches.PatchRegistration.Capability
                                                .CLIENT_PRESENTATION,
                                        com.eynny.npships.api.patches.PatchRegistration.Capability
                                                .PREPARATION,
                                        com.eynny.npships.api.patches.PatchRegistration.Capability
                                                .AFTER_PLACEMENT))
                        .withOrder(
                                com.eynny.npships.api.patches.PatchStage.PREPARE_NATIVE_DATA,
                                java.util.Set.of(),
                                java.util.Set.of())
                        .withOrder(
                                com.eynny.npships.api.patches.PatchStage.POST_PLACEMENT_MAINTENANCE,
                                java.util.Set.of(),
                                java.util.Set.of()));
    }

    @Override
    public Snapshot snapshot(MinecraftServer server) {
        requireThread(server);
        if (!NPShipsNaturalSpawnManager.acceptsExternalRequests(server)
                || server.overworld() == null)
            throw new IllegalStateException(
                    "Catalogue queries require a running server with loaded levels.");
        return NPShipsSchematicManager.publicSnapshot(server);
    }

    @Override
    public RarityParameters rarityParameters(MinecraftServer server) {
        requireThread(server);
        return NPShipsRuntimeConfig.rarity();
    }

    @Override
    public boolean automaticAllowedByAdmin(MinecraftServer server, ResourceLocation id) {
        requireThread(server);
        return snapshot(server).automaticEntries().containsKey(id)
                && NPShipsRuntimeConfig.addonEnabled(id.getNamespace())
                && NPShipsRuntimeConfig.structureEnabled(id.toString());
    }

    private static void requireThread(MinecraftServer server) {
        if (server == null || !server.isSameThread())
            throw new IllegalStateException("NPShips API requires the target server thread.");
    }

    @Override
    public ExactResult spawnExact(
            ServerLevel level, ResourceLocation structureId, BlockPos origin) {
        if (level == null || !level.getServer().isSameThread())
            return ExactStructureSpawner.spawn(level, structureId, origin);
        var operation =
                new NPShipsEventBridge.Operation(
                        java.util.UUID.randomUUID(), Route.EXACT_API, structureId, origin);
        var scope = NPShipsEventBridge.enter(operation, () -> true);
        final ExactResult result;
        try (scope) {
            result = ExactStructureSpawner.spawn(level, structureId, origin);
        }
        var phase =
                result.successful()
                        ? SpawnPhase.SUCCEEDED
                        : scope.accepted() ? SpawnPhase.FAILED : SpawnPhase.REFUSED;
        NPShipsEventBridge.complete(
                level,
                operation,
                phase,
                result.status().name(),
                result.reason(),
                result.placement(),
                result.physicalIds(),
                result.successful() ? result.origin() : null,
                result.placementStarted());
        return result;
    }

    @Override
    public StartResult request(
            ServerLevel level,
            ResourceLocation structureId,
            BlockPos referencePoint,
            @Nullable Consumer<SpawnResult> observer) {
        var result = requestInternal(level, structureId, referencePoint, observer);
        if (result.status() != StartStatus.STARTED && level != null) {
            var operation =
                    new NPShipsEventBridge.Operation(
                            java.util.UUID.randomUUID(),
                            Route.EXTERNAL_SEARCH,
                            structureId,
                            referencePoint);
            NPShipsEventBridge.complete(
                    level,
                    operation,
                    SpawnPhase.REFUSED,
                    result.status().name(),
                    result.reason(),
                    SpawnResult.Placement.UNCONFIRMED,
                    java.util.List.of(),
                    null,
                    false);
        }
        return result;
    }

    private static StartResult requestInternal(
            ServerLevel level,
            ResourceLocation structureId,
            BlockPos referencePoint,
            @Nullable Consumer<SpawnResult> observer) {
        if (level == null)
            return refused(StartStatus.INVALID_REFERENCE, "A target ServerLevel is required.");
        var server = level.getServer();
        requireThread(server);
        if (!NPShipsNaturalSpawnManager.acceptsExternalRequests(server))
            return refused(
                    StartStatus.SERVER_UNAVAILABLE, "The target server is stopping or stopped.");
        if (server.getLevel(level.dimension()) != level
                || referencePoint == null
                || referencePoint.getY() < level.getMinBuildHeight()
                || referencePoint.getY() >= level.getMaxBuildHeight()
                || referencePoint.getX() < -30000000
                || referencePoint.getX() >= 30000000
                || referencePoint.getZ() < -30000000
                || referencePoint.getZ() >= 30000000)
            return refused(
                    StartStatus.INVALID_REFERENCE,
                    "Reference/dimension is outside the target world's supported bounds.");
        if (structureId == null)
            return refused(StartStatus.INVALID_STRUCTURE, "A canonical structure ID is required.");
        try {
            SchematicNames.parse(structureId.toString());
        } catch (IllegalArgumentException invalid) {
            return refused(StartStatus.INVALID_STRUCTURE, invalid.getMessage());
        }
        var schematic = NPShipsSchematicManager.find(server, structureId.toString());
        if (schematic == null
                || schematic.config() == null
                || schematic.naturalSpawnSize() == null
                || schematic.snapshot() == null)
            return refused(
                    StartStatus.INVALID_STRUCTURE,
                    "Missing valid natural-placement schematic/config: " + structureId);
        var candidate =
                new NaturalSpawnStructureCandidate(
                        SchematicNames.parse(schematic.commandName()),
                        schematic,
                        schematic.config(),
                        schematic.naturalSpawnSize());
        var job =
                NPShipsNaturalSpawnManager.startExternalJob(
                        level, candidate, referencePoint.immutable(), observer);
        return job == null
                ? refused(StartStatus.BUSY, "Another natural placement attempt owns the manager.")
                : new StartResult(StartStatus.STARTED, Optional.of(job), "");
    }

    private static StartResult refused(StartStatus status, String reason) {
        return new StartResult(status, Optional.empty(), reason);
    }
}
