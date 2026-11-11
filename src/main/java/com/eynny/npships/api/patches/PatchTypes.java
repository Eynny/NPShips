package com.eynny.npships.api.patches;

import com.eynny.npships.api.internal.NPShipsRuntimeAccess;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * One process-lifetime code registry. Register during construction/common setup; load-complete work
 * freezes definitions before catalogue access. Never register worlds, handlers or server state.
 */
@EventBusSubscriber(modid = "npships", bus = EventBusSubscriber.Bus.MOD)
public final class PatchTypes {
    /** External registration capacity is unchanged; private built-ins use reserved IDs. */
    public static final int MAX_TYPES = 256;

    private static final Map<ResourceLocation, Entry> ENTRIES = new TreeMap<>();
    private static volatile Map<ResourceLocation, Provider> frozenProviders;
    private static volatile Map<ResourceLocation, PatchType> frozen;
    private static volatile Map<ResourceLocation, PatchAuthoring> frozenAuthoring;

    /** Declared executable shape of one provider in the shared registry. */
    public enum Execution {
        /** A real block/entity target PatchType owns runtime handler creation. */
        REGISTERED,
        /** Historical descriptor value; current migrated built-ins use ADAPTER. */
        LEGACY_DISPATCH,
        /** A document-scoped PatchAdapter owns real runtime bindings and handlers. */
        ADAPTER,
        /** Only a PatchAuthoring facet exists; no runtime target or factory is fabricated. */
        AUTHORING
    }

    /**
     * Public definition view. The original five record components remain unchanged. ADAPTER
     * definitions use adapter(id) for real executable code and do not pretend to be block targets.
     * AUTHORING definitions use authoring(id) without a fake runtime factory. LEGACY_DISPATCH is
     * retained as an enum/descriptor compatibility value, not a migrated adapter.
     *
     * @param id stable namespaced provider identifier
     * @param type real executable target definition for REGISTERED, null for other execution shapes
     * @param registration immutable ownership, dependency, stage and policy metadata
     * @param execution executable shape of this definition
     * @param requiredMods frozen union of required native mod identifiers for its code facets
     */
    public record Provider(
            ResourceLocation id,
            @Nullable PatchType type,
            PatchRegistration registration,
            Execution execution,
            Set<String> requiredMods) {
        /**
         * Copies required mods and validates agreement between execution shape and target type.
         *
         * @param id stable namespaced provider identifier
         * @param type target definition, present exactly for REGISTERED
         * @param registration provider ownership and policy metadata
         * @param execution executable shape
         * @param requiredMods required native mod identifiers to copy
         * @throws IllegalArgumentException if type presence disagrees with REGISTERED
         * @throws NullPointerException if id, registration, execution, requiredMods or a mod ID is
         *     null
         */
        public Provider {
            Objects.requireNonNull(id);
            Objects.requireNonNull(registration);
            Objects.requireNonNull(execution);
            requiredMods = Set.copyOf(requiredMods);
            if ((execution == Execution.REGISTERED) != (type != null))
                throw new IllegalArgumentException("Target providers require a real PatchType");
        }
    }

    private record Entry(
            Provider definition,
            @Nullable PatchAdapter adapter,
            @Nullable PatchAuthoring authoring,
            @Nullable PatchPayloads payloads,
            @Nullable PatchMetadata metadata) {
        private Entry(
                Provider definition,
                PatchAdapter adapter,
                PatchAuthoring authoring,
                PatchPayloads payloads) {
            this(definition, adapter, authoring, payloads, null);
        }

        private Entry {
            if ((definition.execution() == Execution.ADAPTER) != (adapter != null))
                throw new IllegalArgumentException("Adapter definitions require executable code");
            if (definition.execution() == Execution.AUTHORING && authoring == null)
                throw new IllegalArgumentException("Authoring definitions require executable code");
        }
    }

