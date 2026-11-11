package com.eynny.npships.integration.cannons;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.integration.OptionalCalls;

import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.util.*;

/**
 * CBC5.11.7 native predicates/operation, linked only after explicit optional availability checks.
 */
public final class CannonWelding {
    private static final String PREFIX = "rbasamoyai.createbigcannons.";

    private CannonWelding() {}

    public static boolean available() {
        return ModList.get().isLoaded("createbigcannons");
    }

    public static void validate(SchematicDocument doc, Level parent, boolean required) {
        var pairs = CannonWelds.pairs(doc);
        if (pairs.isEmpty()) return;
        if (!available()) {
            if (required)
                throw new IllegalArgumentException(
                        "Required CBC welding provider createbigcannons is unavailable");
            return;
        }
        var palette = doc.paletteCopy();
        for (var pair : pairs) validatePair(doc, palette, pair.from(), pair.to(), parent);
    }

    public static void validatePair(SchematicDocument doc, BlockPos a, BlockPos b, Level parent) {
        validatePair(doc, doc.paletteCopy(), a, b, parent);
    }

    /** Native families only; a CBC namespace alone is not a welding capability. */
    public static boolean supports(BlockState state) {
        if (!available()) return false;
        try {
            var block = state.getBlock();
            return Class.forName(PREFIX + "cannons.big_cannons.BigCannonBlock").isInstance(block)
                    || Class.forName(PREFIX + "cannons.autocannon.AutocannonBlock")
                            .isInstance(block);
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return false;
        }
    }

