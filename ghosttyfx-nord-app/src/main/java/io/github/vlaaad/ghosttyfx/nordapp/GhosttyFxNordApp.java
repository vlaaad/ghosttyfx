package io.github.vlaaad.ghosttyfx.nordapp;

import io.github.vlaaad.ghosttyfx.Shell;
import io.github.vlaaad.ghosttyfx.TerminalTheme;
import io.github.vlaaad.ghosttyfx.TerminalView;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

public final class GhosttyFxNordApp {
    private static final Path DEFAULT_CWD = Path.of(System.getProperty("user.home", "."))
            .toAbsolutePath()
            .normalize();

    private GhosttyFxNordApp() {}

    public static void main(String[] args) {
        Platform.startup(() -> {
            var command = args.length == 0 ? defaultCommand() : List.of(args);
            var view = new TerminalView((columns, rows) -> {
                var launcher = Shell.integrate(command, System.getenv());
                return new PtyTerminal(launcher.command(), DEFAULT_CWD, launcher.environment(), columns, rows);
            });
            view.setTheme(nordTheme());

            var stage = new Stage();
            stage.titleProperty().bind(view.titleProperty());
            stage.setScene(new Scene(view, 1200, 800));
            stage.setOnShown(_ -> view.requestFocus());
            stage.setOnCloseRequest(_ -> Thread.ofVirtual().name("ghosttyfx-nord-close").start(view::close));
            stage.show();
        });
    }

    private static TerminalTheme nordTheme() {
        var foreground = color("#d8dee9");
        return new TerminalTheme(
                color("#2e3440"),
                foreground,
                List.of(
                        color("#3b4252"), color("#bf616a"), color("#a3be8c"), color("#ebcb8b"),
                        color("#81a1c1"), color("#b48ead"), color("#88c0d0"), color("#e5e9f0"),
                        color("#4c566a"), color("#bf616a"), color("#a3be8c"), color("#ebcb8b"),
                        color("#81a1c1"), color("#b48ead"), color("#8fbcbb"), color("#eceff4")),
                foreground,
                color("#2e3440"),
                color("#434c5e"),
                foreground,
                0.5,
                foreground.deriveColor(0, 1, 1, 0.45),
                foreground.deriveColor(0, 1, 1, 0.18),
                foreground.deriveColor(0, 1, 1, 0.35));
    }

    private static Color color(String value) {
        return Color.web(value);
    }

    private static List<String> defaultCommand() {
        return isWindows() ? defaultWindowsCommand() : defaultUnixCommand();
    }

    private static List<String> defaultWindowsCommand() {
        var pwsh = resolveExecutable("pwsh.exe");
        if (pwsh != null) {
            return List.of(pwsh.toString());
        }
        var powershell = resolveExecutable("powershell.exe");
        if (powershell != null) {
            return List.of(powershell.toString());
        }
        var comspec = System.getenv("COMSPEC");
        if (comspec != null && !comspec.isBlank()) {
            return List.of(comspec);
        }
        return List.of("cmd.exe");
    }

    private static List<String> defaultUnixCommand() {
        var shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            return List.of(shell);
        }
        return List.of("/bin/sh");
    }

    private static Path resolveExecutable(String candidate) {
        var path = Path.of(candidate);
        if (path.isAbsolute()) {
            return Files.isRegularFile(path) ? path : null;
        }
        if (isWindows()) {
            try {
                var process = new ProcessBuilder("where.exe", candidate).redirectErrorStream(true).start();
                try (var reader = process.inputReader()) {
                    var line = reader.readLine();
                    if (process.waitFor() == 0 && line != null && !line.isBlank()) {
                        var resolved = Path.of(line.trim()).toAbsolutePath().normalize();
                        if (Files.isRegularFile(resolved)) {
                            return resolved;
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return searchPath(candidate);
    }

    private static Path searchPath(String candidate) {
        for (var entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            var resolved = Path.of(entry).resolve(candidate);
            if (Files.isRegularFile(resolved)) {
                return resolved.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
