package io.github.vlaaad.ghostty;

/// Output format for terminal size-report replies.
public enum SizeReportStyle {
    /// In-band size report (mode 2048): {@code ESC [ 48 ; rows ; cols ; height ; width t}.
    MODE_2048,
    /// XTWINOPS text area size in pixels: {@code ESC [ 4 ; height ; width t}.
    CSI_14_T,
    /// XTWINOPS cell size in pixels: {@code ESC [ 6 ; cellHeight ; cellWidth t}.
    CSI_16_T,
    /// XTWINOPS text area size in characters: {@code ESC [ 8 ; rows ; cols t}.
    CSI_18_T
}
