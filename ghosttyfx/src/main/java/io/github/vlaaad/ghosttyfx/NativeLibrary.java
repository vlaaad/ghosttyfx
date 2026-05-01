package io.github.vlaaad.ghosttyfx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;

final class NativeLibrary {
    private static final Path PATH = extractAndLoad();

    private NativeLibrary() {}

    static void ensureLoaded() {
        PATH.toString();
    }

    private static Path extractAndLoad() {
        var platform = HostPlatform.CURRENT;

        var os = switch (platform.os()) {
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            case LINUX -> "linux";
        };
        var ext = switch (platform.os()) {
            case WINDOWS -> "dll";
            case MACOS -> "dylib";
            case LINUX -> "so";
        };
        var arch = switch (platform.arch()) {
            case X86_64 -> "x86_64";
            case AARCH64 -> "aarch64";
        };

        var platformId = os + "-" + arch;
        var fileName = "libghostty-vt-" + platformId + "." + ext;
        var resourcePath = "/native/" + platformId + "/" + fileName;
        var resource = ghostty_vt_h.class.getResource(resourcePath);
        if (resource == null) {
            throw new IllegalStateException("Missing bundled native library: " + resourcePath);
        }

        try {
            var connection = resource.openConnection();
            var lastModified = connection.getLastModified();
            byte[] bytes;
            try (var input = connection.getInputStream()) {
                bytes = input.readAllBytes();
            }
            var digest = sha256(bytes);
            var directory = cacheRoot(platform).resolve("ghosttyfx").resolve("native").resolve(platformId).resolve(digest);
            var library = directory.resolve(fileName);
            if (needsCopy(library, bytes)) {
                Files.createDirectories(directory);
                var temporary = Files.createTempFile(directory, fileName, ".tmp");
                try {
                    Files.write(temporary, bytes);
                    if (lastModified > 0) {
                        Files.setLastModifiedTime(temporary, FileTime.fromMillis(lastModified));
                    }
                    move(temporary, library);
                } catch (IOException | RuntimeException e) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                    throw e;
                }
            }
            System.load(library.toAbsolutePath().toString());
            return library;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract native library: " + resourcePath, e);
        }
    }

    private static Path cacheRoot(HostPlatform platform) {
        switch (platform.os()) {
            case WINDOWS -> {
                var localAppData = System.getenv("LOCALAPPDATA");
                if (localAppData != null && !localAppData.isBlank()) {
                    return Path.of(localAppData);
                }
            }
            case MACOS -> {
                return Path.of(System.getProperty("user.home"), "Library", "Caches");
            }
            case LINUX -> {
                var xdgCacheHome = System.getenv("XDG_CACHE_HOME");
                if (xdgCacheHome != null && !xdgCacheHome.isBlank()) {
                    return Path.of(xdgCacheHome);
                }
            }
        }
        return Path.of(System.getProperty("user.home"), ".cache");
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException _) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean needsCopy(Path library, byte[] bytes) throws IOException {
        if (!Files.isRegularFile(library)) {
            return true;
        }
        if (Files.size(library) != bytes.length) {
            return true;
        }
        return !Arrays.equals(Files.readAllBytes(library), bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
