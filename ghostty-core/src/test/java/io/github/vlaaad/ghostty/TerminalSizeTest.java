package io.github.vlaaad.ghostty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class TerminalSizeTest {
    @Test
    void rejectsNonPositiveCellCounts() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(0, 24, 9, 18));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(80, 0, 9, 18));
    }

    @Test
    void rejectsCellCountsOutsideNativeRange() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(65_536, 24, 9, 18));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(80, 65_536, 9, 18));
    }

    @Test
    void rejectsNegativeCellPixelSizes() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(80, 24, -1, 18));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(80, 24, 9, -1));
    }
}
