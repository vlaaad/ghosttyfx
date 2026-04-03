package io.github.vlaaad.ghostty;

/**
 * Terminal size record.
 * 
 * @param columns number of columns
 * @param rows number of rows
 * @param cellWidthPx cell width in pixels
 * @param cellHeightPx cell height in pixels
 */
public record TerminalSize(
    int columns,
    int rows,
    int cellWidthPx,
    int cellHeightPx
) {}
