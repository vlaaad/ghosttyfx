package io.github.vlaaad.ghosttyfx;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;

final class ResourceCache {
    private static final ConcurrentHashMap<Resource, Path> CACHE = new ConcurrentHashMap<>();

    private ResourceCache() {}

    static Path extractZip(Class<?> anchor, String resourcePath) {
        if (!resourcePath.startsWith("/") || !resourcePath.endsWith(".zip")) {
            throw new IllegalArgumentException("Expected absolute zip resource path: " + resourcePath);
        }
        return CACHE.computeIfAbsent(new Resource(anchor, resourcePath), _ -> {
            try (var input = anchor.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new IllegalStateException("Missing bundled resource: " + resourcePath);
                }
                var bytes = input.readAllBytes();
                var digest = sha256(bytes);
                var cachePath = Path.of(resourcePath.substring(1, resourcePath.length() - ".zip".length()));
                if (cachePath.isAbsolute() || cachePath.normalize().startsWith("..")) {
                    throw new IllegalArgumentException("Unsafe resource path: " + resourcePath);
                }
                var directory = cacheRoot().resolve("ghosttyfx").resolve(cachePath).resolve(digest);
                if (Files.isDirectory(directory)) {
                    return directory;
                }
                Files.createDirectories(directory.getParent());
                var temporary = Files.createTempDirectory(directory.getParent(), directory.getFileName() + "-");
                try {
                    unzip(bytes, temporary);
                    move(temporary, directory);
                    return directory;
                } catch (IOException | RuntimeException e) {
                    deleteQuietly(temporary, e);
                    throw e;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to extract bundled resource: " + resourcePath, e);
            }
        });
    }

    private record Resource(Class<?> anchor, String path) {}

    private static Path cacheRoot() {
        switch (HostPlatform.CURRENT.os()) {
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

    private static void unzip(byte[] bytes, Path destination) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                var resolved = destination.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(destination)) {
                    throw new IOException("zip entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                Files.createDirectories(resolved.getParent());
                Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException _) {
            moveNonAtomic(source, target);
        } catch (FileAlreadyExistsException _) {
            moveOverIncomplete(source, target);
        }
    }

    private static void moveNonAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
        } catch (FileAlreadyExistsException _) {
            moveOverIncomplete(source, target);
        }
    }

    private static void moveOverIncomplete(Path source, Path target) throws IOException {
        if (Files.isDirectory(target)) {
            deleteDirectory(source);
            return;
        }
        deleteDirectory(target);
        Files.move(source, target);
    }

    private static void deleteQuietly(Path path, Exception cause) {
        try {
            deleteDirectory(path);
        } catch (IOException suppressed) {
            cause.addSuppressed(suppressed);
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
