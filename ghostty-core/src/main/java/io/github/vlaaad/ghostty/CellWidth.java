package io.github.vlaaad.ghostty;

/**
 * Cell width property.
 */
public enum CellWidth {
    NARROW,         // Not a wide character, cell width 1
    WIDE,           // Wide character, cell width 2
    SPACER_TAIL,    // Spacer after wide character
    SPACER_HEAD     // Spacer at end of soft-wrapped line
}
