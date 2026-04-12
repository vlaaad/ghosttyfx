package io.github.vlaaad.ghostty;

import java.util.Objects;
import java.util.Set;

/// Build information record.
///
/// @param version version string
/// @param major major version number
/// @param minor minor version number
/// @param patch patch version number
/// @param pre pre-release metadata string
/// @param build build metadata string
/// @param optimize optimization level
/// @param features enabled build features
public record BuildInfo(
    String version,
    long major,
    long minor,
    long patch,
    String pre,
    String build,
    BuildOptimize optimize,
    Set<BuildFeature> features
) {
    public BuildInfo {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(pre, "pre");
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(optimize, "optimize");
        features = Set.copyOf(features);
    }
}
