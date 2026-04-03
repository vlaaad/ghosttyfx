package io.github.vlaaad.ghostty;

/**
 * Color value - sealed interface for different color types.
 */
public sealed interface ColorValue permits DefaultColor, PaletteColor, RgbColor {}

/**
 * Default color.
 */
record DefaultColor() implements ColorValue {}

/**
 * Palette color.
 */
record PaletteColor(int index) implements ColorValue {}

/**
 * RGB color.
 */
record RgbColor(int red, int green, int blue) implements ColorValue {}
