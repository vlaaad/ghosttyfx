package io.github.vlaaad.ghosttyfx;

import java.util.Objects;
import java.util.regex.MatchResult;

/// A link detected in terminal output.
public sealed interface TerminalLink {
    /// An OSC 8 hyperlink with an explicit URI.
    record Osc8(String target) implements TerminalLink {
        public Osc8 {
            Objects.requireNonNull(target, "target");
        }
    }

    /// A regex-matched link from a [TerminalLinkMatcher].
    record Regex(TerminalLinkMatcher matcher, MatchResult match) implements TerminalLink {
        public Regex {
            Objects.requireNonNull(matcher, "matcher");
            Objects.requireNonNull(match, "match");
        }
    }
}
