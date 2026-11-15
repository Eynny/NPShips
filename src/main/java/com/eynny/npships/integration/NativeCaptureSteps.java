package com.eynny.npships.integration;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.integration.aeronautics.*;
import com.eynny.npships.integration.computercraft.*;
import com.eynny.npships.integration.contraptions.MountedStorageSnapshot;
import com.eynny.npships.integration.create.*;
import com.eynny.npships.integration.radars.*;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/** Native extraction functions registered by their actual owner. No world survives capture. */
public final class NativeCaptureSteps {
    private NativeCaptureSteps() {}

    public static final List<String> ORDER =
            List.of(
                    "create_mounted_storage",
                    "computercraft_computer",
                    "computercraft_peripheral",
                    "radars_network",
                    "radars_guided_fuzes",
                    "create_arm",
                    "create_logistics",
                    "simulated_spring",
                    "simulated_docking",
                    "simulated_swivel",
                    "simulated_rope",
                    "aeronautics_lifting_gas",
                    "computercraft_media");

    public static <T> T capability(String type, Class<T> contract) {
        if (contract != PatchBlockCapture.class || !ORDER.contains(type)) return null;
        return contract.cast(
                new PatchBlockCapture() {
                    public int defaultRank() {
                        return ORDER.indexOf(type);
                    }

                    public Operation begin() {
                        return begin(new PatchBlockCapture.Context());
                    }

                    public Operation begin(PatchBlockCapture.Context context) {
                        return new NativeOperation(
                                type, context.memoized(ComputerBudget.class, ComputerBudget::new));
                    }
                });
    }

    private static boolean computer(String id, CompoundTag data) {
        return data.contains("ComputerId")
                || id.substring(id.indexOf(':') + 1).startsWith("computer_")
                || id.substring(id.indexOf(':') + 1).startsWith("turtle_");
    }

    private static final class ComputerBudget {
        private long characters;

        void charge(long amount) {
            characters = Math.addExact(characters, amount);
            if (characters > 12L * 1024 * 1024)
                throw new IllegalArgumentException("Combined computer files exceed export budget.");
        }
    }

    private static final class NativeOperation implements PatchBlockCapture.Operation {
        private final String type;
        private Set<Object> balloons;

        private final ComputerBudget files;

        private NativeOperation(String type, ComputerBudget files) {
            this.type = type;
            this.files = files;
        }

        private Set<Object> balloons() {
            if (balloons == null) balloons = Collections.newSetFromMap(new IdentityHashMap<>());
            return balloons;
        }

        @Override
        public boolean changesEntry() {
            return type.equals("create_mounted_storage") || type.equals("simulated_rope");
        }

        @Override
        public boolean applies(
                ServerLevel level,
                BlockPos origin,
                SchematicDocument source,
                BlockPos p,
                String id,
                CompoundTag data) {
            return switch (type) {
                case "create_mounted_storage" ->
                        data != null
                                && data.contains("Controller")
                                && level.getBlockEntity(origin.offset(p))
                                        instanceof
                                        com.simibubi
                                                .create
                                                .foundation
                                                .blockEntity
                                                .IMultiBlockEntityContainer;
                case "computercraft_computer", "computercraft_peripheral" ->
                        id.startsWith("computercraft:")
                                && !data.isEmpty()
                                && !PatchDevices.kind(source, p).isEmpty()
                                && computer(id, data) == type.equals("computercraft_computer");
                case "radars_network" ->
                        (id.startsWith("create_radar:") || NativeDeviceDescriptions.radarMount(id))
                                && (!data.isEmpty()
                                        || id.equals("create_radar:identification_transponder"));
                case "radars_guided_fuzes" -> !RadarFuzes.stacks(data).isEmpty();
                case "create_arm" -> id.equals("create:mechanical_arm");
                case "create_logistics" -> LogisticsPatch.matches(id);
                case "simulated_spring" -> SpringPatch.matches(id);
                case "simulated_docking" -> DockingPatch.matches(id);
                case "simulated_swivel" -> MechanismPatch.matches(id);
                case "simulated_rope" -> AeronauticsPatch.rope(id);
                case "aeronautics_lifting_gas" -> AeronauticsPatch.burner(id);
                case "computercraft_media" -> !ComputerMedia.ids(data).isEmpty();
                default -> false;
            };
        }

        @Override
        public boolean skipRemaining(
                SchematicDocument source, BlockPos p, String id, CompoundTag data) {
            return type.equals("computercraft_computer")
                    && id.startsWith("computercraft:")
                    && (data.isEmpty() || PatchDevices.kind(source, p).isEmpty());
        }

