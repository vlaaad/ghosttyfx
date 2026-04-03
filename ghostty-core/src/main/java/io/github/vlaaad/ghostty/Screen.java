package io.github.vlaaad.ghostty;

/// Immutable terminal screen.
///
/// @param kind screen kind
/// @param columns number of columns
/// @param rows number of rows
/// @param visibleRows visible rows in this screen
public record Screen(
    ScreenKind kind,
    int columns,
    int rows,
    java.util.List<Row> visibleRows
) {}
