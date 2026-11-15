package com.eynny.npships.integration;

import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.aeronautics.AeronauticsPatch;
import com.eynny.npships.integration.aeronautics.SpringPatch;
import com.eynny.npships.integration.computercraft.ComputerMedia;
import com.eynny.npships.integration.radars.RadarFuzes;

/** Source-only completeness checks. Disabling restoration never disables these safety guards. */
public final class NativeCaptureRequirements {
    private NativeCaptureRequirements() {}

    public static final java.util.List<String> ORDER =
            java.util.List.of(
                    "computercraft_media",
                    "simulated_spring",
                    "simulated_rope",
                    "radars_guided_fuzes",
                    "computercraft_peripheral",
                    "radars_network");

    public static void validate(
            String provider,
            com.eynny.npships.authoring.patches.PatchSourceOperations.Capture source) {
        var doc = source.document();
        var block = source.record();
        var captured = source.captured();
        var paired = source.paired();
        boolean builtin = captured != null && !ExternalPatches.external(captured);
        String id = source.name();
        var data = source.data();
        if (provider.equals("computercraft_media")) {
            var ids = ComputerMedia.ids(data);
            if (!ids.isEmpty() && (!builtin || !captured.has("media")))
                throw new IllegalArgumentException(
                        "Disk files require NPShips export at " + source.position());
            if (builtin && !ids.isEmpty()) ComputerMedia.validateReferences(ids, captured);
        }
        if (block.getBoolean(SchematicPatches.UNPATCHED)) return;
        if (provider.equals("simulated_spring")
                && SpringPatch.matches(id)
                && data.contains("Goal")
                && !builtin
                && !paired.contains(source.position()))
            throw new IllegalArgumentException(
                    "Spring requires NPShips capture. Re-export both endpoints from the world: "
                            + source.position());
        if (provider.equals("simulated_rope")
                && AeronauticsPatch.rope(id)
                && !builtin
                && !paired.contains(source.position())
                && (data.contains("HasRopeAttached") || data.contains("Strand")))
            throw new IllegalArgumentException(
                    "Rope requires NPShips capture at "
                            + source.position()
                            + ". Re-export both endpoints from the world.");
        if (provider.equals("radars_guided_fuzes")
                && RadarFuzes.stacks(data).stream()
                        .anyMatch(stack -> RadarFuzes.custom(stack.data()).contains("monitorPos"))
                && (!builtin || !captured.has("fuze_bindings")))
            throw new IllegalArgumentException(
                    "Guided fuzes require NPShips capture or editor binding at "
                            + source.position());
        boolean nativeDevice =
                provider.equals("computercraft_peripheral")
                                && id.startsWith("computercraft:")
                                && !PatchDevices.kind(doc, source.position()).isEmpty()
                        || provider.equals("radars_network") && id.startsWith("create_radar:");
        if (nativeDevice && !data.isEmpty() && !builtin)
            throw new IllegalArgumentException(
                    "Missing integration capture at "
                            + source.position()
                            + " ("
                            + id
                            + "). Export from the world with NPShips first.");
    }
}
