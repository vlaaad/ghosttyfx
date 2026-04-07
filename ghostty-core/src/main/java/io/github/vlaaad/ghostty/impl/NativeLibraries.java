package io.github.vlaaad.ghostty.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NativeLibraries {
    private static final Map<String, Path> LOADED = new ConcurrentHashMap<>();

    private NativeLibraries() {}

    public static void ensureLoaded(String id, String libraryResource, String libraryFileName, Class<?> owner) {
        LOADED.computeIfAbsent(id, ignored -> {
            try (InputStream input = owner.getClassLoader().getResourceAsStream(libraryResource)) {
                if (input == null) {
                    throw new IllegalStateException(
                        "Native library resource '" + libraryResource
                            + "' for platform '" + id + "' is missing from the classpath"
                    );
                }

                Path directory = Files.createTempDirectory("ghosttyfx-" + id + "-");
                directory.toFile().deleteOnExit();

                Path extracted = directory.resolve(libraryFileName);
                Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
                extracted.toFile().deleteOnExit();
                System.load(extracted.toAbsolutePath().toString());
                return extracted;
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "Failed to extract native library for platform '" + id + "'",
                    e
                );
            }
        });
    }
}
