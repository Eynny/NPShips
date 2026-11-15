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
    private Map<Integer, net.minecraft.world.level.block.state.BlockState> decodedStates =
            new HashMap<>();
    private Map<Integer, Integer> computers = new LinkedHashMap<>();
    private Map<String, String> peripherals = new LinkedHashMap<>();
    private Map<Integer, JsonObject> disks = new LinkedHashMap<>();
    private Map<Integer, Integer> mediaIds = new HashMap<>();
    private Map<Integer, JsonObject> mediaOriginals = new HashMap<>();
    private Map<BlockPos, Integer> computerPositions = new HashMap<>();
    private Map<BlockPos, String> peripheralPositions = new HashMap<>();
    private Map<Integer, JsonObject> diskPatches = new HashMap<>();
    private Map<BlockPos, Map<BlockPos, String>> wiredAddresses = new HashMap<>();
    private Map<Integer, Map<String, Integer>> namedIds = new HashMap<>();
    private Map<Integer, Map<String, String>> namedNames = new HashMap<>();
    private boolean indexed, aliasesPrepared;
    private int indexPhase, indexRecord;
    private boolean indexComputers, indexWelds;
    private ListTag indexPalette, indexBlocks;
    private Class<?> indexAutocannon;
    private java.util.Iterator<BlockPos> indexPorts;
    private CannonWelds.Pairs weldPreparation;
    private ComputerNetwork.Preparation networkPreparation;
    private ComputerNetwork.Wires wirePreparation;
    private java.util.Iterator<BlockPos> gatheredWires;
    private BlockPos indexComputer;
    private com.eynny.npships.resources.PreparedTemplateResources.Copy<CompoundTag>
            preparedCopyCursor;
    private com.eynny.npships.resources.NbtWorkCursor rawPreparedCopy;
    private com.eynny.npships.resources.PreparedTemplateResources.Copy<ListTag> paletteCopyCursor;
    private com.eynny.npships.resources.NbtWorkCursor rawPaletteCopy;
    private Map<ResourceLocation, Set<BlockPos>> footprints = new LinkedHashMap<>();
    private Set<BlockPos> ropeEnds = new HashSet<>();
    private Set<BlockPos> activeWires = new HashSet<>();
    private ComputerNetwork network;
    private List<CannonWelds.Pair> weldPairs = List.of();
    private int weldIndex;
    private Map<BlockPos, MountedStorageSnapshot.Prepared> storage = new LinkedHashMap<>();
    private Map<BlockPos, JsonObject> initialLogistics = new LinkedHashMap<>();
    private Set<UUID> initializedLogisticsNetworks = new HashSet<>();
    private boolean directPlacement;
    private com.eynny.npships.integration.aeronautics.InitialBalloonGas initialGas;
    private com.eynny.npships.integration.aeronautics.InitialSpringLinks initialSprings;
    private Map<UUID, UUID> logisticsNetworks = new HashMap<>();
    private Set<Object> restoredGas = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<Object> restoredRopes = Collections.newSetFromMap(new IdentityHashMap<>());
    private RadarPatch.LocalState radarLocal;
    private JsonObject radarLocalPatch;

    public BuiltinPatchState(PatchedTemplate owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    private Map<Class<?>, Object> nativeData = new HashMap<>();

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
        return owner.resolveFinalPosition(local);
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

    /**
     * Compatibility entry point; production preparation drains prepareIndexStep with its budget.
     */
    private void index() {
        while (!prepareIndexStep()) {}
    }

    /** One record or palette-copy portion; the driver owns checks between these units. */
    public boolean prepareIndexStep() {
        if (indexed) return true;
        switch (indexPhase) {
            case 0 -> {
                if (indexRecord < owner.patches.size()) {
                    indexPatch(owner.patches.get(indexRecord++));
                    return false;
                }
                indexPalette =
                        owner.source.contains("palette", Tag.TAG_LIST)
                                ? owner.source.getList("palette", Tag.TAG_COMPOUND)
                                : owner.source.getList("palettes", Tag.TAG_LIST).getList(0);
                indexRecord = 0;
                indexPhase = 1;
            }
            case 1 -> {
                if (indexRecord < indexPalette.size()) {
                    var value = indexPalette.getCompound(indexRecord++);
                    if (indexAutocannon == null
                            && ModList.get().isLoaded("createbigcannons")
                            && value.getString("Name").startsWith("createbigcannons:")) {
                        try {
                            indexAutocannon =
                                    Class.forName(
                                            "rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlock");
                        } catch (ClassNotFoundException | LinkageError error) {
                            throw new IllegalArgumentException(
                                    "CBC default connection provider is incompatible", error);
                        }
                    }
                    return false;
                }
                indexBlocks = owner.source.getList("blocks", Tag.TAG_COMPOUND);
                indexRecord = 0;
                indexPhase = 2;
            }
            case 2 -> {
                if (indexRecord < indexBlocks.size()) {
                    var block = indexBlocks.getCompound(indexRecord++);
                    var data = block.getCompound("nbt");
                    indexWelds |= data.contains(CannonWelds.KEY);
                    if (block.contains(MountedStorageSnapshot.KEY, Tag.TAG_COMPOUND))
                        targets("create_mounted_storage");
                    if (indexAutocannon != null && !data.contains("Connections")) {
                        var name =
                                ResourceLocation.tryParse(
                                        indexPalette
                                                .getCompound(block.getInt("state"))
                                                .getString("Name"));
                        if (name != null
                                && name.getNamespace().equals("createbigcannons")
                                && indexAutocannon.isInstance(BuiltInRegistries.BLOCK.get(name)))
                            targets("cbc_default_connections");
                    }
                    return false;
                }
                indexBlocks = null;
                indexAutocannon = null;
                indexPhase = 3;
            }
            case 3 -> {
                if (!stepPaletteCopy(onePortion())) return false;
                indexPalette = null;
                indexPhase = 4;
            }
            case 4 -> {
                if (indexWelds) {
                    if (weldPreparation == null)
                        weldPreparation =
                                CannonWelds.beginPairs(
                                        owner.sourceDoc,
                                        owner.source.getList("blocks", Tag.TAG_COMPOUND),
                                        this::reservePreparation);
                    if (!weldPreparation.step()) return false;
                    weldPairs = weldPreparation.result();
                    weldPreparation.close();
                    weldPreparation = null;
                    if (!weldPairs.isEmpty()) targets("cbc_welds");
                }
                indexPhase = 5;
            }
            case 5 -> {
                if (indexComputers) {
                    if (networkPreparation == null)
                        networkPreparation =
                                ComputerNetwork.prepare(
                                        owner.sourceDoc, sourcePalette, this::reservePreparation);
                    if (!networkPreparation.step()) return false;
                    network = networkPreparation.result();
                    networkPreparation.close();
                    networkPreparation = null;
                }
                indexRecord = 0;
                indexPhase = 6;
            }
            case 6 -> {
                if (gatheredWires != null) {
                    if (gatheredWires.hasNext()) {
                        var at = gatheredWires.next();
                        if (!activeWires.contains(at)) {
                            reservePreparation(8L * Long.BYTES);
                            activeWires.add(at);
                        }
                    } else gatheredWires = null;
                    return false;
                }
                if (wirePreparation != null) {
                    if (!wirePreparation.step()) return false;
                    var found = wirePreparation.result();
                    reservePreparation(8L * Long.BYTES);
                    network.retainWires(indexComputer, found);
                    gatheredWires = found.iterator();
                    wirePreparation.close();
                    wirePreparation = null;
                    indexComputer = null;
                    return false;
                }
                if (indexComputers && indexRecord < owner.patches.size()) {
                    var patch = owner.patches.get(indexRecord++);
                    if (kind(patch, "computer")) {
                        indexComputer = SchematicPatches.position(patch.getAsJsonArray("pos"));
                        wirePreparation =
                                network.beginWires(indexComputer, this::reservePreparation);
                    }
                    return false;
                }
                indexPorts = activeWires.iterator();
                indexPhase = 7;
            }
            case 7 -> {
                if (indexPorts.hasNext()) {
                    var at = indexPorts.next();
                    if (!network.ports(at).isEmpty()) add("computercraft_peripheral", at);
                    return false;
                }
                indexPorts = null;
                indexed = true;
            }
            default -> throw new IllegalStateException("Invalid native index phase");
        }
        return indexed;
    }

    private void indexPatch(JsonObject patch) {
        if (ExternalPatches.external(patch)) return;
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
        indexComputers |= kind.equals("computer");
    }

    private void reservePreparation(long bytes) {
        if (owner.resources != null) owner.resources.reserveCapacity(bytes);
    }

    private static java.util.function.BooleanSupplier onePortion() {
        int[] units = {0};
        return () -> units[0]++ == 0;
    }

    private boolean stepPaletteCopy(java.util.function.BooleanSupplier canContinue) {
        if (sourcePalette != null) return true;
        if (owner.resources != null) {
            if (paletteCopyCursor == null)
                paletteCopyCursor = owner.resources.copyIncrementally(indexPalette);
            if (!paletteCopyCursor.step(canContinue)) return false;
            sourcePalette = paletteCopyCursor.result();
            paletteCopyCursor.close();
            paletteCopyCursor = null;
        } else {
            if (rawPaletteCopy == null)
                rawPaletteCopy =
                        com.eynny.npships.resources.NbtWorkCursor.copy(indexPalette, n -> {});
            if (!rawPaletteCopy.step(canContinue)) return false;
            sourcePalette = (ListTag) rawPaletteCopy.copyResult();
            rawPaletteCopy.close();
            rawPaletteCopy = null;
        }
        return true;
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

    /** Starts/continues the existing private copy with shared-budget node and array checks. */
    public boolean stepPreparedCopy(java.util.function.BooleanSupplier canContinue) {
        if (copy != null) return true;
        if (owner.resources != null) {
            if (preparedCopyCursor == null)
                preparedCopyCursor = owner.resources.copyIncrementally(owner.source);
            if (!preparedCopyCursor.step(canContinue)) return false;
            copy = preparedCopyCursor.result();
            preparedCopyCursor.close();
            preparedCopyCursor = null;
        } else {
            if (rawPreparedCopy == null)
                rawPreparedCopy =
                        com.eynny.npships.resources.NbtWorkCursor.copy(owner.source, n -> {});
            if (!rawPreparedCopy.step(canContinue)) return false;
            copy = (CompoundTag) rawPreparedCopy.copyResult();
            rawPreparedCopy.close();
            rawPreparedCopy = null;
        }
        return true;
    }

    public CompoundTag preparedCopy() {
        if (copy == null) throw new IllegalStateException("Native prepared copy is unfinished");
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
        return owner.resolveFinalPosition(local);
    }

    public ListTag sourcePalette() {
        if (sourcePalette == null) index();
        if (sourcePalette == null)
            throw new IllegalStateException("Native source palette was released");
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
        var prepared = MountedStorageSnapshot.prepare(owner.level, owner.source);
        if (owner.resources != null)
            owner.resources.reserveCapacity(
                    Math.multiplyExact((long) prepared.size(), 2L * Long.BYTES));
        for (var entry : prepared) storage.put(entry.pos(), entry);
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
        var local = SchematicPatches.blockPosition(currentBlock);
        if (!initialLogistics.containsKey(local) && owner.resources != null)
            owner.resources.reserveCapacity(2L * Long.BYTES);
        initialLogistics.put(local, patch);
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

    /** Called once after direct mappings are installed, before the first real seed is written. */
    public void beginDirectPlacement(net.minecraft.world.level.Level source) throws Exception {
        if (directPlacement) throw new IllegalStateException("Direct placement already started");
        directPlacement = true;
        initialGas =
                new com.eynny.npships.integration.aeronautics.InitialBalloonGas(
                        source,
                        owner.patches,
                        owner::findFinalPosition,
                        owner::reserveDirectCapacity);
        initialSprings =
                new com.eynny.npships.integration.aeronautics.InitialSpringLinks(
                        owner.patches, owner::reserveDirectCapacity);
    }

    public boolean prepareDirectPlacementStep() throws Exception {
        if (!directPlacement) throw new IllegalStateException("Direct placement was not started");
        return initialGas.prepareStep() && initialSprings.prepareStep();
    }

    public boolean directPlacement() {
        return directPlacement;
    }

    /**
     * Rebind only detached initial data; unresolved cross-body links stay pending, never guessed.
     */
    public void prepareInitialBlock(BlockPos local, CompoundTag data) {
        if (!directPlacement) throw new IllegalStateException("Direct placement was not started");
        var patch = initialLogistics.get(local);
        if (patch != null) LogisticsPatch.writeInitial(patch, data, logisticsNetworks);
    }

    /** Restore resources exactly once, before the first native tick can change them. */
    public void initialBlockPlaced(
            BlockPos local, net.minecraft.world.level.block.entity.BlockEntity entity)
            throws Exception {
        if (!directPlacement) throw new IllegalStateException("Direct placement was not started");
        if (initialGas != null) initialGas.initialBlockPlaced(entity);
        if (initialSprings != null) initialSprings.initialBlockPlaced(local, entity);
        var prepared = storage.get(local);
        var logistics = initialLogistics.get(local);
        if (prepared == null && logistics == null) return;
        var actual = resolve(local);
        if (entity == null
                || !entity.getBlockPos().equals(actual)
                || entity.isRemoved()
                || owner.level.getBlockEntity(actual) != entity)
            throw new IllegalStateException("Initial native block is not current at " + actual);
        if (prepared != null) {
            prepared.restore(owner.level, this::resolve);
            storage.remove(local);
        }
        if (logistics != null) {
            LogisticsPatch.initializeNetworks(
                    logistics, logisticsNetworks, initializedLogisticsNetworks);
            initialLogistics.remove(local);
        }
    }

    void restoreStorage() {
        if (directPlacement) {
            if (!storage.isEmpty())
                throw new IllegalStateException("Direct mounted storage missed initial placement");
            return;
        }
        for (var prepared : storage.values()) prepared.restore(owner.level, this::resolve);
        storage = new LinkedHashMap<>();
    }

    void restoreWelds() throws Exception {
        CannonWelding.restore(owner.level, this::resolve, owner.sourceDoc);
    }

    void restoreDefaultConnections() throws Exception {
        CannonPlacement.initialize(owner.level, this::resolve, owner.sourceDoc);
    }

    void restoreGas() throws Exception {
        if (directPlacement) {
            if (initialGas != null) initialGas.finish(owner.level);
            return;
        }
        for (var patch : owner.patches)
            if (kind(patch, "balloon"))
                AeronauticsPatch.restore(owner.level, patch, this::resolve, restoredGas);
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

    void restoreLogistics() throws Exception {
        if (directPlacement && !initialLogistics.isEmpty())
            throw new IllegalStateException("Direct logistics missed initial placement");
        for (var patch : owner.patches)
            if (kind(patch, "logistics")) {
                if (directPlacement)
                    LogisticsPatch.finishReferences(owner.level, patch, this::resolve);
                else LogisticsPatch.finish(owner.level, patch, this::resolve, logisticsNetworks);
            }
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
        if (kind(currentPatch, "spring")) {
            if (directPlacement) initialSprings.verify(currentPatch);
            else SpringPatch.restore(owner.level, currentPatch, this::resolve);
        }
    }

    void restoreRope() throws Exception {
        if (kind(currentPatch, "rope"))
            AeronauticsPatch.restore(owner.level, currentPatch, this::resolve, restoredRopes);
    }

    /** Drops preparation-only references after the native template has loaded its owned data. */
    public void releasePreparedInput() {
        closePreparationCursors();
        copy = null;
        currentBlock = null;
        cachedBlock = null;
        cachedPatch = null;
        sourcePalette = null;
        decodedStates = new HashMap<>();
        network = null;
        ropeEnds = new HashSet<>();
        activeWires = new HashSet<>();
        computers = new LinkedHashMap<>();
        peripherals = new LinkedHashMap<>();
        disks = new LinkedHashMap<>();
        mediaIds = new HashMap<>();
        mediaOriginals = new HashMap<>();
        computerPositions = new HashMap<>();
        peripheralPositions = new HashMap<>();
        diskPatches = new HashMap<>();
        wiredAddresses = new HashMap<>();
        namedIds = new HashMap<>();
        namedNames = new HashMap<>();
    }

    /** Called after shared handlers close; transaction commit/compensation remains host-owned. */
    private void closePreparationCursors() {
        if (preparedCopyCursor != null) preparedCopyCursor.close();
        if (rawPreparedCopy != null) rawPreparedCopy.close();
        if (paletteCopyCursor != null) paletteCopyCursor.close();
        if (rawPaletteCopy != null) rawPaletteCopy.close();
        if (weldPreparation != null) weldPreparation.close();
        if (networkPreparation != null) networkPreparation.close();
        if (wirePreparation != null) wirePreparation.close();
        weldPreparation = null;
        networkPreparation = null;
        wirePreparation = null;
        gatheredWires = null;
        indexComputer = null;
        preparedCopyCursor = null;
        rawPreparedCopy = null;
        paletteCopyCursor = null;
        rawPaletteCopy = null;
        indexPalette = indexBlocks = null;
        indexPorts = null;
        indexAutocannon = null;
    }

    public void beginCleanup() {
        if (initialGas != null) initialGas.beginCleanup();
        if (initialSprings != null) initialSprings.beginCleanup();
    }

    public boolean stepCleanup() {
        beginCleanup();
        closePreparationCursors();
        if (initialGas != null && !initialGas.stepCleanup()) return false;
        initialGas = null;
        if (initialSprings != null && !initialSprings.stepCleanup()) return false;
        initialSprings = null;
        nativeData = new HashMap<>();
        currentBlock = null;
        cachedBlock = null;
        cachedPatch = null;
        currentPatch = null;
        copy = null;
        sourcePalette = null;
        radarLocal = null;
        radarLocalPatch = null;
        network = null;
        decodedStates = new HashMap<>();
        footprints = new LinkedHashMap<>();
        ropeEnds = new HashSet<>();
        activeWires = new HashSet<>();
        computers = new LinkedHashMap<>();
        peripherals = new LinkedHashMap<>();
        disks = new LinkedHashMap<>();
        mediaIds = new HashMap<>();
        mediaOriginals = new HashMap<>();
        computerPositions = new HashMap<>();
        peripheralPositions = new HashMap<>();
        diskPatches = new HashMap<>();
        wiredAddresses = new HashMap<>();
        namedIds = new HashMap<>();
        namedNames = new HashMap<>();
        logisticsNetworks = new HashMap<>();
        restoredGas = Collections.newSetFromMap(new IdentityHashMap<>());
        restoredRopes = Collections.newSetFromMap(new IdentityHashMap<>());
        weldPairs = List.of();
        storage = new LinkedHashMap<>();
        initialLogistics = new LinkedHashMap<>();
        initializedLogisticsNetworks = new HashSet<>();
        return true;
    }

    public void close() {
        while (!stepCleanup()) {}
    }
}
