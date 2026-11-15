package com.eynny.npships.ship;

public enum SchematicSource {

    /**
     * Schematic supplied through Minecraft/NeoForge resources.
     *
     * <p>Example:
     *
     * <p>{@code data/<namespace>/npships/<name>.nbt}
     */
    RESOURCE,

    /**
     * Working external NPShips content. Commands only, never automatic spawning.
     *
     * <p>Example:
     *
     * <p>{@code <game>/npships/schematics/<name>.nbt}
     */
    EXTERNAL
}
