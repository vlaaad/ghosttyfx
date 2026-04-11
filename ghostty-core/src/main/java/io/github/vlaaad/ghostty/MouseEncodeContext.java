package io.github.vlaaad.ghostty;

import java.util.Objects;

/// Mouse encode context record.
///
/// @param format mouse format
/// @param trackingMode mouse tracking mode
/// @param size renderer geometry used to map surface pixels to terminal coordinates
/// @param anyButtonPressed whether any button is pressed
/// @param trackLastCell whether to track last cell
public record MouseEncodeContext(
    MouseFormat format,
    MouseTrackingMode trackingMode,
    MouseEncoderSize size,
    boolean anyButtonPressed,
    boolean trackLastCell
) {
    public MouseEncodeContext {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(trackingMode, "trackingMode");
        Objects.requireNonNull(size, "size");
    }
}
