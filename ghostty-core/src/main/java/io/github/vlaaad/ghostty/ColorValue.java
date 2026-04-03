package io.github.vlaaad.ghostty;

/// Terminal color value.
///
/// Color values are modeled as semantic variants rather than native tagged unions so the public
/// API can distinguish default colors, palette references, and explicit RGB values without exposing
/// platform details.
public sealed interface ColorValue permits DefaultColor, PaletteColor, RgbColor {}

/// Use the terminal's default color for the requested slot.
record DefaultColor() implements ColorValue {}

/// Use a color from the indexed terminal palette.
record PaletteColor(int index) implements ColorValue {}

/// Use an explicit 24-bit RGB color.
record RgbColor(int red, int green, int blue) implements ColorValue {}
