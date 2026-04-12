package io.github.vlaaad.ghostty;

import java.util.Arrays;

/// Color palette record.
///
/// @param colors array of colors in the palette
public record ColorPalette(
    ColorValue[] colors
) {
    public ColorPalette {
        if (colors.length != 256) {
            throw new IllegalArgumentException("colors must contain exactly 256 entries");
        }
        colors = Arrays.copyOf(colors, colors.length);
    }
}
