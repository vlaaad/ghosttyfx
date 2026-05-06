package io.github.vlaaad.ghosttyfx;

/// Creates terminal backends for [TerminalView].
///
/// [#open(int, int)] is called on a background thread, so implementations may
/// start a process or perform other blocking setup before returning the
/// connected [Terminal].
///
/// When launching an interactive shell, use [Shell#integrate(java.util.List, java.util.Map)]
/// to apply shell integration before starting the process.
@FunctionalInterface
public interface TerminalFactory {

    /// Opens a terminal backend with the requested initial size.
    ///
    /// @param columns the initial terminal width, measured in character cells
    /// @param rows the initial terminal height, measured in character cells
    /// @return the opened terminal backend
    /// @throws Exception if the backend cannot be opened
    Terminal open(int columns, int rows) throws Exception;
}
