package io.github.vlaaad.ghostty.linux;

import io.github.vlaaad.ghostty.Key;
import io.github.vlaaad.ghostty.KeyAction;
import io.github.vlaaad.ghostty.KeyCodec;
import io.github.vlaaad.ghostty.KeyCodecConfig;
import io.github.vlaaad.ghostty.KeyEvent;
import io.github.vlaaad.ghostty.KeyModifiers;
import io.github.vlaaad.ghostty.linux.x86_64.Provider;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class LinuxKeyCodecTest {
    private static final Provider PROVIDER = new Provider();
    private static final KeyModifiers NO_MODS = new KeyModifiers(false, false, false, false, false, false, null, null, null, null);
    private static final KeyModifiers CTRL = new KeyModifiers(false, true, false, false, false, false, null, null, null, null);
    private static final KeyModifiers ALT = new KeyModifiers(false, false, true, false, false, false, null, null, null, null);

    @BeforeAll
    static void requiresLinuxX8664() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        assumeTrue(osName.contains("linux") && (osArch.equals("amd64") || osArch.equals("x86_64")));
    }

    @Test
    void encodesCtrlC() {
        KeyCodec codec = PROVIDER.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[] {0x03},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.C, CTRL, NO_MODS, false, null, 'c'))
        );
    }

    @Test
    void encodesArrowUpInNormalAndApplicationModes() {
        KeyEvent event = new KeyEvent(KeyAction.PRESS, Key.ARROW_UP, NO_MODS, NO_MODS, false, null, 0);

        KeyCodec normal = PROVIDER.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));
        KeyCodec application = PROVIDER.keyCodec(new KeyCodecConfig(true, false, false, false, false, null, null));

        assertArrayEquals(new byte[] {0x1b, '[', 'A'}, normal.encode(event));
        assertArrayEquals(new byte[] {0x1b, 'O', 'A'}, application.encode(event));
    }

    @Test
    void encodesAltWithEscapePrefix() {
        KeyCodec codec = PROVIDER.keyCodec(new KeyCodecConfig(false, false, false, true, false, null, null));

        assertArrayEquals(
            new byte[] {0x1b, 'a'},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.A, ALT, NO_MODS, false, "a", 'a'))
        );
    }

    @Test
    void modifierKeyPressProducesNoBytes() {
        KeyCodec codec = PROVIDER.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[0],
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.SHIFT_LEFT, NO_MODS, NO_MODS, false, null, 0))
        );
    }
}
