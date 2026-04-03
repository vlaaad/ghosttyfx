package io.github.vlaaad.ghostty;

/// Reported state of a terminal mode in a DECRPM response.
public enum ModeReportState {
    NOT_RECOGNIZED,    // Mode is not recognized
    SET,               // Mode is set (enabled)
    RESET,             // Mode is reset (disabled)
    PERMANENTLY_SET,   // Mode is permanently set
    PERMANENTLY_RESET // Mode is permanently reset
}
