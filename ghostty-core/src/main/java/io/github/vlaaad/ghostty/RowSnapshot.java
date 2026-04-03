package io.github.vlaaad.ghostty;

/// Immutable snapshot of a terminal row.
///
/// @param index row index
/// @param flags row flags
/// @param semanticPrompt semantic prompt information
/// @param cells cells in this row
public record RowSnapshot(
    long index,
    RowFlags flags,
    RowSemanticPrompt semanticPrompt,
    java.util.List<CellSnapshot> cells
) {}
