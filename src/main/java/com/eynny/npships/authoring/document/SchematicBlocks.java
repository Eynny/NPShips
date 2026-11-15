package com.eynny.npships.authoring.document;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;

/** Edits a detached document, retaining unrelated template and block-entity data. */
public final class SchematicBlocks {
    private SchematicBlocks() {}

    /** State-only placement proposals retain native/opaque NBT and use the existing validator. */
    public static void validateCombination(
            net.minecraft.resources.ResourceLocation provider,
            SchematicDocument source,
            BlockPos at,
            BlockState expected,
            BlockState replacement) {
        validateCombination(
                provider, source, at, expected, replacement, source.blockEntityData(at));
    }

    public static void validateCombination(
            net.minecraft.resources.ResourceLocation provider,
            SchematicDocument source,
            BlockPos at,
            BlockState expected,
            BlockState replacement,
            CompoundTag proposedData) {
        if (!source.blocks().containsKey(at)
                || SchematicStateProposals.state(source.extensionBlockState(at), false) != expected)
            throw invalid("block_position", "Placement source changed.", at);
        if (expected.getBlock() != replacement.getBlock()
                || expected.hasBlockEntity() != replacement.hasBlockEntity())
            throw invalid(
                    "block_state",
                    "Placement cannot replace the native block or BlockEntity contract.",
                    at);
        var entry = source.blockEntryCopy(at);
        entry.put("nbt", proposedData.copy());
        var data = entry.getCompound("nbt");
        if (data.isEmpty()) return;
        SchematicStateProposals.compatible(provider, replacement, entry, at);
    }

    /**
     * One accepted author operation; child state/data edits never invoke provider transforms twice.
     */
    public static SchematicDocument combine(
            net.minecraft.resources.ResourceLocation provider,
            SchematicDocument source,
            BlockPos at,
            BlockState expected,
            BlockState replacement,
            CompoundTag data) {
        validateCombination(provider, source, at, expected, replacement, data);
        var result =
                SchematicExtensions.intermediate(
                        () -> {
                            var candidate = put(source, at, at, replacement, null);
                            return data.equals(source.blockEntityData(at))
                                    ? candidate
                                    : com.eynny.npships.authoring.patches.SchematicResources.update(
                                            candidate, at, data);
                        });
        return SchematicExtensions.apply(
                source, result, SchematicExtensions.identity(PatchAuthoring.Kind.EDIT));
    }

    public static SchematicDocument put(
            SchematicDocument source,
            BlockPos from,
            BlockPos to,
            BlockState state,
            CompoundTag material) {
        if (source.hasMultiplePalettes())
            throw invalid("block_palette", "Block editing requires a single palette.");
        if (!source.contains(to))
            throw invalid(
                    "block_position",
                    "Position is outside the schematic.",
                    to.toShortString(),
                    source.size().toShortString());
        var palette = source.paletteCopy();
        var encoded = NbtUtils.writeBlockState(state);
        int index = palette.indexOf(encoded);
        if (index < 0) {
            index = palette.size();
            palette.add(encoded);
        }
        CompoundTag previous = source.blockEntryCopy(from);
        if (!to.equals(from)
                && source.blocks().containsKey(to)
                && !source.blockName(to).equals("minecraft:air"))
            throw invalid(
                    "block_occupied",
                    "Destination is occupied; select it to replace the block explicitly.",
                    to.toShortString());
        boolean keepLoot =
                previous != null
                        && palette.getCompound(previous.getInt("state"))
                                .getString("Name")
                                .equals(encoded.getString("Name"));
        var next = new java.util.HashMap<BlockPos, CompoundTag>();
        if (from != null) next.put(from, null);
        next.put(to, null);
        if (!state.isAir()) {
            var block = previous == null ? new CompoundTag() : previous;
            boolean sameBlock =
                    previous != null
                            && palette.getCompound(previous.getInt("state"))
                                    .getString("Name")
                                    .equals(encoded.getString("Name"));
            if (!sameBlock || !state.hasBlockEntity()) block.remove("nbt");
            if (!sameBlock) {
                block.remove(com.eynny.npships.authoring.patches.SchematicPatches.EMBEDDED);
                block.remove(com.eynny.npships.authoring.patches.SchematicPatches.UNPATCHED);
            }
            block.put("pos", StructureSegments.ints(to));
            block.putInt("state", index);
            var be = block.getCompound("nbt");
            for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                code.authoredBlock(encoded.getString("Name"), be, material);
            if (!be.isEmpty()) {
                be.putInt("x", to.getX());
                be.putInt("y", to.getY());
                be.putInt("z", to.getZ());
                block.put("nbt", be);
            }
            next.put(to, block);
        }
        if (from != null && from.equals(to) && (state.isAir() || !keepLoot))
            for (var code : PatchSourceOperationsDispatch.all())
                code.blockReplaced(source, from, next);
        var config = source.configCopy();
        if (!com.eynny.npships.authoring.patches.ExternalPatches.authoringEntries(source).isEmpty())
            config = com.eynny.npships.authoring.patches.SchematicPatches.authoringConfig(source);
        if (config != null && from != null) {
            var oldLoot =
                    LootAssignments.read(config).stream()
                            .filter(e -> e.pos().equals(from))
                            .findFirst();
            config = LootAssignments.assign(config, from, null);
            if (!state.isAir() && previous != null && oldLoot.isPresent() && keepLoot)
                config =
                        LootAssignments.assign(
                                config, to, oldLoot.get().table(), oldLoot.get().rolls());
        }
        config =
                com.eynny.npships.authoring.patches.SchematicPatches.move(
                        config, from, to, keepLoot && !state.isAir());
        embedChanges(source, next, config);
        var result = source.withBlocks(next, palette, config);
        if (!SchematicExtensions.present(source)) return result;
        return SchematicExtensions.apply(
                source,
                result,
                SchematicExtensions.operation(
                        from == null || to.equals(from)
                                ? PatchAuthoring.Kind.EDIT
                                : PatchAuthoring.Kind.MOVE,
                        p -> {
                            if (p.equals(from))
                                return keepLoot && !state.isAir()
                                        ? java.util.List.of(to)
                                        : java.util.List.of();
                            return java.util.List.of(p);
                        },
                        p -> java.util.List.of(p),
                        java.util.function.UnaryOperator.identity(),
                        java.util.Optional::of));
    }

