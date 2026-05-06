package io.github.vlaaad.ghosttyfx;

import java.io.InputStream;
import java.io.OutputStream;

/// A running terminal process connected to the terminal view.
///
/// The terminal exposes byte streams from the perspective of the UI: bytes read
/// from [#output()] are terminal output to render, and bytes written to
/// [#input()] are user input sent to the terminal.
public interface Terminal extends AutoCloseable {

    /// The terminal view reads this stream and writes the received bytes to the
    /// terminal emulator.
    ///
    /// @return the stream that produces terminal output
    /// @throws Exception if the output stream cannot be opened
    InputStream output() throws Exception;

    /// The terminal view writes encoded keyboard input, paste data, and other
    /// terminal input bytes to this stream.
    ///
    /// @return the stream that accepts terminal input
    /// @throws Exception if the input stream cannot be opened
    OutputStream input() throws Exception;

    /// Resizes the terminal's pseudoterminal or equivalent backend.
    ///
    /// @param columns the terminal width, measured in character cells
    /// @param rows the terminal height, measured in character cells
    /// @throws Exception if the backend cannot be resized
    void resize(int columns, int rows) throws Exception;

    /// Closes the terminal and releases its associated resources.
    ///
    /// Implementations should stop the process or backend they own and close any
    /// streams returned by this terminal.
    ///
    /// @throws Exception if the terminal cannot be closed cleanly
    @Override
    void close() throws Exception;
}
