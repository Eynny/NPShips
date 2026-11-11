package com.eynny.npships.authoring.document;

import com.eynny.npships.api.patches.PatchAuthoring.BlockStateClaim;
import com.eynny.npships.api.patches.PatchAuthoring.BlockStateEdit;
import com.eynny.npships.api.patches.PatchAuthoring.Data;
import com.eynny.npships.api.patches.PatchAuthoring.FieldEdit;
import com.eynny.npships.api.patches.PatchAuthoring.Operation;
import com.eynny.npships.api.patches.PatchIdentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One detached author transaction; no registry, native objects or cross-operation cache. */
final class SchematicStateProposals {
    private record ClaimKey(PatchIdentity owner, BlockPos source) {}

    private record FieldKey(BlockPos target, String field) {}

    private record Claimed(BlockStateClaim claim, List<BlockPos> targets) {}

    private record OwnedEdit(ResourceLocation provider, BlockStateEdit edit) {}

    private final SchematicDocument before, candidate;
    private final Operation operation;
    private final Map<ClaimKey, Claimed> claims = new LinkedHashMap<>();
    private final Map<FieldKey, PatchIdentity> fields = new HashMap<>();
    private final Map<BlockPos, Set<String>> fieldsByTarget = new HashMap<>();
    private final Map<FieldKey, ResourceLocation> fieldEdits = new HashMap<>();
    private final List<OwnedEdit> stateEdits = new ArrayList<>();
    private final Set<ResourceLocation> editedProviders = new HashSet<>();
    private Set<PatchIdentity> retained = Set.of();

    SchematicStateProposals(
            SchematicDocument before, SchematicDocument candidate, Operation operation) {
        this.before = before;
        this.candidate = candidate;
        this.operation = operation;
    }

    void claims(ResourceLocation provider, List<Data> owned, List<BlockStateClaim> proposed) {
        Objects.requireNonNull(proposed, "Null BlockState claims");
        Set<PatchIdentity> identities = new HashSet<>();
        for (var data : owned) identities.add(data.identity());
        for (var claim : proposed) {
            if (!provider.equals(claim.owner().type()) || !identities.contains(claim.owner()))
                throw refused(provider, "BlockState claim has no owned source instance");
            if (!before.blocks().containsKey(claim.source())
                    || !before.extensionBlockState(claim.source()).equals(claim.expected()))
                throw refused(
                        provider, "Stale/missing source BlockState claim at " + claim.source());
            var sourceState = state(claim.expected(), false);
            var targets = List.copyOf(operation.mapping().cells(claim.source()));
            if (new HashSet<>(targets).size() != targets.size())
                throw refused(provider, "Ambiguous duplicate BlockState target mapping");
            for (var target : targets) {
                if (!candidate.blocks().containsKey(target))
                    throw refused(provider, "Missing mapped BlockState target " + target);
                var targetState = state(candidate.extensionBlockState(target), false);
                if (sourceState.getBlock() != targetState.getBlock())
                    throw refused(provider, "Host operation replaced the claimed block type");
            }
            var key = new ClaimKey(claim.owner(), claim.source());
            if (claims.putIfAbsent(key, new Claimed(claim, targets)) != null)
                throw refused(provider, "Duplicate BlockState claim " + claim.source());
            for (String field : claim.blockEntityFields()) {
                requireField(field);
                if (!before.extensionBlockEntityReadOnly(claim.source()).contains(field))
                    throw refused(
                            provider,
                            "Claimed BlockEntity field is absent at source "
                                    + claim.source()
                                    + "/"
                                    + field);
                for (var target : targets) {
                    var keyField = new FieldKey(target, field);
                    if (fields.putIfAbsent(keyField, claim.owner()) != null)
                        throw refused(
                                provider,
                                "Conflicting BlockEntity field ownership at "
                                        + target
                                        + "/"
                                        + field);
                    fieldsByTarget.computeIfAbsent(target, ignored -> new HashSet<>()).add(field);
                }
            }
        }
    }

