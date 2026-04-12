package io.github.vlaaad.ghostty;

/// Resolved frame colors packed as `0xRRGGBB`.
public record FrameColors(
    int foreground,
    int background,
    int cursor,
    boolean cursorExplicit
) {}
