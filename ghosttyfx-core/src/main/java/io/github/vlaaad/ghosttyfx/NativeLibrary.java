package io.github.vlaaad.ghosttyfx;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.vlaaad.ghostty.bindings.GhosttySysDecodePngFn;
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;

final class NativeLibrary {
    private static final Arena CALLBACK_ARENA = Arena.global();
    private static final Path PATH = extractAndLoad();
    private static final MemorySegment PNG_DECODER = installPngDecoder();

    private NativeLibrary() {}

    static void ensureLoaded() {
        PATH.toString();
        PNG_DECODER.address();
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

    private static MemorySegment installPngDecoder() {
        try (var arena = Arena.ofConfined()) {
            var supported = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            if (ghostty_vt_h.ghostty_build_info(
                    ghostty_vt_h.GHOSTTY_BUILD_INFO_KITTY_GRAPHICS(),
                    supported) != ghostty_vt_h.GHOSTTY_SUCCESS()
                    || !supported.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                throw new IllegalStateException("Bundled libghostty does not support Kitty graphics");
            }
        }

        var decoder = GhosttySysDecodePngFn.allocate(PngDecoder::decode, CALLBACK_ARENA);
        if (ghostty_vt_h.ghostty_sys_set(
                ghostty_vt_h.GHOSTTY_SYS_OPT_DECODE_PNG(),
                decoder) != ghostty_vt_h.GHOSTTY_SUCCESS()) {
            throw new IllegalStateException("Failed to install libghostty PNG decoder");
        }
        return decoder;
    }
}
