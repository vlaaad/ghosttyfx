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

    public KittyKeyboardFlags(
        boolean disambiguateEscapes,
        boolean reportEventTypes,
        boolean reportAlternateKeys,
        boolean reportAllKeys,
        boolean reportAssociatedKeys
    ) {
        this(packFlags(disambiguateEscapes, reportEventTypes, reportAlternateKeys, reportAllKeys, reportAssociatedKeys));
    }

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

    private static int packFlags(
        boolean disambiguateEscapes,
        boolean reportEventTypes,
        boolean reportAlternateKeys,
        boolean reportAllKeys,
        boolean reportAssociatedKeys
    ) {
        var flags = 0;
        if (disambiguateEscapes) {
            flags |= FLAG_DISAMBIGUATE_ESCAPES;
        }
        if (reportEventTypes) {
            flags |= FLAG_REPORT_EVENT_TYPES;
        }
        if (reportAlternateKeys) {
            flags |= FLAG_REPORT_ALTERNATE_KEYS;
        }
        if (reportAllKeys) {
            flags |= FLAG_REPORT_ALL_KEYS;
        }
        if (reportAssociatedKeys) {
            flags |= FLAG_REPORT_ASSOCIATED_KEYS;
        }
        return flags;
    }
}
