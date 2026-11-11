package com.eynny.npships.config;

import net.minecraft.resources.ResourceLocation;

public record InsideRule(Type type, ResourceLocation fluid) {

    public InsideRule {
        if (type == null) {
            throw new IllegalArgumentException("Inside rule type cannot be null.");
        }

        if (type == Type.FLUID && fluid == null) {
            throw new IllegalArgumentException("Inside fluid rule requires a fluid id.");
        }

        if (type != Type.FLUID && fluid != null) {
            throw new IllegalArgumentException("Fluid id is only valid for inside type 'fluid'.");
        }
    }

    public static InsideRule clear() {
        return new InsideRule(Type.CLEAR, null);
    }

    public enum Type {
        CLEAR,
        IGNORE,
        FLUID
    }
}
