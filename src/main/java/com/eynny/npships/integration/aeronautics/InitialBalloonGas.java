package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.OptionalCalls;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.LongConsumer;

/** Authored gas consumed at native balloon creation, never reloaded after ordinary native ticks. */
public final class InitialBalloonGas implements AutoCloseable {
    private static final String GAS = "dev.eriksonn.aeronautics.content.blocks.hot_air.";
    // Neither key nor value can keep a BE, construction owner, or world alive.
    private static final Map<BlockEntity, Registration> PROVIDERS = new WeakHashMap<>();

    private static final class Registration {
        final WeakReference<BlockEntity> entity;
        final WeakReference<InitialBalloonGas> owner;

        Registration(BlockEntity entity, InitialBalloonGas owner) {
            this.entity = new WeakReference<>(entity);
            this.owner = new WeakReference<>(owner);
        }
    }

    private static final class Intent {
        final BlockPos provider, cast;
        final JsonObject patch;
        final Object gasType;
        boolean consumed;

        Intent(BlockPos provider, BlockPos cast, JsonObject patch, Object gasType) {
            this.provider = provider;
            this.cast = cast;
            this.patch = patch;
            this.gasType = gasType;
        }
    }

    private final Function<BlockPos, BlockPos> findPosition;
    private final LongConsumer reserve;
    private Class<?> providerType;
    private Level source;
    private java.util.Iterator<JsonObject> preparing;
    private List<Intent> intents = new ArrayList<>();
    private List<Registration> registrations = new ArrayList<>();
    private Set<Object> initialized = Collections.newSetFromMap(new IdentityHashMap<>());
    private Exception failure;
    private boolean closed, cleanupComplete;

    /** Opens preparation only. The driver checks its shared budget between prepareStep calls. */
    public InitialBalloonGas(
            Level source,
            List<JsonObject> patches,
            Function<BlockPos, BlockPos> findPosition,
            LongConsumer reserve) {
        this.source = java.util.Objects.requireNonNull(source);
        this.findPosition = findPosition;
        this.reserve = reserve;
        preparing = patches.iterator();
    }

    /** Visits at most one authored record; a gas record uses one native provider raycast. */
    public boolean prepareStep() throws Exception {
        requireOpen();
        if (preparing == null) return true;
        if (preparing.hasNext()) {
            var patch = preparing.next();
            if (!ExternalPatches.external(patch)
                    && patch.has("kind")
                    && patch.get("kind").getAsString().equals("balloon")) {
                if (providerType == null)
                    providerType = Class.forName(GAS + "BlockEntityLiftingGasProvider");
                var local = SchematicPatches.position(patch.getAsJsonArray("pos"));
                var provider = source.getBlockEntity(local);
                if (provider == null || !providerType.isInstance(provider))
                    throw new IllegalArgumentException("Missing detached gas provider at " + local);
                OptionalCalls.call(provider, "doRaycast");
                var cast = (BlockPos) OptionalCalls.call(provider, "getCastPosition");
                if (cast == null)
                    throw new IllegalArgumentException(
                            "Authored gas provider has no enclosed cast at " + local);
                AeronauticsPatch.validate(patch);
                reserve.accept(8L * Long.BYTES);
                intents.add(
                        new Intent(
                                local.immutable(),
                                cast.immutable(),
                                patch,
                                OptionalCalls.call(provider, "getLiftingGasType")));
            }
        }
        if (preparing.hasNext()) return false;
        preparing = null;
        source = null;
        return true;
    }

    /**
     * Bind every fresh provider, including providers without the balloon's single authored patch.
     */
    public void initialBlockPlaced(BlockEntity entity) throws Exception {
        requireOpen();
        checkFailure();
        requirePrepared();
        if (entity == null || providerType == null || !providerType.isInstance(entity)) return;
        if (!(entity.getLevel() instanceof ServerLevel level)
                || entity.isRemoved()
                || level.getBlockEntity(entity.getBlockPos()) != entity)
            throw new IllegalStateException("Initial gas provider is not current");
        if (OptionalCalls.call(entity, "getBalloon") != null)
            throw new IllegalStateException(
                    "Gas provider ran before its initial placement callback");
        // Weak key entry, two weak identity references, owner record and collection growth.
        reserve.accept(24L * Long.BYTES);
        var registration = new Registration(entity, this);
        synchronized (PROVIDERS) {
            var previous = PROVIDERS.get(entity);
            if (previous != null && previous.entity.get() == entity && previous.owner.get() != null)
                throw new IllegalStateException("Gas provider already has an initial owner");
            PROVIDERS.put(entity, registration);
        }
        registrations.add(registration);
    }

