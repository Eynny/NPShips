package com.eynny.npships.integration;

import com.eynny.npships.api.patches.*;
import com.eynny.npships.api.patches.PatchAuthoring.CaptureField;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.integration.aeronautics.*;
import com.eynny.npships.integration.computercraft.*;
import com.eynny.npships.integration.contraptions.MountedStorageSnapshot;
import com.eynny.npships.integration.create.*;
import com.eynny.npships.integration.radars.*;

import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/** Native source aspects advertised through the registered capture provider. */
public final class NativeCaptureAspects {
    private NativeCaptureAspects() {}

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("npships", name);
    }

    public static <T> T capability(String type, Class<T> contract) {
        if (contract != PatchCaptureAspect.class || !NativeCaptureSteps.ORDER.contains(type))
            return null;
        return contract.cast(
                new PatchCaptureAspect() {
                    public int defaultRank() {
                        return switch (type) {
                            case "computercraft_media" -> 20;
                            case "radars_guided_fuzes" -> 30;
                            case "create_mounted_storage" -> 40;
                            default -> 10;
                        };
                    }

                    public List<Offer> inspect(
                            ServerLevel level,
                            BlockPos origin,
                            SchematicDocument doc,
                            BlockPos at,
                            String name,
                            CompoundTag state,
                            CompoundTag data,
                            Fields sourceFields) {
                        if (type.equals("computercraft_media"))
                            return ComputerMedia.ids(data).isEmpty()
                                    ? List.of()
                                    : List.of(
                                            new Offer(
                                                    id("nested_media"),
                                                    List.of(at),
                                                    List.of(),
                                                    false));
                        if (type.equals("radars_guided_fuzes"))
                            return RadarFuzes.stacks(data).isEmpty()
                                    ? List.of()
                                    : List.of(
                                            new Offer(
                                                    id("nested_items"),
                                                    List.of(at),
                                                    List.of(),
                                                    false));
                        if (type.equals("create_mounted_storage"))
                            return sourceFields.inEntry(at, MountedStorageSnapshot.KEY)
                                            || data.contains("Controller")
                                                    && level.getBlockEntity(origin.offset(at))
                                                            instanceof
                                                            com.simibubi
                                                                    .create
                                                                    .foundation
                                                                    .blockEntity
                                                                    .IMultiBlockEntityContainer
                                    ? List.of(
                                            new Offer(
                                                    id("native_ownership"),
                                                    List.of(at),
                                                    List.of(),
                                                    false))
                                    : List.of();
                        if (!possible(type, name)) return List.of();
                        var kind = PatchDevices.kind(name);
                        if (name.equals("computercraft:cable")) {
                            var modem = state.getCompound("Properties").getString("modem");
                            if (!modem.isEmpty() && !modem.equals("none")) kind = "peripheral";
                        }
                        var owner = PatchTypes.legacyId(kind);
                        if (!id(type).equals(owner)
                                || !PatchTypes.provider(owner)
                                        .registration()
                                        .capabilities()
                                        .contains(PatchRegistration.Capability.CAPTURE))
                            return List.of();
                        if (AeronauticsPatch.rope(name)) {
                            var cells = ropeCells(level, origin, doc, at);
                            var fields = new ArrayList<CaptureField>();
                            for (var cell : cells) {
                                for (String key : List.of("OwnStrand", "HasRopeAttached", "Strand"))
                                    if (sourceFields.inData(cell, key))
                                        fields.add(new CaptureField(cell, key));
                            }
                            return List.of(
                                    new Offer(
                                            id("native_references"),
                                            cells,
                                            fields,
                                            !fields.isEmpty()));
                        }
                        return List.of(
                                new Offer(id("native_state"), List.of(at), List.of(), false));
                    }
                });
    }

    private static boolean possible(String type, String name) {
        return switch (type) {
            case "computercraft_computer", "computercraft_peripheral" ->
                    name.startsWith("computercraft:");
            case "radars_network" ->
                    name.startsWith("create_radar:")
                            || Set.of(
                                            "createbigcannons:cannon_mount",
                                            "createbigcannons:fixed_cannon_mount")
                                    .contains(name);
            case "create_arm" -> name.equals("create:mechanical_arm");
            case "create_logistics" -> LogisticsPatch.matches(name);
            case "simulated_spring" -> SpringPatch.matches(name);
            case "simulated_docking" -> DockingPatch.matches(name);
            case "simulated_swivel" -> MechanismPatch.matches(name);
            case "simulated_rope" -> AeronauticsPatch.rope(name);
            case "aeronautics_lifting_gas" -> AeronauticsPatch.burner(name);
            default -> false;
        };
    }

    /** Native pair completeness remains mandatory even when its representation is delegated. */
    private static List<BlockPos> ropeCells(
            ServerLevel level, BlockPos origin, SchematicDocument doc, BlockPos at) {
        if (!(level.getBlockEntity(origin.offset(at))
                instanceof RopeStrandHolderBlockEntity holder))
            throw new IllegalArgumentException("Capture source holder is unavailable at " + at);
        var behavior = holder.getBehavior();
        var strand = behavior.getOwnedStrand();
        if (behavior.isAttached()) {
            var attached = behavior.getAttachedStrand();
            if (attached == null)
                throw new IllegalArgumentException("Rope data is not loaded at " + at);
            if (strand == null) strand = attached;
        }
        if (strand == null) return List.of(at);
        var cells =
                new TreeSet<BlockPos>(
                        Comparator.<BlockPos>comparingInt(p -> p.getX())
                                .thenComparingInt(BlockPos::getY)
                                .thenComparingInt(BlockPos::getZ));
        for (var attachment : strand.getAttachments()) {
            var world = attachment.blockAttachment();
            var local = world.subtract(origin);
            if (!doc.blocks().containsKey(local)
                    || !(level.getBlockEntity(world) instanceof RopeStrandHolderBlockEntity))
                throw new IllegalArgumentException(
                        "Include all native attachment holders in capture: " + at);
            cells.add(local);
        }
        if (cells.size() != 2 || !cells.contains(at))
            throw new IllegalArgumentException(
                    "Capture requires two distinct native attachment holders at " + at);
        return List.copyOf(cells);
    }
}
