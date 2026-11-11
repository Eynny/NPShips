package com.eynny.npships.client.editor;

import com.eynny.npships.integration.cannons.CannonWelding;

/** Actual native spatial tool factories under their existing common provider identity. */
public final class BuiltinSpatialTools {
    private BuiltinSpatialTools() {}

    public static <T> T capability(String provider, Class<T> contract) {
        if (contract != PatchSpatialTool.Factory.class || !provider.equals("cbc_welds"))
            return null;
        return contract.cast(
                new PatchSpatialTool.Factory() {
                    public boolean applies(EditorToolsPanel panel) {
                        return panel.tool == EditorToolsPanel.Tool.PATCHES
                                && panel.entityIndex < 0
                                && panel.pos() != null
                                && CannonWelding.available()
                                && CannonWelding.supports(panel.selectedBlockState());
                    }

                    public PatchSpatialTool open(EditorToolsPanel panel) {
                        return new CannonWeldControls(panel);
                    }
                });
    }
}
