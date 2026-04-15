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
}
