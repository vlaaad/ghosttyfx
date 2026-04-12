package io.github.vlaaad.ghostty;

/// Immutable terminal cell.
///
/// @param column column position
/// @param text text content
/// @param codePoint code point value
/// @param contentTag content tag
/// @param width cell width
/// @param backgroundFill background-only fill color when contentTag is a background color variant
/// @param style cell style
/// @param hyperlink hyperlink if present
/// @param semantic semantic content type
/// @param protectedCell whether cell is protected
public record Cell(
    int column,
    String text,
    int codePoint,
    CellContentTag contentTag,
    CellWidth width,
    ColorValue backgroundFill,
    Style style,
    Hyperlink hyperlink,
    CellSemantic semantic,
    boolean protectedCell
) {}
