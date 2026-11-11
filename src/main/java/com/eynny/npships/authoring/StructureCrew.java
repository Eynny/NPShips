package com.eynny.npships.authoring;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.resources.PreparedTemplateResources;
import com.eynny.npships.resources.ResourceWorkMeter;
import com.eynny.npships.resources.ServerResourcePolicy;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.EmptyBlockGetter;

import java.util.*;

/** Bounded random crew generation in the composed template, before any world mutation. */
public final class StructureCrew {
    public record Choice(String type, int weight, CompoundTag state, CrewEquipment equipment) {
        public Choice(String type, int weight, CompoundTag state) {
            this(type, weight, state, CrewEquipment.empty());
        }
    }

    public record Rule(
            String id,
            BlockPos origin,
            BlockPos size,
            int count,
            int minCount,
            boolean ranged,
            String name,
            String placement,
            List<Choice> pool,
            CompoundTag state,
            boolean preventDespawn) {
        public Rule(
                String id,
                BlockPos origin,
                BlockPos size,
                int count,
                int minCount,
                boolean ranged,
                String name,
                String placement,
                List<Choice> pool,
                CompoundTag state) {
            this(id, origin, size, count, minCount, ranged, name, placement, pool, state, true);
        }
    }

    private StructureCrew() {}

    public static List<Rule> read(JsonObject json) {
        if (json == null || !json.has("crew")) return List.of();
        var result = new ArrayList<Rule>();
        var names = new HashSet<String>();
        for (var value : json.getAsJsonArray("crew")) {
            var rule = value.getAsJsonObject();
            String id = rule.get("id").getAsString();
            if (id.isBlank() || id.length() > 80 || !names.add(id))
                throw new IllegalArgumentException(
                        "Crew rule needs a unique name (1..80 characters).");
            int fallback = integer(rule, "count", 1, 0, Integer.MAX_VALUE);
            int minCount = integer(rule, "min_count", 0, 0, Integer.MAX_VALUE),
                    count = integer(rule, "max_count", fallback, 0, Integer.MAX_VALUE);
            if (minCount > count)
                throw new IllegalArgumentException("min_count must not exceed max_count");
            String name = rule.has("name") ? rule.get("name").getAsString() : "";
            if (name.length() > 256) throw new IllegalArgumentException("Mob name is too long");
            BlockPos origin = vector(rule, "origin", 0), size = vector(rule, "size", 1);
            String placement =
                    rule.has("placement") ? rule.get("placement").getAsString() : "ground";
            if (!Set.of("ground", "air", "water").contains(placement))
                throw new IllegalArgumentException("Crew placement: ground / air / water.");
            if (rule.has("prevent_despawn")
                    && (!rule.get("prevent_despawn").isJsonPrimitive()
                            || !rule.getAsJsonPrimitive("prevent_despawn").isBoolean()))
                throw new IllegalArgumentException("prevent_despawn must be true or false");
            var pool = new ArrayList<Choice>();
            for (var element : rule.getAsJsonArray("pool")) {
                var choice = element.getAsJsonObject();
                String type = choice.get("type").getAsString();
                ResourceLocation.parse(type.startsWith("#") ? type.substring(1) : type);
                pool.add(
                        new Choice(
                                type,
                                integer(choice, "weight", 1, 1, 1_000_000),
                                readState(choice),
                                CrewEquipment.read(choice)));
            }
            if (pool.isEmpty() || pool.size() > 64)
                throw new IllegalArgumentException("Crew pool needs 1..64 types/tags.");
            CompoundTag state = readState(rule);
            result.add(
                    new Rule(
                            id,
                            origin,
                            size,
                            count,
                            minCount,
                            rule.has("min_count") || rule.has("max_count"),
                            name,
                            placement,
                            List.copyOf(pool),
                            state,
                            !rule.has("prevent_despawn")
                                    || rule.get("prevent_despawn").getAsBoolean()));
        }
        return List.copyOf(result);
    }

    private static CompoundTag readState(JsonObject rule) {
        CompoundTag state = new CompoundTag();
        if (rule.has("nbt"))
            try {
                state =
                        com.eynny.npships.authoring.document.SnbtText.parse(
                                rule.get("nbt").getAsString());
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid crew SNBT: " + e.getMessage());
            }
        for (String key :
                List.of("UUID", "Pos", "Motion", "Passengers", "id", "Leash", "RootVehicle"))
            if (state.contains(key))
                throw new IllegalArgumentException(
                        "Crew state must not contain runtime identity/link field: " + key);
        return state;
    }

