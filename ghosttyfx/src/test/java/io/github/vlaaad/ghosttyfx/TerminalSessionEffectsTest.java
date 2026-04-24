package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributes;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributesPrimary;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributesSecondary;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributesTertiary;
import io.github.vlaaad.ghostty.bindings.GhosttySizeReportSize;
import io.github.vlaaad.ghostty.bindings.GhosttyString;
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class TerminalSessionEffectsTest {

    @Test
    void deliversBellAndTitleEffectsFromTerminalInput() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-effects-test-");
        var captureFile = tempDirectory.resolve("stdin.bin");
        var bells = new AtomicInteger();
        var title = new AtomicReference<String>();
        try (var ptySession = new PtySession(captureInputCommand(captureFile), tempDirectory, System.getenv(), 80, 24);
                var session = newSession(ptySession, title::set, bells::incrementAndGet)) {
            session.writeToTerminal("\u0007".getBytes(StandardCharsets.UTF_8));
            session.writeToTerminal("\u001B]2;ghosttyfx title\u001B\\".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(1, bells.get());
        assertEquals("ghosttyfx title", title.get());
    }

    @Test
    void reportsCurrentSizeEffectData() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-effects-size-test-");
        var captureFile = tempDirectory.resolve("stdin.bin");
        try (var ptySession = new PtySession(captureInputCommand(captureFile), tempDirectory, System.getenv(), 80, 24);
                var session = newSession(ptySession, _ -> {}, () -> {});
                var arena = Arena.ofConfined()) {
            var outSize = GhosttySizeReportSize.allocate(arena);
            var result = invoke(session, "reportSize", MemorySegment.NULL, MemorySegment.NULL, outSize);

            assertEquals(Boolean.TRUE, result);
            assertEquals(80, GhosttySizeReportSize.columns(outSize));
            assertEquals(24, GhosttySizeReportSize.rows(outSize));
            assertEquals(9, GhosttySizeReportSize.cell_width(outSize));
            assertEquals(18, GhosttySizeReportSize.cell_height(outSize));
        }
    }

    @Test
    void reportsDeviceAttributesEffectData() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-effects-device-test-");
        var captureFile = tempDirectory.resolve("stdin.bin");
        try (var ptySession = new PtySession(captureInputCommand(captureFile), tempDirectory, System.getenv(), 80, 24);
                var session = newSession(ptySession, _ -> {}, () -> {});
                var arena = Arena.ofConfined()) {
            var attributes = GhosttyDeviceAttributes.allocate(arena);
            var result = invoke(session, "reportDeviceAttributes", MemorySegment.NULL, MemorySegment.NULL, attributes);
            var primary = GhosttyDeviceAttributes.primary(attributes);
            var secondary = GhosttyDeviceAttributes.secondary(attributes);
            var tertiary = GhosttyDeviceAttributes.tertiary(attributes);

            assertEquals(Boolean.TRUE, result);
            assertEquals(ghostty_vt_h.GHOSTTY_DA_CONFORMANCE_VT220(), GhosttyDeviceAttributesPrimary.conformance_level(primary));
            assertEquals(3, GhosttyDeviceAttributesPrimary.num_features(primary));
            assertEquals(ghostty_vt_h.GHOSTTY_DA_FEATURE_COLUMNS_132(), GhosttyDeviceAttributesPrimary.features(primary, 0));
            assertEquals(ghostty_vt_h.GHOSTTY_DA_FEATURE_SELECTIVE_ERASE(), GhosttyDeviceAttributesPrimary.features(primary, 1));
            assertEquals(ghostty_vt_h.GHOSTTY_DA_FEATURE_ANSI_COLOR(), GhosttyDeviceAttributesPrimary.features(primary, 2));
            assertEquals(ghostty_vt_h.GHOSTTY_DA_DEVICE_TYPE_VT220(), GhosttyDeviceAttributesSecondary.device_type(secondary));
            assertEquals(1, GhosttyDeviceAttributesSecondary.firmware_version(secondary));
            assertEquals(0, GhosttyDeviceAttributesSecondary.rom_cartridge(secondary));
            assertEquals(0, GhosttyDeviceAttributesTertiary.unit_id(tertiary));
        }
    }

    @Test
    void reportsXtversionEffectData() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-effects-version-test-");
        var captureFile = tempDirectory.resolve("stdin.bin");
        try (var ptySession = new PtySession(captureInputCommand(captureFile), tempDirectory, System.getenv(), 80, 24);
                var session = newSession(ptySession, _ -> {}, () -> {})) {
            var value = (MemorySegment) invoke(session, "reportXtversion", MemorySegment.NULL, MemorySegment.NULL);
            assertEquals("ghosttyfx", ghosttyString(value));
        }
    }

    private static TerminalSession newSession(
            PtySession ptySession,
            java.util.function.Consumer<String> titleChanged,
            Runnable bell) {
        return new TerminalSession(80, 24, new GhosttyCanvas.CellMetrics(9, 18, 13), ptySession, titleChanged, bell);
    }

    private static Object invoke(TerminalSession session, String methodName, Object... arguments) throws Exception {
        var method = method(methodName, arguments);
        method.setAccessible(true);
        return method.invoke(session, arguments);
    }

    private static Method method(String methodName, Object[] arguments) throws NoSuchMethodException {
        var types = new Class<?>[arguments.length];
        for (var i = 0; i < arguments.length; i++) {
            types[i] = switch (arguments[i]) {
                case MemorySegment _ -> MemorySegment.class;
                default -> arguments[i].getClass();
            };
        }
        return TerminalSession.class.getDeclaredMethod(methodName, types);
    }

    private static String ghosttyString(MemorySegment value) {
        var length = GhosttyString.len(value);
        var pointer = GhosttyString.ptr(value);
        return new String(pointer.reinterpret(length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private static List<String> captureInputCommand(Path captureFile) {
        return isWindows() ? captureWindowsInputCommand(captureFile) : capturePosixInputCommand(captureFile);
    }

    private static List<String> captureWindowsInputCommand(Path captureFile) {
        var systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        var executable = findExecutable(
                List.of("pwsh.exe", "powershell.exe"),
                List.of(Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")));
        var command = "$inputStream = [Console]::OpenStandardInput(); "
                + "$output = [IO.File]::Open(" + quotePowerShell(captureFile) + ", [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite); "
                + "try { $buffer = New-Object byte[] 4096; while (($read = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) { $output.Write($buffer, 0, $read); $output.Flush() } } finally { $output.Dispose() }";
        return List.of(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command);
    }

    private static List<String> capturePosixInputCommand(Path captureFile) {
        var executable = findExecutable(
                List.of("sh", "bash"),
                List.of(Path.of("/bin/sh"), Path.of("/usr/bin/sh"), Path.of("/bin/bash"), Path.of("/usr/bin/bash")));
        return List.of(executable.toString(), "-c", "cat > " + quotePosix(captureFile));
    }

    private static Path findExecutable(List<String> pathCandidates, List<Path> fallbackCandidates) {
        for (var candidate : pathCandidates) {
            var discovered = findOnPath(candidate);
            if (discovered.isPresent()) {
                return discovered.get();
            }
        }
        for (var candidate : fallbackCandidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No suitable shell found. Looked for: " + pathCandidates + " " + fallbackCandidates);
    }

    private static Optional<Path> findOnPath(String fileName) {
        var path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (var rawDirectory : path.split(Pattern.quote(File.pathSeparator))) {
            var directory = rawDirectory.strip();
            if (directory.isEmpty()) {
                continue;
            }
            if (directory.startsWith("\"") && directory.endsWith("\"") && directory.length() > 1) {
                directory = directory.substring(1, directory.length() - 1);
            }
            var candidate = Path.of(directory).resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String quotePowerShell(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }

    private static String quotePosix(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }
}
