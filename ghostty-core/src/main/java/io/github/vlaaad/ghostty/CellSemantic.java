package io.github.vlaaad.ghostty;

/// Semantic content type of a cell.
public enum CellSemantic {
    OUTPUT,        // Regular output content
    INPUT,         // Content that is part of user input
    PROMPT          // Content that is part of a shell prompt
}
