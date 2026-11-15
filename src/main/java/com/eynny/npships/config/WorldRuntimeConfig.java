package com.eynny.npships.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.mojang.logging.LogUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Establishes the native world override before NeoForge loads any SERVER config. */
public final class WorldRuntimeConfig {
    private static final String FILE = "npships-server.toml";

    private WorldRuntimeConfig() {}

    /**
     * No ConfigSpec replacement or reload: NeoForge retains file, watcher, sync and restart
     * ownership.
     */
    public static void initialize(MinecraftServer server) {
        try {
            Origin origin =
                    prepare(
                            server.getWorldPath(LevelResource.ROOT),
                            FMLPaths.CONFIGDIR.get(),
                            FMLPaths.GAMEDIR.get().resolve(FMLConfig.defaultConfigPath()));
            if (origin != Origin.EXISTING)
                LogUtils.getLogger().info("NPShips world configuration initialized ({})", origin);
        } catch (IOException failed) {
            // Falling back to the shared file would silently break world isolation.
            throw new IllegalStateException(
                    "Cannot establish NPShips world/serverconfig/" + FILE, failed);
        }
    }

    enum Origin {
        EXISTING,
        LEGACY_IMPORT,
        TEMPLATE,
        DEFAULTS
    }

    static Origin prepare(Path world, Path sharedConfigs, Path templates) throws IOException {
        Path directory = world.resolve("serverconfig"), target = directory.resolve(FILE);
        if (Files.exists(target)) return Origin.EXISTING;
        Path source = null;
        Origin origin = Origin.DEFAULTS;
        // These saved-data owners prove prior NPShips use, not merely an existing Minecraft world.
        // The retired sensor marker is only historical world-identification evidence here;
        // no foreign config is read, restored or modified.
        Path data = world.resolve("data");
        boolean legacy =
                Files.exists(data.resolve("npships_radar_defaults.dat"))
                        || Files.exists(data.resolve("npships_natural_spawn_rarity.dat"))
                        || Files.exists(data.resolve("npships_ships.dat"));
        if (legacy && Files.exists(sharedConfigs.resolve(FILE))) {
            source = sharedConfigs.resolve(FILE);
            origin = Origin.LEGACY_IMPORT;
        } else if (Files.exists(templates.resolve(FILE))) {
            source = templates.resolve(FILE);
            origin = Origin.TEMPLATE;
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".npships-server-", ".tmp");
        try {
            if (source != null) {
                // Stream the original TOML, including comments; never rewrite the legacy/template
                // input.
                try (var input = Files.newInputStream(source);
                        var output = Files.newOutputStream(temporary)) {
                    input.transferTo(output);
                }
            } else {
                var defaults = CommentedConfig.inMemory();
                NPShipsRuntimeConfig.SPEC.correct(defaults);
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    new TomlWriter().write(defaults, writer);
                }
            }
            try {
                // Same-directory rename, without REPLACE_EXISTING: an existing world file wins.
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException existing) {
                return Origin.EXISTING;
            }
            return origin;
        } finally {
            // This exact temporary file belongs to this invocation; no user file is deleted.
            Files.deleteIfExists(temporary);
        }
    }
}
