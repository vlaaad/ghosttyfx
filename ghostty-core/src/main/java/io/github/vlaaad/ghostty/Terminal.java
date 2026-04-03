package io.github.vlaaad.ghostty;

/// Detached immutable terminal state.
///
/// Instances of this record are safe to retain and share across threads. They are copies of the
/// terminal state at one point in time, not live views backed by native memory.
///
/// @param size terminal size
/// @param cursor cursor state
/// @param activeScreen active screen kind
/// @param cursorVisible whether cursor is visible
/// @param cursorPendingWrap whether cursor has pending wrap
/// @param mouseTracking current mouse tracking mode
/// @param kittyKeyboardFlags kitty keyboard flags
/// @param scrollbar scrollbar information
/// @param title terminal title
/// @param workingDirectory current working directory
/// @param totalRows total number of rows across visible and scrollback history
/// @param scrollbackRows number of rows currently retained in scrollback history
/// @param theme terminal theme
/// @param primary primary screen
/// @param alternate alternate screen
public record Terminal(
    TerminalSize size,
    Cursor cursor,
    ScreenKind activeScreen,
    boolean cursorVisible,
    boolean cursorPendingWrap,
    MouseTrackingMode mouseTracking,
    KittyKeyboardFlags kittyKeyboardFlags,
    TerminalScrollbar scrollbar,
    String title,
    String workingDirectory,
    long totalRows,
    long scrollbackRows,
    Theme theme,
    Screen primary,
    Screen alternate
) {}
