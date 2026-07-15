package io.github.vlaaad.ghosttyfx;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

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
    /// Pixel dimensions are provided so that implementations backed by a PTY
    /// can set the `ws_xpixel` and `ws_ypixel` fields of `TIOCGWINSZ`,
    /// which applications like `kitten icat` rely on to detect pixel-size
    /// reporting support. Implementations that do not need pixel sizes may
    /// ignore `widthPx` and `heightPx`.
    ///
    /// @param columns the terminal width, measured in character cells
    /// @param rows the terminal height, measured in character cells
    /// @param widthPx the terminal width in pixels (0 if unknown)
    /// @param heightPx the terminal height in pixels (0 if unknown)
    /// @throws Exception if the backend cannot be resized
    void resize(int columns, int rows, int widthPx, int heightPx) throws Exception;

    /// Closes the terminal and releases its associated resources.
    ///
    /// Implementations should stop the process or backend they own and close any
    /// streams returned by this terminal.
    ///
    /// @throws Exception if the terminal cannot be closed cleanly
    @Override
    void close() throws Exception;

    /// Sets `ws_xpixel` and `ws_ypixel` on a PTY file descriptor via `ioctl(TIOCSWINSZ)`.
    ///
    /// pty4j's `WinSize` discards pixel dimensions, so this must be called
    /// separately after `setWinSize` to make applications like `kitten icat`
    /// detect pixel-size reporting support through `TIOCGWINSZ`.
    ///
    /// @param fd the PTY master file descriptor
    /// @param columns terminal width in character cells
    /// @param rows terminal height in character cells
    /// @param widthPx terminal width in pixels
    /// @param heightPx terminal height in pixels
    static void setPtyPixelSize(int fd, int columns, int rows, int widthPx, int heightPx) {
        try (var arena = Arena.ofConfined()) {
            var ws = arena.allocate(WINSIZE_LAYOUT);
            ws.set(ValueLayout.JAVA_SHORT, 0, (short) rows);
            ws.set(ValueLayout.JAVA_SHORT, 2, (short) columns);
            ws.set(ValueLayout.JAVA_SHORT, 4, (short) widthPx);
            ws.set(ValueLayout.JAVA_SHORT, 6, (short) heightPx);
            IOCTL.invoke(fd, TIOCSWINSZ, ws);
        } catch (Throwable e) {
            System.err.println("setPtyPixelSize ioctl failed: " + e.getMessage());
        }
    }

    /// @hidden
    MemoryLayout WINSIZE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("ws_row"),
            ValueLayout.JAVA_SHORT.withName("ws_col"),
            ValueLayout.JAVA_SHORT.withName("ws_xpixel"),
            ValueLayout.JAVA_SHORT.withName("ws_ypixel")
    );
    /// @hidden
    long TIOCSWINSZ = System.getProperty("os.name").toLowerCase().contains("linux")
            ? 0x5414L : 0x80087467L;
    /// @hidden
    MethodHandle IOCTL = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            Linker.Option.firstVariadicArg(2));
}
