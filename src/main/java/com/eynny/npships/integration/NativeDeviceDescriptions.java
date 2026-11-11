package com.eynny.npships.integration;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.integration.aeronautics.*;
import com.eynny.npships.integration.computercraft.ComputerCables;
import com.eynny.npships.integration.create.*;
import com.eynny.npships.integration.radars.*;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import java.util.*;

/** Pure native source descriptions. Optional native objects are neither loaded nor constructed. */
public final class NativeDeviceDescriptions {
    private NativeDeviceDescriptions() {}

    private static final List<String> ORDER =
            List.of(
                    "simulated_spring",
                    "simulated_docking",
                    "simulated_swivel",
                    "create_arm",
                    "create_logistics",
                    "simulated_rope",
                    "aeronautics_lifting_gas",
                    "radars_network",
                    "computercraft_computer",
                    "computercraft_peripheral",
                    "radars_guided_fuzes");

    /** Same pure registered-source grammar used by descriptions and native address sanitation. */
    static String kind(String type, String name) {
        boolean match =
                switch (type) {
                    case "simulated_spring" -> SpringPatch.matches(name);
                    case "simulated_docking" -> DockingPatch.matches(name);
                    case "simulated_swivel" -> MechanismPatch.matches(name);
                    case "create_arm" -> name.equals("create:mechanical_arm");
                    case "create_logistics" -> LogisticsPatch.matches(name);
                    case "simulated_rope" -> AeronauticsPatch.rope(name);
                    case "aeronautics_lifting_gas" -> AeronauticsPatch.burner(name);
                    case "radars_network" ->
                            !RadarWiring.role(name).isEmpty() && name.startsWith("create_radar:")
                                    || radarMount(name)
                                    || name.equals("create_radar:identification_transponder");
                    case "computercraft_computer" ->
                            name.startsWith("computercraft:computer_")
                                    || name.startsWith("computercraft:turtle_");
                    case "computercraft_peripheral" ->
                            name.startsWith("computercraft:")
                                    && Set.of(
                                                    "monitor_normal",
                                                    "monitor_advanced",
                                                    "disk_drive",
                                                    "printer",
                                                    "speaker",
                                                    "redstone_relay",
                                                    "wired_modem_full",
                                                    "wireless_modem_normal",
                                                    "wireless_modem_advanced")
                                            .contains(name.substring(name.indexOf(':') + 1));
                    default -> false;
                };
        return match
                ? switch (type) {
                    case "simulated_spring" -> "spring";
                    case "simulated_docking" -> "docking";
                    case "simulated_swivel" -> "mechanism";
                    case "create_arm" -> "arm";
                    case "create_logistics" -> "logistics";
                    case "simulated_rope" -> "rope";
                    case "aeronautics_lifting_gas" -> "balloon";
                    case "radars_network" -> "radar";
                    case "computercraft_computer" -> "computer";
                    case "computercraft_peripheral" -> "peripheral";
                    default -> "";
                }
                : "";
    }

    public static <T> T capability(String type, Class<T> contract) {
        if (contract != PatchDeviceDescription.class || !ORDER.contains(type)) return null;
        return contract.cast(
                new PatchDeviceDescription() {
                    @Override
                    public boolean highlights(net.minecraft.nbt.CompoundTag state) {
                        return !kind(state.getString("Name")).isEmpty()
                                || type.equals("computercraft_peripheral")
                                        && state.getString("Name").equals("computercraft:cable")
                                        && !Set.of("", "none")
                                                .contains(
                                                        state.getCompound("Properties")
                                                                .getString("modem"));
                    }

                    @Override
                    public int defaultRank() {
                        return ORDER.indexOf(type);
                    }

                    @Override
                    public String kind(String name) {
                        return NativeDeviceDescriptions.kind(type, name);
                    }

                    @Override
                    public String kind(SchematicDocument doc, BlockPos p) {
                        if (type.equals("computercraft_peripheral") && ComputerCables.modem(doc, p))
                            return "peripheral";
                        if (type.equals("radars_guided_fuzes"))
                            return RadarFuzes.stacks(doc.blockEntityData(p)).isEmpty()
                                    ? ""
                                    : "radar";
                        return kind(doc.blockName(p));
                    }

                    @Override
                    public JsonObject create(SchematicDocument doc, BlockPos p, String kind) {
                        String name = doc.blockName(p);
                        var value = new JsonObject();
                        value.add("pos", SchematicPatches.vector(p));
                        value.addProperty("block", name);
                        value.addProperty("kind", kind);
                        if (type.equals("create_logistics")) {
                            value =
                                    LogisticsPatch.capture(
                                            doc, p, BlockPos.ZERO, doc.blockEntityData(p));
                            value.add("pos", SchematicPatches.vector(p));
                            value.addProperty("block", name);
                        }
                        if (type.equals("create_arm"))
                            return MechanicalArmPatch.capture(doc, p, doc.blockEntityData(p));
                        if (type.equals("computercraft_computer")) {
                            var data = doc.blockEntityData(p);
                            value.addProperty(
                                    "old_id",
                                    data.contains("ComputerId") ? data.getInt("ComputerId") : -1);
                            value.add("files", new JsonObject());
                        }
                        if (type.equals("simulated_spring"))
                            value.addProperty(
                                    "length",
                                    Math.clamp(
                                            doc.blockEntityData(p).getDouble("DesiredLength"),
                                            1,
                                            9));
                        if (type.equals("aeronautics_lifting_gas"))
                            value.addProperty("fill_volume", 0);
                        return value;
                    }
                });
    }

    public static boolean radarMount(String name) {
        return Set.of("createbigcannons:cannon_mount", "createbigcannons:fixed_cannon_mount")
                        .contains(name)
                && net.neoforged.fml.ModList.get().isLoaded("create_radar");
    }
}
