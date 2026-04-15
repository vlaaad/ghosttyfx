package io.github.vlaaad.ghostty;

import java.util.List;
import java.util.Objects;

/// Detached render row for a frame viewport.
public final class FrameRow {
    private final int index;
    private final boolean dirty;
    private final RowFlags flags;
    private final List<FrameRun> runs;
    private final int columns;

    public FrameRow(
        int index,
        boolean dirty,
        RowFlags flags,
        List<FrameRun> runs
    ) {
        this(index, dirty, flags, runs, false);
    }

    private FrameRow(
        int index,
        boolean dirty,
        RowFlags flags,
        List<FrameRun> runs,
        boolean trusted
    ) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        this.index = index;
        this.dirty = dirty;
        this.flags = Objects.requireNonNull(flags, "flags");
        this.runs = trusted ? Objects.requireNonNull(runs, "runs") : List.copyOf(runs);
        var columns = 0;
        for (var run : this.runs) {
            columns += run.columns();
        }
        this.columns = columns;
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
        return columns;
    }

    public List<FrameRun> runs() {
        return runs;
    }

    public String text() {
        if (runs.isEmpty()) {
            return "";
        }
        var builder = new StringBuilder();
        for (var run : runs) {
            builder.append(run.text());
        }
        return builder.toString();
    }

    public FrameRow withDirty(boolean dirty) {
        return dirty == this.dirty
            ? this
            : new FrameRow(
                index,
                dirty,
                flags.withDirty(dirty),
                runs,
                true
            );
    }
}
