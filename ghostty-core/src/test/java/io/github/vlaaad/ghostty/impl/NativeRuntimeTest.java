package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.Key;
import io.github.vlaaad.ghostty.KeyAction;
import io.github.vlaaad.ghostty.KeyCodec;
import io.github.vlaaad.ghostty.KeyCodecConfig;
import io.github.vlaaad.ghostty.KeyEvent;
import io.github.vlaaad.ghostty.KeyModifiers;
import io.github.vlaaad.ghostty.TypeSchema;
import java.util.Arrays;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class NativeRuntimeTest {
    private static final KeyModifiers NO_MODS = new KeyModifiers(false, false, false, false, false, false, null, null, null, null);
    private static final KeyModifiers CTRL = new KeyModifiers(false, true, false, false, false, false, null, null, null, null);
    private static final KeyModifiers ALT = new KeyModifiers(false, false, true, false, false, false, null, null, null, null);
    private static SupportedPlatform platform;
    private static Provider provider;

    @BeforeAll
    static void requiresSupportedPlatform() {
        platform = SupportedPlatform.current();
        assumeTrue(platform != null);
        Providers.resetForTests();
        provider = Providers.provider();
    }

    @Test
    void serviceLoaderExposesHandwrittenProviders() {
        Set<String> ids = ServiceLoader.load(Provider.class).stream()
            .map(ServiceLoader.Provider::get)
            .map(Provider::id)
            .collect(Collectors.toSet());

        assertTrue(ids.containsAll(
            Arrays.stream(SupportedPlatform.values()).map(SupportedPlatform::id).collect(Collectors.toSet())
        ));
    }

    @Test
    void currentPlatformNativeLibraryIsOnClasspath() {
        assertNotNull(
            NativeRuntimeTest.class.getClassLoader()
                .getResource("native/" + platform.id() + "/" + platform.libraryFileName())
        );
    }

    @Test
    void exposesBuildInfo() {
        BuildInfo buildInfo = provider.buildInfo();

        assertEquals(platform.id(), provider.id());
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
        TypeSchema schema = provider.typeSchema();

        assertFalse(schema.json().isBlank());
        assertTrue(schema.json().startsWith("{"));
        assertTrue(schema.json().contains("\"GhosttyPoint\""));
    }

    @Test
    void encodesCtrlC() {
        KeyCodec codec = provider.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[] {0x03},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.C, CTRL, NO_MODS, false, null, 'c'))
        );
    }

    @Test
    void encodesArrowUpInNormalAndApplicationModes() {
        KeyEvent event = new KeyEvent(KeyAction.PRESS, Key.ARROW_UP, NO_MODS, NO_MODS, false, null, 0);

        KeyCodec normal = provider.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));
        KeyCodec application = provider.keyCodec(new KeyCodecConfig(true, false, false, false, false, null, null));

        assertArrayEquals(new byte[] {0x1b, '[', 'A'}, normal.encode(event));
        assertArrayEquals(new byte[] {0x1b, 'O', 'A'}, application.encode(event));
    }

    @Test
    void encodesAltWithEscapePrefix() {
        KeyCodec codec = provider.keyCodec(new KeyCodecConfig(false, false, false, true, false, null, null));

        assertArrayEquals(
            new byte[] {0x1b, 'a'},
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.A, ALT, NO_MODS, false, "a", 'a'))
        );
    }

    @Test
    void modifierKeyPressProducesNoBytes() {
        KeyCodec codec = provider.keyCodec(new KeyCodecConfig(false, false, false, false, false, null, null));

        assertArrayEquals(
            new byte[0],
            codec.encode(new KeyEvent(KeyAction.PRESS, Key.SHIFT_LEFT, NO_MODS, NO_MODS, false, null, 0))
        );
    }
}
