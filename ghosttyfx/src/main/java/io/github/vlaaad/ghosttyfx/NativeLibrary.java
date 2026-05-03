package io.github.vlaaad.ghosttyfx;

import java.nio.file.Files;
import java.nio.file.Path;

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
        var directory = ResourceCache.extractZip(ghostty_vt_h.class, "/native/" + platformId + ".zip");
        var library = directory.resolve(fileName);
        if (!Files.isRegularFile(library)) {
            throw new IllegalStateException("Missing native library in extracted bundle: " + library);
        }
        System.load(library.toAbsolutePath().toString());
        return library;
    }
}
