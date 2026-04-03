package io.github.vlaaad.ghostty;

/**
 * Immutable snapshot of terminal state.
 * 
 * @param size terminal size
 * @param cursor cursor snapshot
 * @param activeScreen active screen kind
 * @param cursorVisible whether cursor is visible
 * @param cursorPendingWrap whether cursor has pending wrap
 * @param mouseTracking current mouse tracking mode
 * @param kittyKeyboardFlags kitty keyboard flags
 * @param scrollbar scrollbar information
 * @param title terminal title
 * @param workingDirectory current working directory
 * @param totalRows total number of rows
 * @param scrollbackRows number of scrollback rows
 * @param theme theme snapshot
 * @param primary primary screen snapshot
 * @param alternate alternate screen snapshot
 */
public record TerminalSnapshot(
    TerminalSize size,
    CursorSnapshot cursor,
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
    ThemeSnapshot theme,
    ScreenSnapshot primary,
    ScreenSnapshot alternate
) {}
