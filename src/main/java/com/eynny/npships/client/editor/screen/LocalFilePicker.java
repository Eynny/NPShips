package com.eynny.npships.client.editor.screen;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** The OS file chooser; paths are used only on the author's client. */
public final class LocalFilePicker {
    /** Initial directory for PNG exports, including a redirected Windows Downloads folder. */
    public static Path downloadsDirectory() {
        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            try {
                Path downloads = Windows.downloadsDirectory();
                if (Files.isDirectory(downloads)) return downloads;
            } catch (RuntimeException | LinkageError unavailable) {
                // The chooser remains usable when the native folder query is unavailable.
            }
        }
        Path downloads = home.resolve("Downloads");
        return Files.isDirectory(downloads) ? downloads : home;
    }

    private static final class Windows {
        private static Path downloadsDirectory() {
            return Path.of(
                    com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(
                            com.sun.jna.platform.win32.KnownFolders.FOLDERID_Downloads));
        }
    }

    public static Path save(Path suggestion, String title) {
        try (var stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(1);
            filters.put(0, stack.UTF8("*.png"));
            String result =
                    TinyFileDialogs.tinyfd_saveFileDialog(
                            title, suggestion.toAbsolutePath().toString(), filters, "PNG");
            return result == null ? null : Path.of(result);
        }
    }

    public static Path open(Path folder, String extension, String title) {
        try (var stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(1);
            filters.put(0, stack.UTF8(extension.equals("*") ? "*" : "*." + extension));
            String result =
                    TinyFileDialogs.tinyfd_openFileDialog(
                            title,
                            folder.toAbsolutePath() + java.io.File.separator,
                            filters,
                            extension.toUpperCase() + " files",
                            false);
            return result == null ? null : Path.of(result);
        }
    }
}
