package io.github.vlaaad.ghostty;

import java.util.Optional;

/// Synchronous query callbacks used when the terminal requests an immediate host answer.
///
/// These methods are expected to run inline while the native terminal is blocked waiting for a
/// reply. Implementations should therefore return quickly, preferably from already-cached state.
/// They must not call back into {@link TerminalSession}, because doing so would re-enter the session
/// while its actor is already busy serving the current query.
public interface TerminalQueries {
    /// Returns the reply for ENQ ({@code ^E}) requests.
    default String enquiryReply() { return ""; }

    /// Returns the reply for XTVERSION requests.
    default String xtversionReply() { return ""; }

    /// Returns the most recent terminal size to report, if size reports are supported by the host.
    default Optional<TerminalSize> sizeReportValue() { return Optional.empty(); }

    /// Returns the current host color-scheme preference, if known.
    default Optional<ColorScheme> colorSchemeValue() { return Optional.empty(); }

    /// Returns terminal device attributes that should be reported back to the remote side, if any.
    default Optional<DeviceAttributes> deviceAttributesValue() { return Optional.empty(); }
}
