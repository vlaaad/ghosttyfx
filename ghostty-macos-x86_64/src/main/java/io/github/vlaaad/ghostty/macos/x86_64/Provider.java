package io.github.vlaaad.ghostty.macos.x86_64;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.BuildFeature;
import io.github.vlaaad.ghostty.BuildOptimize;
import io.github.vlaaad.ghostty.TypeSchema;
import io.github.vlaaad.ghostty.impl.NativeLibraries;
import io.github.vlaaad.ghostty.impl.NativeSupport;
import io.github.vlaaad.macos.x86_64.GhosttyString;
import io.github.vlaaad.macos.x86_64.ghostty_vt_h;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.Set;

public final class Provider implements io.github.vlaaad.ghostty.impl.Provider {
    private static final String ID = "macos-x86_64";
    private static final String LIBRARY_EXTENSION = "dylib";

    public String id() {
        return ID;
    }

    public BuildInfo buildInfo() {
        ensureLoaded();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment versionOut = GhosttyString.allocate(arena);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_STRING(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_STRING(), versionOut)
            );
            MemorySegment majorOut = arena.allocate(ghostty_vt_h.size_t);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_MAJOR(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_MAJOR(), majorOut)
            );
            MemorySegment minorOut = arena.allocate(ghostty_vt_h.size_t);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_MINOR(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_MINOR(), minorOut)
            );
            MemorySegment patchOut = arena.allocate(ghostty_vt_h.size_t);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_PATCH(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_PATCH(), patchOut)
            );
            MemorySegment buildOut = GhosttyString.allocate(arena);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_BUILD(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_BUILD(), buildOut)
            );
            MemorySegment optimizeOut = arena.allocate(ValueLayout.JAVA_INT);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_OPTIMIZE(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_OPTIMIZE(), optimizeOut)
            );
            MemorySegment simdOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_SIMD(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_SIMD(), simdOut)
            );
            MemorySegment kittyGraphicsOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_KITTY_GRAPHICS(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_KITTY_GRAPHICS(), kittyGraphicsOut)
            );
            MemorySegment tmuxControlModeOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE(), tmuxControlModeOut)
            );
            EnumSet<BuildFeature> features = EnumSet.noneOf(BuildFeature.class);
            if (simdOut.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                features.add(BuildFeature.SIMD);
            }
            if (kittyGraphicsOut.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                features.add(BuildFeature.KITTY_GRAPHICS);
            }
            if (tmuxControlModeOut.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                features.add(BuildFeature.TMUX_CONTROL_MODE);
            }

            int optimize = optimizeOut.get(ValueLayout.JAVA_INT, 0);
            return new BuildInfo(
                NativeSupport.readUtf8String(GhosttyString.ptr(versionOut), GhosttyString.len(versionOut)),
                majorOut.get(ghostty_vt_h.size_t, 0),
                minorOut.get(ghostty_vt_h.size_t, 0),
                patchOut.get(ghostty_vt_h.size_t, 0),
                NativeSupport.readUtf8String(GhosttyString.ptr(buildOut), GhosttyString.len(buildOut)),
                optimize == ghostty_vt_h.GHOSTTY_OPTIMIZE_DEBUG()
                    ? BuildOptimize.DEBUG
                    : optimize == ghostty_vt_h.GHOSTTY_OPTIMIZE_RELEASE_SAFE()
                        ? BuildOptimize.RELEASE_SAFE
                        : optimize == ghostty_vt_h.GHOSTTY_OPTIMIZE_RELEASE_SMALL()
                            ? BuildOptimize.RELEASE_SMALL
                            : optimize == ghostty_vt_h.GHOSTTY_OPTIMIZE_RELEASE_FAST()
                                ? BuildOptimize.RELEASE_FAST
                                : throwUnknownOptimize(optimize),
                Set.copyOf(features)
            );
        }
    }

    public TypeSchema typeSchema() {
        ensureLoaded();

        MemorySegment jsonPointer = ghostty_vt_h.ghostty_type_json();
        if (jsonPointer.address() == 0L) {
            throw new IllegalStateException("ghostty_type_json() returned a null pointer");
        }

        return new TypeSchema(jsonPointer.reinterpret(Long.MAX_VALUE).getString(0));
    }

    private static void ensureLoaded() {
        String libraryFileName = "libghostty-vt-" + ID + "." + LIBRARY_EXTENSION;
        NativeLibraries.ensureLoaded(ID, "native/" + ID + "/" + libraryFileName, libraryFileName, Provider.class);
    }

    private static void checkBuildInfoResult(int field, int result) {
        NativeSupport.checkResult(
            "ghostty_build_info",
            result,
            field,
            ghostty_vt_h.GHOSTTY_SUCCESS(),
            ghostty_vt_h.GHOSTTY_OUT_OF_MEMORY(),
            ghostty_vt_h.GHOSTTY_INVALID_VALUE(),
            ghostty_vt_h.GHOSTTY_OUT_OF_SPACE(),
            ghostty_vt_h.GHOSTTY_NO_VALUE()
        );
    }

    private static BuildOptimize throwUnknownOptimize(int optimize) {
        throw new IllegalStateException("Unknown optimize mode " + optimize);
    }
}
