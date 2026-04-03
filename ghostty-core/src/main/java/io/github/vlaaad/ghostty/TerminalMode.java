package io.github.vlaaad.ghostty;

/**
 * Terminal modes (DEC private modes).
 * Note: These correspond to packed 16-bit values from ghostty_mode_new().
 * Bit 15: 1 = ANSI mode, 0 = DEC private mode.
 * Bits 0-14: mode value.
 */
public enum TerminalMode {
    // ANSI Modes (bit 15 = 1)
    KAM(2, true),                        // Keyboard action mode
    INSERT(4, true),                     // Insert mode
    SRM(12, true),                       // Send/receive mode
    LINEFEED(20, true),                  // Linefeed/new line mode
    
    // DEC Private Modes (bit 15 = 0)
    DECCKM(1, false),                    // Cursor keys
    COLUMNS_132(3, false),               // 132/80 column mode
    SMOOTH_SCROLL(4, false),             // Slow scroll
    REVERSE_VIDEO(5, false),             // Reverse video
    ORIGIN(6, false),                    // Origin mode
    AUTO_WRAP(7, false),                 // Auto-wrap mode
    AUTO_REPEAT(8, false),               // Auto-repeat keys
    X10_MOUSE(9, false),                 // X10 mouse reporting
    CURSOR_BLINKING(12, false),           // Cursor blink
    CURSOR_VISIBLE(25, false),           // Cursor visible (DECTCEM)
    ENABLE_MODE_3(40, false),            // Allow 132 column mode
    REVERSE_WRAP(45, false),             // Reverse wrap
    ALT_SCREEN_LEGACY(47, false),        // Alternate screen (legacy)
    KEYPAD_KEYS(66, false),              // Application keypad
    LEFT_RIGHT_MARGIN(69, false),        // Left/right margin mode
    NORMAL_MOUSE(1000, false),           // Normal mouse tracking
    BUTTON_MOUSE(1002, false),           // Button-event mouse tracking
    ANY_MOUSE(1003, false),              // Any-event mouse tracking
    FOCUS_EVENT(1004, false),            // Focus in/out events
    UTF8_MOUSE(1005, false),             // UTF-8 mouse format
    SGR_MOUSE(1006, false),              // SGR mouse format
    ALT_SCROLL(1007, false),             // Alternate scroll mode
    URXVT_MOUSE(1015, false),            // URxvt mouse format
    SGR_PIXELS_MOUSE(1016, false),       // SGR-Pixels mouse format
    NUMLOCK_KEYPAD(1035, false),          // Ignore keypad with NumLock
    ALT_ESC_PREFIX(1036, false),         // Alt key sends ESC prefix
    ALT_SENDS_ESC(1039, false),          // Alt sends escape
    REVERSE_WRAP_EXT(1045, false),       // Extended reverse wrap
    ALT_SCREEN(1047, false),             // Alternate screen
    SAVE_CURSOR(1048, false),            // Save cursor (DECSC)
    ALT_SCREEN_SAVE(1049, false),        // Alt screen + save cursor + clear
    BRACKETED_PASTE(2004, false),        // Bracketed paste mode
    SYNC_OUTPUT(2026, false),            // Synchronized output
    GRAPHEME_CLUSTER(2027, false),       // Grapheme cluster mode
    COLOR_SCHEME_REPORT(2031, false),    // Report color scheme
    IN_BAND_RESIZE(2048, false);         // In-band size reports
    
    private final int value;
    private final boolean isAnsi;
    
    TerminalMode(int value, boolean isAnsi) {
        this.value = value;
        this.isAnsi = isAnsi;
    }
    
    /** Get the packed 16-bit value for native calls */
    public short packedValue() {
        return (short)((value & 0x7FFF) | (isAnsi ? 0x8000 : 0));
    }
}