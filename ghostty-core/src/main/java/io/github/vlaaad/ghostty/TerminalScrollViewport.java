package io.github.vlaaad.ghostty;

/// Scroll command targeting the session viewport.
///
/// The variants encode intent explicitly instead of mirroring native union tags.
public sealed interface TerminalScrollViewport permits
    TerminalScrollViewport.ScrollViewportTop,
    TerminalScrollViewport.ScrollViewportBottom,
    TerminalScrollViewport.ScrollViewportDelta {

    /// Scroll to the oldest retained row.
    record ScrollViewportTop() implements TerminalScrollViewport {}

    /// Scroll to the live bottom of the terminal.
    record ScrollViewportBottom() implements TerminalScrollViewport {}

    /// Scroll by a signed row delta.
    record ScrollViewportDelta(long delta) implements TerminalScrollViewport {}
}
