package io.github.vlaaad.ghostty;

import java.util.Objects;

/// Mouse event record.
///
/// @param action mouse action (press, release, motion)
/// @param button mouse button, or {@code null} when no button is active
/// @param modifiers key modifiers, or {@code null} for none
/// @param position mouse position in surface-space pixels
public record MouseEvent(
    MouseAction action,
    MouseButton button,
    KeyModifiers modifiers,
    MousePosition position
) {
    public MouseEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(position, "position");
    }
}
