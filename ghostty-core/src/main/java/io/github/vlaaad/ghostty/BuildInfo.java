package io.github.vlaaad.ghostty;

/**
 * Build information record.
 * 
 * @param version version string
 * @param major major version number
 * @param minor minor version number
 * @param patch patch version number
 * @param build build number
 * @param optimize optimization level
 * @param simd SIMD support flag
 * @param kittyGraphics Kitty graphics support flag
 * @param tmuxControlMode TMUX control mode support flag
 */
public record BuildInfo(
    String version,
    int major,
    int minor,
    int patch,
    int build,
    String optimize,
    boolean simd,
    boolean kittyGraphics,
    boolean tmuxControlMode
) {}
