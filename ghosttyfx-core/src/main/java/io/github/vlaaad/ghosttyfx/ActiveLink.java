package io.github.vlaaad.ghosttyfx;

import java.util.Objects;
import java.util.regex.MatchResult;

record ActiveLink(TerminalLink terminalLink, Selection selection, Runnable action) {
    boolean sameTarget(ActiveLink other) {
        if (other == null || !selection.equals(other.selection)) {
            return false;
        }
        return switch (terminalLink) {
            case TerminalLink.Osc8(var target) ->
                    other.terminalLink instanceof TerminalLink.Osc8(var otherTarget)
                            && target.equals(otherTarget);
            case TerminalLink.Regex(var matcher, var match) ->
                    other.terminalLink instanceof TerminalLink.Regex(var otherMatcher, var otherMatch)
                            && matcher.equals(otherMatcher)
                            && sameMatch(match, otherMatch);
        };
    }

    private static boolean sameMatch(MatchResult match, MatchResult other) {
        if (match.groupCount() != other.groupCount()) {
            return false;
        }
        for (var i = 0; i <= match.groupCount(); i++) {
            if (match.start(i) != other.start(i)
                    || match.end(i) != other.end(i)
                    || !Objects.equals(match.group(i), other.group(i))) {
                return false;
            }
        }
        return true;
    }
}
