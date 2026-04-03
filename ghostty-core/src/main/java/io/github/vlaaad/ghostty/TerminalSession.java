package io.github.vlaaad.ghostty;

import java.util.Optional;

/// Thread-safe terminal session.
///
/// All public methods may be called from any thread. Implementations serialize interaction with
/// mutable native terminal state on an internal actor thread and return only immutable Java values.
/// Reads such as {@link #snapshot()}, {@link #cell(Point)}, {@link #row(long, RowCoordinateSpace)},
/// and {@link #screen(ScreenKind)} never expose live native references.
///
/// Notification callbacks delivered through {@link TerminalEvents} must not run on the internal
/// actor thread. They may safely call back into this session. In contrast, {@link TerminalQueries}
/// run synchronously while the terminal is waiting for an immediate answer, so those implementations
/// should avoid blocking and must not re-enter the session.
public interface TerminalSession extends AutoCloseable {
    TerminalConfig config();

    /// Returns a detached immutable view of the current terminal state.
    Terminal snapshot();

    void resize(TerminalSize size);
    void write(byte[] vt);
    void write(byte[] vt, int offset, int length);
    void reset();

    void setMode(TerminalMode mode, boolean enabled);
    Optional<Boolean> mode(TerminalMode mode);

    void setColorScheme(ColorScheme scheme);
    void setWindowTitle(String title);
    void setWorkingDirectory(String pwd);
    void setForeground(ColorValue color);
    void setBackground(ColorValue color);
    void setCursorColor(ColorValue color);
    void setPalette(ColorPalette palette);

    void scrollToTop();
    void scrollToBottom();
    void scrollBy(long delta);
    void scrollViewport(TerminalScrollViewport behavior);

    Optional<Cell> cell(Point point);
    Optional<Row> row(long rowIndex, RowCoordinateSpace space);
    Screen screen(ScreenKind screen);
    Optional<DeviceAttributes> deviceAttributes();

    @Override
    void close();
}
