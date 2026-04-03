package io.github.vlaaad.ghostty;

/// Asynchronous notifications emitted by a {@link TerminalSession}.
///
/// Implementations should assume callbacks are serialized per session but do not run on the
/// native actor thread that owns mutable terminal state. It is therefore safe to call back into the
/// session from these methods. The {@code titleChanged} callback carries the resolved title string
/// because the native layer only signals that the title changed; the wrapper is expected to read the
/// new title before dispatch.
public interface TerminalEvents {
    default void bell(TerminalSession session) {}
    default void titleChanged(TerminalSession session, String title) {}
    default void stateChanged(TerminalSession session) {}
}
