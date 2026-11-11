package com.eynny.npships.naturalspawn;

import com.eynny.npships.api.SpawnWeather;
import com.eynny.npships.config.BiomeFilter;
import com.eynny.npships.config.DifficultyRule;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.config.NPShipsStructureConfig;
import com.eynny.npships.config.RealDateRule;
import com.eynny.npships.config.RealTimeRule;
import com.eynny.npships.config.RegistryFilter;
import com.eynny.npships.config.TimeOfDayRule;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.ship.NPShipsSchematicManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.biome.Biome;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Candidate structure pool for one natural-spawn attempt.
 *
 * <p>This class never performs environment scanning.
 *
 * <p>It answers:
 *
 * <p>Determines which configured structures are currently eligible.
 *
 * <p>Uses restrictions and the pinned initial search point; no point/affordability gate.
 */
public final class NaturalSpawnCandidatePool {

    private final List<NaturalSpawnStructureCandidate> candidates;

    private NaturalSpawnCandidatePool(List<NaturalSpawnStructureCandidate> candidates) {
        this.candidates = List.copyOf(candidates);
    }

    private enum Rejection {
        DISABLED_ADDON,
        DISABLED_STRUCTURE,
        NO_CONFIG,
        HEIGHT,
        RESTRICTIONS,
        NO_SIZE,
        INVALID_ID
    }

    public static NaturalSpawnCandidatePool build(
            final MinecraftServer server, final ServerLevel level, final BlockPos initialPoint) {
        return build(server, level, initialPoint, null);
    }

    public static NaturalSpawnCandidatePool build(
            MinecraftServer server,
            ServerLevel level,
            BlockPos initialPoint,
            @org.jetbrains.annotations.Nullable SpawnTimingProfiler timing) {
        return build(server, level, initialPoint, timing, null);
    }

    static NaturalSpawnCandidatePool build(
            MinecraftServer server,
            ServerLevel level,
            BlockPos initialPoint,
            @org.jetbrains.annotations.Nullable SpawnTimingProfiler timing,
            @org.jetbrains.annotations.Nullable NaturalSpawnStructureCandidate selected) {
        var result = new ArrayList<NaturalSpawnStructureCandidate>();
        var context = new RestrictionContext(level, initialPoint);
        int checked = 0;
        for (var schematic :
                selected == null
                        ? NPShipsSchematicManager.getAutomatic(server)
                        : List.of(selected.schematic())) {
            checked++;
            String name = schematic.commandName();
            Rejection reason = null;
            var id = NPShipsSchematicManager.resolveRequestedId(ResourceLocation.parse(name));
            if (!NPShipsRuntimeConfig.addonEnabled(id.getNamespace()))
                reason = Rejection.DISABLED_ADDON;
            else if (!NPShipsRuntimeConfig.structureEnabled(name))
                reason = Rejection.DISABLED_STRUCTURE;
            else if (schematic.config() == null) reason = Rejection.NO_CONFIG;
            else if (schematic.naturalSpawnSize() == null) reason = Rejection.NO_SIZE;
            else if (!schematic.config().restrictions().heightRanges().isEmpty()
                    && !canReachHeight(
                            schematic.config(),
                            SpawnVolumes.automaticReferenceY(
                                    level,
                                    initialPoint.getY(),
                                    schematic.config(),
                                    schematic.naturalSpawnSize().getY())))
                reason = Rejection.HEIGHT;
            else if (!context.allows(schematic.config().restrictions()))
                reason = Rejection.RESTRICTIONS;
            else if (id == null) reason = Rejection.INVALID_ID;
            if (reason == null)
                result.add(
                        new NaturalSpawnStructureCandidate(
                                id, schematic, schematic.config(), schematic.naturalSpawnSize()));
        }
        if (timing != null) timing.catalogue(checked, result.size());
        return new NaturalSpawnCandidatePool(result);
    }

