package com.eynny.npships.authoring.io;

import net.minecraft.network.chat.Component;

import java.util.Locale;

public enum ExportProfile {
    EMPTY,
    GROUND,
    AIR,
    SEA;

    public Component label() {
        return Component.translatable("npships.export.profile." + name().toLowerCase(Locale.ROOT));
    }
}
