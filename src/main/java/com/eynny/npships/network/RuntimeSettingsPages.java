package com.eynny.npships.network;

import com.eynny.npships.api.NPShipsCatalogueApi;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.resources.ResourceLimitException;
import com.eynny.npships.ship.CatalogueResources;
import com.eynny.npships.ship.NPShipsSchematicManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/** One shallow metadata projection per server, never one catalogue/template copy per viewer. */
final class RuntimeSettingsPages {
    static final int PAGE_BYTES = 32768;
    static final int PAGE_ROWS = 64;
    static final int FRAGMENT_CHARS = 4096;
    private static final int QUERY_CACHE_SIZE = 4;
    private static final WeakHashMap<MinecraftServer, View> VIEWS = new WeakHashMap<>();
    private static long sequence;

    record Row(
            int token,
            String id,
            String search,
            boolean enabled,
            boolean available,
            String source,
            String reason) {
        Row(
                int token,
                String id,
                boolean enabled,
                boolean available,
                String source,
                String reason) {
            this(token, id, id.toLowerCase(Locale.ROOT), enabled, available, source, reason);
        }
    }

    record Filter(String view, String query, boolean disabled) {}

    static final class View {
        final WeakReference<NPShipsCatalogueApi.Snapshot> snapshot;
        final NPShipsSchematicManager.CatalogueStatus status;
        List<String> disabled = List.of(), addons = List.of();
        CatalogueResources.Scope capacity;
        final LinkedHashMap<Filter, Long> queryCapacity = new LinkedHashMap<>();
        final long revision = ++sequence;
        final long generation;
        List<Row> resources, structures, diagnostics;
        final LinkedHashMap<Filter, List<Row>> queries = new LinkedHashMap<>(4, .75f, true);

        View(
                NPShipsCatalogueApi.Snapshot snapshot,
                NPShipsSchematicManager.CatalogueStatus status) {
            this(snapshot, status, null);
        }

        View(
                NPShipsCatalogueApi.Snapshot snapshot,
                NPShipsSchematicManager.CatalogueStatus status,
                CatalogueResources.Scope capacity) {
            this.snapshot = new WeakReference<>(snapshot);
            this.status = status;
            this.generation = snapshot.generation();
            this.capacity = capacity;
            var configured = NPShipsRuntimeConfig.DISABLED_STRUCTURES.get();
            var configuredAddons = NPShipsRuntimeConfig.DISABLED_ADDONS.get();
            if (capacity != null)
                capacity.reserve(
                        CatalogueResources.references(
                                Math.addExact((long) configured.size(), configuredAddons.size())));
            disabled = List.copyOf(configured);
            addons = List.copyOf(configuredAddons);
        }

        boolean current(
                NPShipsCatalogueApi.Snapshot s, NPShipsSchematicManager.CatalogueStatus status) {
            // Exact defensive snapshots also notice a legacy caller mutating a list in place.
            return snapshot.get() == s
                    && this.status.equals(status)
                    && disabled.equals(NPShipsRuntimeConfig.DISABLED_STRUCTURES.get())
                    && addons.equals(NPShipsRuntimeConfig.DISABLED_ADDONS.get());
        }

