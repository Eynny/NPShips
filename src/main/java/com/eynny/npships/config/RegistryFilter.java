package com.eynny.npships.config;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record RegistryFilter(List<ResourceLocation> allow, List<ResourceLocation> forbid) {

    public RegistryFilter {
        allow = List.copyOf(allow == null ? List.of() : allow);

        forbid = List.copyOf(forbid == null ? List.of() : forbid);
    }

    public static RegistryFilter allowAll() {
        return new RegistryFilter(List.of(), List.of());
    }

    public boolean isAllowAll() {
        return allow.isEmpty() && forbid.isEmpty();
    }
}
