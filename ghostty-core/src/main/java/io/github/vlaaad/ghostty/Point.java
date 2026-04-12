package io.github.vlaaad.ghostty;

/// Logical point used to address terminal content.
///
/// The variants describe which coordinate space the row component belongs to instead of exposing
/// native tagged unions directly. This keeps the public API Java-shaped while preserving the
/// important distinction between active-screen, viewport, screen-local, and history coordinates.
public sealed interface Point permits
    Point.ActivePoint,
    Point.ViewportPoint,
    Point.ScreenPoint,
    Point.HistoryPoint {

    /// Point in the active screen's coordinate space.
    record ActivePoint(int column, int row) implements Point {}

    /// Point relative to the current viewport, including scrollback offset.
    record ViewportPoint(int column, int row) implements Point {}

    /// Point in the visible screen-local coordinate space.
    record ScreenPoint(int column, int row) implements Point {}

    /// Point addressed against absolute history rows.
    record HistoryPoint(int column, long row) implements Point {}
}