    /** Frozen code metadata; mutable adapter-returned sets cannot alter an in-flight stage plan. */
    private record RegisteredAdapter(
            PatchAdapter delegate, Set<String> requiredMods, Set<PatchStage> stages)
            implements PatchAdapter {
        private RegisteredAdapter(PatchAdapter delegate) {
            this(
                    Objects.requireNonNull(delegate),
                    Set.copyOf(delegate.requiredMods()),
                    Set.copyOf(delegate.stages()));
        }

        private RegisteredAdapter {
            for (var stage : stages)
                if (stage == PatchStage.VALIDATION
                        || stage == PatchStage.AUTHOR_PREPARATION
                        || stage == PatchStage.CAPTURE_BLOCK
                        || stage == PatchStage.PREPARE_NATIVE_DATA
                        || stage == PatchStage.RESTORE_NATIVE_CONTEXT
                        || stage == PatchStage.POST_PLACEMENT_MAINTENANCE)
                    throw new IllegalArgumentException(
                            "Authoring host not available for document adapter stage "
                                    + stage
                                    + "; use runtime INPUT_VALIDATION for detached spawn preflight");
            if (requiredMods.size() > 16
                    || requiredMods.stream().anyMatch(id -> !id.matches("[a-z][a-z0-9_]{1,63}")))
                throw new IllegalArgumentException("Invalid patch dependency IDs");
        }

        @Override
        public <T> java.util.Optional<T> capability(Class<T> contract) {
            return delegate.capability(contract);
        }

        @Override
        public List<PatchBinding> bind(PatchExecutionContext context) {
            return delegate.bind(context);
        }

        @Override
        public void validate(PatchBinding binding) {
            delegate.validate(binding);
        }

        @Override
        public Set<Class<?>> requestedCapabilities(PatchBinding binding) {
            return delegate.requestedCapabilities(binding);
        }

        @Override
        public Map<java.util.UUID, PatchType.EntityMode> entityRoots(PatchBinding binding) {
            return delegate.entityRoots(binding);
        }

        @Override
        public List<PatchPreparedInput.Claim> preparedClaims(PatchBinding binding) {
            return delegate.preparedClaims(binding);
        }

        @Override
        public PatchHandler create(PatchBinding binding, PatchExecutionContext context)
                throws Exception {
            return delegate.create(binding, context);
        }
    }

    /** Frozen dependency metadata and the optional code facet of the same provider entry. */
    private record RegisteredAuthoring(PatchAuthoring delegate, Set<String> requiredMods)
            implements PatchAuthoring {
        @Override
        public boolean automaticCallbacks() {
            return delegate.automaticCallbacks();
        }

        private RegisteredAuthoring(PatchAuthoring delegate) {
            this(Objects.requireNonNull(delegate), Set.copyOf(delegate.requiredMods()));
        }

        private RegisteredAuthoring {
            if (requiredMods.size() > 16
                    || requiredMods.stream().anyMatch(id -> !id.matches("[a-z][a-z0-9_]{1,63}")))
                throw new IllegalArgumentException("Invalid authoring dependency IDs");
        }

        @Override
        public <T> java.util.Optional<T> capability(Class<T> contract) {
            return delegate.capability(contract);
        }

        @Override
        public List<CaptureClaim> captureClaims(Context context) {
            return delegate.captureClaims(context);
        }

        @Override
        public Proposal capture(Context context) {
            return delegate.capture(context);
        }

        @Override
        public List<BlockStateClaim> blockStateClaims(Context context) {
            return delegate.blockStateClaims(context);
        }

        @Override
        public Proposal transform(Context context) {
            return delegate.transform(context);
        }

        @Override
        public void validate(Context context) {
            delegate.validate(context);
        }
    }