        void build(NPShipsCatalogueApi.Snapshot s) {
            if (resources != null) return;
            var configured = disabled;
            var configuredAddons = addons;
            long rows =
                    Math.addExact(
                            s.automaticEntries().size(),
                            Math.addExact(
                                    s.effectiveEntries().size(),
                                    Math.addExact(configured.size(), status.diagnostics().size())));
            // A bounded upper count for retained row/index/reference slots; only identifiable
            // contents are metered. Hash-node/object headers and native String work are separate.
            long admitted = CatalogueResources.references(Math.multiplyExact(rows, 8));
            for (var entry : s.automaticEntries().values())
                admitted = Math.addExact(admitted, idTextCapacity(entry.id()));
            for (var entry : s.effectiveEntries().values())
                if (entry.source() == NPShipsCatalogueApi.Source.MANUAL_WORKING)
                    admitted = Math.addExact(admitted, idTextCapacity(entry.id()));
            for (String id : configured)
                admitted =
                        Math.addExact(admitted, Math.multiplyExact(CatalogueResources.text(id), 2));
            for (String id : configuredAddons)
                admitted = Math.addExact(admitted, CatalogueResources.text(id));
            for (String detail : status.diagnostics())
                admitted =
                        Math.addExact(
                                admitted, Math.multiplyExact(CatalogueResources.text(detail), 2));
            if (capacity != null) capacity.reserve(admitted);
            var blocked = new HashSet<>(disabled);
            var blockedAddons = new HashSet<>(addons);
            var all = new ArrayList<Row>(s.automaticEntries().size());
            var good = new ArrayList<Row>(s.automaticEntries().size());
            var other = new ArrayList<Row>(Math.toIntExact(rows));
            for (var entry :
                    s.automaticEntries().values().stream()
                            .sorted(Comparator.comparing(e -> e.id().toString()))
                            .toList()) {
                String id = entry.id().toString();
                boolean available = entry.naturalPlacementAvailable() && entry.rarity().isPresent();
                boolean addon = !blockedAddons.contains(entry.id().getNamespace());
                var row =
                        new Row(
                                all.size(),
                                id,
                                !blocked.contains(id),
                                available && addon,
                                "resource",
                                !available
                                        ? "unavailable"
                                        : !addon ? "addon_disabled" : "structure_hint");
                all.add(row);
                (row.available() ? good : other).add(row);
            }
            var automaticIds = new HashSet<String>();
            for (var row : all) automaticIds.add(row.id());
            s.effectiveEntries().values().stream()
                    .filter(e -> e.source() == NPShipsCatalogueApi.Source.MANUAL_WORKING)
                    .sorted(Comparator.comparing(e -> e.id().toString()))
                    .forEach(
                            e ->
                                    other.add(
                                            new Row(
                                                    -1,
                                                    e.id().toString(),
                                                    false,
                                                    false,
                                                    "manual",
                                                    "manual_hint")));
            // Exact configured order, duplicates and opaque strings remain accessible.
            for (String id : disabled)
                if (!automaticIds.contains(id))
                    other.add(new Row(-1, id, false, false, "configured", "configured_hint"));
            for (String message : status.diagnostics())
                other.add(new Row(-1, message, false, false, "excluded", "excluded_hint"));
            resources = List.copyOf(all);
            structures = List.copyOf(good);
            diagnostics = List.copyOf(other);
        }

        private static long idTextCapacity(net.minecraft.resources.ResourceLocation id) {
            long characters =
                    Math.addExact(
                            Math.addExact((long) id.getNamespace().length(), id.getPath().length()),
                            1);
            return Math.multiplyExact(characters, 2L * Character.BYTES);
        }

        void close() {
            // Drop this owner's graphs before returning their accounted capacity.
            resources = null;
            structures = null;
            diagnostics = null;
            disabled = List.of();
            addons = List.of();
            queries.clear();
            queryCapacity.clear();
            if (capacity != null) capacity.close();
            capacity = null;
        }

        List<Row> rows(NPShipsCatalogueApi.Snapshot s, Filter filter) {
            build(s);
            var cached = queries.get(filter);
            if (cached != null) return cached;
            var source = filter.view().equals("structures") ? structures : diagnostics;
            if (filter.query().isEmpty() && !filter.disabled()) return source;
            int matches = 0;
            for (var row : source)
                if ((!filter.disabled() || !row.enabled()) && row.search().contains(filter.query()))
                    matches++;
            long retained =
                    Math.addExact(
                            CatalogueResources.references(matches),
                            CatalogueResources.text(filter.query()));
            // Pre-sized builder and List.copyOf intermediate reference arrays overlap the result.
            long scratch = CatalogueResources.references(Math.multiplyExact((long) matches, 2));
            if (capacity != null) capacity.reserve(Math.addExact(retained, scratch));
            boolean published = false;
            try {
                var selected = new ArrayList<Row>(matches);
                for (var row : source)
                    if ((!filter.disabled() || !row.enabled())
                            && row.search().contains(filter.query())) selected.add(row);
                var result = List.copyOf(selected);
                queries.put(filter, result);
                queryCapacity.put(filter, retained);
                published = true;
                if (queries.size() > QUERY_CACHE_SIZE) {
                    var evicted = queries.keySet().iterator().next();
                    queries.remove(evicted);
                    long released = queryCapacity.remove(evicted);
                    if (capacity != null) capacity.release(released);
                }
                return result;
            } finally {
                if (capacity != null) capacity.release(published ? scratch : retained + scratch);
            }
        }
    }

    static View view(MinecraftServer server) {
        var snapshot = NPShipsSchematicManager.publicSnapshot(server);
        var status = NPShipsSchematicManager.status(server);
        var current = VIEWS.get(server);
        if (current == null || !current.current(snapshot, status)) {
            VIEWS.remove(server);
            if (current != null) current.close();
            var resources = NPShipsSchematicManager.catalogueResources(server);
            var scope = resources == null ? null : resources.derived();
            try {
                current = new View(snapshot, status, scope);
            } catch (RuntimeException | Error failure) {
                if (scope != null) scope.close();
                throw failure;
            }
            VIEWS.put(server, current);
        }
        return current;
    }

