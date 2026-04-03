package io.github.vlaaad.ghostty;

/// Immutable cursor state.
///
/// @param position cursor position
/// @param visible whether cursor is visible
/// @param pendingWrap whether cursor has pending wrap
/// @param style cursor style
public record Cursor(
    Point position,
    boolean visible,
    boolean pendingWrap,
    Style style
) {}
