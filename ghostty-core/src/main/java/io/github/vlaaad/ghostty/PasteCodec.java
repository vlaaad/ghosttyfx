package io.github.vlaaad.ghostty;

/**
 * Paste codec interface.
 */
public interface PasteCodec {
    boolean isSafe(byte[] data);
    byte[] encode(byte[] data, boolean bracketed);
}