    static void invalidate(MinecraftServer server) {
        var previous = VIEWS.remove(server);
        if (previous != null) previous.close();
    }

    static void clear() {
        for (var view : VIEWS.values()) view.close();
        VIEWS.clear();
    }

    static String token(ServerPlayer player, long revision, int token) {
        var view = view(player.server);
        if (revision != view.revision) throw new IllegalArgumentException("stale");
        view.build(NPShipsSchematicManager.publicSnapshot(player.server));
        if (token < 0 || token >= view.resources.size())
            throw new IllegalArgumentException("token");
        return view.resources.get(token).id();
    }

    static JsonObject page(ServerPlayer player, RuntimeSettingsNetworking.PageRequest request) {
        var result = RuntimeSettingsNetworking.metadata(player, "");
        result.addProperty("kind", "page");
        result.addProperty("request_id", request.id());
        result.addProperty("session", request.session());
        result.addProperty("view", request.view());
        result.addProperty("query", request.query());
        result.addProperty("disabled", request.disabled());
        result.addProperty("offset", request.offset());
        result.addProperty("text_offset", request.textOffset());
        result.addProperty("total", 0);
        result.addProperty("next_offset", 0);
        result.addProperty("next_text_offset", 0);
        result.addProperty("stale", false);
        var parts = new JsonArray();
        result.add("rows", parts);
        if (result.has("resource_limit")) return result;
        if ((!request.view().equals("structures") && !request.view().equals("diagnostics"))
                || request.query().length() > 256
                || request.offset() < 0
                || request.textOffset() < 0
                || request.session() < 0
                || request.id() < 0) {
            result.addProperty("query", "");
            result.addProperty("error", "npships.settings.invalid");
            return result;
        }
        var view = view(player.server);
        if (request.revision() != 0 && request.revision() != view.revision) {
            result.addProperty("stale", true);
            return result;
        }
        if (request.view().equals("diagnostics") && !player.hasPermissions(2)) {
            result.addProperty("error", "npships.settings.read_only");
            return result;
        }
        var filter =
                new Filter(
                        request.view(),
                        request.query().toLowerCase(Locale.ROOT),
                        request.disabled());
        final List<Row> rows;
        try {
            rows = view.rows(NPShipsSchematicManager.publicSnapshot(player.server), filter);
        } catch (ResourceLimitException refused) {
            invalidate(player.server);
            result.addProperty("error", "npships.settings.resource_limit");
            result.addProperty("resource_limit", refused.getMessage());
            return result;
        }
        int total = rows.size(), offset = request.offset(), textOffset = request.textOffset();
        result.addProperty("total", total);
        result.addProperty("next_offset", Math.min(offset, total));
        if (offset >= total) {
            if (textOffset != 0 || offset > total)
                result.addProperty("error", "npships.settings.invalid");
            return result;
        }
        if (textOffset >= rows.get(offset).id().length() && textOffset != 0) {
            result.addProperty("error", "npships.settings.invalid");
            return result;
        }
        int remaining = PAGE_BYTES - RuntimeSettingsNetworking.encodedBytes(result) - 128;
        while (offset < total && parts.size() < PAGE_ROWS) {
            var row = rows.get(offset);
            int end = (int) Math.min((long) row.id().length(), (long) textOffset + FRAGMENT_CHARS);
            JsonObject part;
            int cost;
            do {
                part = fragment(row, textOffset, end);
                cost = RuntimeSettingsNetworking.json(part).length() + 1;
                if (cost <= remaining || end == textOffset) break;
                if (!parts.isEmpty()) break;
                end = textOffset + (end - textOffset) / 2;
            } while (true);
            if (cost > remaining || end == textOffset && end != row.id().length()) break;
            parts.add(part);
            remaining -= cost;
            if (end < row.id().length()) {
                textOffset = end;
                break;
            }
            offset++;
            textOffset = 0;
        }
        result.addProperty("next_offset", offset);
        result.addProperty("next_text_offset", textOffset);
        if (parts.isEmpty() && offset < total)
            result.addProperty("error", "npships.settings.invalid");
        if (RuntimeSettingsNetworking.encodedBytes(result) > PAGE_BYTES)
            throw new IllegalStateException("Settings page exceeded its encoded envelope");
        return result;
    }

    private static JsonObject fragment(Row row, int start, int end) {
        var part = new JsonObject();
        part.addProperty("token", row.token());
        part.addProperty("id", row.id().substring(start, end));
        part.addProperty("start", start);
        part.addProperty("length", row.id().length());
        part.addProperty("enabled", row.enabled());
        part.addProperty("available", row.available());
        part.addProperty("source", row.source());
        part.addProperty("reason", row.reason());
        return part;
    }
}
