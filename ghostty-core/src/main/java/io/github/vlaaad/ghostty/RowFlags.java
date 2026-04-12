package io.github.vlaaad.ghostty;

/// Row flags.
public record RowFlags(
    boolean wrapped,
    boolean wrapContinuation,
    boolean grapheme,
    boolean styled,
    boolean hyperlink,
    boolean kittyVirtualPlaceholder,
    boolean dirty
) {}
