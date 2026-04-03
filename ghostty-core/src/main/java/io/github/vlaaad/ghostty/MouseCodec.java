package io.github.vlaaad.ghostty;

/**
 * Mouse codec interface for encoding mouse events.
 */
public interface MouseCodec {
    byte[] encode(MouseEvent event, MouseEncodeContext context);
}