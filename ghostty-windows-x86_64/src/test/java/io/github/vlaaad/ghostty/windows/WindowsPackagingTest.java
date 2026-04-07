package io.github.vlaaad.ghostty.windows;

import io.github.vlaaad.ghostty.impl.Provider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WindowsPackagingTest {
    @Test
    void generatedBindingsAreCompiled() throws ClassNotFoundException {
        Class.forName("io.github.vlaaad.windows.x86_64.ghostty_vt_h");
    }

    @Test
    void nativeLibraryIsPackagedAsResource() {
        assertNotNull(
            WindowsPackagingTest.class.getClassLoader()
                .getResource("native/windows-x86_64/libghostty-vt-windows-x86_64.dll")
        );
    }

    @Test
    void platformIsPublishedViaServiceLoader() {
        assertTrue(
            ServiceLoader.load(Provider.class).stream()
                .anyMatch(provider -> provider.type().getName().equals(
                    "io.github.vlaaad.ghostty.windows.x86_64.Provider"
                ))
        );
    }
}
