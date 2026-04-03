package io.github.vlaaad.ghostty;

/// Row coordinate space enum.
public enum RowCoordinateSpace {
    ACTIVE,        // Active area where the cursor can move
    VIEWPORT,      // Visible viewport (changes when scrolled)
    SCREEN         // Full screen including scrollback
}