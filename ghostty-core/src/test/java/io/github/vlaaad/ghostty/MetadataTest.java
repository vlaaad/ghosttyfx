package io.github.vlaaad.ghostty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetadataTest {
    @Test
    void exposesBuildMetadata() {
        var buildInfo = Ghostty.buildInfo();

        assertFalse(buildInfo.version().isBlank());
        assertTrue(buildInfo.major() >= 0);
        assertTrue(buildInfo.minor() >= 0);
        assertTrue(buildInfo.patch() >= 0);
        assertNotNull(buildInfo.pre());
        assertNotNull(buildInfo.build());
        assertNotNull(buildInfo.optimize());
        assertNotNull(buildInfo.features());

        var expected = VersionMetadata.parse(buildInfo.version());
        assertEquals(expected.pre(), buildInfo.pre());
        assertEquals(expected.build(), buildInfo.build());
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

    private record VersionMetadata(String pre, String build) {
        static VersionMetadata parse(String version) {
            var plusIndex = version.indexOf('+');
            var build = plusIndex >= 0 ? version.substring(plusIndex + 1) : "";
            var withoutBuild = plusIndex >= 0 ? version.substring(0, plusIndex) : version;
            var dashIndex = withoutBuild.indexOf('-');
            var pre = dashIndex >= 0 ? withoutBuild.substring(dashIndex + 1) : "";
            return new VersionMetadata(pre, build);
        }
    }
}
