package io.github.vlaaad.ghostty;

/**
 * Terminal events interface for handling terminal events.
 */
public interface TerminalEvents {
    default void bell(TerminalSession session) {}
    default void titleChanged(TerminalSession session, String title) {}
    default void stateChanged(TerminalSession session) {}
}