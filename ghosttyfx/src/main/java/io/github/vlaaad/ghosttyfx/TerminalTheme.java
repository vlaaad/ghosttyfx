package io.github.vlaaad.ghosttyfx;

import java.util.List;
import java.util.Objects;
import javafx.scene.paint.Color;

public record TerminalTheme(
        Color background,
        Color foreground,
        List<Color> palette,
        Color cursorColor,
        Color cursorText,
        Color selectionColor,
        Color selectionText,
    double faintOpacity,
        Color scrollbarColor) {

    public TerminalTheme {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(foreground, "foreground");
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(cursorColor, "cursorColor");
        Objects.requireNonNull(cursorText, "cursorText");
        Objects.requireNonNull(selectionColor, "selectionColor");
        Objects.requireNonNull(selectionText, "selectionText");
        Objects.requireNonNull(scrollbarColor, "scrollbarColor");
        if (!Double.isFinite(faintOpacity) || faintOpacity < 0.0 || faintOpacity > 1.0) {
            throw new IllegalArgumentException("faintOpacity must be between 0 and 1");
        }
        palette = List.copyOf(palette);
        for (var color : palette) {
            Objects.requireNonNull(color, "palette color");
        }
        if (!palette.isEmpty() && palette.size() != 16 && palette.size() != 256) {
            throw new IllegalArgumentException("palette must be empty, 16 colors, or 256 colors");
        }
    }

    public static TerminalTheme defaults() {
        var background = Color.BLACK;
        var foreground = Color.WHITE;
        return new TerminalTheme(
                background,
                foreground,
                List.of(),
                foreground,
                background,
                foreground,
                background,
                0.5,
                foreground.deriveColor(0, 1, 1, 0.45));
    }
}
