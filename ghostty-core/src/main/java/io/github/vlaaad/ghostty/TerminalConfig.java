package io.github.vlaaad.ghostty;

/// Immutable configuration used when opening a terminal session.
///
/// @param columns initial terminal column count
/// @param rows initial terminal row count
/// @param maxScrollback maximum number of scrollback rows the session should retain
public record TerminalConfig(
    int columns,
    int rows,
    long maxScrollback
) {
    public TerminalConfig {
        if (columns <= 0 || columns > 0xFFFF) {
            throw new IllegalArgumentException("columns must be in range 1..65535");
        }
        if (rows <= 0 || rows > 0xFFFF) {
            throw new IllegalArgumentException("rows must be in range 1..65535");
        }
        if (maxScrollback < 0) {
            throw new IllegalArgumentException("maxScrollback must be non-negative");
        }
    }
}
