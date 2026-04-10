package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.Ghostty;
import io.github.vlaaad.ghostty.Key;
import io.github.vlaaad.ghostty.KeyAction;
import io.github.vlaaad.ghostty.KeyCodec;
import io.github.vlaaad.ghostty.KeyCodecConfig;
import io.github.vlaaad.ghostty.KeyEvent;
import io.github.vlaaad.ghostty.KeyModifiers;
import io.github.vlaaad.ghostty.OptionAsAlt;
import io.github.vlaaad.ghostty.TypeSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class NativeRuntimeTest {
    private static final KeyModifiers NO_MODS = new KeyModifiers(false, false, false, false, false, false, null, null, null, null);
    private static final KeyModifiers CTRL = new KeyModifiers(false, true, false, false, false, false, null, null, null, null);
    private static final KeyModifiers ALT = new KeyModifiers(false, false, true, false, false, false, null, null, null, null);
    private static NativeRuntime runtime;

    @BeforeAll
    static void requiresSupportedRuntime() {
        NativeRuntime.resetForTests();
        assumeTrue(isCurrentPlatformSupported());
        runtime = NativeRuntime.current();
    }

    @Test
    void resolvesCurrentRuntimeOnce() {
        assertSame(runtime, NativeRuntime.current());
    }

    @Test
    void currentPlatformNativeLibraryIsOnClasspath() {
        assertNotNull(
            NativeRuntimeTest.class.getClassLoader()
                .getResource("native/" + runtime.id() + "/" + runtime.libraryFileName())
        );
    }

    @Test
    void exposesBuildInfo() {
        BuildInfo buildInfo = runtime.buildInfo();

        assertFalse(runtime.id().isBlank());
        assertFalse(buildInfo.version().isBlank());
        assertTrue(buildInfo.major() >= 0);
        assertTrue(buildInfo.minor() >= 0);
        assertTrue(buildInfo.patch() >= 0);
        assertNotNull(buildInfo.build());
        assertNotNull(buildInfo.optimize());
        assertNotNull(buildInfo.features());
    }

    @Test
    void exposesTypeSchema() {
        TypeSchema schema = runtime.typeSchema();

        assertFalse(schema.json().isBlank());
        assertTrue(schema.json().startsWith("{"));
        assertTrue(schema.json().contains("\"GhosttyPoint\""));
    }

    @Test
    void ghosttyMetadataMethodsStillUseCurrentRuntime() {
        assertEquals(runtime.buildInfo(), Ghostty.buildInfo());
        assertEquals(runtime.typeSchema(), Ghostty.typeSchema());
    }

    @Test
    void failureIncludesRequestedPlatform() {
        String previousOsName = System.getProperty("os.name");
        String previousOsArch = System.getProperty("os.arch");
        try {
            NativeRuntime.resetForTests();
            System.setProperty("os.name", "Plan 9");
            System.setProperty("os.arch", "mips64");

            UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                NativeRuntime::current
            );

            assertTrue(exception.getMessage().contains("plan9-mips64"));
            assertTrue(exception.getMessage().contains("windows-x86_64"));
        } finally {
            restoreProperty("os.name", previousOsName);
            restoreProperty("os.arch", previousOsArch);
            NativeRuntime.resetForTests();
            runtime = NativeRuntime.current();
        }
    }

    @Test
    void encodesCtrlC() {
        KeyCodec codec = runtime.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[] {0x03},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.C, CTRL, NO_MODS, false, null, 'c'))
        );
    }

    @Test
    void encodesArrowUpInNormalAndApplicationModes() {
        KeyEvent event = new KeyEvent(KeyAction.PRESS, Key.ARROW_UP, NO_MODS, NO_MODS, false, null, 0);

        KeyCodec normal = runtime.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));
        KeyCodec application = runtime.keyCodec(new KeyCodecConfig(true, false, false, false, false, null, null));

        assertArrayEquals(new byte[] {0x1b, '[', 'A'}, normal.encode(event));
        assertArrayEquals(new byte[] {0x1b, 'O', 'A'}, application.encode(event));
    }

    @Test
    void encodesAltWithEscapePrefix() {
        KeyCodec codec = runtime.keyCodec(new KeyCodecConfig(
            false,
            false,
            false,
            true,
            false,
            null,
            runtime.id().startsWith("macos-") ? OptionAsAlt.TRUE : null
        ));

        assertArrayEquals(
            new byte[] {0x1b, 'a'},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.A, ALT, NO_MODS, false, "a", 'a'))
        );
    }

    @Test
    void modifierKeyPressProducesNoBytes() {
        KeyCodec codec = runtime.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[0],
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.SHIFT_LEFT, NO_MODS, NO_MODS, false, null, 0))
        );
    }

    private static boolean isCurrentPlatformSupported() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        return (
            (osName.contains("linux") && (osArch.equals("amd64") || osArch.equals("x86_64")))
                || ((osName.contains("mac") || osName.contains("darwin")) && (osArch.equals("amd64") || osArch.equals("x86_64") || osArch.equals("aarch64") || osArch.equals("arm64")))
                || (osName.contains("win") && (osArch.equals("amd64") || osArch.equals("x86_64")))
        );
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }
}
