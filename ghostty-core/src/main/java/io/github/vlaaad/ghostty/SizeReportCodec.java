package io.github.vlaaad.ghostty;

/// Standalone encoder for terminal size-report replies.
public interface SizeReportCodec {
    byte[] encode(SizeReportRequest request, SizeReport size);
}
