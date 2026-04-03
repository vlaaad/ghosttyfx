package io.github.vlaaad.ghostty;

/// Standalone encoder for terminal focus in/out events.
public interface FocusCodec {
    byte[] encode(FocusEvent event);
}
