package com.eynny.npships.authoring;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.resources.PreparedTemplateResources;
import com.eynny.npships.resources.ResourceWorkMeter;
import com.eynny.npships.resources.ServerResourcePolicy;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Existing crew draw/search algorithm split at actual host loops and native callback boundaries.
 */
final class CrewPreparation implements AutoCloseable {
    private enum Phase {
        READ,
        DOCUMENT_COPY,
        BOUNDS,
        PALETTE,
        RESOLVE,
        MEMBERS,
        FACTORIES,
        ENTITIES_COPY,
        RULE,
        CREATE,
        RULE_STATE,
        CHOICE_STATE,
        MERGE,
        EQUIPMENT,
        LOAD,
        POSITION,
        CELL,
        COLLISION,
        SHAPES,
        FLUID,
        SUPPORT_READ,
        SUPPORT_TEST,
        SAVE,
        OVERRIDES,
        EQUIPMENT_COPY,
        ENTRY,
        RETAIN,
        APPEND,
        NEXT,
        FINISH,
        DONE
    }

    private record Resolved(StructureCrew.Choice choice, List<EntityType<?>> types) {}

    private record RulePlan(StructureCrew.Rule rule, List<Resolved> choices) {}

    private record MergeFrame(CompoundTag target, CompoundTag source, Iterator<String> keys) {}

    private CompoundTag template;
    private JsonObject config;
    private final Level level;
    private final RandomSource random;
    private final SpawnTimingProfiler timing;
    private final PreparedTemplateResources memory;
    private CrewResourceBudget resources;
    private List<StructureCrew.Rule> rules;
    private CrewRuleRead ruleRead;
    private SchematicDocument doc;
    private CompoundTag documentTag;
    private ListTag nativePalette, entities;
    private List<BlockState> palette = new ArrayList<>();
    private List<RulePlan> plans = new ArrayList<>();
    private PreparedTemplateResources.Copy<? extends Tag> copy;
    private PreparedTemplateResources.Retention retention;
    private SchematicDocument.RuntimeRead document;
    private CrewEquipment.Validation equipmentValidation;
    private CrewEquipment.Application equipmentApplication;
    private final ArrayDeque<MergeFrame> merging = new ArrayDeque<>();
    private Phase phase = Phase.READ;
    private int ruleIndex, choiceIndex, memberIndex, paletteIndex;
    private List<Resolved> choices;
    private StructureCrew.Choice resolving;
    private Iterator<Holder<EntityType<?>>> members;
    private List<EntityType<?>> types;
    private StructureCrew.Rule rule;
    private Resolved selected;
    private EntityType<?> type;
    private Mob mob;
    private CompoundTag state, equipment, saved, pending, entry;
    private Tag authoredHands, authoredArmor;
    private long entityCapacity, attempt, attemptLimit, progress;
    private int requested, created, iteration, sum;
    private Map<String, BlockPos> fallbacks;
    private Set<String> impossible;
    private Map<String, Map<BlockPos, Boolean>> collisionResults;
    private Map<BlockPos, Boolean> tested;
    private String placementKey;
    private BlockPos fallback, pos, cell;
    private AABB box;
    private Iterator<BlockPos> cells;
    private Iterator<AABB> shapes;
    private BlockState block;
    private boolean clear, closed, failed, reported;

    CrewPreparation(
            CompoundTag template,
            JsonObject config,
            Level level,
            RandomSource random,
            SpawnTimingProfiler timing,
            PreparedTemplateResources memory) {
        this.template = Objects.requireNonNull(template);
        this.config = config;
        this.level = level;
        this.random = Objects.requireNonNull(random);
        this.timing = timing;
        this.memory = Objects.requireNonNull(memory);
    }