    private static int integer(JsonObject j, String key, int fallback, int min, int max) {
        int value =
                j.has(key)
                        ? com.eynny.npships.authoring.validation.JsonNumbers.integer(
                                j.get(key), key)
                        : fallback;
        if (value < min || value > max)
            throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        return value;
    }

    private static BlockPos vector(JsonObject j, String key, int min) {
        var v = j.getAsJsonArray(key);
        if (v.size() != 3) throw new IllegalArgumentException(key + " requires X Y Z.");
        int[] a = new int[3];
        for (int i = 0; i < 3; i++) {
            a[i] = com.eynny.npships.authoring.validation.JsonNumbers.integer(v.get(i), key);
            if (a[i] < min || a[i] > 512) throw new IllegalArgumentException("Invalid " + key);
        }
        return new BlockPos(a[0], a[1], a[2]);
    }

    public static void validateBounds(SchematicDocument document, JsonObject config) {
        for (var rule : read(config))
            if (!document.contains(rule.origin())
                    || !document.contains(rule.origin().offset(rule.size()).offset(-1, -1, -1)))
                throw new IllegalArgumentException("Crew region outside schematic: " + rule.id());
    }

    public static void apply(CompoundTag template, JsonObject config, ServerLevel level) {
        apply(template, config, level, level.random);
    }

    /**
     * Detached preview uses the same generation rules and its own random source. No world writes.
     */
    public static void apply(
            CompoundTag template,
            JsonObject config,
            net.minecraft.world.level.Level level,
            net.minecraft.util.RandomSource random) {
        apply(template, config, level, random, null);
    }

    public static void apply(
            CompoundTag template,
            JsonObject config,
            net.minecraft.world.level.Level level,
            net.minecraft.util.RandomSource random,
            @org.jetbrains.annotations.Nullable SpawnTimingProfiler timing) {
        apply(template, config, level, random, timing, null);
    }

    static void apply(
            CompoundTag template,
            JsonObject config,
            net.minecraft.world.level.Level level,
            net.minecraft.util.RandomSource random,
            @org.jetbrains.annotations.Nullable SpawnTimingProfiler timing,
            @org.jetbrains.annotations.Nullable CrewResourceBudget resources) {
        applyOwned(template, config, level, random, timing, resources, null);
    }

    public static void applyPrepared(
            CompoundTag template,
            JsonObject config,
            net.minecraft.world.level.Level level,
            net.minecraft.util.RandomSource random,
            @org.jetbrains.annotations.Nullable SpawnTimingProfiler timing,
            PreparedTemplateResources memory) {
        applyOwned(template, config, level, random, timing, null, memory);
    }

