package io.github.vlaaad.ghostty;

import java.util.Set;

/// Build information record.
///
/// @param version version string
/// @param major major version number
/// @param minor minor version number
/// @param patch patch version number
/// @param build build metadata string
/// @param optimize optimization level
/// @param features enabled build features
public record BuildInfo(
    String version,
    long major,
    long minor,
    long patch,
    String build,
    BuildOptimize optimize,
    Set<BuildFeature> features
) {}
