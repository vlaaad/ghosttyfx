package io.github.vlaaad.ghosttyfx;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/// Defines a terminal text pattern that can be activated like a link.
///
/// [#pattern()] is matched against terminal output text. When a match is
/// activated by the user, [#action()] receives the matched text.
///
/// @param pattern the pattern used to find handled terminal text
/// @param action the action to run when matched text is activated
public record TerminalLinkMatcher(Pattern pattern, Consumer<MatchResult> action) {
    public TerminalLinkMatcher {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(action, "action");
    }
}
