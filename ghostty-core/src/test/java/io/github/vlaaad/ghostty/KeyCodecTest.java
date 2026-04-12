package io.github.vlaaad.ghostty;

import java.util.Locale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class KeyCodecTest {
    private static final KeyModifiers NO_MODS = new KeyModifiers(false, false, false, false, false, false, null, null, null, null);
    private static final KeyModifiers CTRL = new KeyModifiers(false, true, false, false, false, false, null, null, null, null);
    private static final KeyModifiers ALT = new KeyModifiers(false, false, true, false, false, false, null, null, null, null);

    @Test
    void encodesControlCharacters() {
        var codec = Ghostty.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[] {0x03},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.C, CTRL, NO_MODS, false, null, 'c'))
        );
    }

    @Test
    void encodesCursorKeysAccordingToApplicationMode() {
        var event = new KeyEvent(KeyAction.PRESS, Key.ARROW_UP, NO_MODS, NO_MODS, false, null, 0);
        var normal = Ghostty.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));
        var application = Ghostty.keyCodec(new KeyCodecConfig(true, false, false, false, false, null, null));

        assertArrayEquals(new byte[] {0x1b, '[', 'A'}, normal.encode(event));
        assertArrayEquals(new byte[] {0x1b, 'O', 'A'}, application.encode(event));
    }

    @Test
    void encodesAltModifiedTextWithEscapePrefix() {
        var codec = Ghostty.keyCodec(new KeyCodecConfig(
            false,
            false,
            false,
            true,
            false,
            null,
            optionAsAltForCurrentPlatform()
        ));

        assertArrayEquals(
            new byte[] {0x1b, 'a'},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.A, ALT, NO_MODS, false, "a", 'a'))
        );
    }

    @Test
    void ignoresModifierOnlyPresses() {
        var codec = Ghostty.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[0],
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.SHIFT_LEFT, NO_MODS, NO_MODS, false, null, 0))
        );
    }

    private static OptionAsAlt optionAsAltForCurrentPlatform() {
        var osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac") || osName.contains("darwin") ? OptionAsAlt.TRUE : null;
    }
}
