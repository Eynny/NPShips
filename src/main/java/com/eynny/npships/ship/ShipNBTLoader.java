package com.eynny.npships.ship;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class ShipNBTLoader {

    private ShipNBTLoader() {}

    public static StructureTemplate loadTemplate(
            final ServerLevel level, final ResourceLocation id) {
        return NPShipsSchematicManager.loadTemplate(level, id);
    }
}
