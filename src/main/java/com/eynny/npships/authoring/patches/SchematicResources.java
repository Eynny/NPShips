package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.SchematicSafety.InspectionScope;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicEdits;
import com.eynny.npships.integration.OptionalCalls;

import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.*;

import java.util.*;

/** Detached inventory adapters. No ticking, live block changes or network callbacks. */
public final class SchematicResources {

    private SchematicResources() {}

    private static final Map<String, Optional<Class<?>>> TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean instance(Object value, String type) {
        return TYPES.computeIfAbsent(
                        type,
                        key -> {
                            try {
                                return Optional.of(Class.forName(key));
                            } catch (ClassNotFoundException e) {
                                return Optional.empty();
                            }
                        })
                .map(c -> c.isInstance(value))
                .orElse(false);
    }

    public static Object invoke(Object value, String method, Object... args) {
        try {
            return OptionalCalls.call(value, method, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Unsupported inventory API: " + method, e);
        }
    }

    public static BlockEntity detached(SchematicDocument doc, BlockPos pos, Level context) {
        return detached(doc, pos, context.registryAccess(), context);
    }

    public static BlockEntity detached(
            SchematicDocument doc, BlockPos pos, HolderLookup.Provider registries) {
        return detached(doc, pos, registries, null);
    }

    private static BlockEntity detached(
            SchematicDocument doc, BlockPos pos, HolderLookup.Provider registries, Level context) {
        var index = doc.blocks().get(pos);
        if (index == null) return null;
        var state =
                NbtUtils.readBlockState(
                        registries.lookupOrThrow(Registries.BLOCK),
                        doc.paletteCopy().getCompound(index));
        if (!(state.getBlock() instanceof EntityBlock block)) return null;
        var be = block.newBlockEntity(pos, state);
        if (be == null) return null;
        if (be instanceof net.createmod.ponder.api.VirtualBlockEntity virtual)
            virtual.markVirtual();
        var data = doc.blockEntityData(pos);
        for (var code :
                PatchCode.applicable(
                        PatchDetachedData.class,
                        provider -> provider.applies(be),
                        com.eynny.npships.api.patches.PatchStage.AUTHOR_PREPARATION))
            if (context == null) code.getValue().prepare(doc, pos, be, data);
            else code.getValue().prepare(doc, pos, be, data, context);
        if (!data.isEmpty()) {
            var complete = be.saveWithoutMetadata(registries);
            complete.merge(data);
            be.loadWithComponents(complete, registries);
        }
        return be;
    }

    /** Detached projection of captured resources; never mutates an accepted document or a world. */
    public static CompoundTag projectedData(SchematicDocument doc, BlockPos pos, Level context) {
        var data = doc.blockEntityData(pos);
        var code =
                PatchCode.applicable(
                        PatchDetachedData.class,
                        c -> c.resourceView(doc, pos),
                        com.eynny.npships.api.patches.PatchStage.AUTHOR_PREPARATION);
        if (code.isEmpty()) return data;
        var state =
                NbtUtils.readBlockState(
                        context.holderLookup(Registries.BLOCK),
                        doc.paletteCopy().getCompound(doc.blocks().get(pos)));
        if (!(state.getBlock() instanceof EntityBlock factory))
            throw new IllegalArgumentException("Captured resources need a block entity");
        var target = factory.newBlockEntity(pos, state);
        if (target == null)
            throw new IllegalArgumentException("Captured resource owner is unavailable");
        for (var entry : code) entry.getValue().prepare(doc, pos, target, data, context);
        return data;
    }

    public static SchematicDocument acceptResourceView(
            SchematicDocument before, SchematicDocument after, BlockPos pos, Level context) {
        for (var code :
                PatchCode.applicable(
                        PatchDetachedData.class,
                        c -> c.resourceView(before, pos),
                        com.eynny.npships.api.patches.PatchStage.AUTHOR_PREPARATION))
            after = code.getValue().acceptResourceView(before, after, pos, context);
        return after;
    }

    /** Do not round-trip native contents through registries that cannot decode their items. */
    public static boolean unavailableItems(Tag data) {
        return unavailableItems(data, 0, false, InspectionScope.NATIVE);
    }

    /** Whole host template only; native records keep their ordinary item semantics. */
    public static boolean unavailableTemplateItems(CompoundTag template) {
        return unavailableItems(
                com.eynny.npships.authoring.AssemblyPoints.validationTemplate(template),
                0,
                false,
                InspectionScope.DOCUMENT);
    }

    private static final class NativeFields {
        static final Set<String> STACK_FIELDS = fields();

        private static Set<String> fields() {
            var result = new java.util.HashSet<>(Set.of("Item", "item"));
            for (var code : PatchCode.sourceFormats(PatchFieldTemplates.class).values())
                result.addAll(code.validationFields());
            return Set.copyOf(result);
        }
    }

    private static final Set<String> STACK_LISTS = Set.of("Items", "HandItems", "ArmorItems");

    private static boolean unavailableItems(
            Tag data, int depth, boolean stackPosition, InspectionScope scope) {
        if (depth > 64) throw new IllegalArgumentException("Resource data is nested too deeply");
        if (data instanceof CompoundTag compound) {
            // A matching pair in unrelated extension NBT is not automatically an ItemStack.
            if (scope != InspectionScope.OPAQUE
                    && (stackPosition || compound.contains("Slot", Tag.TAG_ANY_NUMERIC))
                    && compound.contains("id", Tag.TAG_STRING)
                    && compound.contains("count", Tag.TAG_ANY_NUMERIC)) {
                var id =
                        net.minecraft.resources.ResourceLocation.tryParse(compound.getString("id"));
                if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return true;
                var components = compound.getCompound("components");
                for (String key : components.getAllKeys()) {
                    var component =
                            net.minecraft.resources.ResourceLocation.tryParse(
                                    key.startsWith("!") ? key.substring(1) : key);
                    if (component == null
                            || !BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(component))
                        return true;
                    if (key.equals("minecraft:container")
                            && unavailableItems(
                                    components.get(key), depth + 1, false, InspectionScope.NATIVE))
                        return true;
                    if ((key.equals("minecraft:bundle_contents")
                                    || key.equals("minecraft:charged_projectiles"))
                            && unavailableItems(
                                    components.get(key), depth + 1, true, InspectionScope.NATIVE))
                        return true;
                }
                return false; // Preserve opaque/custom component data; no speculative mod codecs.
            }
            for (String key : compound.getAllKeys()) {
                if (scope != InspectionScope.OPAQUE && key.equals("NeoForgeData")) {
                    // NeoForge's persistent-data map belongs to its writers, not the native
                    // inventory serializer. The host's explicit entity spawn snapshot is the
                    // only native frame within it; its real equipment keeps ordinary admission.
                    var entityId =
                            net.minecraft.resources.ResourceLocation.tryParse(
                                    compound.getString("id"));
                    if (entityId != null
                            && BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)
                            && compound.getCompound(key)
                                    .contains("npships_initial_spawn", Tag.TAG_COMPOUND)
                            && unavailableItems(
                                    compound.getCompound(key).get("npships_initial_spawn"),
                                    depth + 2,
                                    false,
                                    InspectionScope.NATIVE)) return true;
                    if (unavailableItems(
                            compound.get(key), depth + 1, false, InspectionScope.OPAQUE))
                        return true;
                } else if (unavailableItems(
                        compound.get(key),
                        depth + 1,
                        NativeFields.STACK_FIELDS.contains(key) || STACK_LISTS.contains(key),
                        scope.child(compound, key))) return true;
            }
        } else if (data instanceof ListTag list)
            for (Tag value : list)
                if (unavailableItems(value, depth + 1, stackPosition, scope.element())) return true;
        return false;
    }

