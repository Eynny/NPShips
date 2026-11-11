package com.eynny.npships.ship;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/** Case-insensitive user names mapped to Minecraft's lowercase resource IDs. */
public final class SchematicNames {
    private SchematicNames() {}

    public static ResourceLocation parse(final String name) {
        final String normalized = name.toLowerCase(Locale.ROOT);
        final ResourceLocation id =
                ResourceLocation.tryParse(
                        normalized.contains(":") ? normalized : "npships:" + normalized);
        if (id == null) {
            throw new IllegalArgumentException(
                    "Invalid schematic name: "
                            + name
                            + ". Use letters A-Z/a-z, digits, _, -, . and /; optional namespace:name.");
        }
        for (final String part : id.getPath().split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException(
                        "Schematic name must not contain empty, '.' or '..' path segments.");
            }
        }
        return id;
    }
}
