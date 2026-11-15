package com.eynny.npships.config;

import com.eynny.npships.authoring.validation.ValidationProblem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/** Read-only admission for the complete world volume occupied by a structure. */
public record InsideRule(Type type, ResourceLocation fluid, boolean allowEmptySpace) {
    public InsideRule(Type type, ResourceLocation fluid) {
        this(type, fluid, false);
    }

    /** Transient command policy; never written into authored configuration. */
    public static InsideRule emptySpace() {
        return new InsideRule(Type.AIR, null, true);
    }

    public InsideRule {
        if (type == null) {
            throw new IllegalArgumentException("Inside rule type cannot be null.");
        }
        if (type == Type.FLUID) {
            if (fluid == null) {
                throw ValidationProblem.invalid(
                        "inside_fluid_required", "Inside fluid rule requires a source fluid id.");
            }
            var registered = BuiltInRegistries.FLUID.get(fluid);
            if (!BuiltInRegistries.FLUID.containsKey(fluid)
                    || registered == Fluids.EMPTY
                    || !registered.defaultFluidState().isSource()) {
                throw ValidationProblem.invalid(
                        "inside_fluid_invalid",
                        "Inside fluid id must name a registered source fluid: " + fluid,
                        fluid);
            }
        } else if (fluid != null) {
            throw new IllegalArgumentException("Fluid id is only valid for inside type 'fluid'.");
        }
    }

    public static InsideRule air() {
        return new InsideRule(Type.AIR, null);
    }

    /** Does not clear, replace or restore the tested world cell. */
    public boolean matches(BlockState state) {
        if (allowEmptySpace) {
            var actual = state.getFluidState();
            return state.isAir() || !actual.isEmpty() && state == actual.createLegacyBlock();
        }
        if (type == Type.AIR) return state.isAir();
        var actual = state.getFluidState();
        // A waterlogged block or submerged plant is still an occupied world cell.
        return actual.isSource()
                && state == actual.createLegacyBlock()
                && actual.getType().builtInRegistryHolder().key().location().equals(fluid);
    }

    public enum Type {
        AIR,
        FLUID
    }
}
