package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.BuildFeature;
import io.github.vlaaad.ghostty.BuildOptimize;
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
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");

            Provider provider = Providers.provider();
            assertEquals("windows-x86_64", provider.id());
            assertSame(provider, Providers.provider());
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
            return "windows-x86_64";
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
