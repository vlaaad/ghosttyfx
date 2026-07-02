package io.github.vlaaad.ghosttyfx;

/// Terminal grid size measured in character cells.
///
/// @param columns the terminal width
/// @param rows the terminal height
public record TerminalSize(int columns, int rows) {

    /// Creates a terminal size.
    ///
    /// @throws IllegalArgumentException if `columns` or `rows` is not positive
    public TerminalSize {
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be positive");
        }
        if (rows <= 0) {
            throw new IllegalArgumentException("rows must be positive");
        }
    }
}