    /** Native addBalloon HEAD is after unloaded-data loading and before the first balloon tick. */
    public static void beforeNativeAdmission(Object map, Object balloon) {
        synchronized (PROVIDERS) {
            if (PROVIDERS.isEmpty()) return;
        }
        try {
            var owners =
                    Collections.newSetFromMap(new IdentityHashMap<InitialBalloonGas, Boolean>());
            for (var heater : (Iterable<?>) OptionalCalls.call(balloon, "getHeaters")) {
                if (!(heater instanceof BlockEntity entity)) continue;
                Registration registration;
                synchronized (PROVIDERS) {
                    registration = PROVIDERS.get(entity);
                }
                if (registration == null || registration.entity.get() != entity) continue;
                var owner = registration.owner.get();
                if (owner != null && owners.add(owner)) owner.initialize(map, balloon, entity);
            }
        } catch (ReflectiveOperationException error) {
            // An incompatible optional accessor cannot safely attribute this balloon to an owner.
            // Its unconsumed intent fails at finish; never poison unrelated construction owners.
        }
    }

    private void initialize(Object map, Object balloon, BlockEntity heater) {
        if (closed || failure != null || initialized.contains(balloon)) return;
        try {
            var graph = OptionalCalls.call(balloon, "getGraph");
            Intent matched = null;
            for (var intent : intents) {
                if (intent.consumed) continue;
                var provider = findPosition.apply(intent.provider);
                if (provider == null) continue; // Its body has not been allocated yet.
                var cast = intent.cast.offset(provider.subtract(intent.provider));
                if (!(boolean) OptionalCalls.call(graph, "hasBlockAt", cast)) continue;
                if (matched != null)
                    throw new IllegalStateException(
                            "Multiple authored gas records target one native balloon");
                matched = intent;
            }
            if (matched == null) return;
            if (!(heater.getLevel() instanceof ServerLevel level)
                    || heater.isRemoved()
                    || level.getBlockEntity(heater.getBlockPos()) != heater)
                throw new IllegalStateException(
                        "Gas creation came from a replaced native provider");
            reserve.accept(4L * Long.BYTES);
            AeronauticsPatch.initializeGas(map, balloon, matched.patch, matched.gasType);
            matched.consumed = true;
            initialized.add(balloon);
        } catch (Exception error) {
            failure = error;
        }
    }

    /** Complete missing native links if needed; never load gas into an already ticking balloon. */
    public void finish(ServerLevel level) throws Exception {
        requireOpen();
        requirePrepared();
        checkFailure();
        for (var intent : intents) {
            var position = findPosition.apply(intent.provider);
            var provider = position == null ? null : level.getBlockEntity(position);
            if (provider == null || !providerType.isInstance(provider))
                throw new IllegalStateException("Missing final gas provider at " + intent.provider);
            OptionalCalls.call(provider, "doRaycast");
            OptionalCalls.call(provider, "tryJoinBalloon");
            OptionalCalls.call(provider, "tryCreateBalloon");
            checkFailure();
            if (!intent.consumed || OptionalCalls.call(provider, "getBalloon") == null)
                throw new IllegalStateException(
                        "Authored gas missed native creation at " + intent.provider);
        }
    }

    private void requirePrepared() {
        if (preparing != null)
            throw new IllegalStateException("Initial gas preparation is unfinished");
    }

    private void checkFailure() throws Exception {
        if (failure != null) throw failure;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Initial gas owner is closed");
    }

    public void beginCleanup() {
        closed = true;
    }

    /** One weak native-provider registration per owner-driven cleanup unit. */
    public boolean stepCleanup() {
        if (cleanupComplete) return true;
        closed = true;
        if (!registrations.isEmpty()) {
            var registration = registrations.removeLast();
            synchronized (PROVIDERS) {
                var entity = registration.entity.get();
                if (entity != null && PROVIDERS.get(entity) == registration)
                    PROVIDERS.remove(entity);
            }
            return false;
        }
        source = null;
        preparing = null;
        registrations = List.of();
        intents = List.of();
        initialized = Set.of();
        failure = null;
        cleanupComplete = true;
        return true;
    }

    @Override
    public void close() {
        while (!stepCleanup()) {}
    }
}
