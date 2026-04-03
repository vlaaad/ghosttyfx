package io.github.vlaaad.ghostty;

/// Standalone encoder for translating host key events into terminal byte sequences.
public interface KeyCodec {
    byte[] encode(KeyEvent event);
}