        @Override
        public JsonObject capture(
                ServerLevel level,
                BlockPos origin,
                SchematicDocument source,
                BlockPos p,
                String id,
                CompoundTag data,
                JsonObject previous,
                java.util.function.BiPredicate<ResourceLocation, BlockPos> replaced)
                throws Exception {
            JsonObject patch = previous;
            switch (type) {
                case "computercraft_computer", "computercraft_peripheral" -> {
                    if (id.startsWith("computercraft:")) {
                        if (data.isEmpty() || PatchDevices.kind(source, p).isEmpty()) return patch;
                        patch = new JsonObject();
                        boolean computer =
                                data.contains("ComputerId")
                                        || id.substring(id.indexOf(':') + 1).startsWith("computer_")
                                        || id.substring(id.indexOf(':') + 1).startsWith("turtle_");
                        patch.addProperty("kind", computer ? "computer" : "peripheral");
                        if (computer) {
                            int old = data.contains("ComputerId") ? data.getInt("ComputerId") : -1;
                            patch.addProperty("old_id", old);
                            patch.add("files", ComputerPatch.capture(level, old));
                            patch.add("aliases", ComputerPatch.aliases(level, old));
                        }
                    }
                }
                case "radars_guided_fuzes" -> {
                    if (!RadarFuzes.stacks(data).isEmpty()) {
                        if (patch == null) {
                            patch = new JsonObject();
                            patch.addProperty("kind", "radar");
                        }
                        RadarFuzes.capture(patch, source, p, origin);
                    }
                }
                case "create_arm" -> {
                    if (id.equals("create:mechanical_arm")) {
                        var arm = level.getBlockEntity(origin.offset(p));
                        if (arm != null)
                            patch =
                                    MechanicalArmPatch.capture(
                                            source,
                                            p,
                                            arm.saveWithFullMetadata(level.registryAccess()));
                    }
                }
                case "create_logistics" -> {
                    if (LogisticsPatch.matches(id)) {
                        var live = level.getBlockEntity(origin.offset(p));
                        if (live != null)
                            patch =
                                    LogisticsPatch.capture(
                                            source,
                                            p,
                                            origin,
                                            live.saveWithFullMetadata(level.registryAccess()));
                    }
                }
                case "simulated_spring" -> {
                    if (SpringPatch.matches(id))
                        patch = SpringPatch.capture(level, origin, p, source);
                }
                case "simulated_docking" -> {
                    if (DockingPatch.matches(id))
                        patch = DockingPatch.capture(level, origin, p, source);
                }
                case "simulated_swivel" -> {
                    if (MechanismPatch.matches(id)) {
                        var live = level.getBlockEntity(origin.offset(p));
                        if (live
                                        instanceof
                                        dev.simulated_team.simulated.content.blocks.swivel_bearing
                                                                .SwivelBearingBlockEntity
                                                        swivel
                                && swivel.isAssembled())
                            throw new IllegalArgumentException(
                                    "Disassemble swivel bearing before export: " + p);
                    }
                }
                case "simulated_rope" -> {
                    if (patch == null
                            && AeronauticsPatch.rope(id)
                            && !replaced.test(ResourceLocation.parse("npships:simulated_rope"), p))
                        patch = AeronauticsPatch.capture(level, origin, p, source, balloons());
                }
                case "aeronautics_lifting_gas" -> {
                    if (patch == null && AeronauticsPatch.burner(id))
                        patch = AeronauticsPatch.capture(level, origin, p, source, balloons());
                }
                case "computercraft_media" -> {
                    if (!ComputerMedia.ids(data).isEmpty()) {
                        if (patch == null) {
                            patch = new JsonObject();
                            patch.addProperty("kind", "media");
                        }
                        patch.add("media", ComputerMedia.capture(level, data));
                    }
                }
                case "radars_network" -> {
                    patch = RadarPatch.capture(level, origin, p, source);
                }
                case "create_mounted_storage" -> {}
                default ->
                        throw new IllegalStateException("Unknown registered capture code: " + type);
            }
            if (type.equals("computercraft_computer") && patch != null && patch.has("files"))
                for (var file : patch.getAsJsonObject("files").entrySet())
                    files.charge(file.getValue().getAsString().length());
            if (type.equals("computercraft_media") && patch != null && patch.has("media"))
                files.charge(patch.get("media").toString().length());
            return patch;
        }

        @Override
        public CompoundTag entry(
                ServerLevel level,
                BlockPos origin,
                SchematicDocument source,
                BlockPos p,
                String id,
                CompoundTag data,
                CompoundTag previous,
                java.util.function.BiPredicate<ResourceLocation, BlockPos> replaced) {
            if (type.equals("create_mounted_storage")) {
                if (data != null
                        && data.contains("Controller")
                        && level.getBlockEntity(origin.offset(p))
                                instanceof
                                com.simibubi
                                                        .create
                                                        .foundation
                                                        .blockEntity
                                                        .IMultiBlockEntityContainer
                                                multi) {
                    var controller = multi.getController();
                    if (!source.blocks().containsKey(controller.subtract(origin)))
                        throw new IllegalArgumentException(
                                "Include the multiblock controller in the project: " + controller);
                    var entry = source.blockEntryCopy(p);
                    entry.put(
                            MountedStorageSnapshot.CONTROLLER_OFFSET,
                            NbtUtils.writeBlockPos(controller.subtract(origin.offset(p))));
                    return entry;
                }
            }
            if (type.equals("simulated_rope")) {
                if (AeronauticsPatch.rope(id)
                        && replaced.test(
                                net.minecraft.resources.ResourceLocation.parse(
                                        "npships:simulated_rope"),
                                p)) {
                    var entry = previous;
                    if (entry == null) entry = source.blockEntryCopy(p);
                    var embedded = SchematicPatches.embeddedEntries(entry);
                    var retained =
                            embedded.stream()
                                    .filter(
                                            value ->
                                                    ExternalPatches.external(value)
                                                            || !value.get("kind")
                                                                    .getAsString()
                                                                    .equals("rope"))
                                    .toList();
                    if (retained.size() != embedded.size()) {
                        SchematicPatches.writeEmbedded(
                                entry, retained, SchematicPatches.schema(entry));
                        return entry;
                    }
                }
            }
            return previous;
        }

        @Override
        public void complete(ServerLevel level, SchematicDocument source, JsonArray entries)
                throws Exception {
            if (!type.equals("computercraft_computer")) return;
            for (var value : entries) {
                var patch = value.getAsJsonObject();
                if (patch.get("kind").getAsString().equals("computer")) {
                    var refs =
                            ComputerPatch.capturedReferences(
                                    level,
                                    patch.get("old_id").getAsInt(),
                                    SchematicPatches.position(patch.getAsJsonArray("pos")),
                                    source,
                                    entries);
                    if (!refs.isEmpty()) patch.add("references", refs);
                }
            }
        }
    }
}
