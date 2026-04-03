package io.github.vlaaad.ghostty;

/// Immutable snapshot of cursor state.
///
/// @param position cursor position
/// @param visible whether cursor is visible
/// @param pendingWrap whether cursor has pending wrap
/// @param style cursor style
public record CursorSnapshot(
    Point position,
    boolean visible,
    boolean pendingWrap,
    StyleSnapshot style
) {}
