package com.eynny.npships.integration;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchNativeValidation;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.util.List;

/** Optional native API qualification; no saved-data creation, accepted-source or world mutation. */
public final class NativeValidation implements PatchNativeValidation {
    private final String type;

    public NativeValidation(String type) {
        this.type = type;
    }

    public boolean requires(JsonObject patch) {
        String kind = patch.get("kind").getAsString();
        return switch (type) {
            case "computercraft_computer" -> kind.equals("computer");
            case "computercraft_peripheral" -> kind.equals("peripheral");
            case "computercraft_media" -> kind.equals("media") || patch.has("media");
            case "radars_network" -> kind.equals("radar");
            case "radars_guided_fuzes" -> patch.has("fuze_bindings");
            default -> false;
        };
    }

    public ResourceLocation availabilityKey() {
        return ResourceLocation.parse(
                type.startsWith("computercraft_")
                        ? "npships:computercraft_api"
                        : "npships:radars_api");
    }

    public String unavailable() {
        return probe(type.startsWith("computercraft_") ? "computercraft" : "create_radar");
    }

    public void validateDocument(SchematicDocument document, Level context) {
        if (type.equals("cbc_welds"))
            com.eynny.npships.integration.cannons.CannonWelding.validate(document, context, true);
    }

    public static <T> T capability(String type, Class<T> contract) {
        return contract == PatchNativeValidation.class
                        && java.util.Set.of(
                                        "computercraft_computer",
                                        "computercraft_peripheral",
                                        "computercraft_media",
                                        "radars_network",
                                        "radars_guided_fuzes",
                                        "cbc_welds")
                                .contains(type)
                ? contract.cast(new NativeValidation(type))
                : null;
    }

    private static String probe(String mod) {
        if (!ModList.get().isLoaded(mod)) return "Required patch provider is unavailable: " + mod;
        try {
            if (mod.equals("computercraft")) {
                Class.forName(
                                "dan200.computercraft.api.ComputerCraftAPI",
                                false,
                                NativeValidation.class.getClassLoader())
                        .getMethod(
                                "createUniqueNumberedSaveDir", MinecraftServer.class, String.class);
            } else {
                String prefix = "com.happysg.radar.block.behavior.networks.";
                // Inspection only: unlike runtime preflight, do not get/create SavedData here.
                var network =
                        Class.forName(
                                prefix + "NetworkData",
                                false,
                                NativeValidation.class.getClassLoader());
                var weapon =
                        Class.forName(
                                prefix + "WeaponNetworkData",
                                false,
                                NativeValidation.class.getClassLoader());
                for (String name :
                        List.of(
                                "getOrCreateGroup",
                                "attachRadar",
                                "attachMonitor",
                                "attachWeaponEndpoint",
                                "addDataLinkToGroup")) requireMethod(network, name);
                for (String name :
                        List.of("getOrCreateGroup", "tryMergeIntoGroup", "addDataLinkToGroup"))
                    requireMethod(weapon, name);
                var group =
                        Class.forName(
                                prefix + "NetworkData$Group",
                                false,
                                NativeValidation.class.getClassLoader());
                for (String name : List.of("targetingTag", "identificationTag", "detectionTag"))
                    group.getField(name);
            }
            return "";
        } catch (ReflectiveOperationException | LinkageError error) {
            return "Required patch provider API is unavailable: "
                    + mod
                    + " ("
                    + error.getClass().getSimpleName()
                    + ": "
                    + error.getMessage()
                    + ")";
        }
    }

    private static void requireMethod(Class<?> type, String name) throws NoSuchMethodException {
        if (java.util.Arrays.stream(type.getMethods()).noneMatch(m -> m.getName().equals(name)))
            throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
