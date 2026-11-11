package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchBinding;
import com.eynny.npships.api.patches.PatchIdentity;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.integration.aeronautics.AeronauticsPatch;
import com.eynny.npships.integration.aeronautics.DockingPatch;
import com.eynny.npships.integration.aeronautics.SpringPatch;
import com.eynny.npships.integration.cannons.CannonPlacement;
import com.eynny.npships.integration.cannons.CannonWelding;
import com.eynny.npships.integration.cannons.CannonWelds;
import com.eynny.npships.integration.computercraft.ComputerMedia;
import com.eynny.npships.integration.computercraft.ComputerNetwork;
import com.eynny.npships.integration.computercraft.ComputerPatch;
import com.eynny.npships.integration.computercraft.ComputerSides;
import com.eynny.npships.integration.contraptions.MountedStorageSnapshot;
import com.eynny.npships.integration.create.LogisticsPatch;
import com.eynny.npships.integration.create.MechanicalArmPatch;
import com.eynny.npships.integration.create.MechanismPatch;
import com.eynny.npships.integration.radars.RadarFilters;
import com.eynny.npships.integration.radars.RadarFuzes;
import com.eynny.npships.integration.radars.RadarPatch;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-template batch facts. Discovery does not mutate native data, allocate IDs or write files. */
public final class BuiltinPatchState {
    private final PatchedTemplate owner;
    public CompoundTag currentBlock;
    public JsonObject currentPatch;
    private CompoundTag cachedBlock;
    private JsonObject cachedPatch;
    private CompoundTag copy;
    private ListTag sourcePalette;
    private final Map<Integer, net.minecraft.world.level.block.state.BlockState> decodedStates =
            new HashMap<>();
    private final Map<Integer, Integer> computers = new LinkedHashMap<>();
    private final Map<String, String> peripherals = new LinkedHashMap<>();
    private final Map<Integer, JsonObject> disks = new LinkedHashMap<>();
    private final Map<Integer, Integer> mediaIds = new HashMap<>();
    private final Map<Integer, JsonObject> mediaOriginals = new HashMap<>();
    private final Map<BlockPos, Integer> computerPositions = new HashMap<>();
    private final Map<BlockPos, String> peripheralPositions = new HashMap<>();
    private final Map<Integer, JsonObject> diskPatches = new HashMap<>();
    private final Map<BlockPos, Map<BlockPos, String>> wiredAddresses = new HashMap<>();
    private final Map<Integer, Map<String, Integer>> namedIds = new HashMap<>();
    private final Map<Integer, Map<String, String>> namedNames = new HashMap<>();
    private boolean indexed, aliasesPrepared;
    private final Map<ResourceLocation, Set<BlockPos>> footprints = new LinkedHashMap<>();
    private final Set<BlockPos> ropeEnds = new HashSet<>();
    private final Set<BlockPos> activeWires = new HashSet<>();
    private ComputerNetwork network;
    private List<CannonWelds.Pair> weldPairs = List.of();
    private int weldIndex;
    private List<MountedStorageSnapshot.Prepared> storage = List.of();
    private final Map<UUID, UUID> logisticsNetworks = new HashMap<>();
    private final Set<Object> restoredGas = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> restoredRopes = Collections.newSetFromMap(new IdentityHashMap<>());
    private RadarPatch.LocalState radarLocal;
    private JsonObject radarLocalPatch;