    void fields(ResourceLocation provider, List<FieldEdit> edits) {
        for (var edit : edits)
            if (fieldEdits.putIfAbsent(new FieldKey(edit.position(), edit.key()), provider) != null)
                throw refused(
                        provider,
                        "Conflicting provider BlockEntity field proposals at "
                                + edit.position()
                                + "/"
                                + edit.key());
    }

    void states(ResourceLocation provider, List<BlockStateEdit> edits) {
        if (!edits.isEmpty()) editedProviders.add(provider);
        for (var edit : edits) stateEdits.add(new OwnedEdit(provider, edit));
    }

    boolean edited(ResourceLocation provider) {
        return editedProviders.contains(provider);
    }

    void validateFields(List<Data> nextData) {
        if (!claims.isEmpty()) {
            retained = new HashSet<>();
            for (var data : nextData) retained.add(data.identity());
            for (var declared : claims.values())
                if (!declared.targets().isEmpty()
                        && !retained.contains(
                                operation.mapping().identity(declared.claim().owner())))
                    throw refused(
                            declared.claim().owner().type(),
                            "BlockState/field claim removed its mapped author instance");
        }
        for (var entry : fieldEdits.entrySet()) {
            var owner = fields.get(entry.getKey());
            if (owner != null && !owner.type().equals(entry.getValue()))
                throw refused(
                        entry.getValue(),
                        "BlockEntity field belongs to another provider at "
                                + entry.getKey().target());
        }
    }

    /** Inspect the immutable candidate, not data another proposal may have removed. */
    void requireMirrorFields(Set<BlockPos> deferred, int axes) {
        for (var target : deferred) {
            var residual = candidate.blockEntityData(target);
            for (String key : fieldsByTarget.getOrDefault(target, Set.of())) residual.remove(key);
            var expected = state(candidate.extensionBlockState(target), false);
            SchematicMirrorData.blockEntity(expected, residual, axes);
            var ownedField = fieldsByTarget.get(target).iterator().next();
            var owner = fields.get(new FieldKey(target, ownedField));
            compatible(owner.type(), expected, candidate.blockEntryCopy(target), target);
        }
    }

    SchematicDocument apply(SchematicDocument result, Map<BlockPos, CompoundTag> fieldChanges) {
        if (stateEdits.isEmpty())
            return fieldChanges.isEmpty()
                    ? result
                    : result.withBlocks(fieldChanges, result.paletteCopy(), result.configCopy());
        var destinations = new HashSet<BlockPos>();
        var changes = new HashMap<BlockPos, CompoundTag>(fieldChanges);
        var palette = result.paletteCopy();
        var indices = new HashMap<CompoundTag, Integer>();
        for (int i = 0; i < palette.size(); i++) indices.putIfAbsent(palette.getCompound(i), i);
        for (var owned : stateEdits) {
            var provider = owned.provider();
            var edit = owned.edit();
            var declared = claims.get(new ClaimKey(edit.owner(), edit.source()));
            if (!provider.equals(edit.owner().type()) || declared == null)
                throw refused(
                        provider, "Undeclared BlockState target/instance at " + edit.source());
            if (!declared.targets().contains(edit.target()))
                throw refused(provider, "BlockState target is outside the operation mapping");
            if (!retained.contains(operation.mapping().identity(edit.owner())))
                throw refused(provider, "BlockState proposal removed its mapped author instance");
            if (!destinations.add(edit.target()))
                throw refused(provider, "Conflicting BlockState proposals at " + edit.target());
            var expected = candidate.extensionBlockState(edit.target());
            if (!expected.equals(edit.expected()))
                throw refused(provider, "Stale candidate BlockState at " + edit.target());
            var previous = state(expected, false);
            var replacement = state(edit.replacement(), true);
            if (previous.getBlock() != replacement.getBlock())
                throw refused(provider, "BlockState proposal cannot replace the block type");
            var entry = changes.computeIfAbsent(edit.target(), result::blockEntryCopy);
            if (entry == null)
                throw refused(provider, "Missing BlockState target " + edit.target());
            compatible(provider, replacement, entry, edit.target());
            var encoded = edit.replacement();
            Integer index = indices.get(encoded);
            if (index == null) {
                index = palette.size();
                palette.add(encoded);
                indices.put(encoded, index);
            }
            entry.putInt("state", index);
            changes.put(edit.target(), entry);
        }
        return result.withBlocks(changes, palette, result.configCopy());
    }

