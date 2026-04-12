package io.github.vlaaad.ghostty;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class ModeReportCodecTest {
    @Test
    void encodesDecModeReport() {
        var codec = Ghostty.modeReportCodec();

        assertArrayEquals(
            bytes("\u001B[?25;1$y"),
            codec.encode(TerminalMode.CURSOR_VISIBLE, ModeReportState.SET)
        );
    }

    @Test
    void encodesAnsiModeReport() {
        var codec = Ghostty.modeReportCodec();

        assertArrayEquals(
            bytes("\u001B[4;2$y"),
            codec.encode(TerminalMode.INSERT, ModeReportState.RESET)
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
