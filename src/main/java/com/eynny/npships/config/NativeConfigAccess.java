package com.eynny.npships.config;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Serializes infrequent NPShips config writes with NeoForge's existing owner transaction. */
public final class NativeConfigAccess {
    private NativeConfigAccess() {}

    public static <T> T withOwner(
            ModConfigSpec spec, ModConfig.Type type, java.util.function.Supplier<T> action) {
        var owner =
                net.neoforged.fml.config.ModConfigs.getModConfigs("npships").stream()
                        .filter(config -> config.getSpec() == spec && config.getType() == type)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "NPShips configuration owner is unavailable"));
        final java.util.concurrent.locks.ReentrantLock lock;
        try {
            var field = net.neoforged.fml.config.ModConfig.class.getDeclaredField("lock");
            if (!field.trySetAccessible()
                    || !(field.get(owner)
                            instanceof java.util.concurrent.locks.ReentrantLock nativeLock))
                throw new IllegalStateException(
                        "Native ModConfig owner lock is inaccessible or has changed type");
            lock = nativeLock;
        } catch (ReflectiveOperationException | SecurityException failure) {
            throw new IllegalStateException(
                    "Unsupported NeoForge configuration transaction contract: native ModConfig.lock is required",
                    failure);
        }
        lock.lock();
        try {
            if (!spec.isLoaded() || owner.getLoadedConfig() == null)
                throw new IllegalStateException("NPShips configuration is not loaded");
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
