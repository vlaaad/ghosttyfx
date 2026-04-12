package io.github.vlaaad.ghostty;

/// Cursor state for a render frame.
public record FrameCursor(
    boolean visible,
    boolean blinking,
    boolean passwordInput,
    boolean inViewport,
    int column,
    int row,
    boolean wideTail,
    FrameCursorStyle style
) {}
