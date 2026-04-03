package io.github.vlaaad.ghostty;

/**
 * Size report record.
 * 
 * @param widthPx width in pixels
 * @param heightPx height in pixels
 * @param columns number of columns
 * @param rows number of rows
 */
public record SizeReport(
    int widthPx,
    int heightPx,
    int columns,
    int rows
) {}
