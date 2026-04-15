package io.github.vlaaad.ghostty;

/// Row flags.
public record RowFlags(int flags) {
    private static final int FLAG_WRAPPED = 1;
    private static final int FLAG_WRAP_CONTINUATION = 1 << 1;
    private static final int FLAG_GRAPHEME_CLUSTERS = 1 << 2;
    private static final int FLAG_STYLED_CELLS = 1 << 3;
    private static final int FLAG_HYPERLINKS = 1 << 4;
    private static final int FLAG_KITTY_VIRTUAL_PLACEHOLDERS = 1 << 5;
    private static final int FLAG_DIRTY = 1 << 6;

    public RowFlags(
        boolean wrapped,
        boolean wrapContinuation,
        boolean graphemeClusters,
        boolean styledCells,
        boolean hyperlinks,
        boolean kittyVirtualPlaceholders,
        boolean dirty
    ) {
        this(packFlags(wrapped, wrapContinuation, graphemeClusters, styledCells, hyperlinks, kittyVirtualPlaceholders, dirty));
    }

    public boolean isWrapped() {
        return hasFlag(FLAG_WRAPPED);
    }

    public boolean isWrapContinuation() {
        return hasFlag(FLAG_WRAP_CONTINUATION);
    }

    public boolean hasGraphemeClusters() {
        return hasFlag(FLAG_GRAPHEME_CLUSTERS);
    }

    public boolean hasStyledCells() {
        return hasFlag(FLAG_STYLED_CELLS);
    }

    public boolean hasHyperlinks() {
        return hasFlag(FLAG_HYPERLINKS);
    }

    public boolean hasKittyVirtualPlaceholders() {
        return hasFlag(FLAG_KITTY_VIRTUAL_PLACEHOLDERS);
    }

    public boolean isDirty() {
        return hasFlag(FLAG_DIRTY);
    }

    public RowFlags withDirty(boolean dirty) {
        var updatedFlags = dirty ? flags | FLAG_DIRTY : flags & ~FLAG_DIRTY;
        return updatedFlags == flags ? this : new RowFlags(updatedFlags);
    }

    private boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    private static int packFlags(
        boolean wrapped,
        boolean wrapContinuation,
        boolean graphemeClusters,
        boolean styledCells,
        boolean hyperlinks,
        boolean kittyVirtualPlaceholders,
        boolean dirty
    ) {
        var flags = 0;
        if (wrapped) {
            flags |= FLAG_WRAPPED;
        }
        if (wrapContinuation) {
            flags |= FLAG_WRAP_CONTINUATION;
        }
        if (graphemeClusters) {
            flags |= FLAG_GRAPHEME_CLUSTERS;
        }
        if (styledCells) {
            flags |= FLAG_STYLED_CELLS;
        }
        if (hyperlinks) {
            flags |= FLAG_HYPERLINKS;
        }
        if (kittyVirtualPlaceholders) {
            flags |= FLAG_KITTY_VIRTUAL_PLACEHOLDERS;
        }
        if (dirty) {
            flags |= FLAG_DIRTY;
        }
        return flags;
    }
}
