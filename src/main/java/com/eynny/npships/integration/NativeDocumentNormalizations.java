package com.eynny.npships.integration;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDocumentNormalization;
import com.eynny.npships.integration.computercraft.ComputerCables;
import com.eynny.npships.integration.radars.RadarMonitors;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/** Stateless code installed by the actual native adapter, not a second integration registry. */
public final class NativeDocumentNormalizations {
    private NativeDocumentNormalizations() {}

    public static <T> T capability(String type, Class<T> contract) {
        if (contract != PatchDocumentNormalization.class) return null;
        boolean radar = type.equals("radars_network");
        if (!radar && !type.equals("computercraft_peripheral")) return null;
        String name = radar ? "create_radar:monitor" : "computercraft:cable";
        return contract.cast(
                new PatchDocumentNormalization() {
                    @Override
                    public boolean applies(SchematicDocument document) {
                        for (var entry : document.paletteCopy())
                            if (((CompoundTag) entry).getString("Name").equals(name)) return true;
                        return false;
                    }

                    @Override
                    public SchematicDocument normalize(SchematicDocument document, Level context) {
                        return radar
                                ? RadarMonitors.normalize(document)
                                : ComputerCables.normalize(document, context);
                    }

                    @Override
                    public int defaultRank() {
                        return radar ? 10 : 20;
                    }
                });
    }
}
