package io.github.vlaaad.ghostty;

/// Standalone encoder for translating host mouse events into terminal byte sequences.
public interface MouseCodec {
    byte[] encode(MouseEvent event, MouseEncodeContext context);
}
