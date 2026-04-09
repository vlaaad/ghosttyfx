package io.github.vlaaad.ghostty.impl;

import java.util.ArrayList;
import java.util.List;
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

        String id = SupportedPlatform.normalizeId(
            System.getProperty("os.name", ""),
            System.getProperty("os.arch", "")
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
