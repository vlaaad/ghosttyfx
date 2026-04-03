package io.github.vlaaad.ghostty;

/**
 * Key codec interface for encoding key events.
 */
public interface KeyCodec {
    byte[] encode(KeyEvent event);
}