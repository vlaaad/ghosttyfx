package io.github.vlaaad.ghostty;

/**
 * Terminal configuration record.
 * 
 * @param columns number of columns
 * @param rows number of rows
 * @param maxScrollback maximum scrollback size
 */
public record TerminalConfig(
    int columns,
    int rows,
    long maxScrollback
) {}
