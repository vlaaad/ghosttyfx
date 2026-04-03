package io.github.vlaaad.ghostty;

/// Immutable snapshot of terminal theme.
///
/// @param foreground foreground color
/// @param background background color
/// @param cursor cursor color
/// @param palette color palette
/// @param defaultForeground default foreground color
/// @param defaultBackground default background color
/// @param defaultCursor default cursor color
/// @param defaultPalette default color palette
public record ThemeSnapshot(
    ColorValue foreground,
    ColorValue background,
    ColorValue cursor,
    ColorPalette palette,
    ColorValue defaultForeground,
    ColorValue defaultBackground,
    ColorValue defaultCursor,
    ColorPalette defaultPalette
) {}
