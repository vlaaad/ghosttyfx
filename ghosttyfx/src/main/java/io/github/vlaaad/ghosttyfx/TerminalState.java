package io.github.vlaaad.ghosttyfx;

import java.util.Objects;

/// The lifecycle state of a [TerminalView]'s terminal backend.
public sealed interface TerminalState permits TerminalState.Running, TerminalState.Closed, TerminalState.Failed {

    /// The terminal backend is open.
    record Running() implements TerminalState {
    }

    /// The terminal backend closed normally.
    record Closed() implements TerminalState {
    }

    /// The terminal backend failed.
    ///
    /// @param error the error that caused the backend to fail
    record Failed(Exception error) implements TerminalState {

        public Failed {
            Objects.requireNonNull(error, "error");
        }
    }
}
