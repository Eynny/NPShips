package com.eynny.npships.integration.computercraft;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.integration.OptionalCalls;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import java.util.*;

/** Repair old empty cable entries and derive static connections using CC's own rules. */
public final class ComputerCables {
    private ComputerCables() {}

    private static final Set<SchematicDocument> NORMALIZED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Combining a cable with an existing mounted modem must retain its data and links. */
    public static BlockState addCable(BlockState existing, BlockState brush) {
        if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(existing.getBlock())
                        .toString()
                        .equals("computercraft:cable")
                || brush.getBlock() != existing.getBlock()) return null;
        var cable = existing.getBlock().getStateDefinition().getProperty("cable");
        var modem = existing.getBlock().getStateDefinition().getProperty("modem");
        if (!(cable instanceof BooleanProperty flag)
                || modem == null
                || existing.getValue(flag)
                || !brush.getValue(flag)
                || existing.getValue(modem).toString().equalsIgnoreCase("none")
                || !brush.getValue(modem).toString().equalsIgnoreCase("none")) return null;
        return existing.setValue(flag, true);
    }

    public static boolean modem(SchematicDocument doc, BlockPos pos) {
        if (!doc.blockName(pos).equals("computercraft:cable")) return false;
        var index = doc.blocks().get(pos);
        if (index == null) return false;
        String value =
                doc.paletteCopy().getCompound(index).getCompound("Properties").getString("modem");
        return !value.isEmpty() && !value.equals("none");
    }

    public static SchematicDocument normalize(SchematicDocument doc, Level level) {
        // Foreign cable NBT stays editable without CC; decoding it as AIR cannot rebuild links.
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) return doc;
        if (NORMALIZED.contains(doc)) return doc;
        var palette = doc.paletteCopy();
        boolean present = false;
        for (var entry : palette)
            if (((CompoundTag) entry).getString("Name").equals("computercraft:cable"))
                present = true;
        if (!present || doc.hasMultiplePalettes()) {
            NORMALIZED.add(doc);
            return doc;
        }
        var decoded = new ArrayList<BlockState>();
        for (var entry : palette) {
            var state =
                    NbtUtils.readBlockState(
                            level.holderLookup(Registries.BLOCK), (CompoundTag) entry);
            if (((CompoundTag) entry).getString("Name").equals("computercraft:cable")) {
                var cable = state.getBlock().getStateDefinition().getProperty("cable");
                var modem = state.getBlock().getStateDefinition().getProperty("modem");
                if (cable instanceof BooleanProperty flag
                        && modem != null
                        && !state.getValue(flag)
                        && state.getValue(modem).toString().equalsIgnoreCase("none"))
                    state = state.setValue(flag, true);
            }
            decoded.add(state);
        }
        var candidates = new HashSet<BlockPos>();
        var cursor = doc;
        boolean incremental = false;
        for (int step = 0; step < 64 && cursor != null; step++, cursor = cursor.localParent()) {
            if (NORMALIZED.contains(cursor)) {
                incremental = true;
                break;
            }
            for (var p : cursor.localChanges()) {
                candidates.add(p);
                for (var direction : Direction.values()) candidates.add(p.relative(direction));
            }
        }
        if (!incremental) {
            candidates.clear();
            candidates.addAll(doc.blocks().keySet());
        }
        candidates.removeIf(p -> !doc.blockName(p).equals("computercraft:cable"));
        if (candidates.isEmpty()) {
            NORMALIZED.add(doc);
            return doc;
        }
        var view =
                new net.createmod.catnip.levelWrappers.SchematicLevel(BlockPos.ZERO, level) {
                    private final Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity>
                            entities = new HashMap<>();

                    @Override
                    public BlockState getBlockState(BlockPos p) {
                        var index = doc.blocks().get(p);
                        return index == null ? Blocks.AIR.defaultBlockState() : decoded.get(index);
                    }

                    @Override
                    public boolean isLoaded(BlockPos p) {
                        return true;
                    }

                    @Override
                    public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(
                            BlockPos p) {
                        return entities.computeIfAbsent(
                                p.immutable(),
                                key -> {
                                    var state = getBlockState(key);
                                    if (!(state.getBlock()
                                            instanceof
                                            net.minecraft.world.level.block.EntityBlock block))
                                        return null;
                                    var entity = block.newBlockEntity(key, state);
                                    if (entity != null) entity.setLevel(this);
                                    return entity;
                                });
                    }

                    @Override
                    public <T, C> T getCapability(
                            net.neoforged.neoforge.capabilities.BlockCapability<T, C> capability,
                            BlockPos p,
                            C context) {
                        return capability.getCapability(
                                this, p, getBlockState(p), getBlockEntity(p), context);
                    }
                };
        var changes = new HashMap<BlockPos, CompoundTag>();
        for (var p : candidates) {
            var entry = doc.blockEntryCopy(p);
            var state = decoded.get(entry.getInt("state"));
            try {
                state =
                        (BlockState)
                                OptionalCalls.call(
                                        state.getBlock().getClass(),
                                        "correctConnections",
                                        view,
                                        p,
                                        state);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(
                        "Cannot rebuild ComputerCraft cable connections", e);
            }
            var encoded = NbtUtils.writeBlockState(state);
            int index = palette.indexOf(encoded);
            if (index < 0) {
                index = palette.size();
                palette.add(encoded);
            }
            if (index != entry.getInt("state")) {
                entry.putInt("state", index);
                changes.put(p, entry);
            }
        }
        var result = changes.isEmpty() ? doc : doc.withBlocks(changes, palette, doc.configCopy());
        NORMALIZED.add(result);
        return result;
    }
}
