package io.github.vlaaad.ghosttyfx;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import javafx.scene.input.KeyCombination;

public record TerminalShortcut(KeyCombination combination, BooleanSupplier action) {
    public TerminalShortcut {
        Objects.requireNonNull(combination, "combination");
        Objects.requireNonNull(action, "action");
    }
}
