package io.github.vlaaad.ghostty;

/// Terminal size record.
///
/// @param columns number of columns
/// @param rows number of rows
/// @param cellWidthPx cell width in pixels
/// @param cellHeightPx cell height in pixels
public record TerminalSize(
    int columns,
    int rows,
    int cellWidthPx,
    int cellHeightPx
) {
    private static final int MAX_CELL_COUNT = 0xFFFF;

    public TerminalSize {
        requireCellCount(columns, "columns");
        requireCellCount(rows, "rows");
        requireNonNegative(cellWidthPx, "cellWidthPx");
        requireNonNegative(cellHeightPx, "cellHeightPx");
    }

    private static void requireCellCount(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        if (value > MAX_CELL_COUNT) {
            throw new IllegalArgumentException(name + " must be <= " + MAX_CELL_COUNT);
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
