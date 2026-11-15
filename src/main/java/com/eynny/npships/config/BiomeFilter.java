package com.eynny.npships.config;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record BiomeFilter(
        List<ResourceLocation> allow,
        List<ResourceLocation> allowTags,
        List<ResourceLocation> forbid,
        List<ResourceLocation> forbidTags) {

    public BiomeFilter {
        allow = List.copyOf(allow == null ? List.of() : allow);

        allowTags = List.copyOf(allowTags == null ? List.of() : allowTags);

        forbid = List.copyOf(forbid == null ? List.of() : forbid);

        forbidTags = List.copyOf(forbidTags == null ? List.of() : forbidTags);
    }

    public static BiomeFilter allowAll() {
        return new BiomeFilter(List.of(), List.of(), List.of(), List.of());
    }
}
