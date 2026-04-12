package io.github.vlaaad.ghostty;

/// Standalone encoder for paste payloads and bracketed-paste framing.
public interface PasteCodec {
    boolean isSafe(String data);
    byte[] encode(String data, boolean bracketed);
}
