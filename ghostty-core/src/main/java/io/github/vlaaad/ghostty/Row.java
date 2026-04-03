package io.github.vlaaad.ghostty;

/// Immutable terminal row.
///
/// @param index row index
/// @param flags row flags
/// @param semanticPrompt semantic prompt information
/// @param cells cells in this row
public record Row(
    long index,
    RowFlags flags,
    RowSemanticPrompt semanticPrompt,
    java.util.List<Cell> cells
) {}
