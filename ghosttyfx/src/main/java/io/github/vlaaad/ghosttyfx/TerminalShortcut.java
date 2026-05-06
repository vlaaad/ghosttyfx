package io.github.vlaaad.ghosttyfx;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import javafx.scene.input.KeyCombination;

/// A keyboard shortcut handled by [TerminalView].
///
/// When [#combination()] matches a key press, [#action()] is invoked. The action
/// returns whether it handled the key press.
///
/// @param combination the key combination that activates the shortcut
/// @param action the action to run when the shortcut is activated
public record TerminalShortcut(KeyCombination combination, BooleanSupplier action) {
    public TerminalShortcut {
        Objects.requireNonNull(combination, "combination");
        Objects.requireNonNull(action, "action");
    }
}