    public static void validatePair(
            SchematicDocument doc,
            net.minecraft.nbt.ListTag palette,
            BlockPos a,
            BlockPos b,
            Level parent) {
        if (!available()) throw CannonWelds.invalid(a, b, "CBC welding provider is unavailable");
        if (doc.hasMultiplePalettes())
            throw CannonWelds.invalid(
                    a,
                    b,
                    "Choose a native palette before authoring; runtime validates every palette");
        var states = new HashMap<BlockPos, BlockState>();
        var nativeEntities = new HashMap<BlockPos, BlockEntity>();
        var view =
                new SchematicLevel(BlockPos.ZERO, parent) {
                    @Override
                    public BlockState getBlockState(BlockPos p) {
                        return states.getOrDefault(
                                p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    }

                    @Override
                    public BlockEntity getBlockEntity(BlockPos p) {
                        return nativeEntities.get(p);
                    }
                };
        for (var p : List.of(a, b)) {
            var index = doc.blocks().get(p);
            if (index == null)
                throw CannonWelds.invalid(a, b, "Missing authored endpoint " + p.toShortString());
            var state =
                    NbtUtils.readBlockState(
                            parent.holderLookup(Registries.BLOCK), palette.getCompound(index));
            states.put(p, state);
        }
        // Refuse unrelated machinery before calling any foreign BlockEntity constructor/reader.
        for (var state : states.values()) {
            try {
                var big = Class.forName(PREFIX + "cannons.big_cannons.BigCannonBlock");
                var auto = Class.forName(PREFIX + "cannons.autocannon.AutocannonBlock");
                if (!big.isInstance(state.getBlock()) && !auto.isInstance(state.getBlock()))
                    throw CannonWelds.invalid(a, b, "Unsupported native cannon target");
            } catch (ClassNotFoundException | LinkageError error) {
                throw CannonWelds.invalid(a, b, "CBC welding provider unavailable: " + error);
            }
        }
        for (var p : List.of(a, b)) {
            var state = states.get(p);
            if (!(state.getBlock() instanceof EntityBlock factory)) continue;
            var be = factory.newBlockEntity(p, state);
            if (be == null) continue;
            CannonWelds.validateNativeFaces(doc.blockEntityData(p));
            be.setLevel(view);
            var data =
                    be.saveWithoutMetadata(parent.registryAccess()).merge(doc.blockEntityData(p));
            be.loadWithComponents(data, parent.registryAccess());
            nativeEntities.put(p, be);
        }
        requireNative(view, a, b);
    }

    /** No mutation, including no temporary disconnect to coerce the native fresh-weld predicate. */
    public static void requireNative(Level level, BlockPos a, BlockPos b) {
        try {
            var welder = Class.forName(PREFIX + "crafting.welding.CannonWelderItem");
            if (!(boolean) OptionalCalls.call(welder, "validWeldPositions", a, b))
                throw CannonWelds.invalid(a, b, "Endpoints must be face-adjacent");
            var sa = level.getBlockState(a);
            var sb = level.getBlockState(b);
            var weldable = Class.forName(PREFIX + "crafting.welding.WeldableBlock");
            if (!weldable.isInstance(sa.getBlock()) || !weldable.isInstance(sb.getBlock()))
                throw CannonWelds.invalid(a, b, "Both blocks must implement native WeldableBlock");
            if (!(boolean) OptionalCalls.call(sa.getBlock(), "isWeldable", sa)
                    || !(boolean) OptionalCalls.call(sb.getBlock(), "isWeldable", sb))
                throw CannonWelds.invalid(a, b, "Native cannon material is not weldable");
            var dir =
                    Direction.fromDelta(
                            b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ());
            var big = Class.forName(PREFIX + "cannons.big_cannons.BigCannonBlock");
            var auto = Class.forName(PREFIX + "cannons.autocannon.AutocannonBlock");
            var family = big.isInstance(sa.getBlock()) ? big : auto;
            if (!family.isInstance(sa.getBlock()) || !family.isInstance(sb.getBlock()))
                throw CannonWelds.invalid(a, b, "Different or unsupported native cannon families");
            String material = family == big ? "getCannonMaterial" : "getAutocannonMaterial";
            if (OptionalCalls.call(sa.getBlock(), material)
                    != OptionalCalls.call(sb.getBlock(), material))
                throw CannonWelds.invalid(a, b, "Native cannon materials differ");
            if (!(boolean) OptionalCalls.call(sa.getBlock(), "canConnectToSide", sa, dir)
                    || !(boolean)
                            OptionalCalls.call(
                                    sb.getBlock(), "canConnectToSide", sb, dir.getOpposite()))
                throw CannonWelds.invalid(
                        a, b, "Native cannon facing does not permit this connection");
            var ea = level.getBlockEntity(a);
            var eb = level.getBlockEntity(b);
            if (ea == null || eb == null)
                throw CannonWelds.invalid(a, b, "Missing native cannon BlockEntity");
            var ba = OptionalCalls.call(ea, "cannonBehavior");
            var bb = OptionalCalls.call(eb, "cannonBehavior");
            if ((boolean) OptionalCalls.call(ba, "isConnectedTo", dir)
                    && (boolean) OptionalCalls.call(bb, "isConnectedTo", dir.getOpposite()))
                throw CannonWelds.invalid(
                        a,
                        b,
                        (boolean) OptionalCalls.call(ba, "isWeldedOn", dir)
                                        && (boolean)
                                                OptionalCalls.call(
                                                        bb, "isWeldedOn", dir.getOpposite())
                                ? "Already natively welded; no new instruction is needed"
                                : "Already mutually connected; native CBC cannot add a weld to this pair");
            if (!(boolean) OptionalCalls.call(welder, "weldBlocks", level, a, b, true))
                throw CannonWelds.invalid(
                        a, b, "Native CBC fresh-weld simulation refused the pair");
        } catch (ReflectiveOperationException | LinkageError e) {
            throw CannonWelds.invalid(a, b, "CBC native welding unavailable or incompatible: " + e);
        }
    }

    public static void restore(ServerLevel level, BlockPos origin, SchematicDocument doc)
            throws Exception {
        var pairs = CannonWelds.pairs(doc);
        if (pairs.isEmpty()) return;
        if (!available())
            throw new IllegalArgumentException("Required CBC welding provider unavailable");
        var welder = Class.forName(PREFIX + "crafting.welding.CannonWelderItem");
        // Preflight all live pairs before the first weld; this is not a rollback/transaction.
        for (var p : pairs) {
            for (var endpoint : List.of(p.from(), p.to())) {
                var actual = level.getBlockState(origin.offset(endpoint));
                if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(actual.getBlock())
                        .toString()
                        .equals(doc.blockName(endpoint)))
                    throw CannonWelds.invalid(
                            p.from(),
                            p.to(),
                            "Live endpoint differs from pinned authored block; terminal failure");
            }
            requireNative(level, origin.offset(p.from()), origin.offset(p.to()));
        }
        for (var p : pairs) {
            var a = origin.offset(p.from());
            var b = origin.offset(p.to());
            if (!(boolean) OptionalCalls.call(welder, "weldBlocks", level, a, b, false))
                throw CannonWelds.invalid(
                        p.from(), p.to(), "Native weld failed after placement; terminal failure");
            var d =
                    Direction.fromDelta(
                            b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ());
            for (var end : List.of(a, b)) {
                var side = end.equals(a) ? d : d.getOpposite();
                var behavior = OptionalCalls.call(level.getBlockEntity(end), "cannonBehavior");
                if (!(boolean) OptionalCalls.call(behavior, "isConnectedTo", side)
                        || !(boolean) OptionalCalls.call(behavior, "isWeldedOn", side))
                    throw CannonWelds.invalid(
                            p.from(), p.to(), "Native reciprocal weld missing; terminal failure");
            }
        }
    }
}
