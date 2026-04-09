package io.github.vlaaad.ghostty.impl;

import java.lang.foreign.ValueLayout;
import java.util.Locale;

enum SupportedPlatform {
    LINUX_X86_64("linux-x86_64", "so", "unsigned long", ValueLayout.JAVA_LONG),
    MACOS_X86_64("macos-x86_64", "dylib", "unsigned long", ValueLayout.JAVA_LONG),
    MACOS_AARCH64("macos-aarch64", "dylib", "unsigned long", ValueLayout.JAVA_LONG),
    WINDOWS_X86_64("windows-x86_64", "dll", "unsigned long long", ValueLayout.JAVA_LONG);

    private final String id;
    private final String libraryExtension;
    private final String sizeTDeclaration;
    private final ValueLayout.OfLong sizeTLayout;

    SupportedPlatform(String id, String libraryExtension, String sizeTDeclaration, ValueLayout.OfLong sizeTLayout) {
        this.id = id;
        this.libraryExtension = libraryExtension;
        this.sizeTDeclaration = sizeTDeclaration;
        this.sizeTLayout = sizeTLayout;
    }

    String id() {
        return id;
    }

    String libraryFileName() {
        return "libghostty-vt-" + id + "." + libraryExtension;
    }

    ValueLayout.OfLong sizeTLayout() {
        return sizeTLayout;
    }

    String sizeTDeclaration() {
        return sizeTDeclaration;
    }

    static SupportedPlatform forId(String id) {
        for (SupportedPlatform platform : values()) {
            if (platform.id.equals(id)) {
                return platform;
            }
        }
        return null;
    }

    static SupportedPlatform current() {
        return forId(normalizeId(
            System.getProperty("os.name", ""),
            System.getProperty("os.arch", "")
        ));
    }

    static String normalizeId(String osName, String osArch) {
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        String normalizedArch = osArch.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "");
        return (
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
    }
}