    static {
        NPShipsRuntimeAccess.get()
                .registerBuiltIns(
                        new NPShipsRuntimeAccess.BuiltInRegistrar() {
                            @Override
                            public void register(
                                    ResourceLocation id,
                                    PatchAdapter adapter,
                                    PatchRegistration metadata) {
                                builtin(id, adapter, metadata);
                            }

                            @Override
                            public void registerAuthoring(
                                    ResourceLocation id,
                                    PatchAuthoring authoring,
                                    PatchRegistration metadata) {
                                ensureRegistration(id, true);
                                var code = new RegisteredAuthoring(authoring);
                                var provider =
                                        new Provider(
                                                id,
                                                null,
                                                metadata,
                                                Execution.AUTHORING,
                                                code.requiredMods());
                                ENTRIES.put(
                                        id,
                                        new Entry(
                                                provider,
                                                null,
                                                code,
                                                null,
                                                code.capability(PatchMetadata.class).orElse(null)));
                            }
                        });
    }

    private PatchTypes() {}

    private static void builtin(
            ResourceLocation id, PatchAdapter adapter, PatchRegistration metadata) {
        ensureRegistration(id, true);
        var code = new RegisteredAdapter(adapter);
        var definition =
                new Provider(
                        id,
                        null,
                        Objects.requireNonNull(metadata),
                        Execution.ADAPTER,
                        code.requiredMods());
        ENTRIES.put(
                id,
                new Entry(
                        definition,
                        code,
                        null,
                        null,
                        code.capability(PatchMetadata.class).orElse(null)));
    }

    /**
     * Registers a target provider with default required-restoration metadata owned by its
     * namespace.
     *
     * @param id addon-owned provider identifier
     * @param type real target definition and fresh per-binding handler factory
     * @throws IllegalArgumentException if the identifier is reserved, duplicate or too long, or
     *     supplied code metadata is invalid
     * @throws IllegalStateException if registration is frozen or external capacity is exhausted
     * @throws NullPointerException if an argument or required code metadata is null
     */
    public static synchronized void register(ResourceLocation id, PatchType type) {
        register(id, type, PatchRegistration.legacy(Objects.requireNonNull(id).getNamespace()));
    }

