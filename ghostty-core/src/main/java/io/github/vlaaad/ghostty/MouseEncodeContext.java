package io.github.vlaaad.ghostty;

/// Mouse encode context record.
///
/// @param format mouse format
/// @param trackingMode mouse tracking mode
/// @param size terminal size
/// @param anyButtonPressed whether any button is pressed
/// @param trackLastCell whether to track last cell
public record MouseEncodeContext(
    MouseFormat format,
    MouseTrackingMode trackingMode,
    TerminalSize size,
    boolean anyButtonPressed,
    boolean trackLastCell
) {}
