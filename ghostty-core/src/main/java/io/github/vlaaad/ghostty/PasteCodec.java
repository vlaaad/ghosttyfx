package io.github.vlaaad.ghostty;

/// Standalone encoder for paste payloads and bracketed-paste framing.
public interface PasteCodec {
    boolean isSafe(byte[] data);
    byte[] encode(byte[] data, boolean bracketed);
}
