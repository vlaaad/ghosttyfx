package io.github.vlaaad.ghostty;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class SizeReportCodecTest {
    private static final TerminalSize SIZE = new TerminalSize(80, 24, 9, 18);

    @Test
    void encodesMode2048Report() {
        var codec = Ghostty.sizeReportCodec();

        assertArrayEquals(
            bytes("\u001B[48;24;80;432;720t"),
            codec.encode(SizeReportStyle.MODE_2048, SIZE)
        );
    }

    @Test
    void encodesCsi14PixelReport() {
        var codec = Ghostty.sizeReportCodec();

        assertArrayEquals(
            bytes("\u001B[4;432;720t"),
            codec.encode(SizeReportStyle.CSI_14_T, SIZE)
        );
    }

    @Test
    void encodesCsi16CellPixelReport() {
        var codec = Ghostty.sizeReportCodec();

        assertArrayEquals(
            bytes("\u001B[6;18;9t"),
            codec.encode(SizeReportStyle.CSI_16_T, SIZE)
        );
    }

    @Test
    void encodesCsi18CharacterReport() {
        var codec = Ghostty.sizeReportCodec();

        assertArrayEquals(
            bytes("\u001B[8;24;80t"),
            codec.encode(SizeReportStyle.CSI_18_T, SIZE)
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