    public static PatchInventoryEditor.Session open(
            SchematicDocument doc, BlockPos pos, Level context) {
        var data = doc.blockEntityData(pos);
        if (unavailableItems(data))
            return new DetachedInventory(doc, pos, context, null, data) {
                @Override
                public boolean available() {
                    return false;
                }

                @Override
                public String kind() {
                    return "unavailable";
                }
            };
        var entity = detached(doc, pos, context);
        var owners =
                PatchCode.applicable(
                        PatchInventoryEditor.class,
                        code -> code.applies(doc, pos, entity),
                        com.eynny.npships.api.patches.PatchStage.AUTHOR_PREPARATION);
        if (owners.size() > 1)
            throw new IllegalArgumentException("Competing inventory editors at " + pos);
        return owners.isEmpty()
                ? new DetachedInventory(doc, pos, context, entity, data)
                : owners.getFirst().getValue().open(doc, pos, context, entity, data);
    }

    /** Apply native serializer changes, including removed fields, without dropping unknown data. */
    public static void mergeSerialized(CompoundTag stored, CompoundTag before, CompoundTag after) {
        for (String key : before.getAllKeys()) if (!after.contains(key)) stored.remove(key);
        for (String key : after.getAllKeys()) {
            var value = after.get(key);
            if (value instanceof CompoundTag next
                    && before.get(key) instanceof CompoundTag prior
                    && stored.get(key) instanceof CompoundTag retained) {
                mergeSerialized(retained, prior, next);
            } else stored.put(key, value.copy());
        }
    }

    public static SchematicDocument update(SchematicDocument doc, BlockPos pos, CompoundTag data) {
        return update(doc, pos, data, null);
    }

    public static SchematicDocument update(
            SchematicDocument doc, BlockPos pos, CompoundTag data, Level context) {
        var config = LootAssignments.assign(SchematicPatches.authoringConfig(doc), pos, null);
        // Removing inventory items also removes only their now-invalid guided-fuze bindings.
        if (config != null
                && config.has("patches")
                && config.get("patches").isJsonObject()
                && config.getAsJsonObject("patches").has("entries"))
            for (var value : config.getAsJsonObject("patches").getAsJsonArray("entries")) {
                var patch = value.getAsJsonObject();
                if (ExternalPatches.external(patch)
                        || !SchematicPatches.position(patch.getAsJsonArray("pos")).equals(pos))
                    continue;
                for (var code : PatchSourceOperationsDispatch.all())
                    code.inventoryChanged(patch, data);
            }
        var result = SchematicEdits.blockData(doc.withConfig(config), pos, data, context);
        var tag = result.nbtCopy();
        // Registered data-edit projections may have updated this same accepted configuration.
        config = result.configCopy();
        SchematicPatches.embed(tag, config);
        return new SchematicDocument(tag, config);
    }
}
