package io.github.vlaaad.ghostty;

/**
 * Focus codec interface.
 */
public interface FocusCodec {
    byte[] encode(FocusEvent event);
}