package io.github.vlaaad.ghostty;

/**
 * PTY writer interface for writing data to the terminal.
 */
public interface PtyWriter {
    void writePty(byte[] data);
}