    public BuiltinPatchState(PatchedTemplate owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    private final Map<Class<?>, Object> nativeData = new HashMap<>();

    public <T> T nativeData(Class<T> type, java.util.function.Supplier<T> factory) {
        return type.cast(nativeData.computeIfAbsent(type, ignored -> factory.get()));
    }

    public net.minecraft.server.level.ServerLevel nativeLevel() {
        return owner.level;
    }

    public BlockPos placementOrigin() {
        return owner.origin;
    }

    public BlockPos finalPosition(BlockPos local) {
        return owner.moved.getOrDefault(local, owner.origin.offset(local));
    }

    public void track(BlockPos local) {
        owner.tracked.add(local);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("npships", path);
    }

    private Set<BlockPos> targets(String type) {
        return footprints.computeIfAbsent(id(type), ignored -> new LinkedHashSet<>());
    }

    private void add(String type, BlockPos position) {
        targets(type).add(position.immutable());
    }

    /** One shared discovery pass; data owned by source stays unchanged. */
    private void index() {
        if (indexed) return;
        indexed = true;
        boolean computers = false;
        for (var patch : owner.patches) {
            if (ExternalPatches.external(patch)) continue;
            var at = SchematicPatches.position(patch.getAsJsonArray("pos"));
            String kind = patch.get("kind").getAsString();
            var type = PatchTypes.legacyId(kind);
            if (type != null
                    && (!kind.equals("mechanism")
                            || patch.has("assemble") && patch.get("assemble").getAsBoolean())) {
                add(type.getPath(), at);
                for (var target : PatchDevices.targets(patch)) add(type.getPath(), target);
            }
            if (patch.has("media")) add("computercraft_media", at);
            if (patch.has("fuze_bindings")) {
                add("radars_guided_fuzes", at);
                for (var target : PatchDevices.targets(patch)) add("radars_guided_fuzes", target);
            }
            if (kind.equals("rope")) {
                ropeEnds.add(at);
                ropeEnds.addAll(PatchDevices.targets(patch));
            }
            if (kind.equals("computer") || kind.equals("peripheral"))
                add("computercraft_peripheral", at);
            computers |= kind.equals("computer");
        }
        var palette =
                owner.source.contains("palette", Tag.TAG_LIST)
                        ? owner.source.getList("palette", Tag.TAG_COMPOUND)
                        : owner.source.getList("palettes", Tag.TAG_LIST).getList(0);
        Class<?> autocannon = null;
        if (ModList.get().isLoaded("createbigcannons")
                && palette.stream()
                        .anyMatch(
                                value ->
                                        ((CompoundTag) value)
                                                .getString("Name")
                                                .startsWith("createbigcannons:"))) {
            try {
                autocannon =
                        Class.forName(
                                "rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlock");
            } catch (ClassNotFoundException | LinkageError error) {
                throw new IllegalArgumentException(
                        "CBC default connection provider is incompatible", error);
            }
        }
        boolean welds = false;
        for (var value : owner.source.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            var at = SchematicPatches.blockPosition(block);
            var data = block.getCompound("nbt");
            welds |= data.contains(CannonWelds.KEY);
            // These native batches run before body transfer and never resolve a target through a
            // public callback. Do not duplicate a potentially huge whole-document mapping set.
            if (block.contains(MountedStorageSnapshot.KEY, Tag.TAG_COMPOUND))
                targets("create_mounted_storage");
            if (autocannon != null && !data.contains("Connections")) {
                var name =
                        ResourceLocation.tryParse(
                                palette.getCompound(block.getInt("state")).getString("Name"));
                if (name != null
                        && name.getNamespace().equals("createbigcannons")
                        && autocannon.isInstance(BuiltInRegistries.BLOCK.get(name)))
                    targets("cbc_default_connections");
            }
        }
        if (welds) {
            weldPairs = CannonWelds.pairs(owner.sourceDoc);
            // This batch executes entirely before body transfer and uses checked source-local
            // endpoints, never the public mapping resolver. Do not track every cannon in a body.
            if (!weldPairs.isEmpty()) targets("cbc_welds");
        }
        if (computers) {
            network = new ComputerNetwork(owner.sourceDoc);
            for (var patch : owner.patches)
                if (kind(patch, "computer"))
                    activeWires.addAll(
                            network.wires(SchematicPatches.position(patch.getAsJsonArray("pos"))));
            for (var at : activeWires)
                if (!network.ports(at).isEmpty()) add("computercraft_peripheral", at);
        }
    }

    List<PatchBinding> bindings(ResourceLocation type) {
        index();
        var positions = footprints.get(type);
        if (positions == null) return List.of();
        var token =
                UUID.nameUUIDFromBytes(
                        ("npships:builtin-batch/v1/" + type).getBytes(StandardCharsets.UTF_8));
        var payload = new JsonObject();
        payload.addProperty("native_batch", true);
        payload.addProperty("target_count", positions.size());
        return List.of(
                new PatchBinding(new PatchIdentity(type, token, null, null), payload, positions));
    }

    public CompoundTag preparedCopy() {
        if (copy == null)
            copy =
                    owner.resources == null
                            ? owner.source.copy()
                            : owner.resources.copy(owner.source);
        return copy;
    }

    public boolean hasWires() {
        index();
        return !activeWires.isEmpty();
    }

    private JsonObject blockPatch() {
        if (cachedBlock != currentBlock) {
            cachedBlock = currentBlock;
            cachedPatch =
                    currentBlock == null || !currentBlock.contains(SchematicPatches.EMBEDDED)
                            ? null
                            : SchematicPatches.embeddedBuiltin(currentBlock);
        }
        return cachedPatch;
    }

    private static boolean kind(JsonObject patch, String kind) {
        return patch != null
                && !ExternalPatches.external(patch)
                && patch.get("kind").getAsString().equals(kind);
    }

    private BlockPos resolve(BlockPos local) {
        return owner.moved.getOrDefault(local, owner.origin.offset(local));
    }

    public ListTag sourcePalette() {
        if (sourcePalette == null) {
            if (owner.resources != null)
                owner.resources.reserveCopy(
                        owner.source.contains("palette", Tag.TAG_LIST)
                                ? owner.source.getList("palette", Tag.TAG_COMPOUND)
                                : owner.source.getList("palettes", Tag.TAG_LIST).getList(0));
            sourcePalette = owner.sourceDoc.paletteCopy();
        }
        return sourcePalette;
    }

    /**
     * Native defaults/old identity clearing are mandatory even when no device handler is active.
     */
    public void core(CompoundTag block) throws Exception {
        index();
        currentBlock = block;
        owner.coreBlock(block, ropeEnds, sourcePalette(), decodedStates);
        var patch = blockPatch();
        // Native defaults must not introduce source disk IDs without captured media. This is
        // pure validation; restoration/IDs/files belong only to the registered media adapter.
        for (var code : PatchSourceOperationsDispatch.all())
            code.validatePrepared(block.getCompound("nbt"), patch);
    }

    private long ccBegin() {
        return owner.timing == null ? 0 : owner.timing.begin(SpawnTimingProfiler.Stage.CC);
    }

    private void ccEnd(long token) {
        if (owner.timing != null) owner.timing.end(token);
    }

    void prepareMedia() throws Exception {
        var patch = blockPatch();
        if (patch == null || !patch.has("media") && !kind(patch, "media")) return;
        var data = currentBlock.getCompound("nbt");
        boolean fresh = patch.has("fresh_ids") && patch.get("fresh_ids").getAsBoolean();
        long token = ccBegin();
        try {
            ComputerMedia.restore(
                    owner.level,
                    data,
                    patch,
                    fresh ? new HashMap<>() : mediaIds,
                    fresh ? new HashMap<>() : mediaOriginals,
                    owner.computerFiles);
            currentBlock.put("nbt", data);
        } finally {
            ccEnd(token);
        }
    }

    void prepareComputer() throws Exception {
        var patch = blockPatch();
        if (!kind(patch, "computer")) return;
        var data = currentBlock.getCompound("nbt");
        long token = ccBegin();
        try {
            int old = patch.get("old_id").getAsInt();
            if (old >= 0 && computers.containsKey(old))
                throw new IllegalArgumentException("Duplicate computer ID in schematic: " + old);
            int next = ComputerPatch.allocate(owner.level, "computer");
            if (old >= 0) computers.put(old, next);
            disks.put(next, patch.getAsJsonObject("files"));
            if (patch.has("aliases"))
                for (var alias : patch.getAsJsonObject("aliases").getAsJsonArray("computers")) {
                    Integer previous = computers.putIfAbsent(alias.getAsInt(), next);
                    if (previous != null && previous != next)
                        throw new IllegalArgumentException("Ambiguous computer alias: " + alias);
                }
            data.putInt("ComputerId", next);
            currentBlock.put("nbt", data);
            computerPositions.put(SchematicPatches.blockPosition(currentBlock), next);
            diskPatches.put(next, patch);
        } finally {
            ccEnd(token);
        }
    }

    void preparePeripheral() throws Exception {
        var patch = blockPatch();
        if (!kind(patch, "computer") && !kind(patch, "peripheral")) return;
        var data = currentBlock.getCompound("nbt");
        long token = ccBegin();
        try {
            var addresses = new LinkedHashMap<String, String>();
            ComputerPatch.peripheralIds(data, owner.level, addresses);
            if (!patch.has("fresh_ids") || !patch.get("fresh_ids").getAsBoolean())
                for (var address : addresses.entrySet())
                    if (peripherals.putIfAbsent(address.getKey(), address.getValue()) != null)
                        throw new IllegalArgumentException(
                                "Ambiguous duplicate peripheral address: " + address.getKey());
            var names = addresses.values().stream().sorted().toList();
            if (names.size() == 1)
                peripheralPositions.put(
                        SchematicPatches.blockPosition(currentBlock), names.getFirst());
            currentBlock.put("nbt", data);
        } finally {
            ccEnd(token);
        }
    }

    void wire() throws Exception {
        var block = currentBlock;
        if (block == null || network == null) return;
        var pos = SchematicPatches.blockPosition(block);
        if (!activeWires.contains(pos)) return;
        var ports = network.ports(pos);
        if (ports.isEmpty()) return;
        long token = ccBegin();
        try {
            var data = block.getCompound("nbt");
            if (!block.contains(SchematicPatches.EMBEDDED)) {
                var old = new LinkedHashMap<String, String>();
                ComputerPatch.peripheralIds(data, owner.level, old);
            }
            var names = new HashMap<BlockPos, String>();
            for (var port : ports) {
                String key = "PeripheralId" + port.suffix(),
                        typeKey = "PeripheralType" + port.suffix();
                if (!data.contains(key)
                        || data.getInt(key) < 0
                        || !data.getString(typeKey).equals(port.type())) {
                    data.putInt(
                            key, ComputerPatch.allocate(owner.level, "peripheral." + port.type()));
                    data.putString(typeKey, port.type());
                }
                names.put(port.device(), port.type() + "_" + data.getInt(key));
            }
            wiredAddresses.put(pos, names);
            block.put("nbt", data);
        } finally {
            ccEnd(token);
        }
    }

    /**
     * Shared alias preflight also applies when a document contains peripherals without computers.
     */
    void prepareAliases() {
        if (aliasesPrepared) return;
        for (var patch : owner.patches)
            if (!ExternalPatches.external(patch) && patch.has("aliases"))
                for (var alias :
                        patch.getAsJsonObject("aliases")
                                .getAsJsonObject("peripherals")
                                .entrySet()) {
                    String current = peripherals.get(alias.getValue().getAsString());
                    if (current != null) {
                        String previous = peripherals.putIfAbsent(alias.getKey(), current);
                        if (previous != null && !previous.equals(current))
                            throw new IllegalArgumentException(
                                    "Ambiguous peripheral alias: " + alias.getKey());
                    }
                }
        aliasesPrepared = true;
    }

    /** Every program is checked before the first computer-program file is written. */
    void prepareReferences() throws Exception {
        prepareAliases();
        for (var disk : disks.entrySet()) {
            var ids = new HashMap<String, Integer>();
            var names = new HashMap<String, String>();
            var patch = diskPatches.get(disk.getKey());
            var at = SchematicPatches.position(patch.getAsJsonArray("pos"));
            var reachable = new HashMap<BlockPos, String>();
            for (var modem : network.wires(at).stream().sorted().toList())
                wiredAddresses.getOrDefault(modem, Map.of()).forEach(reachable::putIfAbsent);
            if (patch.has("references"))
                for (var ref : patch.getAsJsonObject("references").entrySet()) {
                    var target =
                            at.offset(
                                    SchematicPatches.position(
                                            ref.getValue()
                                                    .getAsJsonObject()
                                                    .getAsJsonArray("offset")));
                    String side = ComputerSides.name(owner.sourceDoc, at, target);
                    if (computerPositions.containsKey(target))
                        ids.put(ref.getKey(), computerPositions.get(target));
                    else if (side != null) names.put(ref.getKey(), side);
                    else if (reachable.containsKey(target))
                        names.put(ref.getKey(), reachable.get(target));
                    else if (peripheralPositions.containsKey(target))
                        names.put(ref.getKey(), peripheralPositions.get(target));
                    else
                        throw new IllegalArgumentException(
                                "Named reference has no accessible CC address: "
                                        + ref.getKey()
                                        + " at "
                                        + target
                                        + ". Place it directly next to the computer, or capture a wired-network address.");
                }
            namedIds.put(disk.getKey(), ids);
            namedNames.put(disk.getKey(), names);
            var check = new HashMap<>(peripherals);
            check.putAll(names);
            var programs = ComputerPatch.namedPrograms(disk.getValue(), ids, check);
            ComputerPatch.preflightPrograms(programs, computers, check);
        }
    }

    void finalizeComputerFiles() throws Exception {
        long token = disks.isEmpty() ? 0 : ccBegin();
        try {
            for (var disk : disks.entrySet())
                ComputerPatch.write(
                        owner.level,
                        disk.getKey(),
                        disk.getValue(),
                        computers,
                        peripherals,
                        namedIds.get(disk.getKey()),
                        namedNames.get(disk.getKey()),
                        owner.computerFiles);
        } finally {
            ccEnd(token);
        }
    }

    void prepareStorage() {
        storage = MountedStorageSnapshot.prepare(owner.level, owner.source);
    }

    void preflightRadar() throws Exception {
        RadarPatch.preflight(owner.level);
    }

    /**
     * One existing cooperative validation unit, before provider factories can mutate external
     * state.
     */
    boolean validateWeldStep() {
        if (weldIndex >= weldPairs.size()) return true;
        if (!CannonWelding.available())
            throw new IllegalArgumentException("Required CBC welding provider is unavailable");
        var pair = weldPairs.get(weldIndex++);
        CannonWelding.validatePair(
                owner.sourceDoc, sourcePalette(), pair.from(), pair.to(), owner.level);
        return weldIndex >= weldPairs.size();
    }

    void prepareRadar() {
        var patch = blockPatch();
        if (!kind(patch, "radar")) return;
        var data = currentBlock.getCompound("nbt");
        RadarFilters.prepare(patch.get("block").getAsString(), data);
        data.remove("TargetOffset");
        data.remove("LinkedShipId");
        data.remove("TargetOffsetShip");
        data.remove("LastKnownPos");
        if (patch.has("monitor_controller")) data.remove("Controller");
        var components = data.getCompound("components");
        var custom = components.getCompound("minecraft:custom_data");
        custom.remove("SelectedFiltererPos");
        custom.remove("SelectedMountPos");
        if (components.contains("minecraft:custom_data")) {
            components.put("minecraft:custom_data", custom);
            data.put("components", components);
        }
        currentBlock.put("nbt", data);
    }

    void prepareFuzes() {
        var patch = blockPatch();
        if (patch == null || !patch.has("fuze_bindings")) return;
        var data = currentBlock.getCompound("nbt");
        for (var stack : RadarFuzes.stacks(data)) {
            var components = stack.data().getCompound("components");
            var custom = RadarFuzes.custom(stack.data());
            custom.remove("monitorPos");
            components.put("minecraft:custom_data", custom);
            stack.data().put("components", components);
        }
        currentBlock.put("nbt", data);
    }

    void prepareLogistics() {
        var patch = blockPatch();
        if (!kind(patch, "logistics")) return;
        var data = currentBlock.getCompound("nbt");
        LogisticsPatch.write(patch, data, owner.origin::offset, logisticsNetworks);
        currentBlock.put("nbt", data);
    }

    void prepareArm() {
        var patch = blockPatch();
        if (!kind(patch, "arm")) return;
        var data = currentBlock.getCompound("nbt");
        MechanicalArmPatch.write(patch, data, java.util.function.Function.identity());
        currentBlock.put("nbt", data);
    }

    void restoreStorage() {
        for (var prepared : storage) prepared.restore(owner.level, owner.origin);
    }

    void restoreWelds() throws Exception {
        CannonWelding.restore(owner.level, owner.origin, owner.sourceDoc);
    }

    void restoreDefaultConnections() throws Exception {
        CannonPlacement.initialize(owner.level, owner.origin, owner.sourceDoc);
    }

    void restoreGas() throws Exception {
        for (var patch : owner.patches)
            if (kind(patch, "balloon"))
                AeronauticsPatch.restore(owner.level, patch, owner.origin::offset, restoredGas);
    }

    void restoreMechanism() {
        // A saved child-mechanism flag cannot make an otherwise static schematic physical.
        for (var patch : owner.patches) {
            if (!kind(patch, "mechanism")) continue;
            var local = SchematicPatches.position(patch.getAsJsonArray("pos"));
            if (owner.moved.containsKey(local))
                MechanismPatch.restore(owner.level, patch, this::resolve);
        }
    }

    void restoreDocking() {
        for (var patch : owner.patches)
            if (kind(patch, "docking")) DockingPatch.restore(owner.level, patch, this::resolve);
    }

    void restoreLogistics() {
        for (var patch : owner.patches)
            if (kind(patch, "logistics"))
                LogisticsPatch.finish(owner.level, patch, this::resolve, logisticsNetworks);
    }

    void restoreRadarLocal() throws Exception {
        if (!kind(currentPatch, "radar")) return;
        radarLocal = RadarPatch.beginLocal(owner.level, currentPatch, this::resolve);
        radarLocalPatch = currentPatch;
        if (!currentPatch.has("fuze_bindings")) {
            if (radarLocal != null) radarLocal.commit();
            radarLocal = null;
            radarLocalPatch = null;
        }
    }

    void restoreFuzesLocal() throws Exception {
        if (!kind(currentPatch, "radar") || !currentPatch.has("fuze_bindings")) return;
        if (radarLocalPatch != currentPatch)
            throw new IllegalStateException("Radar local phase did not precede guided fuzes");
        if (radarLocal != null) {
            radarLocal.restoreFuzes();
            radarLocal.commit();
        }
        radarLocal = null;
        radarLocalPatch = null;
    }

    void restoreFuzesReferences() {
        if (currentPatch == null
                || ExternalPatches.external(currentPatch)
                || !currentPatch.has("fuze_bindings")
                || kind(currentPatch, "radar")) return;
        var at = resolve(SchematicPatches.position(currentPatch.getAsJsonArray("pos")));
        var be = owner.level.getBlockEntity(at);
        if (be == null) throw new IllegalStateException("Missing fuze inventory");
        var data = be.saveWithFullMetadata(owner.level.registryAccess());
        RadarFuzes.restore(data, currentPatch, this::resolve);
        be.loadWithComponents(data, owner.level.registryAccess());
        be.setChanged();
        owner.level.sendBlockUpdated(at, be.getBlockState(), be.getBlockState(), 3);
    }

    void restoreRadarReferences() throws Exception {
        if (kind(currentPatch, "radar"))
            RadarPatch.restore(owner.level, currentPatch, this::resolve);
    }

    void restoreArm() {
        if (kind(currentPatch, "arm"))
            MechanicalArmPatch.restore(owner.level, currentPatch, this::resolve);
    }

    void restoreSpring() {
        if (kind(currentPatch, "spring"))
            SpringPatch.restore(owner.level, currentPatch, this::resolve);
    }

    void restoreRope() throws Exception {
        if (kind(currentPatch, "rope"))
            AeronauticsPatch.restore(owner.level, currentPatch, this::resolve, restoredRopes);
    }

    /** Drops preparation-only references after the native template has loaded its owned data. */
    public void releasePreparedInput() {
        copy = null;
        currentBlock = null;
        cachedBlock = null;
        cachedPatch = null;
        sourcePalette = null;
        decodedStates.clear();
        network = null;
        ropeEnds.clear();
        activeWires.clear();
        computers.clear();
        peripherals.clear();
        disks.clear();
        mediaIds.clear();
        mediaOriginals.clear();
        computerPositions.clear();
        peripheralPositions.clear();
        diskPatches.clear();
        wiredAddresses.clear();
        namedIds.clear();
        namedNames.clear();
    }

    /** Called after shared handlers close; transaction commit/compensation remains host-owned. */
    public void close() {
        nativeData.clear();
        currentBlock = null;
        cachedBlock = null;
        cachedPatch = null;
        currentPatch = null;
        copy = null;
        sourcePalette = null;
        radarLocal = null;
        radarLocalPatch = null;
        network = null;
        decodedStates.clear();
        footprints.clear();
        ropeEnds.clear();
        activeWires.clear();
        computers.clear();
        peripherals.clear();
        disks.clear();
        mediaIds.clear();
        mediaOriginals.clear();
        computerPositions.clear();
        peripheralPositions.clear();
        diskPatches.clear();
        wiredAddresses.clear();
        namedIds.clear();
        namedNames.clear();
        logisticsNetworks.clear();
        restoredGas.clear();
        restoredRopes.clear();
        weldPairs = List.of();
        storage = List.of();
    }
}
