package com.eynny.npships.integration.computercraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Supported Windows handle query where the JDK does not expose a BasicFileAttributes key. */
final class ComputerFileIdentity {
    private ComputerFileIdentity() {}

    static Object read(Path path, boolean directory) throws IOException {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"))
            return null;
        return Windows.read(path, directory);
    }

    private record Key(long volume, long low, long high, long creation) {}

    private static final class Windows {
        static Object read(Path path, boolean directory) throws IOException {
            var api = com.sun.jna.platform.win32.Kernel32.INSTANCE;
            String name = path.toAbsolutePath().normalize().toString();
            if (!name.startsWith("\\\\?\\"))
                name =
                        name.startsWith("\\\\")
                                ? "\\\\?\\UNC\\" + name.substring(2)
                                : "\\\\?\\" + name;
            var handle =
                    api.CreateFile(
                            name,
                            com.sun.jna.platform.win32.WinNT.FILE_READ_ATTRIBUTES,
                            com.sun.jna.platform.win32.WinNT.FILE_SHARE_READ
                                    | com.sun.jna.platform.win32.WinNT.FILE_SHARE_WRITE
                                    | com.sun.jna.platform.win32.WinNT.FILE_SHARE_DELETE,
                            null,
                            com.sun.jna.platform.win32.WinNT.OPEN_EXISTING,
                            com.sun.jna.platform.win32.WinNT.FILE_FLAG_BACKUP_SEMANTICS
                                    | com.sun.jna.platform.win32.WinNT.FILE_FLAG_OPEN_REPARSE_POINT,
                            null);
            if (com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(handle))
                throw new IOException(
                        "Cannot open no-follow computer identity handle (Win32 "
                                + api.GetLastError()
                                + "): "
                                + path);
            IOException failed = null;
            try (var information = new com.sun.jna.Memory(40)) {
                // FILE_BASIC_INFO contains creation time and the same type/reparse flags as
                // FILE_ATTRIBUTE_TAG_INFO. Query both there, then reuse this handle-local buffer
                // for the 24-byte FILE_ID_INFO; no identity or ancestor check is omitted.
                if (!api.GetFileInformationByHandleEx(
                        handle,
                        com.sun.jna.platform.win32.WinBase.FileBasicInfo,
                        information,
                        new com.sun.jna.platform.win32.WinDef.DWORD(40)))
                    throw new IOException(
                            "Cannot read computer basic information (Win32 "
                                    + api.GetLastError()
                                    + "): "
                                    + path);
                long creation = information.getLong(0);
                int flags = information.getInt(32);
                if ((flags
                                        & (com.sun.jna.platform.win32.WinNT
                                                        .FILE_ATTRIBUTE_REPARSE_POINT
                                                | com.sun.jna.platform.win32.WinNT
                                                        .FILE_ATTRIBUTE_DEVICE))
                                != 0
                        || ((flags & com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_DIRECTORY)
                                        != 0)
                                != directory)
                    throw new IOException("Unsafe native computer destination type: " + path);
                if (!api.GetFileInformationByHandleEx(
                        handle,
                        com.sun.jna.platform.win32.WinBase.FileIdInfo,
                        information,
                        new com.sun.jna.platform.win32.WinDef.DWORD(24)))
                    throw new IOException(
                            "Cannot read stable computer file ID (Win32 "
                                    + api.GetLastError()
                                    + "): "
                                    + path);
                return new Key(
                        information.getLong(0),
                        information.getLong(8),
                        information.getLong(16),
                        creation);
            } catch (IOException failure) {
                failed = failure;
                throw failure;
            } finally {
                if (!api.CloseHandle(handle)) {
                    var failure =
                            new IOException(
                                    "Cannot close computer identity handle (Win32 "
                                            + api.GetLastError()
                                            + "): "
                                            + path);
                    if (failed == null) throw failure;
                    failed.addSuppressed(failure);
                }
            }
        }
    }
}
