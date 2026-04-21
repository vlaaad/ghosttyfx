package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import javafx.scene.text.Font;

final class GhosttyCanvasTest {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @BeforeAll
    static void initializeJavaFxRuntime() throws InterruptedException {
        var started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException _) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(15, TimeUnit.SECONDS), "Timed out waiting for JavaFX runtime startup");
    }

    @Test
    void startsProcessAndStopsItOnClose() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);
        ProcessHandle handle;

        try (var _ = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            handle = await("shell process to start", START_TIMEOUT, () -> readAliveProcess(pidFile));
            assertTrue(handle.isAlive(), "Expected shell process to be alive: " + handle.pid());
        }

        try {
            awaitProcessStop(handle);
        } finally {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
    }

    @Test
    void updatesPreferredSizeWhenFontChanges() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-font-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var canvas = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            var initialPrefWidth = canvas.prefWidth(-1);
            var initialPrefHeight = canvas.prefHeight(-1);
            canvas.fontProperty().set(Font.font("Monospaced", canvas.fontProperty().get().getSize() + 6));
            assertTrue(
                    canvas.prefWidth(-1) != initialPrefWidth || canvas.prefHeight(-1) != initialPrefHeight,
                    "Expected font change to update preferred size");
            assertThrows(NullPointerException.class, () -> canvas.fontProperty().set(null));
        }
    }

    @Test
    void ctrlCCopyClearsSelection() throws Exception {
        var marker = "ghosttyfx-copy-shortcut";
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-copy-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, marker);
        var clipboardContents = runOnFxThread(GhosttyCanvasTest::snapshotClipboardContents);

        try {
            try (var canvas = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
                var selectedText = await("terminal output to become selectable", START_TIMEOUT, () -> runOnFxThread(() -> {
                    fireShortcut(canvas, canvas.getSelectAllShortcut());
                    var text = canvas.getInputMethodRequests().getSelectedText();
                    return text != null && text.contains(marker) ? Optional.of(text) : Optional.empty();
                }));

                runOnFxThread(() -> {
                    fireShortcut(canvas, canvas.getCopyShortcut());
                    var clipboard = Clipboard.getSystemClipboard();
                    assertTrue(selectedText.equals(clipboard.getString()), "Expected copied text to match current selection");
                    var remainingSelection = canvas.getInputMethodRequests().getSelectedText();
                    assertTrue(remainingSelection == null || remainingSelection.isEmpty(), "Expected copy to clear selection");
                    return null;
                });
            }
        } finally {
            runOnFxThread(() -> {
                restoreClipboardContents(clipboardContents);
                return null;
            });
        }
    }

    private static void awaitProcessStop(ProcessHandle handle) throws Exception {
        await("shell process to stop", STOP_TIMEOUT, () -> handle.isAlive() ? Optional.empty() : Optional.of(Boolean.TRUE));
    }

    private static <T> T runOnFxThread(CheckedSupplier<T> supplier) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return supplier.get();
        }

        var completed = new CountDownLatch(1);
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        assertTrue(completed.await(15, TimeUnit.SECONDS), "Timed out waiting for JavaFX task");

        var throwable = failure.get();
        if (throwable instanceof Exception exception) {
            throw exception;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable != null) {
            throw new RuntimeException(throwable);
        }

        return result.get();
    }

    private static Map<DataFormat, Object> snapshotClipboardContents() {
        var clipboard = Clipboard.getSystemClipboard();
        var result = new HashMap<DataFormat, Object>();
        for (var format : clipboard.getContentTypes()) {
            result.put(format, clipboard.getContent(format));
        }
        return Map.copyOf(result);
    }

    private static void restoreClipboardContents(Map<DataFormat, Object> clipboardContents) {
        var clipboard = Clipboard.getSystemClipboard();
        if (clipboardContents.isEmpty()) {
            clipboard.clear();
            return;
        }
        clipboard.setContent(clipboardContents);
    }

    private static void fireShortcut(GhosttyCanvas canvas, KeyCombination shortcut) {
        if (!(shortcut instanceof KeyCodeCombination combination)) {
            throw new IllegalArgumentException("Expected key-code shortcut, got: " + shortcut);
        }

        var event = new KeyEvent(
                KeyEvent.KEY_PRESSED,
                "",
                "",
                combination.getCode(),
                modifierDown(combination.getShift()),
                modifierDown(combination.getControl()) || (shortcutDownOnCurrentPlatform(combination.getShortcut()) && !isMac()),
                modifierDown(combination.getAlt()),
                modifierDown(combination.getMeta()) || (shortcutDownOnCurrentPlatform(combination.getShortcut()) && isMac()));
        Event.fireEvent(canvas, event);
    }

    private static boolean modifierDown(KeyCombination.ModifierValue value) {
        return value == KeyCombination.ModifierValue.DOWN;
    }

    private static boolean shortcutDownOnCurrentPlatform(KeyCombination.ModifierValue value) {
        return value == KeyCombination.ModifierValue.DOWN;
    }

    private static ShellCommand discoverShell(Path pidFile) {
        return isWindows() ? discoverWindowsShell(pidFile) : discoverPosixShell(pidFile);
    }

    private static ShellCommand discoverOutputShell(Path pidFile, String output) {
        return isWindows() ? discoverWindowsOutputShell(pidFile, output) : discoverPosixOutputShell(pidFile, output);
    }

    private static ShellCommand discoverWindowsShell(Path pidFile) {
        var systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        var executable = findExecutable(
                List.of("pwsh.exe", "powershell.exe"),
                List.of(Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")));
        var command = "$ErrorActionPreference = 'Stop'; Set-Content -Path "
                + quotePowerShell(pidFile)
                + " -Value $PID; Start-Sleep -Seconds 600";
        return new ShellCommand(List.of(
                executable.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                command));
    }

    private static ShellCommand discoverWindowsOutputShell(Path pidFile, String output) {
        var systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        var executable = findExecutable(
                List.of("pwsh.exe", "powershell.exe"),
                List.of(Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")));
        var command = "$ErrorActionPreference = 'Stop'; Set-Content -Path "
                + quotePowerShell(pidFile)
                + " -Value $PID; Write-Output "
                + quotePowerShell(output)
                + "; Start-Sleep -Seconds 600";
        return new ShellCommand(List.of(
                executable.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                command));
    }

    private static ShellCommand discoverPosixShell(Path pidFile) {
        var executable = findExecutable(
                List.of("sh", "bash"),
                List.of(Path.of("/bin/sh"), Path.of("/usr/bin/sh"), Path.of("/bin/bash"), Path.of("/usr/bin/bash")));
        var command = "printf '%s\\n' $$ > " + quotePosix(pidFile) + "; exec sleep 600";
        return new ShellCommand(List.of(executable.toString(), "-c", command));
    }

    private static ShellCommand discoverPosixOutputShell(Path pidFile, String output) {
        var executable = findExecutable(
                List.of("sh", "bash"),
                List.of(Path.of("/bin/sh"), Path.of("/usr/bin/sh"), Path.of("/bin/bash"), Path.of("/usr/bin/bash")));
        var command = "printf '%s\\n' $$ > " + quotePosix(pidFile)
                + "; printf '%s\\n' "
                + quotePosix(output)
                + "; exec sleep 600";
        return new ShellCommand(List.of(executable.toString(), "-c", command));
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

    private static Optional<ProcessHandle> readAliveProcess(Path pidFile) throws IOException {
        if (!Files.isRegularFile(pidFile)) {
            return Optional.empty();
        }
        var contents = Files.readString(pidFile).trim();
        if (contents.isEmpty()) {
            return Optional.empty();
        }
        var pid = Long.parseLong(contents);
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
    }

    private static <T> T await(String description, Duration timeout, CheckedSupplier<Optional<T>> supplier) throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var result = supplier.get();
            if (result.isPresent()) {
                return result.get();
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        fail("Timed out waiting for " + description + " within " + timeout);
        throw new AssertionError("unreachable");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        var os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }

    private static String quotePowerShell(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }

    private static String quotePowerShell(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private static String quotePosix(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private static String quotePosix(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private record ShellCommand(List<String> command) {}
}
