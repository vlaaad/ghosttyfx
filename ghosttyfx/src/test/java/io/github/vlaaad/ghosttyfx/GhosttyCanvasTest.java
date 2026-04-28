package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import javafx.event.EventType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

final class GhosttyCanvasTest {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);
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
    void themePropertyRejectsNullAndStoresTheme() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-theme-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var canvas = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            var theme = new TerminalTheme(
                    Color.WHITE,
                    Color.BLACK,
                    List.of(),
                    Color.BLACK,
                    Color.WHITE,
                    Color.DARKBLUE,
                    Color.WHITE,
                    0.5,
                    Color.gray(0.25));
            runOnFxThread(() -> {
                canvas.setTheme(theme);
                assertEquals(theme, canvas.getTheme());
                assertThrows(NullPointerException.class, () -> canvas.setTheme(null));
                assertThrows(NullPointerException.class, () -> canvas.themeProperty().set(null));
                return null;
            });
        }
    }

    @Test
    void exposesShortcutListProperty() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-selection-shortcuts-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var canvas = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            runOnFxThread(() -> {
                var combinations = canvas.getShortcuts().stream()
                        .map(Shortcut::combination)
                        .toList();
                assertTrue(combinations.contains(isMac()
                        ? new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN)
                        : new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN)));
                assertTrue(combinations.contains(isMac()
                        ? new KeyCodeCombination(KeyCode.A, KeyCombination.META_DOWN)
                        : new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN)));
                assertTrue(combinations.contains(new KeyCodeCombination(KeyCode.PAGE_UP, KeyCombination.SHIFT_DOWN)));
                assertTrue(combinations.contains(new KeyCodeCombination(KeyCode.END, KeyCombination.SHIFT_DOWN)));
                assertTrue(combinations.contains(isMac()
                        ? new KeyCodeCombination(KeyCode.PAGE_UP, KeyCombination.META_DOWN)
                        : new KeyCodeCombination(KeyCode.PAGE_UP, KeyCombination.SHIFT_DOWN)));
                assertTrue(combinations.contains(isMac()
                        ? new KeyCodeCombination(KeyCode.PAGE_DOWN, KeyCombination.META_DOWN)
                        : new KeyCodeCombination(KeyCode.PAGE_DOWN, KeyCombination.SHIFT_DOWN)));
                assertTrue(combinations.contains(isMac()
                        ? new KeyCodeCombination(KeyCode.HOME, KeyCombination.META_DOWN)
                        : new KeyCodeCombination(KeyCode.HOME, KeyCombination.SHIFT_DOWN)));
                assertTrue(combinations.contains(isMac()
                        ? new KeyCodeCombination(KeyCode.END, KeyCombination.META_DOWN)
                        : new KeyCodeCombination(KeyCode.END, KeyCombination.SHIFT_DOWN)));
                if (isMac()) {
                    assertTrue(combinations.contains(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN)));
                    assertTrue(combinations.contains(new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.ALT_DOWN)));
                    assertTrue(combinations.contains(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.META_DOWN)));
                    assertTrue(combinations.contains(new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.META_DOWN)));
                    assertTrue(combinations.contains(new KeyCodeCombination(KeyCode.BACK_SPACE, KeyCombination.META_DOWN)));
                }

                var shortcut = new Shortcut(
                        new KeyCodeCombination(KeyCode.B, KeyCombination.SHIFT_DOWN),
                        () -> false);
                canvas.getShortcuts().add(shortcut);
                assertTrue(canvas.getShortcuts().contains(shortcut));
                assertThrows(NullPointerException.class, () -> canvas.setShortcuts(null));
                return null;
            });
        }
    }

    @Test
    void sendTextAndSendEscExposeShortcutActions() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-send-text-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var canvas = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            runOnFxThread(() -> {
                assertFalse(canvas.sendText(""));
                assertTrue(canvas.sendText("A"));
                assertTrue(canvas.sendEsc("b"));
                assertThrows(NullPointerException.class, () -> canvas.sendText(null));
                assertThrows(NullPointerException.class, () -> canvas.sendEsc(null));

                var textShortcut = new KeyCodeCombination(KeyCode.B, KeyCombination.ALT_DOWN);
                var sendTextShortcut = new Shortcut(textShortcut, () -> canvas.sendText("B"));
                canvas.getShortcuts().add(sendTextShortcut);
                assertTrue(canvas.getShortcuts().contains(sendTextShortcut));
                assertTrue(sendTextShortcut.action().getAsBoolean());

                var escShortcut = new KeyCodeCombination(KeyCode.F, KeyCombination.ALT_DOWN);
                var sendEscShortcut = new Shortcut(escShortcut, () -> canvas.sendEsc("f"));
                canvas.getShortcuts().add(sendEscShortcut);
                assertTrue(canvas.getShortcuts().contains(sendEscShortcut));
                assertTrue(sendEscShortcut.action().getAsBoolean());
                return null;
            });
        }
    }

    @Test
    void shiftArrowShortcutsExtendExistingSelection() throws Exception {
        var marker = "ghosttyfx-selection";
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-selection-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, marker);

        try (var canvas = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            await("terminal output to be addressable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireShortcut(canvas, selectAllShortcut());
                return marker.equals(canvas.getInputMethodRequests().getSelectedText())
                        ? Optional.of(Boolean.TRUE)
                        : Optional.empty();
            }));

            runOnFxThread(() -> {
                dragSelection(canvas, 1, 1);
                fireShortcut(canvas, new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(1, 3), canvas.getInputMethodRequests().getSelectedText());

                fireShortcut(canvas, new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(1, 2), canvas.getInputMethodRequests().getSelectedText());

                fireShortcut(canvas, new KeyCodeCombination(KeyCode.HOME, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(0, 2), canvas.getInputMethodRequests().getSelectedText());

                fireShortcut(canvas, new KeyCodeCombination(KeyCode.END, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(1), canvas.getInputMethodRequests().getSelectedText());
                return null;
            });
        }
    }

    @Test
    void viewportScrollShortcutsReportUnavailableWithoutScrollableViewport() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-viewport-scroll-shortcuts-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var canvas = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            runOnFxThread(() -> {
                assertFalse(canvas.scrollViewportPageUp());
                assertFalse(canvas.scrollViewportPageDown());
                assertFalse(canvas.scrollViewportToTop());
                assertFalse(canvas.scrollViewportToBottom());
                return null;
            });
        }
    }

    @Test
    void viewportScrollShortcutsConsumeAtScrollableBoundaries() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-viewport-scroll-boundary-shortcuts-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, lineOutput(80));

        try (var canvas = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            await("scrollable terminal output", START_TIMEOUT, () -> runOnFxThread(() ->
                    canvas.scrollViewportToTop() ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertTrue(canvas.scrollViewportPageUp());
                assertTrue(canvas.scrollViewportToTop());
                assertTrue(canvas.scrollViewportToBottom());
                assertTrue(canvas.scrollViewportPageDown());
                assertTrue(canvas.scrollViewportToBottom());
                return null;
            });
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
                    fireShortcut(canvas, selectAllShortcut());
                    var text = canvas.getInputMethodRequests().getSelectedText();
                    return text != null && text.contains(marker) ? Optional.of(text) : Optional.empty();
                }));

                runOnFxThread(() -> {
                    fireShortcut(canvas, copyShortcut());
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

    @Test
    void closeStopsProcessButKeepsTerminalViewReadable() throws Exception {
        var marker = "ghosttyfx-close-keeps-view";
        var tempDirectory = Files.createTempDirectory("ghosttyfx-canvas-close-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, marker);
        try (var canvas = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            var handle = await("shell process to start", START_TIMEOUT, () -> readAliveProcess(pidFile));
            try {
                var selectedText = await("terminal output to become selectable", START_TIMEOUT, () -> runOnFxThread(() -> {
                    fireShortcut(canvas, selectAllShortcut());
                    var text = canvas.getInputMethodRequests().getSelectedText();
                    return text != null && text.contains(marker) ? Optional.of(text) : Optional.empty();
                }));

                canvas.close();

                var remainingSelection = runOnFxThread(() -> canvas.getInputMethodRequests().getSelectedText());
                assertTrue(selectedText.equals(remainingSelection), "Expected terminal contents to remain readable after close()");
                awaitProcessStop(handle);
            } finally {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            }
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

    private static KeyCombination copyShortcut() {
        return isMac()
                ? new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN)
                : new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN);
    }

    private static KeyCombination selectAllShortcut() {
        return isMac()
                ? new KeyCodeCombination(KeyCode.A, KeyCombination.META_DOWN)
                : new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
    }

    private static KeyEvent fireShortcut(GhosttyCanvas canvas, KeyCombination shortcut) {
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
        return event;
    }

    private static void dragSelection(GhosttyCanvas canvas, int fromColumn, int toColumn) {
        var cellWidth = (canvas.prefWidth(-1) - 10) / 80;
        var cellHeight = canvas.prefHeight(-1) / 24;
        var fromX = fromColumn * cellWidth + cellWidth * 0.1;
        var toX = toColumn * cellWidth + cellWidth * 0.8;
        var y = cellHeight * 0.5;
        Event.fireEvent(canvas, mouseEvent(MouseEvent.MOUSE_PRESSED, fromX, y, true));
        Event.fireEvent(canvas, mouseEvent(MouseEvent.MOUSE_DRAGGED, toX, y, true));
        Event.fireEvent(canvas, mouseEvent(MouseEvent.MOUSE_RELEASED, toX, y, false));
    }

    private static MouseEvent mouseEvent(EventType<MouseEvent> eventType, double x, double y, boolean primaryButtonDown) {
        return new MouseEvent(
                eventType,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                primaryButtonDown,
                false,
                false,
                false,
                false,
                false,
                null);
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

    private static String lineOutput(int count) {
        var lines = new StringBuilder();
        for (var i = 0; i < count; i++) {
            if (!lines.isEmpty()) {
                lines.append('\n');
            }
            lines.append("line-").append(i);
        }
        return lines.toString();
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
