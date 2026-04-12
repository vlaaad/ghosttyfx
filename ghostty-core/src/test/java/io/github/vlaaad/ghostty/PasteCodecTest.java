package io.github.vlaaad.ghostty;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PasteCodecTest {
    @Test
    void detectsUnsafePastePayloads() {
        var codec = Ghostty.pasteCodec();

        assertTrue(codec.isSafe("hello world"));
        assertFalse(codec.isSafe("hello\nworld"));
        assertFalse(codec.isSafe("hello\u001B[201~world"));
    }

    @Test
    void encodesBracketedPasteFromStringInput() {
        var codec = Ghostty.pasteCodec();

        assertArrayEquals(
            bytes("\u001B[200~hel lo world\u001B[201~"),
            codec.encode("hel\u001Blo\u0000world", true)
        );
    }

    @Test
    void encodesUnbracketedPasteByReplacingNewlines() {
        var codec = Ghostty.pasteCodec();

        assertArrayEquals(
            bytes("hello\r\rworld\r"),
            codec.encode("hello\r\nworld\n", false)
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