    /**
     * Registers a target provider before the existing process-lifetime freeze.
     *
     * @param id addon-owned provider identifier
     * @param type real target definition; existing factories and handlers remain valid
     * @param metadata multiplicity, dependency, ordering and disabled-policy declaration
     * @throws IllegalArgumentException if the identifier is reserved, duplicate or too long, or
     *     supplied code metadata is invalid
     * @throws IllegalStateException if registration is frozen or external capacity is exhausted
     * @throws NullPointerException if an argument or required code metadata is null
     */
    public static synchronized void register(
            ResourceLocation id, PatchType type, PatchRegistration metadata) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(metadata);
        ensureRegistration(id, false);
        ENTRIES.put(
                id,
                new Entry(
                        new Provider(id, type, metadata, Execution.REGISTERED, type.requiredMods()),
                        null,
                        null,
                        null));
    }

    /**
     * Registers document-scoped runtime code in the same owner as target providers.
     *
     * @param id addon-owned provider identifier
     * @param adapter binding and fresh handler code; dependency/stage declarations are snapshotted
     * @param metadata provider ownership, dependency, ordering and disabled-policy declaration
     * @throws IllegalArgumentException if the identifier is reserved, duplicate or too long, or
     *     supplied code metadata is invalid
     * @throws IllegalStateException if registration is frozen or external capacity is exhausted
     * @throws NullPointerException if an argument or required code metadata is null
     */
    public static synchronized void register(
            ResourceLocation id, PatchAdapter adapter, PatchRegistration metadata) {
        Objects.requireNonNull(metadata);
        ensureRegistration(id, false);
        var code = new RegisteredAdapter(adapter);
        ENTRIES.put(
                id,
                new Entry(
                        new Provider(id, null, metadata, Execution.ADAPTER, code.requiredMods()),
                        code,
                        null,
                        null));
    }

    /**
     * Registers one target provider with an optional author-data code facet.
     *
     * @param id addon-owned provider identifier
     * @param type real executable target definition
     * @param metadata shared provider ownership, ordering and policy declaration
     * @param authoring capture, transform and pure validation code; dependencies are snapshotted
     * @throws IllegalArgumentException if the identifier is reserved, duplicate or too long, or
     *     supplied code metadata is invalid
     * @throws IllegalStateException if registration is frozen or external capacity is exhausted
     * @throws NullPointerException if an argument or required code metadata is null
     */
    public static synchronized void register(
            ResourceLocation id,
            PatchType type,
            PatchRegistration metadata,
            PatchAuthoring authoring) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(metadata);
        ensureRegistration(id, false);
        var code = new RegisteredAuthoring(authoring);
        ENTRIES.put(
                id,
                new Entry(
                        new Provider(
                                id,
                                type,
                                metadata,
                                Execution.REGISTERED,
                                dependencies(type.requiredMods(), code.requiredMods())),
                        null,
                        code,
                        null));
    }

    /**
     * Registers runtime and authoring capabilities under one provider identity, policy and freeze.
     *
     * @param id addon-owned provider identifier
     * @param adapter document-scoped runtime code; dependency/stage declarations are snapshotted
     * @param metadata shared provider ownership, ordering and policy declaration
     * @param authoring capture, transform and pure validation code; dependencies are snapshotted
     * @throws IllegalArgumentException if the identifier is reserved, duplicate or too long, or
     *     supplied code metadata is invalid
     * @throws IllegalStateException if registration is frozen or external capacity is exhausted
     * @throws NullPointerException if an argument or required code metadata is null
     */
    public static synchronized void register(
            ResourceLocation id,
            PatchAdapter adapter,
            PatchRegistration metadata,
            PatchAuthoring authoring) {
        Objects.requireNonNull(metadata);
        ensureRegistration(id, false);
        var runtime = new RegisteredAdapter(adapter);
        var code = new RegisteredAuthoring(authoring);
        ENTRIES.put(
                id,
                new Entry(
                        new Provider(
                                id,
                                null,
                                metadata,
                                Execution.ADAPTER,
                                dependencies(runtime.requiredMods(), code.requiredMods())),
                        runtime,
                        code,
                        null));
    }

    /**
     * Registers authoring-only code without inventing a target or runtime handler factory.
     *
     * @param id addon-owned provider identifier
     * @param authoring capture, transform and pure validation code; dependencies are snapshotted
     * @param metadata provider ownership, ordering and policy declaration
     * @throws IllegalArgumentException if the identifier is reserved, duplicate or too long, or
     *     supplied code metadata is invalid
     * @throws IllegalStateException if registration is frozen or external capacity is exhausted
     * @throws NullPointerException if an argument or required code metadata is null
     */
    public static synchronized void register(
            ResourceLocation id, PatchAuthoring authoring, PatchRegistration metadata) {
        Objects.requireNonNull(metadata);
        ensureRegistration(id, false);
        var code = new RegisteredAuthoring(authoring);
        ENTRIES.put(
                id,
                new Entry(
                        new Provider(id, null, metadata, Execution.AUTHORING, code.requiredMods()),
                        null,
                        code,
                        null));
    }

    /**
     * Attaches immutable exact payload readers to this same provider before the existing freeze.
     * Register the provider first. Target schemas must match its PatchType version; named NBT
     * schemas require its authoring facet. Attaching readers does not rewrite author data.
     *
     * @param id already registered addon-owned provider identifier
     * @param payloads optional target and named author-data version schemas
     * @throws IllegalArgumentException if the identifier is reserved or absent, readers already
     *     exist, the target version disagrees, or named schemas have no authoring facet
     * @throws IllegalStateException if registration is frozen
     * @throws NullPointerException if id or payloads is null
     */
    public static synchronized void registerPayloads(ResourceLocation id, PatchPayloads payloads) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(payloads);
        if (frozenProviders != null)
            throw new IllegalStateException("Patch registration is frozen: " + id);
        if (id.getNamespace().equals("minecraft") || id.getNamespace().equals("npships"))
            throw new IllegalArgumentException("Reserved provider payload registration: " + id);
        var entry = ENTRIES.get(id);
        if (entry == null)
            throw new IllegalArgumentException("Register provider before payload readers: " + id);
        if (entry.payloads() != null)
            throw new IllegalArgumentException("Duplicate payload readers: " + id);
        if (payloads.target() != null
                && (entry.definition().type() == null
                        || payloads.target().currentVersion()
                                != entry.definition().type().version()))
            throw new IllegalArgumentException(
                    "Target payload schema must match its PatchType version: " + id);
        if (!payloads.authorData().isEmpty() && entry.authoring() == null)
            throw new IllegalArgumentException(
                    "Author-data readers require an authoring capability: " + id);
        ENTRIES.put(
                id,
                new Entry(
                        entry.definition(),
                        entry.adapter(),
                        entry.authoring(),
                        payloads,
                        entry.metadata()));
    }

    /**
     * Returns the immutable optional version facet of the same entry, including before freeze. This
     * lookup does not convert payloads or create a runtime cache.
     *
     * @param id provider identifier
     * @return its registered payload schemas, or null when the provider or facet is absent
     * @throws NullPointerException if id is null
     */
    public static synchronized @Nullable PatchPayloads payloads(ResourceLocation id) {
        var entry = ENTRIES.get(Objects.requireNonNull(id));
        return entry == null ? null : entry.payloads();
    }

    /**
     * Attaches optional display/subject metadata to this same registered provider before freeze.
     * This does not enable the provider, declare a dependency or infer incompatibility.
     *
     * @param id already registered addon-owned provider identifier
     * @param metadata immutable display and shared-scope declaration
     * @throws IllegalArgumentException for absent/reserved IDs or duplicate metadata
     * @throws IllegalStateException after registration freezes
     * @throws NullPointerException for null arguments
     */
    public static synchronized void registerMetadata(ResourceLocation id, PatchMetadata metadata) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(metadata);
        if (frozenProviders != null)
            throw new IllegalStateException("Patch registration is frozen: " + id);
        if (id.getNamespace().equals("npships") || id.getNamespace().equals("minecraft"))
            throw new IllegalArgumentException("Reserved metadata registration: " + id);
        var entry = ENTRIES.get(id);
        if (entry == null || entry.metadata() != null)
            throw new IllegalArgumentException(
                    "Absent provider or duplicate display metadata: " + id);
        ENTRIES.put(
                id,
                new Entry(
                        entry.definition(),
                        entry.adapter(),
                        entry.authoring(),
                        entry.payloads(),
                        metadata));
    }

    /**
     * Returns registered detached display metadata without invoking callbacks or creating handlers.
     *
     * @param id provider identifier
     * @return optional metadata, null when unspecified or absent
     * @throws NullPointerException for a null identifier
     */
    public static synchronized @Nullable PatchMetadata metadata(ResourceLocation id) {
        var entry = ENTRIES.get(Objects.requireNonNull(id));
        return entry == null ? null : entry.metadata();
    }

    private static Set<String> dependencies(Set<String> runtime, Set<String> authoring) {
        var dependencies = new java.util.TreeSet<>(runtime);
        dependencies.addAll(authoring);
        return Set.copyOf(dependencies);
    }

    private static void ensureRegistration(ResourceLocation id, boolean builtin) {
        Objects.requireNonNull(id);
        if (id.toString().length() > 256)
            throw new IllegalArgumentException("Patch type ID exceeds 256 characters");
        if (frozenProviders != null)
            throw new IllegalStateException("Patch registration is frozen: " + id);
        if (builtin
                ? !id.getNamespace().equals("npships")
                : id.getNamespace().equals("minecraft") || id.getNamespace().equals("npships"))
            throw new IllegalArgumentException(
                    "External patch type must use its provider namespace: " + id);
        if (ENTRIES.containsKey(id))
            throw new IllegalArgumentException("Duplicate patch type: " + id);
        if (!builtin
                && ENTRIES.keySet().stream()
                                .filter(key -> !key.getNamespace().equals("npships"))
                                .count()
                        >= MAX_TYPES)
            throw new IllegalStateException("Patch type limit is " + MAX_TYPES);
    }

    /**
     * Tests whether load-complete publication has frozen further registration.
     *
     * @return whether the process-lifetime immutable provider snapshot is published
     */
    public static boolean isFrozen() {
        return frozenProviders != null;
    }

    /**
     * Looks up a real executable target definition, including during CLIENT setup before freeze.
     *
     * @param id provider identifier
     * @return its target definition, or null for an absent or non-target provider
     * @throws NullPointerException if id is null
     */
    public static synchronized @Nullable PatchType type(ResourceLocation id) {
        var entry = ENTRIES.get(Objects.requireNonNull(id));
        return entry == null ? null : entry.definition().type();
    }

    /**
     * Looks up detached provider metadata, including before freeze.
     *
     * @param id provider identifier
     * @return its immutable definition, or null when absent
     * @throws NullPointerException if id is null
     */
    public static synchronized @Nullable Provider provider(ResourceLocation id) {
        var entry = ENTRIES.get(Objects.requireNonNull(id));
        return entry == null ? null : entry.definition();
    }

    /**
     * Looks up the shared declaration used by runtime, authoring and SERVER policy.
     *
     * @param id provider identifier
     * @return its registration metadata, or null when absent
     * @throws NullPointerException if id is null
     */
    public static synchronized @Nullable PatchRegistration registration(ResourceLocation id) {
        var entry = ENTRIES.get(Objects.requireNonNull(id));
        return entry == null ? null : entry.definition().registration();
    }

    /**
     * Returns snapshotted code metadata and delegated runtime callbacks. No handler or world is
     * created or returned by this lookup; the preparation owner acquires fresh binding handlers.
     *
     * @param id provider identifier
     * @return its runtime adapter, or null for an absent or non-adapter provider
     * @throws NullPointerException if id is null
     */
    public static synchronized @Nullable PatchAdapter adapter(ResourceLocation id) {
        var entry = ENTRIES.get(Objects.requireNonNull(id));
        return entry == null ? null : entry.adapter();
    }

    /**
     * Returns the optional author-data capability of this same entry, including before freeze. The
     * code definition is process-lived; contexts passed to its callbacks remain callback-scoped.
     *
     * @param id provider identifier
     * @return its authoring code, or null when the provider or facet is absent
     * @throws NullPointerException if id is null
     */
    public static synchronized @Nullable PatchAuthoring authoring(ResourceLocation id) {
        var entry = ENTRIES.get(Objects.requireNonNull(id));
        return entry == null ? null : entry.authoring();
    }

    /**
     * Returns the immutable authoring projection of the one frozen entry owner.
     *
     * @return providers with a real authoring code facet
     * @throws IllegalStateException if load-complete publication has not frozen the registry
     */
    public static Map<ResourceLocation, PatchAuthoring> authoringProviders() {
        var snapshot = frozenAuthoring;
        if (snapshot == null) throw new IllegalStateException("Patch registry is not frozen yet");
        return snapshot;
    }

    private static final ClassValue<Map<ResourceLocation, ?>> CAPABILITIES =
            new ClassValue<>() {
                @Override
                protected Map<ResourceLocation, ?> computeValue(Class<?> contract) {
                    var result = new TreeMap<ResourceLocation, Object>();
                    for (var definition : providers().values()) {
                        if (!definition.requiredMods().stream()
                                .allMatch(mod -> net.neoforged.fml.ModList.get().isLoaded(mod)))
                            continue;
                        var entry = ENTRIES.get(definition.id());
                        Object runtime =
                                entry.adapter() == null
                                        ? null
                                        : entry.adapter().capability(contract).orElse(null);
                        Object author =
                                entry.authoring() == null
                                        ? null
                                        : entry.authoring().capability(contract).orElse(null);
                        if (runtime != null && author != null && runtime != author)
                            throw new IllegalArgumentException(
                                    "Competing code capabilities: " + definition.id());
                        Object code = runtime == null ? author : runtime;
                        if (code != null) result.put(definition.id(), contract.cast(code));
                    }
                    return Collections.unmodifiableMap(result);
                }
            };

    /**
     * Pure-code projection of the one frozen registry. Contains no world policy or active handler.
     * The actual host validates applicability, policy, ordering and callback lifetime.
     *
     * @param <T> host contract type
     * @param contract exact host code interface
     * @return immutable available provider code capabilities
     */
    @SuppressWarnings("unchecked")
    public static <T> Map<ResourceLocation, T> capabilities(Class<T> contract) {
        Objects.requireNonNull(contract);
        providers();
        return (Map<ResourceLocation, T>) (Map<?, ?>) CAPABILITIES.get(contract);
    }

    /**
     * Returns the frozen compatibility view of real executable targets only.
     *
     * @return immutable target definitions; document adapters never become fake targets
     * @throws IllegalStateException if load-complete publication has not frozen the registry
     */
    public static Map<ResourceLocation, PatchType> registered() {
        var snapshot = frozen;
        if (snapshot == null) throw new IllegalStateException("Patch registry is not frozen yet");
        return snapshot;
    }

    /**
     * Returns the immutable definition view for target, runtime-adapter and authoring-only entries.
     *
     * @return complete process-lifetime frozen provider metadata
     * @throws IllegalStateException if load-complete publication has not frozen the registry
     */
    public static Map<ResourceLocation, Provider> providers() {
        var snapshot = frozenProviders;
        if (snapshot == null) throw new IllegalStateException("Patch registry is not frozen yet");
        return snapshot;
    }

    /**
     * Maps a known historic unnamespaced payload kind without rewriting stored author data.
     *
     * @param kind historic kind such as arm, rope or computer
     * @return the corresponding reserved builtin identifier, or null for an unknown kind
     * @throws NullPointerException if kind is null
     */
    public static @Nullable ResourceLocation legacyId(String kind) {
        String path =
                switch (Objects.requireNonNull(kind)) {
                    case "arm" -> "create_arm";
                    case "logistics" -> "create_logistics";
                    case "spring" -> "simulated_spring";
                    case "docking" -> "simulated_docking";
                    case "rope" -> "simulated_rope";
                    case "balloon" -> "aeronautics_lifting_gas";
                    case "mechanism" -> "simulated_swivel";
                    case "computer" -> "computercraft_computer";
                    case "peripheral" -> "computercraft_peripheral";
                    case "media" -> "computercraft_media";
                    case "radar" -> "radars_network";
                    default -> null;
                };
        return path == null ? null : ResourceLocation.fromNamespaceAndPath("npships", path);
    }

    /**
     * Queues the host's single load-complete validation and immutable snapshot publication. Called
     * by the native mod event bus; addons register before this boundary rather than invoking it. It
     * freezes existing entries and introduces no second registry, handlers or server state.
     *
     * @param event native load-complete event that owns queued registration-finalization work
     */
    @SubscribeEvent
    public static void loadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(
                () -> {
                    synchronized (PatchTypes.class) {
                        var metadata = new TreeMap<ResourceLocation, PatchRegistration>();
                        ENTRIES.forEach(
                                (id, entry) -> metadata.put(id, entry.definition().registration()));
                        PatchOrdering.validate(metadata);
                        var executable = new TreeMap<ResourceLocation, PatchType>();
                        var providers = new TreeMap<ResourceLocation, Provider>();
                        var authoring = new TreeMap<ResourceLocation, PatchAuthoring>();
                        ENTRIES.forEach(
                                (id, entry) -> {
                                    var provider = entry.definition();
                                    providers.put(id, provider);
                                    if (entry.authoring() != null
                                            && entry.authoring().automaticCallbacks())
                                        authoring.put(id, entry.authoring());
                                    if (provider.type() != null)
                                        executable.put(id, provider.type());
                                });
                        frozen = Collections.unmodifiableMap(executable);
                        frozenAuthoring = Collections.unmodifiableMap(authoring);
                        frozenProviders = Collections.unmodifiableMap(providers);
                    }
                });
    }
}
