package io.github.vlaaad.ghostty;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GhosttyMetadataTest {
    @BeforeAll
    static void requiresSupportedRuntime() {
        GhosttyTestSupport.assumeRuntimeAvailable();
    }

    @Test
    void exposesBuildMetadata() {
        var buildInfo = Ghostty.buildInfo();

        assertFalse(buildInfo.version().isBlank());
        assertTrue(buildInfo.major() >= 0);
        assertTrue(buildInfo.minor() >= 0);
        assertTrue(buildInfo.patch() >= 0);
        assertNotNull(buildInfo.build());
        assertNotNull(buildInfo.optimize());
        assertNotNull(buildInfo.features());
    }

    @Test
    void exposesTypeSchemaJson() {
        var schema = Ghostty.typeSchema();

        assertFalse(schema.json().isBlank());
        assertTrue(schema.json().startsWith("{"));
        assertTrue(schema.json().contains("\"GhosttyPoint\""));
    }

    @Test
    void returnsStableMetadataAcrossCalls() {
        assertEquals(Ghostty.buildInfo(), Ghostty.buildInfo());
        assertEquals(Ghostty.typeSchema(), Ghostty.typeSchema());
    }
}
