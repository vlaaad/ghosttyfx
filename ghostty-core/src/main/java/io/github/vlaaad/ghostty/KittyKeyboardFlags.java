package io.github.vlaaad.ghostty;

/// Kitty keyboard flags.
///
/// @param disambiguate whether to disambiguate escape sequences
/// @param reportEvents whether to report events
/// @param reportAlternates whether to report alternate keys
/// @param reportAll whether to report all keys
/// @param reportAssociated whether to report associated keys
public record KittyKeyboardFlags(
    boolean disambiguate,
    boolean reportEvents,
    boolean reportAlternates,
    boolean reportAll,
    boolean reportAssociated
) {}
