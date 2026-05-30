package io.github.vlaaad.ghosttyfx;

import java.util.List;
import java.util.Objects;
import javafx.scene.paint.Color;

/// Colors and rendering parameters used by [TerminalView].
///
/// The palette may be empty to use the terminal emulator defaults, or contain
/// exactly 16 or 256 colors.
///
/// @param background the default terminal background color
/// @param foreground the default terminal foreground color
/// @param palette the indexed terminal color palette
/// @param cursorColor the cursor color
/// @param cursorText the text color used for text covered by the cursor
/// @param selectionColor the selected text background color
/// @param selectionText the selected text foreground color
/// @param faintOpacity the opacity multiplier for faint text, from `0.0` to `1.0`
/// @param scrollbarColor the scrollbar thumb color
/// @param scrollbarActiveColor the scrollbar thumb color while hovered or dragged
/// @param searchMatchColor the background color for search matches
/// @param searchMatchBorderColor the border color for search matches
/// @param searchCurrentMatchColor the background color for the selected search match
/// @param searchCurrentMatchBorderColor the border color for the selected search match
public record TerminalTheme(
        Color background,
        Color foreground,
        List<Color> palette,
        Color cursorColor,
        Color cursorText,
        Color selectionColor,
        Color selectionText,
        double faintOpacity,
        Color scrollbarColor,
        Color scrollbarActiveColor,
        Color searchMatchColor,
        Color searchMatchBorderColor,
        Color searchCurrentMatchColor,
        Color searchCurrentMatchBorderColor) {

    public TerminalTheme(
            Color background,
            Color foreground,
            List<Color> palette,
            Color cursorColor,
            Color cursorText,
            Color selectionColor,
            Color selectionText,
            double faintOpacity,
            Color scrollbarColor,
            Color scrollbarActiveColor,
            Color searchMatchColor,
            Color searchCurrentMatchColor) {
        this(
                background,
                foreground,
                palette,
                cursorColor,
                cursorText,
                selectionColor,
                selectionText,
                faintOpacity,
                scrollbarColor,
                scrollbarActiveColor,
                searchMatchColor,
                searchMatchColor,
                searchCurrentMatchColor,
                searchCurrentMatchColor);
    }

    public TerminalTheme(
            Color background,
            Color foreground,
            List<Color> palette,
            Color cursorColor,
            Color cursorText,
            Color selectionColor,
            Color selectionText,
            double faintOpacity,
            Color scrollbarColor,
            Color searchMatchColor,
            Color searchCurrentMatchColor) {
        this(
                background,
                foreground,
                palette,
                cursorColor,
                cursorText,
                selectionColor,
                selectionText,
                faintOpacity,
                scrollbarColor,
                scrollbarColor,
                searchMatchColor,
                searchMatchColor,
                searchCurrentMatchColor,
                searchCurrentMatchColor);
    }

    public TerminalTheme {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(foreground, "foreground");
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(cursorColor, "cursorColor");
        Objects.requireNonNull(cursorText, "cursorText");
        Objects.requireNonNull(selectionColor, "selectionColor");
        Objects.requireNonNull(selectionText, "selectionText");
        Objects.requireNonNull(scrollbarColor, "scrollbarColor");
        Objects.requireNonNull(scrollbarActiveColor, "scrollbarActiveColor");
        Objects.requireNonNull(searchMatchColor, "searchMatchColor");
        Objects.requireNonNull(searchMatchBorderColor, "searchMatchBorderColor");
        Objects.requireNonNull(searchCurrentMatchColor, "searchCurrentMatchColor");
        Objects.requireNonNull(searchCurrentMatchBorderColor, "searchCurrentMatchBorderColor");
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

    /// Returns the default terminal theme.
    ///
    /// @return the default terminal theme
    public static TerminalTheme defaults() {
        var background = Color.BLACK;
        var foreground = Color.WHITE;
        var scrollbarColor = foreground.deriveColor(0, 1, 1, 0.45);
        return new TerminalTheme(
                background,
                foreground,
                List.of(),
                foreground,
                background,
                foreground,
                background,
                0.5,
                scrollbarColor,
                scrollbarColor,
                foreground.deriveColor(0, 1, 1, 0.18),
                foreground,
                foreground.deriveColor(0, 1, 1, 0.35),
                foreground);
    }
}
