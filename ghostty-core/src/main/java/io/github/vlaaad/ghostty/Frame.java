package io.github.vlaaad.ghostty;

import java.util.List;

/// Detached immutable render frame for the active terminal viewport.
///
/// Instances are safe to retain and share across threads. Each frame contains only detached
/// viewport data and never exposes live native references.
public record Frame(
    long revision,
    FrameDirty dirty,
    TerminalSize size,
    ScreenKind activeScreen,
    FrameCursor cursor,
    FrameColors colors,
    MouseTrackingMode mouseTracking,
    KittyKeyboardFlags kittyKeyboardFlags,
    TerminalScrollbar scrollbar,
    String title,
    String workingDirectory,
    List<FrameStyle> styles,
    List<FrameRow> rows
) {
    public Frame {
        styles = List.copyOf(styles);
        rows = List.copyOf(rows);
    }
}