    public boolean step(BooleanSupplier allowed) {
        if (closed || failed)
            throw new IllegalStateException("Crew preparation is closed or failed");
        Objects.requireNonNull(allowed);
        long token = timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.CREW);
        try {
            while (phase != Phase.DONE) {
                if (ruleRead != null) {
                    long before = ruleRead.progress();
                    boolean done = ruleRead.step(allowed);
                    progress += ruleRead.progress() - before;
                    if (!done) return false;
                    rules = ruleRead.result();
                    ruleRead.close();
                    ruleRead = null;
                    if (rules.isEmpty()) phase = Phase.DONE;
                    else {
                        resources =
                                new CrewResourceBudget(
                                        ServerResourcePolicy.current(level.getServer()));
                        phase = Phase.DOCUMENT_COPY;
                    }
                } else if (copy != null) {
                    long before = copy.progress();
                    boolean done = copy.step(allowed);
                    progress += copy.progress() - before;
                    if (!done) return false;
                    var value = copy.result();
                    copy.close();
                    copy = null;
                    copied(value);
                } else if (document != null) {
                    long before = document.progress();
                    boolean done = document.step(allowed);
                    progress += document.progress() - before;
                    if (!done) return false;
                    doc = document.result();
                    document.close();
                    document = null;
                    ruleIndex = 0;
                    phase = Phase.BOUNDS;
                } else if (equipmentValidation != null) {
                    long before = equipmentValidation.progress();
                    boolean done = equipmentValidation.step(allowed);
                    progress += equipmentValidation.progress() - before;
                    if (!done) return false;
                    equipmentValidation.close();
                    equipmentValidation = null;
                    phase = Phase.MEMBERS;
                } else if (equipmentApplication != null) {
                    long before = equipmentApplication.progress();
                    boolean done = equipmentApplication.step(allowed);
                    progress += equipmentApplication.progress() - before;
                    if (!done) return false;
                    equipment = equipmentApplication.result();
                    equipmentApplication.close();
                    equipmentApplication = null;
                    phase = Phase.LOAD;
                } else if (retention != null) {
                    long before = retention.progress();
                    boolean done = retention.step(allowed);
                    progress += retention.progress() - before;
                    if (!done) return false;
                    retention.close();
                    retention = null;
                    phase = Phase.APPEND;
                } else {
                    if (!allowed.getAsBoolean()) return false;
                    one();
                    progress++;
                }
            }
            report();
            return true;
        } catch (RuntimeException | Error error) {
            failed = true;
            report();
            clearReferences();
            throw error;
        } finally {
            if (timing != null) timing.end(token);
        }
    }

    private void copied(Tag value) {
        switch (phase) {
            case DOCUMENT_COPY -> {
                documentTag = (CompoundTag) value;
                document =
                        SchematicDocument.beginOwnedRuntime(documentTag, memory::reserveCapacity);
            }
            case ENTITIES_COPY -> {
                entities = (ListTag) value;
                entityCapacity = entities.size();
                ruleIndex = 0;
                phase = Phase.RULE;
            }
            case RULE_STATE -> {
                state = (CompoundTag) value;
                phase = Phase.CHOICE_STATE;
            }
            case CHOICE_STATE -> {
                var source = (CompoundTag) value;
                merging.push(new MergeFrame(state, source, source.getAllKeys().iterator()));
                phase = Phase.MERGE;
            }
            case OVERRIDES -> {
                var persistent = saved.getCompound("NeoForgeData");
                if (equipment == null) {
                    persistent.put("npships_initial_spawn", value);
                    saved.put("NeoForgeData", persistent);
                    phase = Phase.ENTRY;
                } else {
                    pending = new CompoundTag();
                    pending.putInt("npships:format", 2);
                    pending.put("overrides", value);
                    phase = Phase.EQUIPMENT_COPY;
                }
            }
            case EQUIPMENT_COPY -> {
                pending.put("equipment", value);
                var persistent = saved.getCompound("NeoForgeData");
                persistent.put("npships_initial_spawn", pending);
                saved.put("NeoForgeData", persistent);
                pending = null;
                phase = Phase.ENTRY;
            }
            default -> throw new IllegalStateException("Crew copy lost continuation");
        }
    }

    private void one() {
        switch (phase) {
            case READ -> ruleRead = new CrewRuleRead(config, memory::reserveCapacity);
            case DOCUMENT_COPY -> copy = memory.copyIncrementally(template);
            case BOUNDS -> {
                if (ruleIndex < rules.size()) {
                    var current = rules.get(ruleIndex++);
                    if (!doc.contains(current.origin())
                            || !doc.contains(
                                    current.origin().offset(current.size()).offset(-1, -1, -1)))
                        throw new IllegalArgumentException(
                                "Crew region outside schematic: " + current.id());
                } else {
                    if (doc.hasMultiplePalettes())
                        throw new IllegalArgumentException(
                                "Crew generation requires a single palette.");
                    nativePalette =
                            documentTag.contains("palette", Tag.TAG_LIST)
                                    ? documentTag.getList("palette", Tag.TAG_COMPOUND)
                                    : documentTag.getList("palettes", Tag.TAG_LIST).getList(0);
                    memory.reserveCapacity((long) nativePalette.size() * Long.BYTES);
                    phase = Phase.PALETTE;
                    ruleIndex = 0;
                }
            }
            case PALETTE -> {
                if (paletteIndex < nativePalette.size())
                    palette.add(
                            NbtUtils.readBlockState(
                                    level.holderLookup(Registries.BLOCK),
                                    nativePalette.getCompound(paletteIndex++)));
                else {
                    phase = Phase.RESOLVE;
                    choiceIndex = 0;
                }
            }
            case RESOLVE -> {
                if (ruleIndex == rules.size()) {
                    ruleIndex = choiceIndex = memberIndex = 0;
                    phase = Phase.FACTORIES;
                    return;
                }
                if (choices == null) {
                    choices = new ArrayList<>();
                    memory.reserveCapacity(
                            (long) rules.get(ruleIndex).pool().size() * 3 * Long.BYTES);
                }
                if (choiceIndex == rules.get(ruleIndex).pool().size()) {
                    plans.add(
                            new RulePlan(
                                    rules.get(ruleIndex), Collections.unmodifiableList(choices)));
                    choices = null;
                    choiceIndex = 0;
                    ruleIndex++;
                    return;
                }
                resolving = rules.get(ruleIndex).pool().get(choiceIndex);
                equipmentValidation = resolving.equipment().beginValidation(level.registryAccess());
            }
            case MEMBERS -> {
                if (types == null) {
                    var lookup = level.holderLookup(Registries.ENTITY_TYPE);
                    if (resolving.type().startsWith("#")) {
                        var found =
                                lookup.getOrThrow(
                                        TagKey.create(
                                                Registries.ENTITY_TYPE,
                                                ResourceLocation.parse(
                                                        resolving.type().substring(1))));
                        resources.planFactories(found.size());
                        memory.reserveCapacity((long) found.size() * Long.BYTES);
                        types = new ArrayList<>(found.size());
                        members = found.iterator();
                    } else {
                        resources.planFactories(1);
                        types = new ArrayList<>(1);
                        types.add(
                                lookup.getOrThrow(
                                                ResourceKey.create(
                                                        Registries.ENTITY_TYPE,
                                                        ResourceLocation.parse(resolving.type())))
                                        .value());
                        members = Collections.emptyIterator();
                    }
                } else if (members.hasNext()) types.add(members.next().value());
                else {
                    if (types.isEmpty())
                        throw new IllegalArgumentException("Empty crew tag: " + resolving.type());
                    choices.add(new Resolved(resolving, Collections.unmodifiableList(types)));
                    types = null;
                    members = null;
                    resolving = null;
                    choiceIndex++;
                    phase = Phase.RESOLVE;
                }
            }
            case FACTORIES -> {
                if (ruleIndex == plans.size()) {
                    copy =
                            memory.copyInputIncrementally(
                                    template.getList("entities", Tag.TAG_COMPOUND));
                    phase = Phase.ENTITIES_COPY;
                    return;
                }
                var choices = plans.get(ruleIndex).choices();
                if (choiceIndex == choices.size()) {
                    ruleIndex++;
                    choiceIndex = memberIndex = 0;
                    return;
                }
                var types = choices.get(choiceIndex).types();
                if (memberIndex == types.size()) {
                    choiceIndex++;
                    memberIndex = 0;
                    return;
                }
                var type = types.get(memberIndex++);
                resources.charge(CrewResourceBudget.Family.FACTORY_PROBE);
                if (!(type.create(level) instanceof Mob))
                    throw new IllegalArgumentException(
                            "Crew pool contains a non-mob type: " + EntityType.getKey(type));
            }
            case RULE -> {
                if (ruleIndex == plans.size()) {
                    phase = Phase.FINISH;
                    return;
                }
                var plan = plans.get(ruleIndex);
                rule = plan.rule();
                choices = plan.choices();
                sum = 0;
                for (var choice : choices) sum += choice.choice().weight();
                requested =
                        rule.ranged()
                                ? rule.minCount()
                                        + (int)
                                                (random.nextDouble()
                                                        * ((long) rule.count()
                                                                - rule.minCount()
                                                                + 1))
                                : rule.count();
                resources.request(requested);
                created = iteration = 0;
                fallbacks = new HashMap<>();
                impossible = new HashSet<>();
                collisionResults = new HashMap<>();
                phase = Phase.CREATE;
            }
            case CREATE -> {
                if (iteration == requested) {
                    finishRule();
                    return;
                }
                int roll = random.nextInt(sum);
                selected = choices.getFirst();
                for (var choice : choices) {
                    roll -= choice.choice().weight();
                    if (roll < 0) {
                        selected = choice;
                        break;
                    }
                }
                type = selected.types().get(random.nextInt(selected.types().size()));
                resources.charge(CrewResourceBudget.Family.GENERATED_FACTORY);
                mob = (Mob) type.create(level);
                copy = memory.copyIncrementally(rule.state());
                phase = Phase.RULE_STATE;
            }
            case CHOICE_STATE -> {
                copy = memory.copyIncrementally(selected.choice().state());
            }
            case MERGE -> {
                if (merging.isEmpty()) {
                    authoredHands = state.get("HandItems");
                    authoredArmor = state.get("ArmorItems");
                    phase = Phase.EQUIPMENT;
                    return;
                }
                var frame = merging.peek();
                if (!frame.keys().hasNext()) {
                    merging.pop();
                    return;
                }
                var key = frame.keys().next();
                var value = frame.source().get(key);
                if (value instanceof CompoundTag compound
                        && frame.target().contains(key, Tag.TAG_COMPOUND))
                    merging.push(
                            new MergeFrame(
                                    frame.target().getCompound(key),
                                    compound,
                                    compound.getAllKeys().iterator()));
                else frame.target().put(key, value);
            }
            case EQUIPMENT ->
                    equipmentApplication =
                            selected.choice()
                                    .equipment()
                                    .beginApply(state, random, resources, memory::reserveCapacity);
            case LOAD -> {
                state.putBoolean("PersistenceRequired", rule.preventDespawn());
                resources.charge(CrewResourceBudget.Family.NATIVE_LOAD);
                mob.load(state);
                if (!rule.name().isEmpty())
                    mob.setCustomName(net.minecraft.network.chat.Component.literal(rule.name()));
                placementKey = EntityType.getKey(type) + ":" + mob.getDimensions(mob.getPose());
                if (impossible.contains(placementKey)) {
                    phase = Phase.NEXT;
                    return;
                }
                tested = collisionResults.computeIfAbsent(placementKey, key -> new HashMap<>());
                fallback = fallbacks.get(placementKey);
                long cells = (long) rule.size().getX() * rule.size().getY() * rule.size().getZ();
                attempt = 0;
                attemptLimit = 64 + (fallback == null ? cells : 1);
                phase = Phase.POSITION;
            }
            case POSITION -> {
                if (attempt == attemptLimit) {
                    impossible.add(placementKey);
                    if (com.eynny.npships.config.NPShipsRuntimeConfig.developerModeEnabled())
                        com.mojang.logging.LogUtils.getLogger()
                                .debug(
                                        "[NPShips crew] No free position for {} in {} after checking the zone",
                                        EntityType.getKey(type),
                                        rule.id());
                    phase = Phase.NEXT;
                    return;
                }
                resources.charge(CrewResourceBudget.Family.POSITION_TRIAL);
                long at = attempt++, index = at - 64;
                pos =
                        at < 64
                                ? rule.origin()
                                        .offset(
                                                random.nextInt(rule.size().getX()),
                                                random.nextInt(rule.size().getY()),
                                                random.nextInt(rule.size().getZ()))
                                : fallback != null
                                        ? fallback
                                        : rule.origin()
                                                .offset(
                                                        (int) (index % rule.size().getX()),
                                                        (int)
                                                                (index
                                                                        / rule.size().getX()
                                                                        % rule.size().getY()),
                                                        (int)
                                                                (index
                                                                        / rule.size().getX()
                                                                        / rule.size().getY()));
                var cached = tested.get(pos);
                if (Boolean.FALSE.equals(cached)) return;
                box =
                        mob.getDimensions(mob.getPose())
                                .makeBoundingBox(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
                if (cached != null) {
                    phase = Phase.SAVE;
                    return;
                }
                StructureCrew.validateBodyBounds(box, rule.id());
                clear = true;
                cells =
                        BlockPos.betweenClosed(
                                        Mth.floor(box.minX),
                                        Mth.floor(box.minY),
                                        Mth.floor(box.minZ),
                                        Mth.floor(box.maxX - 1e-6),
                                        Mth.floor(box.maxY - 1e-6),
                                        Mth.floor(box.maxZ - 1e-6))
                                .iterator();
                phase = Phase.CELL;
            }
            case CELL -> {
                if (!cells.hasNext()) {
                    if (rule.placement().equals("ground")) phase = Phase.SUPPORT_READ;
                    else acceptPosition();
                    return;
                }
                cell = cells.next().immutable();
                resources.charge(CrewResourceBudget.Family.DOCUMENT_READ);
                block = StructureCrew.state(doc, palette, cell);
                phase = Phase.COLLISION;
            }
            case COLLISION -> {
                resources.charge(CrewResourceBudget.Family.COLLISION_QUERY);
                shapes =
                        block.getCollisionShape(EmptyBlockGetter.INSTANCE, cell)
                                .toAabbs()
                                .iterator();
                phase = Phase.SHAPES;
            }
            case SHAPES -> {
                if (shapes.hasNext()) {
                    resources.charge(CrewResourceBudget.Family.SHAPE_BOX);
                    if (shapes.next().move(cell).intersects(box)) {
                        clear = false;
                        phase = Phase.FLUID;
                    }
                } else phase = Phase.FLUID;
            }
            case FLUID -> {
                resources.charge(CrewResourceBudget.Family.FLUID_TEST);
                if (rule.placement().equals("water")
                        && !block.getFluidState().is(net.minecraft.tags.FluidTags.WATER))
                    clear = false;
                if (!rule.placement().equals("water") && !block.getFluidState().isEmpty())
                    clear = false;
                if (!clear) rejectPosition();
                else phase = Phase.CELL;
            }
            case SUPPORT_READ -> {
                resources.charge(CrewResourceBudget.Family.DOCUMENT_READ);
                block = StructureCrew.state(doc, palette, pos.below());
                phase = Phase.SUPPORT_TEST;
            }
            case SUPPORT_TEST -> {
                resources.charge(CrewResourceBudget.Family.SUPPORT_TEST);
                if (!block.isFaceSturdy(
                        EmptyBlockGetter.INSTANCE, pos.below(), net.minecraft.core.Direction.UP))
                    rejectPosition();
                else acceptPosition();
            }
            case SAVE -> {
                saved = new CompoundTag();
                resources.charge(CrewResourceBudget.Family.NATIVE_SAVE);
                mob.save(saved);
                if (equipment != null) {
                    if (authoredHands == null) state.remove("HandItems");
                    else state.put("HandItems", authoredHands);
                    if (authoredArmor == null) state.remove("ArmorItems");
                    else state.put("ArmorItems", authoredArmor);
                }
                copy = memory.copyIncrementally(state);
                phase = Phase.OVERRIDES;
            }
            case EQUIPMENT_COPY -> copy = memory.copyIncrementally(equipment);
            case ENTRY -> {
                saved.remove("UUID");
                saved.remove("Pos");
                saved.remove("Motion");
                saved.putString("id", EntityType.getKey(type).toString());
                entry = new CompoundTag();
                entry.put("nbt", saved);
                entry.put("blockPos", StructureSegments.ints(pos));
                var location = new ListTag();
                location.add(DoubleTag.valueOf(pos.getX() + .5));
                location.add(DoubleTag.valueOf(pos.getY()));
                location.add(DoubleTag.valueOf(pos.getZ() + .5));
                entry.put("pos", location);
                retention = memory.retainIncrementally(entry);
                phase = Phase.RETAIN;
            }
            case APPEND -> {
                long previous = -1;
                if (entities.size() == entityCapacity) {
                    long next =
                            entityCapacity == 0
                                    ? 1
                                    : entityCapacity + Math.max(1, entityCapacity / 2);
                    memory.reserveEntityGrowth(next);
                    previous = entityCapacity;
                    entityCapacity = next;
                }
                entities.add(entry);
                if (previous >= 0) memory.entityGrowthCompleted(previous);
                fallbacks.putIfAbsent(placementKey, pos.immutable());
                created++;
                if (timing != null) timing.crewCreated();
                phase = Phase.NEXT;
            }
            case NEXT -> {
                mob = null;
                state = null;
                equipment = null;
                saved = null;
                pending = null;
                entry = null;
                authoredHands = authoredArmor = null;
                cells = null;
                shapes = null;
                block = null;
                box = null;
                iteration++;
                phase = Phase.CREATE;
            }
            case FINISH -> {
                template.put("entities", entities);
                phase = Phase.DONE;
            }
            default -> throw new IllegalStateException("Crew phase lost continuation: " + phase);
        }
    }

    private void acceptPosition() {
        if (tested.size() < 65536) tested.put(pos.immutable(), true);
        phase = Phase.SAVE;
    }

    private void rejectPosition() {
        if (tested.size() < 65536) tested.put(pos.immutable(), false);
        cells = null;
        shapes = null;
        phase = Phase.POSITION;
    }

    private void finishRule() {
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
        ruleIndex++;
        choices = null;
        rule = null;
        fallbacks = null;
        impossible = null;
        collisionResults = null;
        phase = Phase.RULE;
    }

    private void report() {
        if (reported) return;
        reported = true;
        if (resources != null) {
            memory.work().record(ResourceWorkMeter.Family.CREW_PRIMITIVE, resources.work());
            if (timing != null)
                timing.crewResources(
                        resources.requested(), resources.factoryProbes(), resources.work());
        }
    }

    public long progress() {
        return progress;
    }

    private void clearReferences() {
        if (ruleRead != null) ruleRead.close();
        ruleRead = null;
        if (copy != null) copy.close();
        if (document != null) document.close();
        if (retention != null) retention.close();
        if (equipmentValidation != null) equipmentValidation.close();
        if (equipmentApplication != null) equipmentApplication.close();
        copy = null;
        document = null;
        retention = null;
        equipmentValidation = null;
        equipmentApplication = null;
        template = null;
        config = null;
        rules = null;
        doc = null;
        documentTag = null;
        nativePalette = null;
        entities = null;
        state = null;
        equipment = null;
        saved = null;
        pending = null;
        entry = null;
        mob = null;
        choices = null;
        types = null;
        members = null;
        cells = null;
        shapes = null;
        block = null;
        box = null;
        tested = null;
        fallbacks = null;
        impossible = null;
        collisionResults = null;
        merging.clear();
        palette = null;
        plans = null;
        rule = null;
        selected = null;
        resolving = null;
        fallback = null;
        pos = null;
        cell = null;
        authoredHands = authoredArmor = null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        report();
        clearReferences();
    }
}
