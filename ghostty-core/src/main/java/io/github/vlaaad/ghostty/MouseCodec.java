package io.github.vlaaad.ghostty;

/// Standalone encoder for translating host mouse events into terminal byte sequences.
///
/// Mouse encoding can maintain motion deduplication state when
/// {@link MouseEncodeContext#trackLastCell()} is enabled.
public interface MouseCodec {
    /// Clears internal motion deduplication state.
    void reset();

    /// Encodes a mouse event using the supplied terminal reporting context.
    byte[] encode(MouseEvent event, MouseEncodeContext context);
}
