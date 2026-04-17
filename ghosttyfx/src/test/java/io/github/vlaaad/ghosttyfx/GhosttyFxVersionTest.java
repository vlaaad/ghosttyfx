package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GhosttyFxVersionTest {
    @Test
    void returnsSemanticVersionCore() {
        var version = GhosttyFx.version();
        assertFalse(version.isBlank());
        var coreVersion = version.split("[-+]", 2)[0];
        assertTrue(coreVersion.matches("\\d+\\.\\d+\\.\\d+"), () -> "Unexpected Ghostty version: " + version);
    }
}
