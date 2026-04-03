package io.github.vlaaad.ghostty;

/**
 * Size report codec interface.
 */
public interface SizeReportCodec {
    byte[] encode(SizeReportRequest request, SizeReport size);
}