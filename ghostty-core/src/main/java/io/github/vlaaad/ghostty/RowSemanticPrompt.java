package io.github.vlaaad.ghostty;

/**
 * Semantic prompt state of a row.
 */
public enum RowSemanticPrompt {
    NONE,                    // No prompt cells in this row
    PROMPT,                 // Prompt cells exist and this is a primary prompt line
    PROMPT_CONTINUATION      // Prompt cells exist and this is a continuation line
}
