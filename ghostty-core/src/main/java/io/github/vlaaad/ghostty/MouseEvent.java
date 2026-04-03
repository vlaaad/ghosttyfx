package io.github.vlaaad.ghostty;

/// Mouse event record.
///
/// @param action mouse action (press, release, motion)
/// @param button mouse button
/// @param modifiers key modifiers
/// @param position pixel position
public record MouseEvent(
    MouseAction action,
    MouseButton button,
    KeyModifiers modifiers,
    PixelPoint position
) {}