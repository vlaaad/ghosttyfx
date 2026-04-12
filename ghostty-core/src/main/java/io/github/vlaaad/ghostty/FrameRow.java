package io.github.vlaaad.ghostty;

import java.util.Objects;

/// Detached render row for a frame viewport.
public final class FrameRow {
    private static final CellWidth[] CELL_WIDTHS = CellWidth.values();

    private final int index;
    private final boolean dirty;
    private final RowFlags flags;
    private final String[] text;
    private final byte[] widths;
    private final int[] styleIds;

    public FrameRow(
        int index,
        boolean dirty,
        RowFlags flags,
        String[] text,
        byte[] widths,
        int[] styleIds
    ) {
        this(index, dirty, flags, text, widths, styleIds, false);
    }

    private FrameRow(
        int index,
        boolean dirty,
        RowFlags flags,
        String[] text,
        byte[] widths,
        int[] styleIds,
        boolean trusted
    ) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        this.index = index;
        this.dirty = dirty;
        this.flags = Objects.requireNonNull(flags, "flags");
        this.text = trusted ? Objects.requireNonNull(text, "text") : Objects.requireNonNull(text, "text").clone();
        this.widths = trusted ? Objects.requireNonNull(widths, "widths") : Objects.requireNonNull(widths, "widths").clone();
        this.styleIds = trusted ? Objects.requireNonNull(styleIds, "styleIds") : Objects.requireNonNull(styleIds, "styleIds").clone();
        if (this.text.length != this.widths.length || this.text.length != this.styleIds.length) {
            throw new IllegalArgumentException("text, widths, and styleIds must have the same length");
        }
    }

    public int index() {
        return index;
    }

    public boolean dirty() {
        return dirty;
    }

    public RowFlags flags() {
        return flags;
    }

    public int columns() {
        return text.length;
    }

    public String text(int column) {
        return text[columnIndex(column)];
    }

    public CellWidth width(int column) {
        return CELL_WIDTHS[Byte.toUnsignedInt(widths[columnIndex(column)])];
    }

    public int styleId(int column) {
        return styleIds[columnIndex(column)];
    }

    public FrameRow withDirty(boolean dirty) {
        return dirty == this.dirty
            ? this
            : new FrameRow(
                index,
                dirty,
                new RowFlags(
                    flags.wrapped(),
                    flags.wrapContinuation(),
                    flags.grapheme(),
                    flags.styled(),
                    flags.hyperlink(),
                    flags.kittyVirtualPlaceholder(),
                    dirty
                ),
                text,
                widths,
                styleIds,
                true
            );
    }

    private int columnIndex(int column) {
        return Objects.checkIndex(column, text.length);
    }
}
