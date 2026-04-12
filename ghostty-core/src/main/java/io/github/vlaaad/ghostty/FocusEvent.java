package io.github.vlaaad.ghostty;

import java.util.Objects;

/// Focus event record.
///
/// @param action focus action
public record FocusEvent(
    FocusAction action
) {
    public FocusEvent {
        Objects.requireNonNull(action, "action");
    }
}
