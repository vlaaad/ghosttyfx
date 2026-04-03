package io.github.vlaaad.ghostty;

import java.util.Optional;

/**
 * Terminal session interface.
 * Provides methods for interacting with a terminal session.
 */
public interface TerminalSession extends AutoCloseable {
    TerminalConfig config();
    TerminalSnapshot snapshot();

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

    Optional<CellSnapshot> cell(Point point);
    Optional<RowSnapshot> row(long rowIndex, RowCoordinateSpace space);
    ScreenSnapshot screen(ScreenKind screen);
    Optional<DeviceAttributes> deviceAttributes();

    @Override
    void close();
}