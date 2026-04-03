package io.github.vlaaad.ghostty;

/**
 * Point interface - sealed interface for different point types.
 */
public sealed interface Point permits ActivePoint, ViewportPoint, ScreenPoint, HistoryPoint {}

/**
 * Active point record.
 */
record ActivePoint(int column, int row) implements Point {}

/**
 * Viewport point record.
 */
record ViewportPoint(int column, int row) implements Point {}

/**
 * Screen point record.
 */
record ScreenPoint(int column, int row) implements Point {}

/**
 * History point record.
 */
record HistoryPoint(int column, long row) implements Point {}