    static void compatible(
            ResourceLocation provider, BlockState state, CompoundTag entry, BlockPos target) {
        if (!entry.contains("nbt", Tag.TAG_COMPOUND)) return;
        var data = entry.getCompound("nbt");
        if (!state.hasBlockEntity())
            throw refused(provider, "BlockState cannot retain BlockEntity data at " + target);
        if (data.isEmpty()) return;
        net.minecraft.world.level.block.entity.BlockEntityType<?> type;
        if (!data.contains("id")
                && state.getBlock()
                        instanceof net.minecraft.world.level.block.EntityBlock factory) {
            // Native text editors omit identity fields; check the registered factory without
            // changing the accepted payload or creating a live world object.
            var entity = factory.newBlockEntity(target, state);
            type = entity == null ? null : entity.getType();
            if (type != null
                    && !BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(
                            BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type))) type = null;
        } else {
            var id =
                    data.contains("id", Tag.TAG_STRING)
                            ? ResourceLocation.tryParse(data.getString("id"))
                            : null;
            type =
                    id == null
                            ? null
                            : BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).orElse(null);
        }
        if (type == null || !type.isValid(state))
            throw refused(
                    provider, "BlockState is incompatible with retained BlockEntity at " + target);
    }

    /** Native decoding may silently substitute defaults; proposals must reject unknown values. */
    static BlockState state(CompoundTag encoded, boolean complete) {
        if (!encoded.contains("Name", Tag.TAG_STRING)
                || encoded.getAllKeys().stream()
                        .anyMatch(k -> !k.equals("Name") && !k.equals("Properties"))
                || encoded.contains("Properties")
                        && !encoded.contains("Properties", Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Malformed provider BlockState encoding");
        var id = ResourceLocation.tryParse(encoded.getString("Name"));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
            throw new IllegalArgumentException(
                    "Unregistered provider BlockState " + encoded.getString("Name"));
        var block = BuiltInRegistries.BLOCK.get(id);
        var result = block.defaultBlockState();
        var properties = encoded.getCompound("Properties");
        var declared = new HashSet<String>();
        for (var property : block.getStateDefinition().getProperties())
            declared.add(property.getName());
        if (complete && !declared.equals(properties.getAllKeys()))
            throw new IllegalArgumentException(
                    "Replacement BlockState must declare every native property");
        for (String key : properties.getAllKeys()) {
            var property = block.getStateDefinition().getProperty(key);
            if (property == null || !properties.contains(key, Tag.TAG_STRING))
                throw new IllegalArgumentException("Unknown provider BlockState property " + key);
            result = property(result, property, properties.getString(key));
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState property(
            BlockState state, Property<T> property, String value) {
        var parsed = property.getValue(value);
        if (parsed.isEmpty())
            throw new IllegalArgumentException(
                    "Invalid provider BlockState value " + property.getName() + "=" + value);
        return state.setValue(property, parsed.get());
    }

    private static void requireField(String key) {
        if (key.isEmpty()
                || Set.of(
                                "id",
                                "x",
                                "y",
                                "z",
                                "components",
                                "NeoForgeData",
                                "ForgeData",
                                "ForgeCaps")
                        .contains(key)
                || key.startsWith("npships")
                || key.startsWith("NPShips"))
            throw new IllegalArgumentException(
                    "BlockState claim cannot own native/host field " + key);
    }

    private static IllegalArgumentException refused(ResourceLocation provider, String detail) {
        return new IllegalArgumentException(
                "Author data provider "
                        + provider
                        + ": "
                        + detail
                        + "; original document retained");
    }
}
