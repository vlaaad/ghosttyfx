package io.github.vlaaad.ghostty;

import java.util.Objects;

/// Detached render run for a frame row.
public record FrameRun(String text, FrameStyle style, int columns) {
    public FrameRun {
        text = Objects.requireNonNull(text, "text");
        style = Objects.requireNonNull(style, "style");
        if (columns < 0) {
            throw new IllegalArgumentException("columns must be non-negative");
        }
    }
}
