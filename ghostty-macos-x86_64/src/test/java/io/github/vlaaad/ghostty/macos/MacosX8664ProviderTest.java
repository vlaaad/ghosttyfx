package io.github.vlaaad.ghostty.macos;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.TypeSchema;
import io.github.vlaaad.ghostty.macos.x86_64.Provider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MacosX8664ProviderTest {
    private static final Provider PROVIDER = new Provider();

    @BeforeAll
    static void requiresMacosX8664() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        assumeTrue(osName.contains("mac") && (osArch.equals("amd64") || osArch.equals("x86_64")));
    }

    @Test
    void exposesBuildInfo() {
        BuildInfo buildInfo = PROVIDER.buildInfo();

        assertEquals("macos-x86_64", PROVIDER.id());
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
        TypeSchema schema = PROVIDER.typeSchema();

        assertFalse(schema.json().isBlank());
        assertTrue(schema.json().startsWith("{"));
        assertTrue(schema.json().contains("\"GhosttyPoint\""));
    }
}
