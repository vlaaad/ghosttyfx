package io.github.vlaaad.ghostty;

/// Kitty keyboard flags.
///
/// @param flags packed kitty keyboard flag bits
public record KittyKeyboardFlags(int flags) {
    private static final int FLAG_DISAMBIGUATE_ESCAPES = 1;
    private static final int FLAG_REPORT_EVENT_TYPES = 1 << 1;
    private static final int FLAG_REPORT_ALTERNATE_KEYS = 1 << 2;
    private static final int FLAG_REPORT_ALL_KEYS = 1 << 3;
    private static final int FLAG_REPORT_ASSOCIATED_KEYS = 1 << 4;

    public boolean disambiguatesEscapes() {
        return hasFlag(FLAG_DISAMBIGUATE_ESCAPES);
    }

    public boolean reportsEventTypes() {
        return hasFlag(FLAG_REPORT_EVENT_TYPES);
    }

    public boolean reportsAlternateKeys() {
        return hasFlag(FLAG_REPORT_ALTERNATE_KEYS);
    }

    public boolean reportsAllKeys() {
        return hasFlag(FLAG_REPORT_ALL_KEYS);
    }

    public boolean reportsAssociatedKeys() {
        return hasFlag(FLAG_REPORT_ASSOCIATED_KEYS);
    }

    private boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }
}
