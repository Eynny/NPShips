package com.eynny.npships.naturalspawn;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SavedData-owned bounded events and their admitted pressure; excess is discarded on admission. */
public final class RarityHistory {
    public static final int MAX_EVENTS_PER_OWNER_DIMENSION = 64;
    public static final int MAX_EVENTS = 4096;
    public static final int LIFETIME_HALF_LIVES = 12;

    public record Event(
            UUID id,
            UUID owner,
            String dimension,
            long time,
            double authorProbability,
            long halfLifeTicks) {
        public Event {
            if (id == null
                    || owner == null
                    || ResourceLocation.tryParse(dimension) == null
                    || time < 0
                    || !Double.isFinite(authorProbability)
                    || authorProbability <= 0
                    || authorProbability > 1
                    || halfLifeTicks < 1
                    || halfLifeTicks > 1000000000L)
                throw new IllegalArgumentException("Invalid rarity history event");
        }

        public long expiresAt() {
            long duration = halfLifeTicks * LIFETIME_HALF_LIVES;
            return time > Long.MAX_VALUE - duration ? Long.MAX_VALUE : time + duration;
        }

        public double contribution(long now) {
            if (now >= expiresAt()) return 0;
            double s = 1 - authorProbability;
            return decay(s * s, now);
        }

        private double decay(double admitted, long now) {
            if (now >= expiresAt()) return 0;
            return admitted * Math.pow(2, -(Math.max(0.0, (double) now - time) / halfLifeTicks));
        }
    }

    private record OwnerDimension(UUID owner, String dimension) {}

    private final Map<OwnerDimension, ArrayList<Event>> histories = new HashMap<>();
    private final Map<UUID, Double> admitted = new HashMap<>();
    private int size;
    private long lastObservedTime;

    public int size() {
        return size;
    }

    public List<Event> events() {
        return histories.values().stream().flatMap(List::stream).toList();
    }

    public double pressure(UUID owner, String dimension, long now, double maximum) {
        lastObservedTime = Math.max(lastObservedTime, now);
        return readPressure(owner, dimension, now, maximum);
    }

    /** Diagnostics must not advance persistence/pruning time merely by observing. */
    public double readPressure(UUID owner, String dimension, long now, double maximum) {
        var events = histories.get(new OwnerDimension(owner, dimension));
        if (events == null) return 0;
        double total = 0;
        for (var event : events) total += contribution(event, now);
        return Math.min(maximum, total);
    }

    /** Raw-event import/math primitive. Production success supplies its captured maximum. */
    public boolean append(Event event, long now) {
        return append(event, now, Double.POSITIVE_INFINITY);
    }

    public boolean append(Event event, long now, double maximum) {
        return append(event, now, maximum, event.contribution(event.time()));
    }

    private boolean append(Event event, long now, double maximum, double requested) {
        if (Double.isNaN(maximum) || maximum < 0)
            throw new IllegalArgumentException("Invalid pressure maximum");
        prune(now);
        // Every retained event has an admitted dose, including a zero dose at saturation.
        if (admitted.containsKey(event.id())) return false;
        if (now >= event.expiresAt()) return false;
        var key = new OwnerDimension(event.owner(), event.dimension());
        var list = histories.computeIfAbsent(key, k -> new ArrayList<>());
        double total = 0;
        for (var old : list) total += contribution(old, now);
        if (total > maximum) {
            // A lower WORLD policy or legacy raw history cannot retain a hidden excess.
            double scale = maximum / total;
            for (var old : list) admitted.put(old.id(), initial(old) * scale);
            total = maximum;
        }
        double dose = Math.min(requested, Math.max(0, maximum - total));
        if (list.size() >= MAX_EVENTS_PER_OWNER_DIMENSION) {
            var weakest = weakest(list, now);
            if (contribution(weakest, now) >= event.decay(dose, now)) return false;
            list.remove(weakest);
            admitted.remove(weakest.id());
            size--;
        }
        if (size >= MAX_EVENTS) {
            Event weakest = null;
            for (var events : histories.values())
                for (var old : events)
                    if (weakest == null || compare(old, weakest, now) < 0) weakest = old;
            if (weakest != null && contribution(weakest, now) >= event.decay(dose, now)) {
                if (list.isEmpty()) histories.remove(key);
                return false;
            }
            if (weakest != null) {
                histories
                        .get(new OwnerDimension(weakest.owner(), weakest.dimension()))
                        .remove(weakest);
                admitted.remove(weakest.id());
                size--;
            }
        }
        list.add(event);
        admitted.put(event.id(), dose);
        size++;
        return true;
    }

