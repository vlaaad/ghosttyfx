package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MouseInputSelectionTest {
    private static final int COLUMNS = 10;
    private static final int CELL_WIDTH = 10;

    @Test
    void clampsClickCountToSupportedRange() {
        assertEquals(1, MouseInput.normalizeClickCount(0));
        assertEquals(1, MouseInput.normalizeClickCount(1));
        assertEquals(2, MouseInput.normalizeClickCount(2));
        assertEquals(3, MouseInput.normalizeClickCount(5));
    }

    @Test
    void computesLeftToRightLinearSelectionWithThresholds() {
        var selection = MouseInput.selectionForDrag(
                new Selection.ScreenPoint(3, 2),
                new Selection.ScreenPoint(5, 4),
                30,
                59,
                false,
                COLUMNS,
                CELL_WIDTH);

        assertEquals(
                Selection.linear(new Selection.ScreenPoint(3, 2), new Selection.ScreenPoint(5, 4)),
                selection);
    }

    @Test
    void computesRightToLeftLinearSelectionWithThresholds() {
        var selection = MouseInput.selectionForDrag(
                new Selection.ScreenPoint(5, 2),
                new Selection.ScreenPoint(3, 4),
                59,
                30,
                false,
                COLUMNS,
                CELL_WIDTH);

        assertEquals(
                Selection.linear(new Selection.ScreenPoint(6, 2), new Selection.ScreenPoint(2, 4)),
                selection);
    }

    @Test
    void computesRectangleSelectionWithoutWrapping() {
        var selection = MouseInput.selectionForDrag(
                new Selection.ScreenPoint(5, 2),
                new Selection.ScreenPoint(3, 4),
                50,
                39,
                true,
                COLUMNS,
                CELL_WIDTH);

        assertEquals(
                new Selection(new Selection.ScreenPoint(4, 2), new Selection.ScreenPoint(4, 4), true),
                selection);
    }

    @Test
    void returnsEmptySelectionWhenThresholdIsNotCrossed() {
        var selection = MouseInput.selectionForDrag(
                new Selection.ScreenPoint(3, 2),
                new Selection.ScreenPoint(3, 4),
                30,
                31,
                true,
                COLUMNS,
                CELL_WIDTH);

        assertTrue(selection.isEmpty());
    }
}
