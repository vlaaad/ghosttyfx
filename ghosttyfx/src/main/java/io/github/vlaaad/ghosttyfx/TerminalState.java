package io.github.vlaaad.ghosttyfx;

import java.util.Objects;

public sealed interface TerminalState permits TerminalState.Running, TerminalState.Closed, TerminalState.Failed {

    record Running() implements TerminalState {
    }

    record Closed() implements TerminalState {
    }

    record Failed(Throwable error) implements TerminalState {

        public Failed {
            Objects.requireNonNull(error, "error");
        }
    }
}
