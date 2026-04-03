package io.github.vlaaad.ghostty;

/**
 * Mouse tracking modes.
 */
public enum MouseTrackingMode {
    NONE,       // Mouse reporting disabled
    X10,        // X10 mouse mode
    NORMAL,     // Normal mouse mode (button press/release only)
    BUTTON,     // Button-event tracking mode
    ANY         // Any-event tracking mode
}
