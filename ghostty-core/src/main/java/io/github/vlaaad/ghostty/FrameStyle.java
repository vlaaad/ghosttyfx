package io.github.vlaaad.ghostty;

/// Resolved cell style packed for rendering.
///
/// Colors are packed as `0xRRGGBB`.
public record FrameStyle(
    int foreground,
    int background,
    int underlineColor,
    UnderlineStyle underlineStyle,
    boolean bold,
    boolean faint,
    boolean italic,
    boolean underline,
    boolean blink,
    boolean inverse,
    boolean invisible,
    boolean strikethrough,
    boolean overline
) {}
