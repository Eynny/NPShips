package com.eynny.npships.ship;

import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Per-dimension construction ownership. An incomplete stamp is saved in the same native body record
 * as its plot blocks; journal retirement never makes an older partial body look complete.
 *
 * <p>Native storage/index/entity writes still have no shared durable acknowledgement. This journal
 * does not promise atomicity or repair corrupt native records.
 */
public final class DirectSpawnJournal extends SavedData {
    private static final String DATA_NAME = "npships_direct_spawn";
    static final String STAMP = "npships:incomplete_construction";
    private static final int VERSION = 1;

    public static final SubLevelLoadingTicketType<UUID> BUILD_TICKET =
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath("npships", "spawn_build"),
                    UUIDUtil.CODEC);

    private final Map<UUID, UUID> pending = new HashMap<>();
    private final Map<UUID, Set<UUID>> entities = new HashMap<>();
    // Exact cancelled identities outlive independently saved/loaded native entity files. Without
    // a native durable entity-store acknowledgement they cannot safely be pruned by age or size.
    private final Set<UUID> cancelledEntities = new HashSet<>();
    // Only reservations made by this live server may stamp a newly serialized body.
    private final Set<UUID> active = new HashSet<>();
    private final Set<UUID> recovery = new HashSet<>();
    private final Map<UUID, ServerSubLevel> recoveryBodies = new HashMap<>();
    private boolean initializing;

    // Live publication only: old incomplete native records remain authoritative after restart.
    private static final class Publication {
        int members, ready;
        boolean published, cancelling;
    }

    private final Map<UUID, Publication> publications = new HashMap<>();
    private final Set<UUID> ready = new HashSet<>();
    private final Map<UUID, Iterator<UUID>> cancellationCursors = new HashMap<>();
    private final Set<UUID> cancellationReady = new HashSet<>();

    private boolean published(UUID attempt) {
        var publication = publications.get(attempt);
        return publication != null && publication.published;
    }

    boolean pending(UUID body) {
        var attempt = pending.get(body);
        return attempt != null && !published(attempt);
    }

    /** Register the native ticket codec before levels load persisted tickets. */
    public static void initialize() {}

    private static SavedData.Factory<DirectSpawnJournal> factory() {
        return new SavedData.Factory<>(DirectSpawnJournal::new, DirectSpawnJournal::load);
    }

    private static DirectSpawnJournal get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    private static DirectSpawnJournal peek(ServerLevel level) {
        return level.getDataStorage().get(factory(), DATA_NAME);
    }

    private static void thread(ServerLevel level) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException(
                    "Direct construction journal requires the server thread");
    }

    /** Persisted pending identity also protects a body during restart/loading callbacks. */
    public static boolean pending(ServerLevel level, UUID body) {
        var journal = peek(level);
        return journal != null && journal.pending(body);
    }

    /** Record an exact freshly created entity UUID before it can enter either entity store. */
    public static void reserveEntity(ServerLevel level, UUID attempt, UUID body, UUID entity) {
        thread(level);
        get(level).reserveEntity(attempt, body, entity);
    }

    void reserveEntity(UUID attempt, UUID body, UUID entity) {
        Objects.requireNonNull(entity);
        if (cancelledEntities.contains(entity))
            throw new IllegalArgumentException(
                    "Cancelled construction entity UUID was reused: " + entity);
        if (!Objects.equals(pending.get(body), attempt)
                || !active.contains(body)
                || published(attempt)
                || publications.containsKey(attempt) && publications.get(attempt).cancelling
                || cancellationCursors.containsKey(body)
                || cancellationReady.contains(body))
            throw new IllegalStateException("Entity construction owner does not match: " + body);
        if (entities.computeIfAbsent(body, ignored -> new HashSet<>()).add(entity)) setDirty();
    }

    /** O(1) exact identity guard for later entity-file loads, including after body retirement. */
    public static boolean cancelledEntity(ServerLevel level, UUID entity) {
        var journal = peek(level);
        return journal != null && journal.cancelledEntities.contains(entity);
    }

    private void recordCancellation(UUID body) {
        if (cancellationReady.contains(body)) return;
        var owned = entities.get(body);
        if (owned != null && cancelledEntities.addAll(owned)) setDirty();
    }

    /** Membership is recorded by the creator; plot position never confers ownership. */
    public static boolean ownsEntity(ServerLevel level, UUID body, UUID entity) {
        var journal = peek(level);
        return journal != null && journal.ownsEntity(body, entity);
    }

    boolean ownsEntity(UUID body, UUID entity) {
        var owned = entities.get(body);
        return pending(body) && owned != null && owned.contains(entity);
    }

    /** Must precede allocateSubLevel with this exact fresh UUID, including native callbacks. */
    public static void reserve(ServerLevel level, UUID attempt, UUID body) {
        thread(level);
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(body);
        var container = SubLevelContainer.getContainer(level);
        if (container == null
                || container.getSubLevel(body) != null
                || container.getHoldingChunkMap().getHoldingSubLevel(body) != null)
            throw new IllegalArgumentException(
                    "Direct construction UUID is already allocated: " + body);
        get(level).reserve(attempt, body);
    }

    void reserve(UUID attempt, UUID body) {
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(body);
        if (pending.containsKey(body))
            throw new IllegalArgumentException(
                    "Direct construction UUID is already reserved: " + body);
        var publication = publications.computeIfAbsent(attempt, ignored -> new Publication());
        if (publication.published || publication.cancelling)
            throw new IllegalStateException("Direct construction attempt is already terminating");
        pending.put(body, attempt);
        publication.members++;
        active.add(body);
        setDirty();
    }

    /** One release unit validates its native member; publication completes the whole attempt. */
    public static void markReady(ServerLevel level, UUID attempt, UUID body) {
        thread(level);
        var nativeBody = SubLevelContainer.getContainer(level).getSubLevel(body);
        if (!(nativeBody instanceof ServerSubLevel current)
                || current.isRemoved()
                || current.getLevel() != level)
            throw new IllegalStateException(
                    "Completed construction body is no longer current: " + body);
        get(level).markReady(attempt, body);
    }

    void markReady(UUID attempt, UUID body) {
        var publication = publications.get(attempt);
        if (!Objects.equals(pending.get(body), attempt)
                || !active.contains(body)
                || publication == null
                || publication.published
                || publication.cancelling)
            throw new IllegalStateException(
                    "Direct construction readiness owner does not match: " + body);
        if (ready.add(body)) publication.ready++;
    }

    /** The exact owner's native removal event revokes readiness without scanning other bodies. */
    static boolean invalidateReady(ServerLevel level, UUID body) {
        thread(level);
        var journal = peek(level);
        if (journal == null || !journal.pending(body) || !journal.ready.remove(body)) return false;
        journal.publications.get(journal.pending.get(body)).ready--;
        return true;
    }

    /** The sole owner publishes all ready members in one non-yielding server-thread unit. */
    public static void publish(ServerLevel level, UUID attempt) {
        thread(level);
        get(level).publish(attempt);
    }

    void publish(UUID attempt) {
        var publication = publications.get(attempt);
        if (publication == null
                || publication.members == 0
                || publication.cancelling
                || publication.ready != publication.members)
            throw new IllegalStateException("Direct construction attempt has unready members");
        publication.published = true;
        setDirty();
    }

    /** Retires one row after whole-attempt publication. It never publishes a partial attempt. */
    public static void commit(ServerLevel level, UUID attempt, UUID body) {
        thread(level);
        get(level).retire(attempt, body, true);
    }

    /** Establishes at most one late-load entity tombstone, before owned native-body removal. */
    public static boolean prepareCancellationStep(ServerLevel level, UUID attempt, UUID body) {
        thread(level);
        return get(level).prepareCancellationStep(attempt, body);
    }

    boolean prepareCancellationStep(UUID attempt, UUID body) {
        var owner = pending.get(body);
        if (owner == null) return true;
        if (!owner.equals(attempt) || published(attempt))
            throw new IllegalStateException(
                    "Direct construction cancellation owner does not match: " + body);
        var publication = publications.get(attempt);
        if (publication != null) publication.cancelling = true;
        if (cancellationReady.contains(body)) return true;
        var cursor =
                cancellationCursors.computeIfAbsent(
                        body,
                        ignored -> {
                            var owned = entities.get(body);
                            return owned == null
                                    ? java.util.Collections.emptyIterator()
                                    : owned.iterator();
                        });
        if (cursor.hasNext()) {
            if (cancelledEntities.add(cursor.next())) setDirty();
            return false;
        }
        cancellationCursors.remove(body);
        cancellationReady.add(body);
        return true;
    }

    /** Called after exact-owned cleanup, or after allocation failed without creating a body. */
    public static void cancelled(ServerLevel level, UUID attempt, UUID body) {
        thread(level);
        get(level).retire(attempt, body, false);
    }

    void retire(UUID attempt, UUID body, boolean commit) {
        var owner = pending.get(body);
        if (owner == null && !commit) return;
        if (!Objects.equals(owner, attempt)
                || commit && (!active.contains(body) || !published(attempt)))
            throw new IllegalStateException("Direct construction owner does not match: " + body);
        if (!commit && published(attempt))
            throw new IllegalStateException("Published construction cannot be cancelled: " + body);
        if (!commit) recordCancellation(body);
        var publication = publications.get(attempt);
        if (publication != null) {
            if (!commit) publication.cancelling = true;
            if (ready.remove(body)) publication.ready--;
            if (--publication.members == 0) publications.remove(attempt);
        }
        cancellationCursors.remove(body);
        cancellationReady.remove(body);
        pending.remove(body);
        entities.remove(body);
        active.remove(body);
        recovery.remove(body);
        recoveryBodies.remove(body);
        setDirty();
    }

    /** Adds only detached, tiny ownership data to the native serialization snapshot. */
    public static void stamp(ServerSubLevel body, SubLevelData serialized) {
        if (!body.getUniqueId().equals(serialized.uuid()))
            throw new IllegalArgumentException("Native serialized body UUID changed owner");
        var journal = peek(body.getLevel());
        if (journal != null) journal.stamp(body.getUniqueId(), serialized.fullTag());
    }

    void stamp(UUID body, CompoundTag nativeTag) {
        if (!active.contains(body) && !recovery.contains(body)) return;
        UUID attempt = pending.get(body);
        if (attempt == null) throw new IllegalStateException("Active construction lost its owner");
        if (published(attempt)) return;
        var tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.putUUID("attempt", attempt);
        tag.putUUID("body", body);
        tag.put("entities", entityTags(body));
        nativeTag.put(STAMP, tag);
    }

    static UUID stampedAttempt(UUID body, CompoundTag nativeTag) {
        if (!nativeTag.contains(STAMP)) return null;
        if (!nativeTag.contains(STAMP, Tag.TAG_COMPOUND))
            throw new IllegalStateException("Invalid native construction ownership stamp: " + body);
        var stamp = nativeTag.getCompound(STAMP);
        if (stamp.getInt("version") != VERSION
                || !stamp.hasUUID("attempt")
                || !stamp.hasUUID("body")
                || !body.equals(stamp.getUUID("body")))
            throw new IllegalStateException("Invalid native construction ownership stamp: " + body);
        return stamp.getUUID("attempt");
    }

    /** Enter before Sable iterates force-loaded tickets; removal must wait until iteration ends. */
    public static void beginRecovery(ServerSubLevelContainer container) {
        get(container.getLevel()).initializing = true;
    }

    public static void finishRecovery(ServerSubLevelContainer container) {
        var journal = get(container.getLevel());
        journal.initializing = false;
        for (UUID body : List.copyOf(journal.recovery)) journal.recover(container, body);
    }

    /** Incomplete native records must reach recovery, never the ordinary expiry/AABB cleaner. */
    public static boolean incompleteRecord(SubLevelData saved) {
        return stampedAttempt(saved.uuid(), saved.fullTag()) != null;
    }

    /** Classify before allocation; a duplicate snapshot never owns an already current object. */
    public static boolean loading(ServerLevel level, SubLevelData saved) {
        thread(level);
        UUID body = saved.uuid();
        if (SubLevelContainer.getContainer(level).getSubLevel(body) != null) return false;
        UUID attempt = stampedAttempt(body, saved.fullTag());
        var journal = attempt == null ? peek(level) : get(level);
        if (journal == null) return true;
        journal.observeRecord(body, attempt);
        if (attempt != null) journal.readEntities(body, saved.fullTag().getCompound(STAMP));
        return true;
    }

    /** The actual loader return, not a later UUID lookup, establishes recovery object ownership. */
    public static void loadedRecord(ServerLevel level, SubLevelData saved, ServerSubLevel loaded) {
        thread(level);
        if (loaded == null) return;
        var journal = peek(level);
        if (journal == null || !journal.recovery.contains(saved.uuid())) return;
        if (loaded.getLevel() != level
                || !loaded.getUniqueId().equals(saved.uuid())
                || SubLevelContainer.getContainer(level).getSubLevel(saved.uuid()) != loaded)
            throw new IllegalStateException("Native loaded construction changed exact owner");
        journal.recoveryBodies.put(saved.uuid(), loaded);
    }

    void observeRecord(UUID body, UUID attempt) {
        if (attempt == null) {
            // A complete native record wins over an older journal entry. Missing/unloaded IDs
            // are never pruned here: only this exact record has actually been observed.
            if (!active.contains(body)) {
                recovery.remove(body);
                recoveryBodies.remove(body);
                entities.remove(body);
                if (pending.remove(body) != null) setDirty();
            }
            return;
        }
        UUID previous = pending.putIfAbsent(body, attempt);
        if (previous != null && !previous.equals(attempt))
            throw new IllegalStateException("Conflicting native construction owner: " + body);
        recovery.add(body);
        setDirty();
    }

    /** Native loading has finished and assigned its saved pointer before this hook runs. */
    public static void loaded(ServerSubLevelContainer container, HoldingSubLevel saved) {
        var journal = peek(container.getLevel());
        UUID id = saved.data().uuid();
        if (journal != null && journal.recovery.contains(id) && !journal.initializing)
            journal.recover(container, id);
    }

    private void recover(ServerSubLevelContainer container, UUID id) {
        var level = container.getLevel();
        var body = recoveryBodies.get(id);
        if (body == null) return;
        var found = container.getSubLevel(id);
        if (found == null || body.isRemoved()) return;
        if (found != body)
            throw new IllegalStateException(
                    "Native recovery no longer owns the current object: " + id);
        if (body.getLevel() != level || !id.equals(body.getUniqueId()))
            throw new IllegalStateException("Native recovery UUID changed owner: " + id);
        UUID attempt = pending.get(id);
        if (attempt == null)
            throw new IllegalStateException("Recovery lost construction owner: " + id);
        // Establish exact late-load rejection before any cleanup callback can reinsert an actor.
        recordCancellation(id);
        var owned = entities.get(id);
        if (owned != null)
            for (UUID entityId : owned) {
                var entity = level.getEntity(entityId);
                if (entity != null) entity.discard();
            }
        ShipKineticRepair.cancel(level, body);
        container.removeForceLoadTicket(body, BUILD_TICKET, attempt);
        // Use only the exact native body. The lifecycle removal command additionally deletes
        // ordinary world-area entities and is intentionally not used for construction recovery.
        container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        if (container.getSubLevel(id) != null)
            throw new IllegalStateException("Native construction recovery did not remove: " + id);
        NaturalSpawnPhysicalChunkManager.untrack(level, id);
        NPShipsLifecycleManager.untrack(level, id);
        retire(attempt, id, false);
        // Native deletion is submitted, not durably acknowledged. If old bytes return, their
        // incomplete stamp makes the next actual load repeat this exact-UUID cancellation.
    }

    static DirectSpawnJournal load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("version") != VERSION)
            throw new IllegalStateException("Unsupported direct construction journal version");
        var journal = new DirectSpawnJournal();
        if (!(tag.get("pending") instanceof ListTag entries)
                || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalStateException("Invalid direct construction journal entry list");
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            if (!entry.hasUUID("body") || !entry.hasUUID("attempt"))
                throw new IllegalStateException("Invalid direct construction journal entry");
            UUID body = entry.getUUID("body"), attempt = entry.getUUID("attempt");
            if (journal.pending.putIfAbsent(body, attempt) != null)
                throw new IllegalStateException(
                        "Duplicate direct construction journal UUID: " + body);
            journal.readEntities(body, entry);
        }
        readUuids(tag, "cancelled_entities", journal.cancelledEntities);
        return journal;
    }

    private ListTag entityTags(UUID body) {
        var result = new ListTag();
        var owned = entities.get(body);
        if (owned != null) for (UUID entity : owned) result.add(NbtUtils.createUUID(entity));
        return result;
    }

    private void readEntities(UUID body, CompoundTag record) {
        if (!record.contains("entities")) return;
        readUuids(record, "entities", entities.computeIfAbsent(body, ignored -> new HashSet<>()));
    }

    private static void readUuids(CompoundTag record, String key, Set<UUID> target) {
        // Version 1 records written before entity ownership had no UUID lists.
        if (!record.contains(key)) return;
        if (!(record.get(key) instanceof ListTag list)
                || !list.isEmpty() && list.getElementType() != Tag.TAG_INT_ARRAY)
            throw new IllegalStateException("Invalid construction entity UUID list: " + key);
        for (var entry : list) {
            try {
                target.add(NbtUtils.loadUUID(entry));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "Invalid construction entity UUID: " + key, invalid);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", VERSION);
        var entries = new ListTag();
        for (var record : pending.entrySet()) {
            // A transient published row awaits sliced metadata retirement only. This omission
            // does not override an older incomplete stamp in an independent native body file.
            if (published(record.getValue())) continue;
            var entry = new CompoundTag();
            entry.putUUID("body", record.getKey());
            entry.putUUID("attempt", record.getValue());
            entry.put("entities", entityTags(record.getKey()));
            entries.add(entry);
        }
        tag.put("pending", entries);
        var cancelled = new ListTag();
        for (UUID entity : cancelledEntities) cancelled.add(NbtUtils.createUUID(entity));
        tag.put("cancelled_entities", cancelled);
        return tag;
    }
}
