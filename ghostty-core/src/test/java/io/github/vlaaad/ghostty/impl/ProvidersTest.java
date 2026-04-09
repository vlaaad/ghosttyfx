package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.BuildFeature;
import io.github.vlaaad.ghostty.BuildOptimize;
import io.github.vlaaad.ghostty.FocusCodec;
import io.github.vlaaad.ghostty.Ghostty;
import io.github.vlaaad.ghostty.KeyCodec;
import io.github.vlaaad.ghostty.KeyCodecConfig;
import io.github.vlaaad.ghostty.MouseCodec;
import io.github.vlaaad.ghostty.MouseCodecConfig;
import io.github.vlaaad.ghostty.PasteCodec;
import io.github.vlaaad.ghostty.PtyWriter;
import io.github.vlaaad.ghostty.SizeReportCodec;
import io.github.vlaaad.ghostty.TerminalConfig;
import io.github.vlaaad.ghostty.TerminalEvents;
import io.github.vlaaad.ghostty.TerminalQueries;
import io.github.vlaaad.ghostty.TerminalSession;
import io.github.vlaaad.ghostty.TypeSchema;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProvidersTest {
    @Test
    void loadsMatchingProviderFromServiceLoader() {
        String previousOsName = System.getProperty("os.name");
        String previousOsArch = System.getProperty("os.arch");
        try {
            Providers.resetForTests();
            System.setProperty("os.name", "TestOS");
            System.setProperty("os.arch", "amd64");

            Provider provider = Providers.provider();
            assertEquals("testos-x86_64", provider.id());
            assertSame(provider, Providers.provider());
        } finally {
            restoreProperty("os.name", previousOsName);
            restoreProperty("os.arch", previousOsArch);
            Providers.resetForTests();
        }
    }

    @Test
    void ghosttyMetadataMethodsStillUseResolvedProvider() {
        String previousOsName = System.getProperty("os.name");
        String previousOsArch = System.getProperty("os.arch");
        try {
            Providers.resetForTests();
            System.setProperty("os.name", "TestOS");
            System.setProperty("os.arch", "amd64");

            Provider provider = Providers.provider();

            assertEquals(provider.buildInfo(), Ghostty.buildInfo());
            assertEquals(provider.typeSchema(), Ghostty.typeSchema());
        } finally {
            restoreProperty("os.name", previousOsName);
            restoreProperty("os.arch", previousOsArch);
            Providers.resetForTests();
        }
    }

    @Test
    void failureIncludesRequestedPlatform() {
        String previousOsName = System.getProperty("os.name");
        String previousOsArch = System.getProperty("os.arch");
        try {
            Providers.resetForTests();
            System.setProperty("os.name", "Plan 9");
            System.setProperty("os.arch", "mips64");

            UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                Providers::provider
            );

            assertTrue(exception.getMessage().contains("plan9-mips64"));
            assertTrue(exception.getMessage().contains("windows-x86_64"));
        } finally {
            restoreProperty("os.name", previousOsName);
            restoreProperty("os.arch", previousOsArch);
            Providers.resetForTests();
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    public static final class TestProvider implements Provider {
        @Override
        public String id() {
            return "testos-x86_64";
        }

        @Override
        public TerminalSession open(
            TerminalConfig config,
            PtyWriter ptyWriter,
            TerminalQueries queries,
            TerminalEvents events
        ) {
            throw new UnsupportedOperationException("Not used in this test provider");
        }

        @Override
        public KeyCodec keyCodec(KeyCodecConfig config) {
            throw new UnsupportedOperationException("Not used in this test provider");
        }

        @Override
        public MouseCodec mouseCodec(MouseCodecConfig config) {
            throw new UnsupportedOperationException("Not used in this test provider");
        }

        @Override
        public PasteCodec pasteCodec() {
            throw new UnsupportedOperationException("Not used in this test provider");
        }

        @Override
        public FocusCodec focusCodec() {
            throw new UnsupportedOperationException("Not used in this test provider");
        }

        @Override
        public SizeReportCodec sizeReportCodec() {
            throw new UnsupportedOperationException("Not used in this test provider");
        }

        @Override
        public BuildInfo buildInfo() {
            return new BuildInfo(
                "test-version",
                1,
                2,
                3,
                "4",
                BuildOptimize.RELEASE_FAST,
                Set.of(BuildFeature.SIMD, BuildFeature.KITTY_GRAPHICS)
            );
        }

        @Override
        public TypeSchema typeSchema() {
            return new TypeSchema("{}");
        }
    }
}
