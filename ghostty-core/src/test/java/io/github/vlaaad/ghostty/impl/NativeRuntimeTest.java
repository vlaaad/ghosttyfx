package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.Ghostty;
import io.github.vlaaad.ghostty.Key;
import io.github.vlaaad.ghostty.KeyAction;
import io.github.vlaaad.ghostty.KeyCodecConfig;
import io.github.vlaaad.ghostty.KeyEvent;
import io.github.vlaaad.ghostty.KeyModifiers;
import io.github.vlaaad.ghostty.OptionAsAlt;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
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
        try {
            runtime = NativeRuntime.instance();
        } catch (UnsupportedOperationException exception) {
            assumeTrue(false, exception.getMessage());
        }
    }

    @Test
    void resolvesCurrentRuntimeOnce() {
        assertSame(runtime, NativeRuntime.instance());
    }

    @Test
    void exposesBuildInfo() {
        var buildInfo = runtime.buildInfo();

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
        var schema = runtime.typeSchema();

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
    void statusCallTreatsNoValueAsNoValue() throws ReflectiveOperationException {
        var handle = MethodHandles.lookup().findStatic(
            NativeRuntimeTest.class,
            "returnNoValue",
            MethodType.methodType(int.class, int.class, MemorySegment.class)
        );

        var exception = assertThrows(
            ResultException.class,
            () -> NativeRuntime.callStatus(handle, "ghostty_key_encoder_encode", 0, MemorySegment.NULL)
        );

        assertEquals(-4, exception.result);
        assertEquals("ghostty_key_encoder_encode failed: no value", exception.getMessage());
    }

    @Test
    void encodesCtrlC() {
        var codec = runtime.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[] {0x03},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.C, CTRL, NO_MODS, false, null, 'c'))
        );
    }

    @Test
    void encodesArrowUpInNormalAndApplicationModes() {
        var event = new KeyEvent(KeyAction.PRESS, Key.ARROW_UP, NO_MODS, NO_MODS, false, null, 0);

        var normal = runtime.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));
        var application = runtime.keyCodec(new KeyCodecConfig(true, false, false, false, false, null, null));

        assertArrayEquals(new byte[] {0x1b, '[', 'A'}, normal.encode(event));
        assertArrayEquals(new byte[] {0x1b, 'O', 'A'}, application.encode(event));
    }

    @Test
    void encodesAltWithEscapePrefix() {
        var osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var codec = runtime.keyCodec(new KeyCodecConfig(
            false,
            false,
            false,
            true,
            false,
            null,
            osName.contains("mac") || osName.contains("darwin") ? OptionAsAlt.TRUE : null
        ));

        assertArrayEquals(
            new byte[] {0x1b, 'a'},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.A, ALT, NO_MODS, false, "a", 'a'))
        );
    }

    @Test
    void modifierKeyPressProducesNoBytes() {
        var codec = runtime.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[0],
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.SHIFT_LEFT, NO_MODS, NO_MODS, false, null, 0))
        );
    }

    private static int returnNoValue(int ignored, MemorySegment out) {
        return -4;
    }
}
