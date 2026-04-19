package io.github.vlaaad.ghosttyfx;

import io.github.vlaaad.ghostty.bindings.GhosttyString;
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GhosttyFx {
    private static final int GHOSTTY_SUCCESS = 0;
    private static final Path DEFAULT_CWD = Path.of(System.getProperty("user.dir", "."));

    private GhosttyFx() {}

    public static GhosttyCanvas create(List<String> command) {
        return create(command, DEFAULT_CWD, System.getenv());
    }

    public static GhosttyCanvas create(List<String> command, Path cwd) {
        return create(command, cwd, System.getenv());
    }

    public static GhosttyCanvas create(List<String> command, Path cwd, Map<String, String> environment) {
        var copiedCommand = List.copyOf(command);
        if (copiedCommand.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        return new GhosttyCanvas(copiedCommand, cwd, Map.copyOf(environment));
    }

    public static String version() {
        NativeLibraryHolder.ensureLoaded();
        try (var arena = Arena.ofConfined()) {
            var version = GhosttyString.allocate(arena);
            var result = ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_STRING(), version);
            if (result != GHOSTTY_SUCCESS) {
                throw new IllegalStateException("ghostty_build_info failed with result=" + result);
            }
            return toJavaString(version);
        }
    }

    private static String toJavaString(MemorySegment value) {
        var length = GhosttyString.len(value);
        if (length == 0) {
            return "";
        }
        var pointer = GhosttyString.ptr(value);
        if (pointer.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Ghostty version pointer is null");
        }
        var bytes = pointer.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static final class NativeLibraryHolder {
        private static final java.nio.file.Path PATH = extractAndLoad();

        private NativeLibraryHolder() {}

        static void ensureLoaded() {
            PATH.toString();
        }

        private static java.nio.file.Path extractAndLoad() {
            var osName = System.getProperty("os.name", "");
            var archName = System.getProperty("os.arch", "");
            var osKey = osName.toLowerCase(Locale.ROOT);
            var arch = switch (archName.toLowerCase(Locale.ROOT)) {
                case "x8664", "amd64", "x86_64" -> "x86_64";
                case "aarch64", "arm64" -> "aarch64";
                default -> throw new IllegalStateException("Unsupported os.arch: " + archName);
            };
            String os;
            String ext;
            if (osKey.contains("linux")) {
                os = "linux";
                ext = "so";
            } else if (osKey.contains("mac") || osKey.contains("darwin")) {
                os = "macos";
                ext = "dylib";
            } else if (osKey.contains("win")) {
                os = "windows";
                ext = "dll";
            } else {
                throw new IllegalStateException("Unsupported os.name: " + osName);
            }
            var fileName = "libghostty-vt-" + os + "-" + arch + "." + ext;
            var resourcePath = "/native/" + os + "-" + arch + "/" + fileName;
            try (var input = ghostty_vt_h.class.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new IllegalStateException("Missing bundled native library: " + resourcePath);
                }
                var directory = Files.createTempDirectory("ghosttyfx-");
                var library = directory.resolve(fileName);
                Files.copy(input, library);
                directory.toFile().deleteOnExit();
                library.toFile().deleteOnExit();
                System.load(library.toAbsolutePath().toString());
                return library;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to extract native library: " + resourcePath, e);
            }
        }
    }
}
