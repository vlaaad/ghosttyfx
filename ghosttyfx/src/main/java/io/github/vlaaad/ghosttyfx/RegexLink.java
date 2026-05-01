package io.github.vlaaad.ghosttyfx;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public record RegexLink(Pattern pattern, Consumer<MatchResult> action) {
    public RegexLink {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(action, "action");
    }
}
