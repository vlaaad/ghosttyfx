package io.github.vlaaad.ghosttyfx;

import java.util.regex.MatchResult;

/// A link detected in terminal output.
public sealed interface TerminalLink {
    /// An OSC 8 hyperlink with an explicit URI.
    record Osc8(String target) implements TerminalLink {}

    /// A regex-matched link from a [TerminalLinkMatcher].
    record Regex(TerminalLinkMatcher matcher, MatchResult match) implements TerminalLink {}
}
