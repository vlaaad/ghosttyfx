package io.github.vlaaad.ghostty;

/// Standalone encoder for DECRPM terminal mode reports.
public interface ModeReportCodec {
    byte[] encode(TerminalMode mode, ModeReportState state);
}