    private double initial(Event event) {
        var value = admitted.get(event.id());
        return value == null ? event.contribution(event.time()) : value;
    }

    private double contribution(Event event, long now) {
        return event.decay(initial(event), now);
    }

    private int compare(Event a, Event b, long now) {
        int c = Double.compare(contribution(a, now), contribution(b, now));
        return c != 0 ? c : a.id().compareTo(b.id());
    }

    private Event weakest(List<Event> list, long now) {
        Event weakest = list.getFirst();
        for (var event : list) if (compare(event, weakest, now) < 0) weakest = event;
        return weakest;
    }

    public boolean prune(long now) {
        lastObservedTime = Math.max(lastObservedTime, now);
        int before = size;
        histories
                .values()
                .removeIf(
                        list -> {
                            int n = list.size();
                            list.removeIf(
                                    e -> {
                                        if (now < e.expiresAt()) return false;
                                        admitted.remove(e.id());
                                        return true;
                                    });
                            size -= n - list.size();
                            return list.isEmpty();
                        });
        return size != before;
    }

    public ListTag save() {
        prune(lastObservedTime);
        var result = new ListTag();
        var events = new ArrayList<>(events());
        events.sort(java.util.Comparator.comparing(Event::id));
        for (var e : events) {
            var tag = new CompoundTag();
            tag.putUUID("event", e.id());
            tag.putUUID("owner", e.owner());
            tag.putString("dimension", e.dimension());
            tag.putLong("time", e.time());
            tag.putDouble("author_probability", e.authorProbability());
            tag.putLong("half_life_ticks", e.halfLifeTicks());
            tag.putDouble("admitted_pressure", initial(e));
            result.add(tag);
        }
        return result;
    }

    public void load(ListTag tags) {
        load(tags, Double.POSITIVE_INFINITY);
    }

    private record Loaded(Event event, double dose, boolean legacy) {}

    /** Restore detached records, replaying legacy admission chronologically under the world cap. */
    public boolean load(ListTag tags, double maximum) {
        if (tags.size() > MAX_EVENTS)
            throw new IllegalArgumentException("Too many rarity history events");
        histories.clear();
        admitted.clear();
        size = 0;
        lastObservedTime = 0;
        var parsed = new ArrayList<Loaded>();
        for (var value : tags) {
            var tag = (CompoundTag) value;
            var e =
                    new Event(
                            tag.getUUID("event"),
                            tag.getUUID("owner"),
                            tag.getString("dimension"),
                            tag.getLong("time"),
                            tag.getDouble("author_probability"),
                            tag.getLong("half_life_ticks"));
            boolean legacy = !tag.contains("admitted_pressure");
            double dose = legacy ? e.contribution(e.time()) : tag.getDouble("admitted_pressure");
            if ((!legacy
                            && !tag.contains(
                                    "admitted_pressure", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC))
                    || !Double.isFinite(dose)
                    || dose < 0
                    || dose > e.contribution(e.time()))
                throw new IllegalArgumentException("Invalid admitted rarity pressure");
            parsed.add(new Loaded(e, dose, legacy));
        }
        parsed.sort(
                java.util.Comparator.comparingLong((Loaded e) -> e.event().time())
                        .thenComparing(e -> e.event().id()));
        boolean changed = false;
        for (var loaded : parsed) {
            var e = loaded.event();
            boolean added = append(e, e.time(), maximum, loaded.dose());
            changed |= loaded.legacy() || !added || initial(e) != loaded.dose();
        }
        prune(lastObservedTime);
        return changed;
    }
}
