package io.github.vlaaad.ghostty;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class GhosttyTestSupport {
    private GhosttyTestSupport() {
    }

    static void assumeRuntimeAvailable() {
        try {
            Ghostty.buildInfo();
        } catch (UnsupportedOperationException exception) {
            assumeTrue(false, exception.getMessage());
        }
    }
}