    private static void applyOwned(
            CompoundTag template,
            JsonObject config,
            net.minecraft.world.level.Level level,
            net.minecraft.util.RandomSource random,
            @org.jetbrains.annotations.Nullable SpawnTimingProfiler timing,
            @org.jetbrains.annotations.Nullable CrewResourceBudget resources,
            @org.jetbrains.annotations.Nullable PreparedTemplateResources memory) {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.CREW);
        try {
            var rules = read(config);
            if (rules.isEmpty()) return;
            if (resources == null)
                resources = new CrewResourceBudget(ServerResourcePolicy.current(level.getServer()));
            var doc = new SchematicDocument(template, null);
            validateBounds(doc, config);
            if (doc.hasMultiplePalettes())
                throw new IllegalArgumentException("Crew generation requires a single palette.");
            var palette = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
            for (var value : doc.paletteCopy())
                palette.add(
                        NbtUtils.readBlockState(
                                level.holderLookup(Registries.BLOCK), (CompoundTag) value));
            var lookup = level.holderLookup(Registries.ENTITY_TYPE);
            record Resolved(Choice choice, List<EntityType<?>> types) {}
            var resolved = new LinkedHashMap<Rule, List<Resolved>>();
            // Resolve every pool before committing entities, including rules that later find no
            // positions.
            for (var rule : rules) {
                var choices = new ArrayList<Resolved>();
                for (var choice : rule.pool()) {
                    choice.equipment().validate(level.registryAccess());
                    List<EntityType<?>> types;
                    if (choice.type().startsWith("#")) {
                        var members =
                                lookup.getOrThrow(
                                        TagKey.create(
                                                Registries.ENTITY_TYPE,
                                                ResourceLocation.parse(
                                                        choice.type().substring(1))));
                        resources.planFactories(members.size());
                        types = members.stream().map(net.minecraft.core.Holder::value).toList();
                    } else {
                        resources.planFactories(1);
                        types =
                                List.of(
                                        lookup.getOrThrow(
                                                        ResourceKey.create(
                                                                Registries.ENTITY_TYPE,
                                                                ResourceLocation.parse(
                                                                        choice.type())))
                                                .value());
                    }
                    if (types.isEmpty())
                        throw new IllegalArgumentException("Empty crew tag: " + choice.type());
                    choices.add(new Resolved(choice, types));
                }
                resolved.put(rule, List.copyOf(choices));
            }
            // Admit the entire expanded plan before any native factory. Duplicates, tag members
            // and zero-count rules still submit their original factories in the original order.
            for (var choices : resolved.values())
                for (var choice : choices)
                    for (var type : choice.types()) {
                        resources.charge(CrewResourceBudget.Family.FACTORY_PROBE);
                        if (!(type.create(level) instanceof Mob))
                            throw new IllegalArgumentException(
                                    "Crew pool contains a non-mob type: "
                                            + EntityType.getKey(type));
                    }
            var originalEntities = template.getList("entities", Tag.TAG_COMPOUND);
            var entities =
                    memory == null
                            ? originalEntities.copy()
                            : memory.copyEntities(originalEntities);
            long entityCapacity = originalEntities.size();
            for (var rule : rules) {
                var choices = resolved.get(rule);
                int sum = choices.stream().mapToInt(c -> c.choice().weight()).sum();
                int
                        requested =
                                rule.ranged()
                                        ? rule.minCount()
                                                + (int)
                                                        (random.nextDouble()
                                                                * ((long) rule.count()
                                                                        - rule.minCount()
                                                                        + 1))
                                        : rule.count(),
                        created = 0;
                // Sample at the existing draw boundary; maxima are not substituted for the draw.
                // All rules share this composition budget, including impossible iterations.
                resources.request(requested);
                var fallbackPositions = new java.util.HashMap<String, BlockPos>();
                var impossible = new HashSet<String>();
                var collisionResults = new HashMap<String, Map<BlockPos, Boolean>>();
                for (int i = 0; i < requested; i++) {
                    int roll = random.nextInt(sum);
                    Resolved choice = choices.getFirst();
                    for (var c : choices) {
                        roll -= c.choice().weight();
                        if (roll < 0) {
                            choice = c;
                            break;
                        }
                    }
                    var type = choice.types().get(random.nextInt(choice.types().size()));
                    resources.charge(CrewResourceBudget.Family.GENERATED_FACTORY);
                    var mob = (Mob) type.create(level);
                    var state = rule.state().copy();
                    state.merge(choice.choice().state());
                    var authoredHands = state.get("HandItems");
                    var authoredArmor = state.get("ArmorItems");
                    var equipment = choice.choice().equipment().apply(state, random, resources);
                    // This explicit zone policy also survives native finalizeSpawn and reload.
                    state.putBoolean("PersistenceRequired", rule.preventDespawn());
                    resources.charge(CrewResourceBudget.Family.NATIVE_LOAD);
                    mob.load(state);
                    if (!rule.name().isEmpty())
                        mob.setCustomName(
                                net.minecraft.network.chat.Component.literal(rule.name()));
                    boolean placed = false;
                    String placementKey =
                            EntityType.getKey(type) + ":" + mob.getDimensions(mob.getPose());
                    if (impossible.contains(placementKey)) continue;
                    var tested =
                            collisionResults.computeIfAbsent(placementKey, key -> new HashMap<>());
                    var fallback = fallbackPositions.get(placementKey);
                    long cells =
                            (long) rule.size().getX() * rule.size().getY() * rule.size().getZ();
                    for (long attempt = 0;
                            attempt < 64 + (fallback == null ? cells : 1);
                            attempt++) {
                        resources.charge(CrewResourceBudget.Family.POSITION_TRIAL);
                        long cellIndex = attempt - 64;
                        var pos =
                                attempt < 64
                                        ? rule.origin()
                                                .offset(
                                                        random.nextInt(rule.size().getX()),
                                                        random.nextInt(rule.size().getY()),
                                                        random.nextInt(rule.size().getZ()))
                                        : fallback != null
                                                ? fallback
                                                : rule.origin()
                                                        .offset(
                                                                (int)
                                                                        (cellIndex
                                                                                % rule.size()
                                                                                        .getX()),
                                                                (int)
                                                                        (cellIndex
                                                                                / rule.size().getX()
                                                                                % rule.size()
                                                                                        .getY()),
                                                                (int)
                                                                        (cellIndex
                                                                                / rule.size().getX()
                                                                                / rule.size()
                                                                                        .getY()));
                        Boolean cachedClear = tested.get(pos);
                        if (Boolean.FALSE.equals(cachedClear)) continue;
                        var box =
                                mob.getDimensions(mob.getPose())
                                        .makeBoundingBox(
                                                pos.getX() + .5, pos.getY(), pos.getZ() + .5);
                        if (cachedClear == null) {
                            validateBodyBounds(box, rule.id());
                            // The zone constrains the spawn point, not the mob's complete bounding
                            // box.
                            // Still check the full body for collision with schematic blocks below.
                            boolean clear = true;
                            for (var cell :
                                    BlockPos.betweenClosed(
                                            net.minecraft.util.Mth.floor(box.minX),
                                            net.minecraft.util.Mth.floor(box.minY),
                                            net.minecraft.util.Mth.floor(box.minZ),
                                            net.minecraft.util.Mth.floor(box.maxX - 1e-6),
                                            net.minecraft.util.Mth.floor(box.maxY - 1e-6),
                                            net.minecraft.util.Mth.floor(box.maxZ - 1e-6))) {
                                resources.charge(CrewResourceBudget.Family.DOCUMENT_READ);
                                var block = state(doc, palette, cell);
                                resources.charge(CrewResourceBudget.Family.COLLISION_QUERY);
                                for (var collision :
                                        block.getCollisionShape(EmptyBlockGetter.INSTANCE, cell)
                                                .toAabbs()) {
                                    resources.charge(CrewResourceBudget.Family.SHAPE_BOX);
                                    if (collision.move(cell).intersects(box)) {
                                        clear = false;
                                        break;
                                    }
                                }
                                resources.charge(CrewResourceBudget.Family.FLUID_TEST);
                                if (rule.placement().equals("water")
                                        && !block.getFluidState()
                                                .is(net.minecraft.tags.FluidTags.WATER))
                                    clear = false;
                                if (!rule.placement().equals("water")
                                        && !block.getFluidState().isEmpty()) clear = false;
                                if (!clear) break;
                            }
                            if (!clear) {
                                if (tested.size() < 65536) tested.put(pos.immutable(), false);
                                continue;
                            }
                            if (rule.placement().equals("ground")) {
                                resources.charge(CrewResourceBudget.Family.DOCUMENT_READ);
                                var support = state(doc, palette, pos.below());
                                resources.charge(CrewResourceBudget.Family.SUPPORT_TEST);
                                if (!support.isFaceSturdy(
                                        EmptyBlockGetter.INSTANCE,
                                        pos.below(),
                                        net.minecraft.core.Direction.UP)) {
                                    if (tested.size() < 65536) tested.put(pos.immutable(), false);
                                    continue;
                                }
                            }
                            if (tested.size() < 65536) tested.put(pos.immutable(), true);
                        }
                        var saved = new CompoundTag();
                        resources.charge(CrewResourceBudget.Family.NATIVE_SAVE);
                        mob.save(saved);
                        // Native equipment/variants require server spawn initialization at the real
                        // placement position, not while composing a detached template.
                        var persistent = saved.getCompound("NeoForgeData");
                        if (equipment == null)
                            persistent.put("npships_initial_spawn", state.copy());
                        else {
                            // Native list serialization needs padded siblings for detached preview.
                            // Only authored lists, then individually selected slots, override the
                            // actual native finalizeSpawn defaults at the real placement position.
                            if (authoredHands == null) state.remove("HandItems");
                            else state.put("HandItems", authoredHands);
                            if (authoredArmor == null) state.remove("ArmorItems");
                            else state.put("ArmorItems", authoredArmor);
                            var pending = new CompoundTag();
                            pending.putInt("npships:format", 2);
                            pending.put("overrides", state.copy());
                            pending.put("equipment", equipment.copy());
                            persistent.put("npships_initial_spawn", pending);
                        }
                        saved.put("NeoForgeData", persistent);
                        saved.remove("UUID");
                        saved.remove("Pos");
                        saved.remove("Motion");
                        saved.putString("id", EntityType.getKey(type).toString());
                        var entry = new CompoundTag();
                        entry.put("nbt", saved);
                        entry.put("blockPos", StructureSegments.ints(pos));
                        var location = new ListTag();
                        location.add(DoubleTag.valueOf(pos.getX() + .5));
                        location.add(DoubleTag.valueOf(pos.getY()));
                        location.add(DoubleTag.valueOf(pos.getZ() + .5));
                        entry.put("pos", location);
                        long previousEntityCapacity = -1;
                        if (memory != null) {
                            // Native Mob.save already returned: this bounds retained insertion, not
                            // that atomic callback's allocation peak or provider/native state.
                            memory.retain(entry);
                            if (entities.size() == entityCapacity) {
                                long next =
                                        entityCapacity == 0
                                                ? 1
                                                : entityCapacity + Math.max(1, entityCapacity / 2);
                                // ListTag.copy uses ArrayList(Collection), including an explicit
                                // empty
                                // backing array: first append grows to one, not the default ten.
                                memory.reserveEntityGrowth(next);
                                previousEntityCapacity = entityCapacity;
                                entityCapacity = next;
                            }
                        }
                        entities.add(entry);
                        if (previousEntityCapacity >= 0)
                            memory.entityGrowthCompleted(previousEntityCapacity);
                        fallbackPositions.putIfAbsent(placementKey, pos.immutable());
                        created++;
                        if (timing != null) timing.crewCreated();
                        placed = true;
                        break;
                    }
                    if (!placed) impossible.add(placementKey);
                    if (!placed
                            && com.eynny.npships.config.NPShipsRuntimeConfig.developerModeEnabled())
                        com.mojang.logging.LogUtils.getLogger()
                                .debug(
                                        "[NPShips crew] No free position for {} in {} after checking the zone",
                                        EntityType.getKey(type),
                                        rule.id());
                }
                if (com.eynny.npships.config.NPShipsRuntimeConfig.developerModeEnabled())
                    com.mojang.logging.LogUtils.getLogger()
                            .info(
                                    "[NPShips mob zone] {}: requested={}, created={}, placement={}",
                                    rule.id(),
                                    requested,
                                    created,
                                    rule.placement());
                if (created < rule.minCount())
                    throw new IllegalArgumentException(
                            "Mob zone "
                                    + rule.id()
                                    + ": found space for "
                                    + created
                                    + ", minimum is "
                                    + rule.minCount());
            }
            template.put("entities", entities);

        } finally {

            if (memory != null && resources != null)
                memory.work().record(ResourceWorkMeter.Family.CREW_PRIMITIVE, resources.work());
            if (spawnTiming != null) {
                if (resources != null)
                    spawnTiming.crewResources(
                            resources.requested(), resources.factoryProbes(), resources.work());
                spawnTiming.end(spawnToken);
            }
        }
    }

    private static void validateBodyBounds(net.minecraft.world.phys.AABB box, String rule) {
        if (!nativeCoordinate(box.minX)
                || !nativeCoordinate(box.minY)
                || !nativeCoordinate(box.minZ)
                || !nativeCoordinate(box.maxX)
                || !nativeCoordinate(box.maxY)
                || !nativeCoordinate(box.maxZ))
            throw new IllegalArgumentException(
                    "Crew body is outside native coordinate range in rule: " + rule);
        try {
            long x = (long) Math.floor(box.maxX - 1e-6) - (long) Math.floor(box.minX) + 1;
            long y = (long) Math.floor(box.maxY - 1e-6) - (long) Math.floor(box.minY) + 1;
            long z = (long) Math.floor(box.maxZ - 1e-6) - (long) Math.floor(box.minZ) + 1;
            if (x < 0 || y < 0 || z < 0) throw new ArithmeticException("negative body span");
            // Native betweenClosed uses an int-sized iterator product. Reject its overflow
            // before invoking it; this is representability, not an administrator body-size cap.
            Math.toIntExact(Math.multiplyExact(Math.multiplyExact(x, y), z));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Crew body volume overflows native traversal in rule: " + rule, e);
        }
    }

    private static boolean nativeCoordinate(double value) {
        return Double.isFinite(value) && value > Integer.MIN_VALUE && value < Integer.MAX_VALUE;
    }

    private static net.minecraft.world.level.block.state.BlockState state(
            SchematicDocument doc,
            List<net.minecraft.world.level.block.state.BlockState> palette,
            BlockPos pos) {
        var index = doc.blocks().get(pos);
        return index == null
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : palette.get(index);
    }
}
