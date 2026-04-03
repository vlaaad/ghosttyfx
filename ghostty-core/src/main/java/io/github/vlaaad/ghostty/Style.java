package io.github.vlaaad.ghostty;

/// Immutable cell style.
///
/// @param foreground foreground color
/// @param background background color
/// @param bold whether text is bold
/// @param faint whether text is faint
/// @param italic whether text is italic
/// @param underline whether text is underlined
/// @param blink whether text blinks
/// @param inverse whether text is inverse
/// @param invisible whether text is invisible
/// @param strikethrough whether text has strikethrough
public record Style(
    ColorValue foreground,
    ColorValue background,
    boolean bold,
    boolean faint,
    boolean italic,
    boolean underline,
    boolean blink,
    boolean inverse,
    boolean invisible,
    boolean strikethrough
) {}
