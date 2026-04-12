package io.github.vlaaad.ghostty;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class FocusCodecTest {
    @Test
    void encodesFocusGained() {
        var codec = Ghostty.focusCodec();

        assertArrayEquals(bytes("\u001B[I"), codec.encode(new FocusEvent(FocusAction.GAINED)));
    }

    @Test
    void encodesFocusLost() {
        var codec = Ghostty.focusCodec();

        assertArrayEquals(bytes("\u001B[O"), codec.encode(new FocusEvent(FocusAction.LOST)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
