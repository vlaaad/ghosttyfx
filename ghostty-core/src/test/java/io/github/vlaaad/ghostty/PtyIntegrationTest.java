package io.github.vlaaad.ghostty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PtyIntegrationTest {
    @Test
    void terminalTracksRealPtyOutputForAvailableShells() throws Exception {
        var shells = availableShells();
        assertFalse(shells.isEmpty(), "No supported test shell found on PATH");

        for (var shell : shells) {
            try (var harness = new PtyHarness(shell)) {
                assertTrue(harness.awaitText(shell.expectedText(), Duration.ofSeconds(15)), shell.name());
                assertTrue(harness.waitForExit(Duration.ofSeconds(10)), shell.name());
            }
        }
    }

    private static List<ShellSpec> availableShells() {
        var result = new ArrayList<ShellSpec>();
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            addIfPresent(result, "cmd", "cmd.exe", new String[] { "/d", "/c", "echo ghosttyfx-pty-cmd" }, "ghosttyfx-pty-cmd");
            addIfPresent(result, "powershell", "powershell.exe", new String[] { "-NoLogo", "-NoProfile", "-Command", "Write-Output 'ghosttyfx-pty-powershell'" }, "ghosttyfx-pty-powershell");
            addIfPresent(result, "pwsh", "pwsh.exe", new String[] { "-NoLogo", "-NoProfile", "-Command", "Write-Output 'ghosttyfx-pty-pwsh'" }, "ghosttyfx-pty-pwsh");
        } else {
            addIfPresent(result, "sh", "sh", new String[] { "-lc", "printf 'ghosttyfx-pty-sh\\n'" }, "ghosttyfx-pty-sh");
            addIfPresent(result, "bash", "bash", new String[] { "-lc", "printf 'ghosttyfx-pty-bash\\n'" }, "ghosttyfx-pty-bash");
            addIfPresent(result, "zsh", "zsh", new String[] { "-lc", "printf 'ghosttyfx-pty-zsh\\n'" }, "ghosttyfx-pty-zsh");
        }
        return result;
    }

    private static void addIfPresent(List<ShellSpec> shells, String name, String command, String[] args, String expectedText) {
        var executable = resolveExecutable(command);
        if (executable == null) {
            return;
        }
        var fullCommand = new String[args.length + 1];
        fullCommand[0] = executable.toString();
        System.arraycopy(args, 0, fullCommand, 1, args.length);
        shells.add(new ShellSpec(name, fullCommand, expectedText));
    }

    private static Path resolveExecutable(String executable) {
        var candidate = Path.of(executable);
        if (candidate.isAbsolute() && Files.isRegularFile(candidate)) {
            return candidate;
        }

        var path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }

        var windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        var extensions = executable.contains(".") || !windows
            ? List.of("")
            : Arrays.stream(System.getenv().getOrDefault("PATHEXT", ".EXE;.CMD;.BAT;.COM").split(";"))
                .filter(ext -> !ext.isBlank())
                .map(String::toLowerCase)
                .toList();

        for (var entry : path.split(java.io.File.pathSeparator)) {
            var directory = Path.of(entry);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            if (executable.contains(".")) {
                var resolved = directory.resolve(executable);
                if (Files.isRegularFile(resolved)) {
                    return resolved;
                }
                continue;
            }
            for (var extension : extensions) {
                var resolved = directory.resolve(executable + extension);
                if (Files.isRegularFile(resolved)) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private record ShellSpec(String name, String[] command, String expectedText) {}

    private static final class PtyHarness implements AutoCloseable {
        private final ShellSpec shell;
        private final PtyProcess process;
        private final TerminalSession session;
        private final Thread reader;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PtyHarness(ShellSpec shell) throws IOException {
            this.shell = shell;
            process = new PtyProcessBuilder(shell.command())
                .setEnvironment(new HashMap<>(System.getenv()))
                .setDirectory(System.getProperty("user.home"))
                .setConsole(false)
                .start();
            session = Ghostty.open(
                new TerminalConfig(120, 40, 4096),
                bytes -> {
                    try {
                        process.getOutputStream().write(bytes);
                        process.getOutputStream().flush();
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                },
                new TerminalQueries() {},
                new TerminalEvents() {}
            );
            reader = Thread.ofPlatform().name("pty-reader-" + shell.name()).start(this::pump);
        }

        private void pump() {
            try (var input = process.getInputStream()) {
                var buffer = new byte[4096];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    if (read == 0) {
                        continue;
                    }
                    session.write(buffer, 0, read);
                }
            } catch (IOException exception) {
                if (!closed.get()) {
                    throw new RuntimeException(exception);
                }
            }
        }

        private boolean awaitText(String text, Duration timeout) throws InterruptedException {
            var deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                var frame = session.frame();
                if (frameText(frame).contains(text)) {
                    return true;
                }
                Thread.sleep(50);
            }
            return false;
        }

        private boolean waitForExit(Duration timeout) throws InterruptedException {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } finally {
                reader.join(TimeUnit.SECONDS.toMillis(5));
                session.close();
            }
        }

        private static String frameText(Frame frame) {
            var builder = new StringBuilder();
            for (var row : frame.rows()) {
                for (var column = 0; column < row.columns(); column++) {
                    builder.append(row.text(column));
                }
                builder.append('\n');
            }
            return builder.toString();
        }
    }
}
