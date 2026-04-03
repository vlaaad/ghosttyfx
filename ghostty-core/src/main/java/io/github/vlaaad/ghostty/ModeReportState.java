package io.github.vlaaad.ghostty;

/**
 * Mode report states (from native bindings).
 * These are used for DECRPM (DEC Private Mode Report) responses.
 */
public enum ModeReportState {
    NOT_RECOGNIZED,    // Mode is not recognized
    SET,               // Mode is set (enabled)
    RESET,             // Mode is reset (disabled)
    PERMANENTLY_SET,   // Mode is permanently set
    PERMANENTLY_RESET // Mode is permanently reset
}
