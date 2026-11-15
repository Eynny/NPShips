package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchMetadata;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Display facets attached by the owning built-in registrations; never controls dispatch/policy. */
public final class BuiltInPatchMetadata {
    private BuiltInPatchMetadata() {}

    public static PatchMetadata describe(String provider) {
        return switch (provider) {
            case "create_arm" ->
                    new PatchMetadata(
                            "npships.patch.create_arm.name",
                            "Mechanical arms",
                            "npships.patch.create_arm.description",
                            "Captures and restores mechanical-arm input/output points.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:create_arm"),
                                            "npships.patch.create_arm.subject",
                                            "Mechanical arms")));
            case "create_logistics" ->
                    new PatchMetadata(
                            "npships.patch.create_logistics.name",
                            "Create logistics",
                            "npships.patch.create_logistics.description",
                            "Restores package addresses, links and logistics references.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:create_logistics"),
                                            "npships.patch.create_logistics.subject",
                                            "Create logistics")));
            case "simulated_spring" ->
                    new PatchMetadata(
                            "npships.patch.simulated_spring.name",
                            "Springs",
                            "npships.patch.simulated_spring.description",
                            "Restores spring connections between their authored endpoints.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:simulated_spring"),
                                            "npships.patch.simulated_spring.subject",
                                            "Simulated springs")));
            case "simulated_docking" ->
                    new PatchMetadata(
                            "npships.patch.simulated_docking.name",
                            "Docking connections",
                            "npships.patch.simulated_docking.description",
                            "Restores docking-port relationships after placement.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:simulated_docking"),
                                            "npships.patch.simulated_docking.subject",
                                            "Simulated docking")));
            case "simulated_rope" ->
                    new PatchMetadata(
                            "npships.patch.simulated_rope.name",
                            "Ropes",
                            "npships.patch.simulated_rope.description",
                            "Captures rope endpoints and restores their native attachment.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:simulated_rope"),
                                            "npships.patch.simulated_rope.subject",
                                            "Simulated ropes")));
            case "aeronautics_lifting_gas" ->
                    new PatchMetadata(
                            "npships.patch.aeronautics_lifting_gas.name",
                            "Lifting gas",
                            "npships.patch.aeronautics_lifting_gas.description",
                            "Restores balloon and lifting-gas state before physical assembly.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse(
                                                    "npships:aeronautics_lifting_gas"),
                                            "npships.patch.aeronautics_lifting_gas.subject",
                                            "Balloons and lifting gas")));
            case "simulated_swivel" ->
                    new PatchMetadata(
                            "npships.patch.simulated_swivel.name",
                            "Swivels and mechanisms",
                            "npships.patch.simulated_swivel.description",
                            "Restores authored mechanism connections at body placement.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:simulated_swivel"),
                                            "npships.patch.simulated_swivel.subject",
                                            "Simulated mechanisms")));
            case "computercraft_computer" ->
                    new PatchMetadata(
                            "npships.patch.computercraft_computer.name",
                            "Computers",
                            "npships.patch.computercraft_computer.description",
                            "Prepares computer identity, saved files and references.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse(
                                                    "npships:computercraft_computer"),
                                            "npships.patch.computercraft_computer.subject",
                                            "ComputerCraft computers")));
            case "computercraft_peripheral" ->
                    new PatchMetadata(
                            "npships.patch.computercraft_peripheral.name",
                            "Peripherals and monitors",
                            "npships.patch.computercraft_peripheral.description",
                            "Restores peripheral names, wired links and monitor state.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse(
                                                    "npships:computercraft_peripheral"),
                                            "npships.patch.computercraft_peripheral.subject",
                                            "ComputerCraft peripherals")));
            case "computercraft_media" ->
                    new PatchMetadata(
                            "npships.patch.computercraft_media.name",
                            "Computer media",
                            "npships.patch.computercraft_media.description",
                            "Preserves disk data and allocates independent media identities.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:computercraft_media"),
                                            "npships.patch.computercraft_media.subject",
                                            "ComputerCraft disks")));
            case "radars_network" ->
                    new PatchMetadata(
                            "npships.patch.radars_network.name",
                            "Radar networks",
                            "npships.patch.radars_network.description",
                            "Restores radar controllers, filters and their saved references.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:radar_network"),
                                            "npships.patch.radars_network.subject",
                                            "Radar network references")));
            case "radars_guided_fuzes" ->
                    new PatchMetadata(
                            "npships.patch.radars_guided_fuzes.name",
                            "Guided fuzes",
                            "npships.patch.radars_guided_fuzes.description",
                            "Restores guided-fuze links to the selected radar network.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:radar_network"),
                                            "npships.patch.radars_guided_fuzes.subject",
                                            "Radar network references")));
            case "cbc_welds" ->
                    new PatchMetadata(
                            "npships.patch.cbc_welds.name",
                            "Cannon welds",
                            "npships.patch.cbc_welds.description",
                            "Validates and restores saved cannon welds before assembly.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:cannon_connections"),
                                            "npships.patch.cbc_welds.subject",
                                            "Cannon connectivity")));
            case "cbc_default_connections" ->
                    new PatchMetadata(
                            "npships.patch.cbc_default_connections.name",
                            "Cannon connections",
                            "npships.patch.cbc_default_connections.description",
                            "Restores ordinary cannon connections before assembly.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:cannon_connections"),
                                            "npships.patch.cbc_default_connections.subject",
                                            "Cannon connectivity")));
            case "create_mounted_storage" ->
                    new PatchMetadata(
                            "npships.patch.create_mounted_storage.name",
                            "Mounted storage",
                            "npships.patch.create_mounted_storage.description",
                            "Preserves inventories and tanks transferred by native assembly.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse(
                                                    "npships:create_mounted_storage"),
                                            "npships.patch.create_mounted_storage.subject",
                                            "Create mounted inventories and tanks")));
            case "create_devices" ->
                    new PatchMetadata(
                            "npships.patch.create_devices.name",
                            "Create devices",
                            "npships.patch.create_devices.description",
                            "Captures, edits and previews Create device state, materials and multiblock data.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:create_devices"),
                                            "npships.patch.create_devices.subject",
                                            "Create device author data")));
            case "simulated_devices" ->
                    new PatchMetadata(
                            "npships.patch.simulated_devices.name",
                            "Simulated devices",
                            "npships.patch.simulated_devices.description",
                            "Captures, edits and previews Simulated device settings and references.",
                            List.of(
                                    new PatchMetadata.Subject(
                                            ResourceLocation.parse("npships:data_domain"),
                                            ResourceLocation.parse("npships:simulated_devices"),
                                            "npships.patch.simulated_devices.subject",
                                            "Simulated device author data")));
            default ->
                    throw new IllegalArgumentException(
                            "Unknown built-in display owner: " + provider);
        };
    }
}
