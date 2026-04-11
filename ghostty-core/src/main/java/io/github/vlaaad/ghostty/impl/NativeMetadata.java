package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.BuildFeature;
import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.BuildOptimize;
import io.github.vlaaad.ghostty.TypeSchema;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.EnumSet;
import java.util.Set;

final class NativeMetadata {
    private static final int GHOSTTY_OPTIMIZE_DEBUG = 0;
    private static final int GHOSTTY_OPTIMIZE_RELEASE_SAFE = 1;
    private static final int GHOSTTY_OPTIMIZE_RELEASE_SMALL = 2;
    private static final int GHOSTTY_OPTIMIZE_RELEASE_FAST = 3;
    
    private static final int GHOSTTY_BUILD_INFO_SIMD = 1;
    private static final int GHOSTTY_BUILD_INFO_KITTY_GRAPHICS = 2;
    private static final int GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE = 3;
    private static final int GHOSTTY_BUILD_INFO_OPTIMIZE = 4;
    private static final int GHOSTTY_BUILD_INFO_VERSION_STRING = 5;
    private static final int GHOSTTY_BUILD_INFO_VERSION_MAJOR = 6;
    private static final int GHOSTTY_BUILD_INFO_VERSION_MINOR = 7;
    private static final int GHOSTTY_BUILD_INFO_VERSION_PATCH = 8;
    private static final int GHOSTTY_BUILD_INFO_VERSION_BUILD = 9;

    private final MethodHandle ghosttyTypeJson;
    private final MethodHandle ghosttyBuildInfo;

    NativeMetadata() {
        var lookup = SymbolLookup.loaderLookup();
        ghosttyTypeJson = NativeRuntime.bind(lookup, "ghostty_type_json", FunctionDescriptor.of(ValueLayout.ADDRESS));
        ghosttyBuildInfo = NativeRuntime.bind(lookup, "ghostty_build_info", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS
        ));
    }

    BuildInfo buildInfo() {
        try (var arena = Arena.ofConfined()) {
            var versionOut = NativeString.allocate(arena);
            NativeRuntime.invokeStatus(
                ghosttyBuildInfo,
                "ghostty_build_info(version_string)",
                GHOSTTY_BUILD_INFO_VERSION_STRING,
                versionOut
            );

            var majorOut = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
            NativeRuntime.invokeStatus(
                ghosttyBuildInfo,
                "ghostty_build_info(version_major)",
                GHOSTTY_BUILD_INFO_VERSION_MAJOR,
                majorOut
            );

            var minorOut = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
            NativeRuntime.invokeStatus(
                ghosttyBuildInfo,
                "ghostty_build_info(version_minor)",
                GHOSTTY_BUILD_INFO_VERSION_MINOR,
                minorOut
            );

            var patchOut = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
            NativeRuntime.invokeStatus(
                ghosttyBuildInfo,
                "ghostty_build_info(version_patch)",
                GHOSTTY_BUILD_INFO_VERSION_PATCH,
                patchOut
            );

            var buildOut = NativeString.allocate(arena);
            NativeRuntime.invokeStatus(
                ghosttyBuildInfo,
                "ghostty_build_info(version_build)",
                GHOSTTY_BUILD_INFO_VERSION_BUILD,
                buildOut
            );

            var optimizeOut = arena.allocate(ValueLayout.JAVA_INT);
            NativeRuntime.invokeStatus(
                ghosttyBuildInfo,
                "ghostty_build_info(optimize)",
                GHOSTTY_BUILD_INFO_OPTIMIZE,
                optimizeOut
            );

            var simdOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            NativeRuntime.invokeStatus(
                ghosttyBuildInfo,
                "ghostty_build_info(simd)",
                GHOSTTY_BUILD_INFO_SIMD,
                simdOut
            );

            var kittyGraphicsOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            NativeRuntime.invokeStatus(
                ghosttyBuildInfo,
                "ghostty_build_info(kitty_graphics)",
                GHOSTTY_BUILD_INFO_KITTY_GRAPHICS,
                kittyGraphicsOut
            );

            var tmuxControlModeOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            NativeRuntime.invokeStatus(
                ghosttyBuildInfo,
                "ghostty_build_info(tmux_control_mode)",
                GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE,
                tmuxControlModeOut
            );

            var features = EnumSet.noneOf(BuildFeature.class);
            if (simdOut.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                features.add(BuildFeature.SIMD);
            }
            if (kittyGraphicsOut.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                features.add(BuildFeature.KITTY_GRAPHICS);
            }
            if (tmuxControlModeOut.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                features.add(BuildFeature.TMUX_CONTROL_MODE);
            }

            var optimize = optimizeOut.get(ValueLayout.JAVA_INT, 0);
            return new BuildInfo(
                NativeString.readUtf8(versionOut),
                majorOut.get(NativeRuntime.SIZE_T_LAYOUT, 0),
                minorOut.get(NativeRuntime.SIZE_T_LAYOUT, 0),
                patchOut.get(NativeRuntime.SIZE_T_LAYOUT, 0),
                NativeString.readUtf8(buildOut),
                switch (optimize) {
                    case GHOSTTY_OPTIMIZE_DEBUG -> BuildOptimize.DEBUG;
                    case GHOSTTY_OPTIMIZE_RELEASE_SAFE -> BuildOptimize.RELEASE_SAFE;
                    case GHOSTTY_OPTIMIZE_RELEASE_SMALL -> BuildOptimize.RELEASE_SMALL;
                    case GHOSTTY_OPTIMIZE_RELEASE_FAST -> BuildOptimize.RELEASE_FAST;
                    default -> throw new IllegalStateException("Unknown optimize mode " + optimize);
                },
                Set.copyOf(features)
            );
        }
    }

    TypeSchema typeSchema() {
        MemorySegment jsonPointer = NativeRuntime.invoke(ghosttyTypeJson);
        if (jsonPointer.address() == 0L) {
            throw new IllegalStateException("ghostty_type_json() returned a null pointer");
        }
        return new TypeSchema(jsonPointer.reinterpret(Long.MAX_VALUE).getString(0));
    }
}
