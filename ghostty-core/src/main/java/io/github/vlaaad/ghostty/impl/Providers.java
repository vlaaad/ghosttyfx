package io.github.vlaaad.ghostty.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

public final class Providers {
    private static final AtomicReference<Provider> PROVIDER = new AtomicReference<>();

    private Providers() {}

    static void resetForTests() {
        PROVIDER.set(null);
    }

    public static Provider provider() {
        Provider current = PROVIDER.get();
        if (current != null) {
            return current;
        }

        String normalizedOs = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String normalizedArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "");
        String id = (
            (normalizedOs.contains("win")
                ? "windows"
                : normalizedOs.contains("mac") || normalizedOs.contains("darwin")
                    ? "macos"
                    : normalizedOs.contains("linux")
                        ? "linux"
                        : normalizedOs.replaceAll("[^a-z0-9]+", ""))
                + "-"
                + switch (normalizedArch) {
                    case "x8664", "amd64", "x86_64" -> "x86_64";
                    case "aarch64", "arm64" -> "aarch64";
                    default -> normalizedArch;
                }
        );
        List<String> available = new ArrayList<>();
        Provider resolved = null;
        ServiceLoader<Provider> loader = ServiceLoader.load(Provider.class, Providers.class.getClassLoader());
        for (Provider provider : loader) {
            available.add(provider.id());
            if (provider.id().equals(id)) {
                resolved = provider;
                break;
            }
        }
        if (resolved == null) {
            throw new UnsupportedOperationException(
                "Native runtime is not available for platform '" + id + "'. Discovered platforms: " + available
            );
        }
        if (PROVIDER.compareAndSet(null, resolved)) {
            return resolved;
        }
        return PROVIDER.get();
    }
}
