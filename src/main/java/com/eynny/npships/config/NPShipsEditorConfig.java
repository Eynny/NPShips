package com.eynny.npships.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class NPShipsEditorConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CLAIM_WAVES;
    public static final ModConfigSpec.DoubleValue ORBIT, ZOOM;
    public static final ModConfigSpec.IntValue INSPECTOR_WIDTH;
    public static final ModConfigSpec.ConfigValue<String> PALETTE, VIEW, PINS;

    static {
        var b = new ModConfigSpec.Builder();
        CLAIM_WAVES =
                b.comment(
                                "Show Claim surface waves on this client, only when the server sends them. Disabling immediately clears local waves; re-enabling affects future waves. Does not change Claim gameplay. Live local setting.")
                        .define("show_claim_waves", true);
        ORBIT = b.defineInRange("orbit_sensitivity", 0.6, 0.05, 3.0);
        ZOOM = b.defineInRange("zoom_step", 1.15, 1.01, 2.0);
        PALETTE =
                b.define(
                        "palette",
                        "DEFAULT",
                        v ->
                                v instanceof String name
                                        && java.util.Set.of(
                                                        "DEFAULT",
                                                        "SOFT",
                                                        "SLATE",
                                                        "BLUE",
                                                        "WARM",
                                                        "MINT",
                                                        "LOW_CONTRAST",
                                                        "MIDNIGHT",
                                                        "PLUM",
                                                        "FOREST",
                                                        "CRIMSON",
                                                        "AMBER",
                                                        "COPPER",
                                                        "SCARLET",
                                                        "OCEAN",
                                                        "OLIVE",
                                                        "VIOLET")
                                                .contains(name));
        INSPECTOR_WIDTH =
                b.comment(
                                "Preferred schematic inspector width in scaled GUI units; 0 uses automatic width.")
                        .defineInRange("inspector_preferred_width", 0, 0, Integer.MAX_VALUE);
        PINS = b.define("pinned_brushes", "{}");
        VIEW = b.define("view_preferences", "{}");
        SPEC = b.build();
    }

    /**
     * Serialize this mod's CLIENT actions with the native owner's read/publish/save transaction.
     * The native lock is shared by this mod's configs; no watcher or publication code is changed.
     * Keep this off frame/tick paths. Unsupported native contracts refuse before any mutation.
     */
    public static <T> T withNativeOwner(java.util.function.Supplier<T> action) {
        return NativeConfigAccess.withOwner(
                SPEC, net.neoforged.fml.config.ModConfig.Type.CLIENT, action);
    }

    public static void withNativeOwner(Runnable action) {
        withNativeOwner(
                () -> {
                    action.run();
                    return null;
                });
    }

    public static boolean claimWavesEnabled() {
        return !SPEC.isLoaded() || CLAIM_WAVES.get();
    }

    public static int inspectorWidth() {
        return SPEC.isLoaded() ? INSPECTOR_WIDTH.get() : 0;
    }

    /** Only completed user resize/reset actions persist; native clamps are presentation state. */
    public static void inspectorWidth(int width) {
        if (SPEC.isLoaded()) {
            withNativeOwner(
                    () -> {
                        INSPECTOR_WIDTH.set(Math.max(0, width));
                        SPEC.save();
                    });
        }
    }

    public static float orbit() {
        return SPEC.isLoaded() ? ORBIT.get().floatValue() : 0.6f;
    }

    public static double zoom() {
        return SPEC.isLoaded() ? ZOOM.get() : 1.15;
    }
}
