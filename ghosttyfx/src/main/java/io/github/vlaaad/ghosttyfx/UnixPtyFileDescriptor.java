package io.github.vlaaad.ghosttyfx;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/// Utilities for Unix pseudoterminal file descriptors.
public final class UnixPtyFileDescriptor {

    private UnixPtyFileDescriptor() {}

    /// Resizes a Unix pseudoterminal.
    ///
    /// @param fd the pseudoterminal master file descriptor
    /// @param columns the terminal width, measured in character cells
    /// @param rows the terminal height, measured in character cells
    /// @param widthPx the terminal width, measured in pixels
    /// @param heightPx the terminal height, measured in pixels
    /// @throws UnsupportedOperationException on Windows
    public static void resize(int fd, int columns, int rows, int widthPx, int heightPx) {
        if (HostPlatform.CURRENT.os() == HostPlatform.OS.WINDOWS) {
            throw new UnsupportedOperationException("Unix pseudoterminal file descriptors are not supported on Windows");
        }
        if (columns < 0 || columns > 0xFFFF
                || rows < 0 || rows > 0xFFFF
                || widthPx < 0 || widthPx > 0xFFFF
                || heightPx < 0 || heightPx > 0xFFFF) {
            throw new IllegalArgumentException("PTY dimensions must fit unsigned 16-bit values");
        }
        Unix.resize(fd, columns, rows, widthPx, heightPx);
    }

    private static final class Unix {
        private static final MemoryLayout WINSIZE_LAYOUT = MemoryLayout.structLayout(
                ValueLayout.JAVA_SHORT,
                ValueLayout.JAVA_SHORT,
                ValueLayout.JAVA_SHORT,
                ValueLayout.JAVA_SHORT);
        // TIOCSWINSZ from the Linux and Darwin system headers.
        private static final long TIOCSWINSZ = HostPlatform.CURRENT.os() == HostPlatform.OS.LINUX
                ? 0x5414L
                : 0x80087467L;
        private static final MethodHandle IOCTL = Linker.nativeLinker().downcallHandle(
                Linker.nativeLinker().defaultLookup().find("ioctl").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS),
                Linker.Option.firstVariadicArg(2));

        private Unix() {}

        private static void resize(int fd, int columns, int rows, int widthPx, int heightPx) {
            try (var arena = Arena.ofConfined()) {
                var winsize = arena.allocate(WINSIZE_LAYOUT);
                winsize.set(ValueLayout.JAVA_SHORT, 0, (short) rows);
                winsize.set(ValueLayout.JAVA_SHORT, 2, (short) columns);
                winsize.set(ValueLayout.JAVA_SHORT, 4, (short) widthPx);
                winsize.set(ValueLayout.JAVA_SHORT, 6, (short) heightPx);
                if ((int) IOCTL.invokeExact(fd, TIOCSWINSZ, winsize) != 0) {
                    throw new IllegalStateException("ioctl(TIOCSWINSZ) failed");
                }
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
        }
    }
}
