package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.IntStream;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

final class TerminalThemeTest {

    @Test
    void defaultsResolveDerivedColors() {
        var theme = TerminalTheme.defaults();

        assertEquals(Color.BLACK, theme.background());
        assertEquals(Color.WHITE, theme.foreground());
        assertEquals(List.of(), theme.palette());
        assertEquals(theme.foreground(), theme.cursorColor());
        assertEquals(theme.background(), theme.cursorText());
        assertEquals(theme.foreground(), theme.selectionColor());
        assertEquals(theme.background(), theme.selectionText());
        assertEquals(0.5, theme.faintOpacity());
        assertEquals(theme.foreground().deriveColor(0, 1, 1, 0.45), theme.scrollbarColor());
    }

    @Test
    void acceptsEmpty16And256ColorPalettes() {
        newTheme(List.of());
        newTheme(colors(16));
        newTheme(colors(256));
    }

    @Test
    void rejectsInvalidPaletteSizes() {
        assertThrows(IllegalArgumentException.class, () -> newTheme(colors(1)));
        assertThrows(IllegalArgumentException.class, () -> newTheme(colors(17)));
        assertThrows(IllegalArgumentException.class, () -> newTheme(colors(255)));
    }

    @Test
    void rejectsNullThemeValues() {
        assertThrows(NullPointerException.class, () -> new TerminalTheme(
                null,
                Color.WHITE,
                List.of(),
                Color.WHITE,
                Color.BLACK,
                Color.WHITE,
                Color.BLACK,
                0.5,
                Color.WHITE));
        assertThrows(NullPointerException.class, () -> newTheme(List.of(Color.BLACK, null)));
    }

    private static TerminalTheme newTheme(List<Color> palette) {
        return new TerminalTheme(
                Color.BLACK,
                Color.WHITE,
                palette,
                Color.WHITE,
                Color.BLACK,
                Color.WHITE,
                Color.BLACK,
                0.5,
                Color.gray(0.5));
    }

    private static List<Color> colors(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Color.gray((double) i / Math.max(1, count - 1)))
                .toList();
    }
}
