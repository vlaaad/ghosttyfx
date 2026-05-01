package io.github.vlaaad.ghosttyfx;

record HostPlatform(OS os, Arch arch) {
    static final HostPlatform CURRENT = detect();

    private static HostPlatform detect() {
        var osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        var os = osName.contains("mac") || osName.contains("darwin")
                ? OS.MACOS
                : osName.contains("win")
                        ? OS.WINDOWS
                        : OS.LINUX;
        var archName = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        var arch = switch (archName) {
            case "x8664", "amd64", "x86_64" -> Arch.X86_64;
            case "aarch64", "arm64" -> Arch.AARCH64;
            default -> throw new IllegalStateException("Unsupported os.arch: " + archName);
        };
        return new HostPlatform(os, arch);
    }

    enum OS {
        WINDOWS,
        MACOS,
        LINUX
    }

    enum Arch {
        X86_64,
        AARCH64
    }
}
