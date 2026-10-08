package io.github.vlaaad.ghosttyfx;

import java.util.Objects;

/// The latest command lifecycle step reported by shell integration.
///
/// Shells may omit steps or report a prompt more than once. These states
/// reflect the reported markers without inferring missing transitions.
public sealed interface ShellState permits ShellState.Prompt, ShellState.InputReady, ShellState.Running, ShellState.Finished {

    /// The shell started drawing a prompt.
    ///
    /// @param kind the kind of prompt being drawn
    record Prompt(PromptKind kind) implements ShellState {
        public Prompt {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /// The prompt is drawn and the shell is ready for command input.
    record InputReady() implements ShellState {}

    /// A command started running.
    ///
    /// @param command the command text, or an empty string when unavailable
    record Running(String command) implements ShellState {
        public Running {
            Objects.requireNonNull(command, "command");
        }
    }

    /// A command finished running.
    ///
    /// @param exitCode the reported exit code, or `null` when unavailable;
    ///                 negative exit codes are valid
    /// @param error the reported error description, or an empty string when unavailable
    record Finished(Integer exitCode, String error) implements ShellState {
        public Finished {
            Objects.requireNonNull(error, "error");
        }
    }

    /// The kind of prompt reported by the shell.
    enum PromptKind {
        /// The main command prompt.
        PRIMARY,
        /// A prompt drawn on the right side of the line.
        RIGHT,
        /// A prompt for a continuation line of a command.
        CONTINUATION,
        /// A secondary prompt for an extra line of input.
        SECONDARY
    }
}
