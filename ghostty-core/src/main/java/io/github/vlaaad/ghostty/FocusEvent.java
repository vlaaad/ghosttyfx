package io.github.vlaaad.ghostty;

/// Focus event record.
///
/// @param action focus action (IN or OUT)
public record FocusEvent(
    FocusAction action
) {}

/// Focus action enum.
enum FocusAction {
    IN, OUT
}