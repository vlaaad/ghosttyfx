package io.github.vlaaad.ghosttyfx;

import java.util.Objects;

/// A progress indication from the application running in the terminal.
public sealed interface Progress permits Progress.Determinate, Progress.Indeterminate {

    /// Returns the progress state.
    ///
    /// @return the progress state
    State state();

    /// The state of a progress indication.
    enum State {
        ACTIVE,
        PAUSED,
        FAILED
    }

    /// A progress indication with a known percentage.
    ///
    /// @param state the progress state
    /// @param percentage the percentage from 0 through 100
    record Determinate(State state, int percentage) implements Progress {

        public Determinate {
            Objects.requireNonNull(state, "state");
            if (percentage < 0 || percentage > 100) {
                throw new IllegalArgumentException("percentage must be between 0 and 100");
            }
        }
    }

    /// A progress indication without a known percentage.
    ///
    /// @param state the progress state
    record Indeterminate(State state) implements Progress {

        public Indeterminate {
            Objects.requireNonNull(state, "state");
        }
    }
}