    /** Synchronize only indexed patch owners; unrelated geometry never needs copying. */
    public static void embedChanges(
            SchematicDocument source,
            java.util.Map<BlockPos, CompoundTag> changes,
            com.google.gson.JsonObject config) {
        var patches = com.eynny.npships.authoring.patches.SchematicPatches.read(config);
        var owners = new java.util.HashSet<BlockPos>();
        for (var p : com.eynny.npships.authoring.patches.SchematicPatches.read(source.configCopy()))
            owners.add(
                    com.eynny.npships.authoring.patches.SchematicPatches.position(
                            p.getAsJsonArray("pos")));
        for (var p : patches)
            owners.add(
                    com.eynny.npships.authoring.patches.SchematicPatches.position(
                            p.getAsJsonArray("pos")));
        var grouped =
                new java.util.LinkedHashMap<BlockPos, java.util.List<com.google.gson.JsonObject>>();
        for (var patch : patches)
            grouped.computeIfAbsent(
                            com.eynny.npships.authoring.patches.SchematicPatches.position(
                                    patch.getAsJsonArray("pos")),
                            ignored -> new java.util.ArrayList<>())
                    .add(patch);
        for (var pos : owners) {
            var block = changes.containsKey(pos) ? changes.get(pos) : source.blockEntryCopy(pos);
            if (block != null) {
                if (!grouped.containsKey(pos))
                    block.remove(com.eynny.npships.authoring.patches.SchematicPatches.EMBEDDED);
                changes.put(pos, block);
            }
        }
        for (var entry : grouped.entrySet()) {
            var block = changes.get(entry.getKey());
            if (block == null)
                throw new IllegalArgumentException("Patch target is absent: " + entry.getKey());
            if (entry.getValue().stream()
                    .anyMatch(
                            p -> !com.eynny.npships.authoring.patches.ExternalPatches.external(p)))
                block.remove(com.eynny.npships.authoring.patches.SchematicPatches.UNPATCHED);
            com.eynny.npships.authoring.patches.SchematicPatches.writeEmbedded(
                    block,
                    entry.getValue(),
                    config.getAsJsonObject("patches").get("version").getAsInt());
            for (var patch : entry.getValue())
                for (var code :
                        com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                    code.embed(patch, block);
        }
    }

    private static BlockPos position(CompoundTag entry) {
        var p = entry.getList("pos", Tag.TAG_INT);
        return new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
    }
}