    public record Inspection(List<NaturalSpawnStructureCandidate> candidates, boolean unknown) {
        public Inspection {
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * Catalogue/scalar comparison plus a loaded-only native biome probe; no chunk requests or
     * weather providers.
     */
    public static Inspection inspect(MinecraftServer server, ServerLevel level, BlockPos point) {
        var result = new ArrayList<NaturalSpawnStructureCandidate>();
        var context = new RestrictionContext(level, point);
        boolean unknown = false;
        for (var schematic : NPShipsSchematicManager.getAutomatic(server)) {
            var id =
                    NPShipsSchematicManager.resolveRequestedId(
                            ResourceLocation.parse(schematic.commandName()));
            if (!NPShipsRuntimeConfig.addonEnabled(id.getNamespace())
                    || !NPShipsRuntimeConfig.structureEnabled(schematic.commandName())
                    || schematic.config() == null
                    || schematic.naturalSpawnSize() == null) continue;
            var metadata =
                    schematic.snapshot() == null ? null : schematic.snapshot().rarityMetadata();
            // These entries cannot enter the production lottery, regardless of world conditions.
            if (metadata == null || !metadata.available() || metadata.range().maximum() == 0)
                continue;
            var config = schematic.config();
            if (!config.restrictions().heightRanges().isEmpty()
                    && !canReachHeight(
                            config,
                            SpawnVolumes.automaticReferenceY(
                                    level,
                                    point.getY(),
                                    config,
                                    schematic.naturalSpawnSize().getY()))) continue;
            var allowed = context.allowsScalars(config.restrictions());
            if (allowed == null) unknown = true;
            else if (allowed)
                result.add(
                        new NaturalSpawnStructureCandidate(
                                id, schematic, config, schematic.naturalSpawnSize()));
        }
        return new Inspection(result, unknown);
    }

    public boolean isEmpty() {
        return this.candidates.isEmpty();
    }

    public int size() {
        return this.candidates.size();
    }

    public List<NaturalSpawnStructureCandidate> getAll() {
        return this.candidates;
    }

    /**
     * Conservative origin-space bound; exact build-height and environment bounds follow in search.
     */
    public static boolean canReachHeight(NPShipsStructureConfig config, int referenceY) {
        long min = (long) referenceY - NaturalSpawnLimits.max_vertical_radius();
        long max = (long) referenceY + NaturalSpawnLimits.max_vertical_radius();
        return config.restrictions().heightRanges().isEmpty()
                || config.restrictions().heightRanges().stream()
                        .anyMatch(range -> range.min() <= max && range.max() >= min);
    }

    public static boolean allowsRestrictions(
            ServerLevel level,
            BlockPos origin,
            NPShipsStructureConfig.RestrictionsConfig restrictions) {
        return new RestrictionContext(level, origin).allows(restrictions);
    }

    /**
     * Lazy, one-build observations preserve short-circuit order and never outlive this pool build.
     */
    static final class RestrictionContext {
        private final ServerLevel level;
        private final BlockPos origin;
        private boolean inspectedBiome;

        private static final class UnloadedBiome extends RuntimeException {
            static final UnloadedBiome INSTANCE = new UnloadedBiome();

            private UnloadedBiome() {
                super(null, null, false, false);
            }
        }

        private Difficulty difficulty;
        private Holder<Biome> biome;
        private ResourceLocation biomeId;
        private Integer time;
        private LocalDate date;
        private LocalTime realTime;
        private SpawnWeather.Context weather;

        RestrictionContext(ServerLevel level, BlockPos origin) {
            this.level = level;
            this.origin = origin.immutable();
        }

        // null means the exact pool needs native observations. Check all scalar refusals first.
        Boolean allowsScalars(NPShipsStructureConfig.RestrictionsConfig r) {
            if (!allowsDifficulty(level.getDifficulty(), r.difficulty())
                    || !allowsDimension(level, r.dimensions())
                    || !allowsTimeOfDay(Math.floorMod(level.getDayTime(), 24000), r.timeOfDay()))
                return false;
            if (!r.realDate().intervals().isEmpty()) {
                if (date == null) date = LocalDate.now();
                if (!allowsRealDate(date, r.realDate())) return false;
            }
            if (!r.realTime().intervals().isEmpty()) {
                if (realTime == null) realTime = LocalTime.now();
                if (!allowsRealTime(realTime, r.realTime())) return false;
            }
            var b = r.biomes();
            if (!b.allow().isEmpty()
                    || !b.forbid().isEmpty()
                    || !b.allowTags().isEmpty()
                    || !b.forbidTags().isEmpty()) {
                if (!inspectedBiome) {
                    inspectedBiome = true;
                    try {
                        // Keep native seed/fiddled coordinates, but the selected quart source
                        // can only access already-FULL chunks. Never substitute a raw biome.
                        biome =
                                level.getBiomeManager()
                                        .withDifferentSource(
                                                (x, y, z) -> {
                                                    var chunk =
                                                            level.getChunkSource()
                                                                    .getChunkNow(x >> 2, z >> 2);
                                                    if (chunk == null) throw UnloadedBiome.INSTANCE;
                                                    return chunk.getNoiseBiome(x, y, z);
                                                })
                                        .getBiome(origin);
                        biomeId = biome.unwrapKey().map(ResourceKey::location).orElse(null);
                    } catch (UnloadedBiome unavailable) {
                        biome = null;
                    }
                }
                if (biome == null) return null;
                if (!allowsBiome(biome, biomeId, b)) return false;
            }
            if (!r.weather().allow().isEmpty() || !r.weather().forbid().isEmpty()) {
                if (java.util.stream.Stream.concat(
                                r.weather().allow().stream(), r.weather().forbid().stream())
                        .anyMatch(
                                id ->
                                        !id.equals("clear")
                                                && !id.equals("rain")
                                                && !id.equals("thunder"))) return null;
                // Built-in weather reads server flags only. Arbitrary addon detectors are not
                // called.
                if (weather == null) weather = SpawnWeather.context(level, origin);
                if (!weather.allows(r.weather())) return false;
            }
            return true;
        }

        boolean allows(NPShipsStructureConfig.RestrictionsConfig restrictions) {
            if (difficulty == null) difficulty = level.getDifficulty();
            if (!allowsDifficulty(difficulty, restrictions.difficulty())) return false;
            if (!allowsDimension(level, restrictions.dimensions())) return false;
            var biomeFilter = restrictions.biomes();
            if (!biomeFilter.allow().isEmpty()
                    || !biomeFilter.forbid().isEmpty()
                    || !biomeFilter.allowTags().isEmpty()
                    || !biomeFilter.forbidTags().isEmpty()) {
                if (biome == null) {
                    biome = level.getBiome(origin);
                    biomeId = biome.unwrapKey().map(ResourceKey::location).orElse(null);
                }
                if (!allowsBiome(biome, biomeId, biomeFilter)) return false;
            }
            if (!restrictions.timeOfDay().intervals().isEmpty() && time == null)
                time = Math.floorMod(level.getDayTime(), 24000);
            if (!allowsTimeOfDay(time, restrictions.timeOfDay())) return false;
            if (!restrictions.weather().allow().isEmpty()
                    || !restrictions.weather().forbid().isEmpty()) {
                if (weather == null) weather = SpawnWeather.context(level, origin);
                if (!weather.allows(restrictions.weather())) return false;
            }
            if (!restrictions.realDate().intervals().isEmpty() && date == null)
                date = LocalDate.now();
            if (!allowsRealDate(date, restrictions.realDate())) return false;
            if (!restrictions.realTime().intervals().isEmpty() && realTime == null)
                realTime = LocalTime.now();
            return allowsRealTime(realTime, restrictions.realTime());
        }
    }

    private static boolean allowsDifficulty(final Difficulty current, final DifficultyRule rule) {

        return rule.allows(current);
    }

    private static boolean allowsDimension(final ServerLevel level, final RegistryFilter filter) {

        final ResourceLocation dimensionId = level.dimension().location();

        /*
         * Explicit blacklist wins.
         */
        if (filter.forbid().contains(dimensionId)) {

            return false;
        }

        /*
         * Empty allow list means all dimensions are allowed.
         */
        return filter.allow().isEmpty() || filter.allow().contains(dimensionId);
    }

    private static boolean allowsBiome(
            final Holder<Biome> biome, final ResourceLocation biomeId, final BiomeFilter filter) {

        /*
         * Explicit biome blacklist.
         */
        if (biomeId != null && filter.forbid().contains(biomeId)) {

            return false;
        }

        /*
         * Biome blacklist tags.
         */
        for (final ResourceLocation tagId : filter.forbidTags()) {

            final TagKey<Biome> tag = TagKey.create(Registries.BIOME, tagId);

            if (biome.is(tag)) {
                return false;
            }
        }

        /*
         * No positive filters => allowed.
         */
        if (filter.allow().isEmpty() && filter.allowTags().isEmpty()) {

            return true;
        }

        if (biomeId != null && filter.allow().contains(biomeId)) {

            return true;
        }

        for (final ResourceLocation tagId : filter.allowTags()) {

            final TagKey<Biome> tag = TagKey.create(Registries.BIOME, tagId);

            if (biome.is(tag)) {
                return true;
            }
        }

        return false;
    }

    private static boolean allowsTimeOfDay(final Integer time, final TimeOfDayRule rule) {

        if (rule.intervals().isEmpty()) {
            return true;
        }

        for (final TimeOfDayRule.Interval interval : rule.intervals()) {

            if (isInMinecraftTimeInterval(time, interval.from(), interval.to())) {

                return true;
            }
        }

        return false;
    }

    private static boolean isInMinecraftTimeInterval(
            final int value, final int from, final int to) {

        if (from <= to) {

            return value >= from && value <= to;
        }

        return value >= from || value <= to;
    }

    private static boolean allowsRealDate(final LocalDate now, final RealDateRule rule) {

        if (rule.intervals().isEmpty()) {
            return true;
        }

        final int value = now.getMonthValue() * 100 + now.getDayOfMonth();

        for (final RealDateRule.Interval interval : rule.intervals()) {

            final int from =
                    interval.from().getMonthValue() * 100 + interval.from().getDayOfMonth();

            final int to = interval.to().getMonthValue() * 100 + interval.to().getDayOfMonth();

            if (from <= to) {

                if (value >= from && value <= to) {

                    return true;
                }

            } else {

                if (value >= from || value <= to) {

                    return true;
                }
            }
        }

        return false;
    }

    private static boolean allowsRealTime(final LocalTime now, final RealTimeRule rule) {

        if (rule.intervals().isEmpty()) {
            return true;
        }

        for (final RealTimeRule.Interval interval : rule.intervals()) {

            final LocalTime from = interval.from();

            final LocalTime to = interval.to();

            if (!from.isAfter(to)) {

                if (!now.isBefore(from) && !now.isAfter(to)) {

                    return true;
                }

            } else {

                if (!now.isBefore(from) || !now.isAfter(to)) {

                    return true;
                }
            }
        }

        return false;
    }
}
