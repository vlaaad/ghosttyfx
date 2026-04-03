package io.github.vlaaad.ghostty;

/// Content type of a cell.
public enum CellContentTag {
    CODEPOINT,             // A single codepoint (may be zero for empty)
    CODEPOINT_GRAPHEME,   // A codepoint that is part of a multi-codepoint grapheme cluster
    BG_COLOR_PALETTE,      // No text; background color from palette
    BG_COLOR_RGB           // No text; background color as RGB
}
