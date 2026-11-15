package com.eynny.npships.network;

import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.resources.ResourceMemoryBudget;
import com.eynny.npships.resources.ServerResourcePolicy;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Bounded request-scoped projection of the existing SERVER ConfigSpec and frozen registry. */
@net.neoforged.fml.common.EventBusSubscriber(
        modid = "npships",
        bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
public final class PatchManagerPolicy {
    public static final int MAX_POLICY_CHARS = 262144;
    private static final int PAGE_BYTES = 32768, PAGE_ROWS = 32, FRAGMENT_CHARS = 4096;
    private static final int MAX_UPLOADS = 4;
    private static final long UPLOAD_NANOS = 30_000_000_000L;
    private static final Map<Object, Peer> PEERS = new HashMap<>();
    private static final Set<Peer> UPLOADS = new HashSet<>();
    private static volatile String registryFingerprint;
    private static volatile net.neoforged.fml.config.ModConfig nativeConfig;
    private static long sequence;

    private PatchManagerPolicy() {}

    private static final class Peer {
        final String epoch = UUID.randomUUID().toString();
        Upload upload;
    }

    private static final class Upload implements AutoCloseable {
        final long token, started;
        final String registry, configured, effective, epoch;
        final ResourceMemoryBudget.Scope capacity;
        char[] data;
        int offset;

        Upload(
                long token,
                long started,
                View view,
                String epoch,
                ResourceMemoryBudget.Scope capacity,
                int length) {
            this.token = token;
            this.started = started;
            this.registry = view.registry;
            this.configured = view.configuredRevision;
            this.effective = view.effectiveRevision;
            this.epoch = epoch;
            this.capacity = capacity;
            // Exact payload arrays and conversion overlap; not an estimate of arbitrary Gson
            // graphs.
            capacity.reserve(Math.multiplyExact(ResourceMemoryBudget.arrayBytes(length, 2), 2));
            data = new char[length];
        }

        public void close() {
            data = null;
            capacity.close();
        }
    }

    private record View(
            List<PatchManagerModel.Row> rows,
            List<String> disabled,
            List<String> order,
            String registry,
            String configuredRevision,
            String effectiveRevision,
            boolean pending) {}

    static JsonObject response(
            ServerPlayer player,
            RuntimeSettingsNetworking.PolicyRequest request,
            Object connection) {
        if (!player.server.isSameThread())
            throw new IllegalStateException("Patch policy requires server thread");
        long now = System.nanoTime();
        expire(now);
        var peer = PEERS.computeIfAbsent(connection, ignored -> new Peer());
        JsonObject result;
        try (var transientCapacity =
                ServerResourcePolicy.memory(player.server).scope("patch manager metadata")) {
            if (!NPShipsRuntimeConfig.SPEC.isLoaded())
                throw new IllegalArgumentException("npships.patch_manager.unavailable");
            var view =
                    view(
                            request.action().equals("page") || request.action().equals("begin"),
                            transientCapacity);
            checkJson(request.json(), 12000);
            var parsedInput = JsonParser.parseString(request.json());
            if (!parsedInput.isJsonObject())
                throw new IllegalArgumentException("npships.patch_manager.invalid");
            var input = parsedInput.getAsJsonObject();
            requestShape(request.action(), input);
            result = header(player, request, peer, view);
            switch (request.action()) {
                case "page" -> {
                    if (!expected(input, peer, view, true)) stale(result);
                    else page(result, input, view);
                }
                case "begin" -> {
                    requireOperator(player);
                    if (!expected(input, peer, view, false)) stale(result);
                    else {
                        int length = input.get("length").getAsInt();
                        if (length < 2 || length > MAX_POLICY_CHARS)
                            throw new IllegalArgumentException("npships.patch_manager.too_large");
                        release(peer);
                        if (UPLOADS.size() >= MAX_UPLOADS)
                            throw new IllegalArgumentException("npships.patch_manager.busy");
                        var capacity =
                                ServerResourcePolicy.memory(player.server)
                                        .scope("patch manager upload");
                        try {
                            peer.upload =
                                    new Upload(++sequence, now, view, peer.epoch, capacity, length);
                            UPLOADS.add(peer);
                        } catch (RuntimeException failure) {
                            capacity.close();
                            throw failure;
                        }
                        result.addProperty("status", "begun");
                        result.addProperty("upload", peer.upload.token);
                    }
                }
                case "part" -> {
                    requireOperator(player);
                    var upload = upload(peer, input);
                    if (!current(upload, peer, view)) {
                        release(peer);
                        stale(result);
                    } else {
                        int offset = input.get("offset").getAsInt();
                        String text = input.get("text").getAsString();
                        if (offset != upload.offset
                                || text.isEmpty()
                                || text.length() > FRAGMENT_CHARS
                                || text.length() > upload.data.length - upload.offset)
                            throw new IllegalArgumentException(
                                    "npships.patch_manager.invalid_upload");
                        text.getChars(0, text.length(), upload.data, upload.offset);
                        upload.offset += text.length();
                        result.addProperty("status", "part");
                        result.addProperty("upload", upload.token);
                        result.addProperty("next_offset", upload.offset);
                    }
                }
                case "commit" -> {
                    requireOperator(player);
                    var upload = upload(peer, input);
                    try {
                        if (!current(upload, peer, view)) stale(result);
                        else {
                            if (upload.offset != upload.data.length)
                                throw new IllegalArgumentException(
                                        "npships.patch_manager.invalid_upload");
                            String text = new String(upload.data);
                            checkJson(text, MAX_POLICY_CHARS);
                            var parsedMutation = JsonParser.parseString(text);
                            if (!parsedMutation.isJsonObject())
                                throw new IllegalArgumentException("npships.patch_manager.invalid");
                            var mutation = parsedMutation.getAsJsonObject();
                            mutationShape(mutation);
                            mutate(view(true, transientCapacity), mutation);
                            result = header(player, request, peer, view(false, transientCapacity));
                            result.addProperty("status", "applied");
                        }
                    } finally {
                        release(peer);
                    }
                }
                case "cancel" -> {
                    // A connection may always discard only its own bounded upload, even after OP
                    // loss.
                    if (peer.upload != null
                            && input.has("upload")
                            && peer.upload.token == input.get("upload").getAsLong()) release(peer);
                    result.addProperty("status", "cancelled");
                }
                default -> throw new IllegalArgumentException("npships.patch_manager.invalid");
            }
        } catch (com.eynny.npships.resources.ResourceLimitException failure) {
            release(peer);
            result = failure(request, peer, "npships.settings.resource_limit");
        } catch (com.google.gson.JsonParseException failure) {
            result = failure(request, peer, "npships.patch_manager.invalid");
        } catch (IllegalArgumentException | IllegalStateException failure) {
            result = failure(request, peer, safeReason(failure.getMessage()));
        } catch (RuntimeException failure) {
            release(peer);
            com.mojang.logging.LogUtils.getLogger()
                    .warn("[NPShips patch manager] Configuration request failed", failure);
            result = failure(request, peer, "npships.settings.update_failed");
        }
        if (RuntimeSettingsNetworking.encodedBytes(result) > PAGE_BYTES)
            return failure(request, peer, "npships.patch_manager.too_large");
        return result;
    }

    private static void requireOperator(ServerPlayer player) {
        if (!player.hasPermissions(2))
            throw new IllegalArgumentException("npships.settings.read_only");
    }

    private static Upload upload(Peer peer, JsonObject input) {
        if (peer.upload == null || peer.upload.token != input.get("upload").getAsLong())
            throw new IllegalArgumentException("npships.patch_manager.invalid_upload");
        return peer.upload;
    }

    private static boolean current(Upload upload, Peer peer, View view) {
        return upload.epoch.equals(peer.epoch)
                && upload.registry.equals(view.registry)
                && upload.configured.equals(view.configuredRevision)
                && upload.effective.equals(view.effectiveRevision);
    }

    private static boolean expected(JsonObject input, Peer peer, View view, boolean first) {
        for (String key :
                List.of("epoch", "registry", "configured_revision", "effective_revision")) {
            String expected = input.has(key) ? input.get(key).getAsString() : "";
            if (first && expected.isEmpty()) continue;
            String actual =
                    switch (key) {
                        case "epoch" -> peer.epoch;
                        case "registry" -> view.registry;
                        case "configured_revision" -> view.configuredRevision;
                        default -> view.effectiveRevision;
                    };
            if (!actual.equals(expected)) return false;
        }
        return true;
    }

    private static JsonObject header(
            ServerPlayer player,
            RuntimeSettingsNetworking.PolicyRequest request,
            Peer peer,
            View view) {
        var result = base(request, peer);
        result.addProperty("registry", view.registry);
        result.addProperty("configured_revision", view.configuredRevision);
        result.addProperty("effective_revision", view.effectiveRevision);
        result.addProperty("editable", player.hasPermissions(2));
        result.addProperty("pending_restart", view.pending);
        result.addProperty("status", "page");
        return result;
    }

    private static JsonObject base(RuntimeSettingsNetworking.PolicyRequest request, Peer peer) {
        var result = new JsonObject();
        result.addProperty("kind", "patch_state");
        result.addProperty("request_id", request.id());
        result.addProperty("session", request.session());
        result.addProperty("epoch", peer.epoch);
        result.addProperty("reason", "");
        return result;
    }

    private static JsonObject failure(
            RuntimeSettingsNetworking.PolicyRequest request, Peer peer, String reason) {
        var result = base(request, peer);
        result.addProperty("status", "refused");
        result.addProperty("reason", reason);
        return result;
    }

    private static String safeReason(String message) {
        return message == null || message.length() > 1024 || !message.startsWith("npships.")
                ? "npships.patch_manager.invalid"
                : message;
    }

    private static void stale(JsonObject result) {
        result.addProperty("status", "stale");
        result.addProperty("reason", "npships.settings.stale");
    }

    private static void page(JsonObject result, JsonObject input, View view) {
        String query = input.has("query") ? input.get("query").getAsString() : "";
        if (!query.isEmpty()) throw new IllegalArgumentException("npships.patch_manager.invalid");
        int offset = input.has("offset") ? input.get("offset").getAsInt() : 0;
        int textOffset = input.has("text_offset") ? input.get("text_offset").getAsInt() : 0;
        if (offset < 0 || offset > view.rows.size() || textOffset < 0)
            throw new IllegalArgumentException("npships.patch_manager.invalid");
        result.addProperty("offset", offset);
        result.addProperty("total", view.rows.size());
        result.addProperty("next", offset);
        var rows = new com.google.gson.JsonArray();
        result.add("rows", rows);
        if (offset == view.rows.size()) {
            if (textOffset != 0)
                throw new IllegalArgumentException("npships.patch_manager.invalid");
            return;
        }
        var firstRow = view.rows.get(offset).toJson();
        String text = RuntimeSettingsNetworking.json(firstRow);
        if (text.length() > MAX_POLICY_CHARS)
            throw new IllegalArgumentException("npships.patch_manager.too_large");
        if (textOffset > 0 || text.length() > 16000) {
            if (textOffset >= text.length())
                throw new IllegalArgumentException("npships.patch_manager.invalid");
            int end = Math.min(text.length(), textOffset + FRAGMENT_CHARS);
            var fragment = new JsonObject();
            fragment.addProperty("index", offset);
            fragment.addProperty("text_offset", textOffset);
            fragment.addProperty("length", text.length());
            fragment.addProperty("text", text.substring(textOffset, end));
            result.add("fragment", fragment);
            result.addProperty("next", end == text.length() ? offset + 1 : offset);
            return;
        }
        // Nested compact JSON has the same ASCII row length as standalone row JSON. Count
        // each row once instead of repeatedly serializing every already accepted row.
        int headerCharacters = RuntimeSettingsNetworking.json(result).length();
        int initialNextDigits = Integer.toString(offset).length();
        int rowCharacters = 0;
        for (int index = offset; index < view.rows.size() && rows.size() < PAGE_ROWS; index++) {
            var row = index == offset ? firstRow : view.rows.get(index).toJson();
            int length =
                    index == offset ? text.length() : RuntimeSettingsNetworking.json(row).length();
            int separator = rows.isEmpty() ? 0 : 1;
            int nextDigits = Integer.toString(index + 1).length();
            int characters =
                    headerCharacters
                            + rowCharacters
                            + separator
                            + length
                            + nextDigits
                            - initialNextDigits;
            if (RuntimeSettingsNetworking.encodedBytes(characters) > PAGE_BYTES) break;
            rows.add(row);
            rowCharacters += separator + length;
            result.addProperty("next", index + 1);
        }
    }

    private static void mutate(View view, JsonObject mutation) {
        Set<String> known = new HashSet<>();
        for (var row : view.rows) if (row.present()) known.add(row.id());
        var disabled = ids(mutation.getAsJsonArray("disabled"));
        var order = new ArrayList<>(idsOrdered(mutation.getAsJsonArray("order")));
        if (!known.containsAll(disabled) || !known.equals(new HashSet<>(order)))
            throw new IllegalArgumentException("npships.patch_manager.invalid");
        boolean reorder = mutation.get("reorder").getAsBoolean();
        boolean reset = mutation.get("reset_order").getAsBoolean();
        var previousDisabled = PatchManagerModel.configuredDisabled(view.rows);
        var previousOrder = PatchManagerModel.knownOrder(view.rows);
        if (reset) {
            String reason = PatchManagerModel.validateDefaults(view.rows, disabled, order);
            if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
        } else if (reorder) {
            String reason =
                    PatchManagerModel.validate(
                            view.rows, previousDisabled, previousOrder, disabled, order);
            if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
        } else {
            String reason =
                    PatchManagerModel.validate(
                            view.rows, previousDisabled, previousOrder, disabled, previousOrder);
            if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
        }
        var nextDisabled = new ArrayList<String>();
        for (String id : view.disabled) if (!known.contains(id)) nextDisabled.add(id);
        nextDisabled.addAll(new TreeSet<>(disabled));
        var nextOrder = new ArrayList<>(view.order);
        if (reset) nextOrder.removeIf(known::contains);
        else if (reorder) {
            // Preserve every absent ID and its order; replace only present slots, appending new
            // ones.
            int cursor = 0;
            for (int i = 0; i < nextOrder.size(); i++)
                if (known.contains(nextOrder.get(i))) nextOrder.set(i, order.get(cursor++));
            while (cursor < order.size()) nextOrder.add(order.get(cursor++));
        }
        // Prime WORLD effective caches before modifying raw saved values. In-flight policies stay
        // immutable.
        NPShipsRuntimeConfig.DISABLED_PATCH_TYPES.get();
        NPShipsRuntimeConfig.PATCH_TYPE_ORDER.get();
        var owner = nativeConfig;
        var loaded = owner == null ? null : owner.getLoadedConfig();
        if (loaded == null
                || !(loaded.config()
                        instanceof
                        com.electronwill.nightconfig.core.concurrent.ConcurrentCommentedConfig
                                        config))
            throw new IllegalArgumentException("npships.patch_manager.unavailable");
        var disabledPath = NPShipsRuntimeConfig.DISABLED_PATCH_TYPES.getPath();
        var orderPath = NPShipsRuntimeConfig.PATCH_TYPE_ORDER.getPath();
        config.bulkCommentedUpdate(
                raw -> {
                    if (owner.getLoadedConfig() != loaded
                            || !view.disabled.equals(raw.get(disabledPath))
                            || !view.order.equals(raw.get(orderPath)))
                        throw new IllegalArgumentException("npships.settings.stale");
                    raw.set(disabledPath, List.copyOf(nextDisabled));
                    raw.set(orderPath, List.copyOf(nextOrder));
                });
        try {
            // Save outside the config lock: native save delivers a ModConfig event under its own
            // owner lock. Reversing those public lock boundaries would deadlock FileWatcher.
            loaded.save();
            if (owner.getLoadedConfig() != loaded)
                throw new IllegalArgumentException("npships.settings.stale");
        } catch (RuntimeException failure) {
            config.bulkCommentedUpdate(
                    raw -> {
                        if (owner.getLoadedConfig() == loaded
                                && nextDisabled.equals(raw.get(disabledPath))
                                && nextOrder.equals(raw.get(orderPath))) {
                            raw.set(disabledPath, view.disabled);
                            raw.set(orderPath, view.order);
                        }
                    });
            throw failure;
        }
    }

    private static Set<String> ids(com.google.gson.JsonArray array) {
        return Set.copyOf(idsOrdered(array));
    }

    private static List<String> idsOrdered(com.google.gson.JsonArray array) {
        if (array == null || array.size() > PatchTypes.MAX_TYPES + 15)
            throw new IllegalArgumentException("npships.patch_manager.invalid");
        var result = new ArrayList<String>();
        var unique = new HashSet<String>();
        for (var value : array) {
            String id = value.getAsString();
            var parsed = ResourceLocation.tryParse(id);
            if (id.length() > 256
                    || parsed == null
                    || !parsed.toString().equals(id)
                    || !unique.add(id))
                throw new IllegalArgumentException("npships.patch_manager.invalid");
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static View view(boolean includeRows, ResourceMemoryBudget.Scope capacity) {
        var providers = PatchTypes.providers();
        // Native config owns the text. Charge only new owned reference/list slots here, before
        // projection/copy growth; arbitrary object headers and Gson/native graphs stay qualified.
        long configuredSlots =
                (long) NPShipsRuntimeConfig.DISABLED_PATCH_TYPES.getRaw().size()
                        + NPShipsRuntimeConfig.PATCH_TYPE_ORDER.getRaw().size()
                        + NPShipsRuntimeConfig.DISABLED_PATCH_TYPES.get().size()
                        + NPShipsRuntimeConfig.PATCH_TYPE_ORDER.get().size();
        capacity.reserve(ResourceMemoryBudget.arrayBytes(configuredSlots, Long.BYTES));
        if (includeRows || registryFingerprint == null)
            capacity.reserve(
                    ResourceMemoryBudget.arrayBytes(
                            Math.multiplyExact(configuredSlots + providers.size(), 24L),
                            Long.BYTES));
        // get() first captures the native WORLD effective value; getRaw() is configured/pending.
        List<String> effectiveDisabled =
                List.copyOf(NPShipsRuntimeConfig.DISABLED_PATCH_TYPES.get());
        List<String> effectiveOrder = List.copyOf(NPShipsRuntimeConfig.PATCH_TYPE_ORDER.get());
        var configured = configuredPair();
        List<String> disabled = configured.get(0), order = configured.get(1);
        if (!includeRows && registryFingerprint != null)
            return new View(
                    List.of(),
                    disabled,
                    order,
                    registryFingerprint,
                    revision(disabled, order),
                    revision(effectiveDisabled, effectiveOrder),
                    !disabled.equals(effectiveDisabled) || !order.equals(effectiveOrder));
        var effectiveRanks = new ArrayList<String>();
        for (String id : effectiveOrder) if (!effectiveRanks.contains(id)) effectiveRanks.add(id);
        var rows = new ArrayList<PatchManagerModel.Row>();
        for (var provider : providers.values()) {
            var metadata = provider.registration();
            String id = provider.id().toString();
            var stages = new TreeSet<String>();
            var adapter = PatchTypes.adapter(provider.id());
            if (adapter != null) adapter.stages().forEach(stage -> stages.add(stage.name()));
            else if (provider.type() != null)
                for (var stage :
                        List.of(
                                PatchStage.PREPARE,
                                PatchStage.BEFORE_ASSEMBLY,
                                PatchStage.AFTER_PLACEMENT)) stages.add(stage.name());
            var author = PatchTypes.authoring(provider.id());
            boolean authoring = author != null && author.automaticCallbacks();
            if (authoring) {
                stages.add(PatchStage.VALIDATION.name());
                stages.add(PatchStage.AUTHOR_PREPARATION.name());
            }
            if (metadata.capabilities()
                    .contains(com.eynny.npships.api.patches.PatchRegistration.Capability.CAPTURE))
                stages.add(PatchStage.CAPTURE_BLOCK.name());
            if (metadata.capabilities()
                    .contains(
                            com.eynny.npships.api.patches.PatchRegistration.Capability
                                    .AUTHORED_TRANSFORM))
                stages.add(PatchStage.AUTHOR_PREPARATION.name());
            var edges = new TreeMap<String, PatchManagerModel.StageOrder>();
            metadata.ordering()
                    .forEach(
                            (stage, edge) -> {
                                stages.add(stage.name());
                                edges.put(
                                        stage.name(),
                                        new PatchManagerModel.StageOrder(
                                                sorted(edge.before()), sorted(edge.after())));
                            });
            String availability = availability(provider.id(), new HashSet<>());
            rows.add(
                    new PatchManagerModel.Row(
                            id,
                            metadata.ownerMod(),
                            id.startsWith("npships:"),
                            true,
                            availability.isEmpty(),
                            availability,
                            provider.execution().name(),
                            metadata.disabledBehavior().name(),
                            !disabled.contains(id),
                            !effectiveDisabled.contains(id),
                            order.contains(id) ? order.indexOf(id) : Integer.MAX_VALUE,
                            effectiveRanks.contains(id)
                                    ? effectiveRanks.indexOf(id)
                                    : Integer.MAX_VALUE,
                            List.copyOf(stages),
                            sorted(metadata.requires()),
                            sorted(metadata.conflicts()),
                            edges,
                            authoring,
                            id,
                            modName(metadata.ownerMod()),
                            PatchTypes.metadata(provider.id())));
        }
        var unknown = new TreeSet<String>();
        unknown.addAll(disabled);
        unknown.addAll(order);
        unknown.addAll(effectiveDisabled);
        unknown.addAll(effectiveOrder);
        for (String id : unknown)
            if (!providers.containsKey(ResourceLocation.parse(id)))
                rows.add(
                        new PatchManagerModel.Row(
                                id,
                                ResourceLocation.parse(id).getNamespace(),
                                false,
                                false,
                                false,
                                "missing_provider",
                                "ABSENT",
                                "UNKNOWN",
                                !disabled.contains(id),
                                !effectiveDisabled.contains(id),
                                order.indexOf(id),
                                effectiveOrder.indexOf(id),
                                List.of(),
                                List.of(),
                                List.of(),
                                Map.of(),
                                false));
        rows.sort(
                Comparator.comparingInt(PatchManagerModel.Row::configuredRank)
                        .thenComparing(PatchManagerModel.Row::id));
        var registry = new com.google.gson.JsonArray();
        for (var row :
                rows.stream().sorted(Comparator.comparing(PatchManagerModel.Row::id)).toList())
            if (row.present()) {
                var definition = row.toJson();
                for (String key :
                        List.of(
                                "configured_enabled",
                                "effective_enabled",
                                "configured_rank",
                                "effective_rank")) definition.remove(key);
                registry.add(definition);
            }
        registryFingerprint = hash(registry.toString());
        return new View(
                List.copyOf(rows),
                disabled,
                order,
                registryFingerprint,
                revision(disabled, order),
                revision(effectiveDisabled, effectiveOrder),
                !disabled.equals(effectiveDisabled) || !order.equals(effectiveOrder));
    }

    private static String availability(ResourceLocation id, Set<ResourceLocation> visited) {
        if (!visited.add(id)) return "";
        var provider = PatchTypes.provider(id);
        if (provider == null) return "missing_provider:" + id;
        for (String mod : new TreeSet<>(provider.requiredMods()))
            if (!ModList.get().isLoaded(mod)) return "missing_mod:" + mod;
        for (var required : new TreeSet<>(provider.registration().requires())) {
            String missing = availability(required, visited);
            if (!missing.isEmpty()) return missing;
        }
        return "";
    }

    private static List<String> sorted(Iterable<ResourceLocation> values) {
        var result = new TreeSet<String>();
        for (var id : values) result.add(id.toString());
        return List.copyOf(result);
    }

    private static String revision(List<String> disabled, List<String> order) {
        var json = new JsonObject();
        json.add("disabled", PatchManagerModel.array(disabled));
        json.add("order", PatchManagerModel.array(order));
        return hash(json.toString());
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void requestShape(String action, JsonObject input) {
        for (String key :
                List.of("epoch", "registry", "configured_revision", "effective_revision", "query"))
            if (input.has(key)) requireString(input, key);
        switch (action) {
            case "page" -> {
                if (input.has("offset")) requireNumber(input, "offset", true);
                if (input.has("text_offset")) requireNumber(input, "text_offset", true);
            }
            case "begin" -> {
                for (String key :
                        List.of("epoch", "registry", "configured_revision", "effective_revision"))
                    requireString(input, key);
                requireNumber(input, "length", true);
            }
            case "part" -> {
                requireNumber(input, "upload", false);
                requireNumber(input, "offset", true);
                requireString(input, "text");
            }
            case "commit" -> requireNumber(input, "upload", false);
            case "cancel" -> {
                if (input.has("upload")) requireNumber(input, "upload", false);
            }
            default -> throw new IllegalArgumentException("npships.patch_manager.invalid");
        }
    }

    private static void requireString(JsonObject input, String key) {
        if (!input.has(key)
                || !input.get(key).isJsonPrimitive()
                || !input.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException("npships.patch_manager.invalid");
    }

    private static void requireNumber(JsonObject input, String key, boolean integer) {
        if (!input.has(key)
                || !input.get(key).isJsonPrimitive()
                || !input.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException("npships.patch_manager.invalid");
        String text = input.get(key).getAsString();
        if (!text.matches("-?[0-9]{1,19}"))
            throw new IllegalArgumentException("npships.patch_manager.invalid");
        try {
            long number = Long.parseLong(text);
            if (integer && (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE))
                throw new NumberFormatException();
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("npships.patch_manager.invalid");
        }
    }

    private static void mutationShape(JsonObject input) {
        for (String key : List.of("disabled", "order"))
            if (!input.has(key) || !input.get(key).isJsonArray())
                throw new IllegalArgumentException("npships.patch_manager.invalid");
        for (String key : List.of("reorder", "reset_order"))
            if (!input.has(key)
                    || !input.get(key).isJsonPrimitive()
                    || !input.getAsJsonPrimitive(key).isBoolean())
                throw new IllegalArgumentException("npships.patch_manager.invalid");
        for (String key : List.of("disabled", "order"))
            for (var value : input.getAsJsonArray(key))
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("npships.patch_manager.invalid");
    }

    private static void checkJson(String text, int max) {
        if (text.length() > max)
            throw new IllegalArgumentException("npships.patch_manager.too_large");
        int depth = 0;
        boolean string = false, escape = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (string) {
                if (escape) escape = false;
                else if (ch == '\\') escape = true;
                else if (ch == '"') string = false;
            } else if (ch == '"') string = true;
            else if (ch == '{' || ch == '[') {
                if (++depth > 16)
                    throw new IllegalArgumentException("npships.patch_manager.invalid");
            } else if (ch == '}' || ch == ']') depth--;
        }
    }

    private static void release(Peer peer) {
        UPLOADS.remove(peer);
        if (peer.upload != null) {
            peer.upload.close();
            peer.upload = null;
        }
    }

    private static void expire(long now) {
        for (var peer : List.copyOf(UPLOADS))
            if (now - peer.upload.started >= UPLOAD_NANOS) release(peer);
    }

    private static List<List<String>> configuredPair() {
        var owner = nativeConfig;
        var loaded = owner == null ? null : owner.getLoadedConfig();
        if (loaded != null
                && loaded.config()
                        instanceof
                        com.electronwill.nightconfig.core.concurrent.ConcurrentCommentedConfig
                                        config)
            return config.bulkCommentedRead(
                    raw -> {
                        List<String> disabled =
                                raw.get(NPShipsRuntimeConfig.DISABLED_PATCH_TYPES.getPath());
                        List<String> order =
                                raw.get(NPShipsRuntimeConfig.PATCH_TYPE_ORDER.getPath());
                        return List.of(List.copyOf(disabled), List.copyOf(order));
                    });
        return List.of(
                List.copyOf(NPShipsRuntimeConfig.DISABLED_PATCH_TYPES.getRaw()),
                List.copyOf(NPShipsRuntimeConfig.PATCH_TYPE_ORDER.getRaw()));
    }

    private static String modName(String id) {
        return ModList.get()
                .getModContainerById(id)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(id);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void loading(net.neoforged.fml.event.config.ModConfigEvent.Loading event) {
        config(event);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void reloading(net.neoforged.fml.event.config.ModConfigEvent.Reloading event) {
        config(event);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void unloading(net.neoforged.fml.event.config.ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == NPShipsRuntimeConfig.SPEC) nativeConfig = null;
    }

    private static void config(net.neoforged.fml.event.config.ModConfigEvent event) {
        if (event.getConfig().getSpec() == NPShipsRuntimeConfig.SPEC)
            nativeConfig = event.getConfig();
    }

    static void disconnected(Object connection) {
        var peer = PEERS.remove(connection);
        if (peer != null) release(peer);
    }

    static void clear() {
        for (var peer : PEERS.values()) release(peer);
        PEERS.clear();
    }
}
