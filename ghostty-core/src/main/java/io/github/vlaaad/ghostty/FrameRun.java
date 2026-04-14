package io.github.vlaaad.ghostty;

import java.util.Objects;

/// Detached render run for a frame row.
public record FrameRun(String text, int styleId, int columns) {
    public FrameRun {
        text = Objects.requireNonNull(text, "text");
        if (styleId < 0) {
            throw new IllegalArgumentException("styleId must be non-negative");
        }
        if (columns < 0) {
            throw new IllegalArgumentException("columns must be non-negative");
        }
    }
}
