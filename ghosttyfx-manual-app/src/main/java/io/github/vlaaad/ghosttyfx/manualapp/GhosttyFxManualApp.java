package io.github.vlaaad.ghosttyfx.manualapp;

import io.github.vlaaad.ghosttyfx.GhosttyCanvas;
import io.github.vlaaad.ghosttyfx.GhosttyFx;
import io.github.vlaaad.ghosttyfx.TerminalTheme;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.media.AudioClip;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

public final class GhosttyFxManualApp {
    private static final Path DEFAULT_CWD = Path.of(System.getProperty("user.dir", "."))
            .toAbsolutePath()
            .normalize();

    private GhosttyFxManualApp() {}

    public static void main(String[] args) {
        Platform.startup(() -> {
            var terminals = FXCollections.observableArrayList(detectTerminals());
            var terminalPicker = new ComboBox<TerminalOption>(terminals);
            if (!terminals.isEmpty()) {
                terminalPicker.getSelectionModel().selectFirst();
            }

            var themePicker = new ComboBox<ThemeOption>(FXCollections.observableArrayList(themes()));
            themePicker.getSelectionModel().selectFirst();
            var cwdField = new TextField(DEFAULT_CWD.toString());
            var chooseDirectory = new Button("Browse...");
            var startTerminal = new Button("New Terminal");
            var tabs = new TabPane();
            var bellSound = new AudioClip(Objects.requireNonNull(
                    GhosttyFxManualApp.class.getResource("bell_ding1.wav"),
                    "bell_ding1.wav").toExternalForm());

            chooseDirectory.setOnAction(_ -> {
                var chooser = new DirectoryChooser();
                chooser.setTitle("Choose Terminal Working Directory");
                try {
                    var path = Path.of(cwdField.getText()).toAbsolutePath().normalize();
                    chooser.setInitialDirectory(Files.isDirectory(path) ? path.toFile() : DEFAULT_CWD.toFile());
                } catch (RuntimeException e) {
                    chooser.setInitialDirectory(DEFAULT_CWD.toFile());
                }
                var selected = chooser.showDialog((Stage) tabs.getScene().getWindow());
                if (selected != null) {
                    cwdField.setText(selected.toPath().toAbsolutePath().normalize().toString());
                }
            });

            startTerminal.setDisable(terminals.isEmpty());
            startTerminal.setOnAction(_ -> {
                var terminal = terminalPicker.getValue();
                if (terminal == null) {
                    var alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("GhosttyFX");
                    alert.setHeaderText("Unable to start terminal");
                    alert.setContentText("No terminal selected.");
                    alert.showAndWait();
                    return;
                }

                final Path cwd;
                try {
                    cwd = Path.of(cwdField.getText()).toAbsolutePath().normalize();
                } catch (RuntimeException e) {
                    var alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("GhosttyFX");
                    alert.setHeaderText("Unable to start terminal");
                    alert.setContentText("Invalid directory: " + cwdField.getText());
                    alert.showAndWait();
                    return;
                }
                if (!Files.isDirectory(cwd)) {
                    var alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("GhosttyFX");
                    alert.setHeaderText("Unable to start terminal");
                    alert.setContentText("Directory does not exist: " + cwd);
                    alert.showAndWait();
                    return;
                }
                var theme = themePicker.getValue();

                final GhosttyCanvas canvas;
                try {
                    canvas = GhosttyFx.create(terminal.command(), cwd, System.getenv());
                    if (theme != null) {
                        canvas.setTheme(theme.theme());
                    }
                } catch (RuntimeException e) {
                    var alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("GhosttyFX");
                    alert.setHeaderText("Unable to start terminal");
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                    return;
                }

                var tab = new Tab();
                tab.textProperty().bind(canvas.titleProperty());
                tab.setContent(canvas);
                tab.setClosable(true);
                canvas.setOnBell(bellSound::play);
                tab.setOnClosed(_2 -> Thread.ofVirtual().name("ghosttyfx-tab-close").start(canvas::close));
                tabs.getTabs().add(tab);
                tabs.getSelectionModel().select(tab);
                canvas.requestFocus();
                canvas.processExitedProperty().addListener((_, _, _) -> tabs.getTabs().remove(tab));
            });
            themePicker.valueProperty().addListener((_, _, theme) -> {
                if (theme == null) {
                    return;
                }
                for (var tab : tabs.getTabs()) {
                    if (tab.getContent() instanceof GhosttyCanvas canvas) {
                        canvas.setTheme(theme.theme());
                    }
                }
            });

            HBox.setHgrow(cwdField, Priority.ALWAYS);
            var controls = new HBox(8, terminalPicker, themePicker, cwdField, chooseDirectory, startTerminal);
            controls.setPadding(new Insets(8));

            var root = new BorderPane();
            root.setTop(controls);
            root.setCenter(tabs);

            var stage = new Stage();
            stage.setTitle("GhosttyFX Manual App");
            stage.setScene(new Scene(root, 1200, 800));
            stage.setOnCloseRequest(_ -> tabs.getTabs()
                    .stream()
                    .map(Tab::getContent)
                    .filter(GhosttyCanvas.class::isInstance)
                    .map(GhosttyCanvas.class::cast)
                    .forEach(canvas -> Thread.ofVirtual().name("ghosttyfx-stage-close").start(canvas::close)));
            stage.show();
        });
    }

    private static List<ThemeOption> themes() {
        return List.of(
                new ThemeOption("Ghostty Default", TerminalTheme.defaults()),
                darkTheme(
                        "Monokai",
                        "#272822",
                        "#f8f8f2",
                        List.of(
                                "#272822", "#f92672", "#a6e22e", "#f4bf75",
                                "#66d9ef", "#ae81ff", "#a1efe4", "#f8f8f2",
                                "#75715e", "#f92672", "#a6e22e", "#f4bf75",
                                "#66d9ef", "#ae81ff", "#a1efe4", "#f9f8f5"),
                        "#49483e",
                        "#f8f8f2"),
                darkTheme(
                        "Dracula",
                        "#282a36",
                        "#f8f8f2",
                        List.of(
                                "#000000", "#ff5555", "#50fa7b", "#f1fa8c",
                                "#bd93f9", "#ff79c6", "#8be9fd", "#bbbbbb",
                                "#555555", "#ff5555", "#50fa7b", "#f1fa8c",
                                "#bd93f9", "#ff79c6", "#8be9fd", "#ffffff"),
                        "#44475a",
                        "#f8f8f2"),
                darkTheme(
                        "Catppuccin Mocha",
                        "#1e1e2e",
                        "#cdd6f4",
                        List.of(
                                "#45475a", "#f38ba8", "#a6e3a1", "#f9e2af",
                                "#89b4fa", "#f5c2e7", "#94e2d5", "#bac2de",
                                "#585b70", "#f38ba8", "#a6e3a1", "#f9e2af",
                                "#89b4fa", "#f5c2e7", "#94e2d5", "#a6adc8"),
                        "#313244",
                        "#cdd6f4"),
                darkTheme(
                        "Gruvbox Dark",
                        "#282828",
                        "#ebdbb2",
                        List.of(
                                "#282828", "#cc241d", "#98971a", "#d79921",
                                "#458588", "#b16286", "#689d6a", "#a89984",
                                "#928374", "#fb4934", "#b8bb26", "#fabd2f",
                                "#83a598", "#d3869b", "#8ec07c", "#ebdbb2"),
                        "#504945",
                        "#ebdbb2"),
                darkTheme(
                        "Nord",
                        "#2e3440",
                        "#d8dee9",
                        List.of(
                                "#3b4252", "#bf616a", "#a3be8c", "#ebcb8b",
                                "#81a1c1", "#b48ead", "#88c0d0", "#e5e9f0",
                                "#4c566a", "#bf616a", "#a3be8c", "#ebcb8b",
                                "#81a1c1", "#b48ead", "#8fbcbb", "#eceff4"),
                        "#434c5e",
                        "#eceff4"),
                darkTheme(
                        "Tokyo Night",
                        "#1a1b26",
                        "#c0caf5",
                        List.of(
                                "#15161e", "#f7768e", "#9ece6a", "#e0af68",
                                "#7aa2f7", "#bb9af7", "#7dcfff", "#a9b1d6",
                                "#414868", "#f7768e", "#9ece6a", "#e0af68",
                                "#7aa2f7", "#bb9af7", "#7dcfff", "#c0caf5"),
                        "#28344a",
                        "#c0caf5"),
                darkTheme(
                        "Solarized Dark",
                        "#002b36",
                        "#839496",
                        List.of(
                                "#073642", "#dc322f", "#859900", "#b58900",
                                "#268bd2", "#d33682", "#2aa198", "#eee8d5",
                                "#002b36", "#cb4b16", "#586e75", "#657b83",
                                "#839496", "#6c71c4", "#93a1a1", "#fdf6e3"),
                        "#073642",
                        "#93a1a1"),
                lightTheme(
                        "Solarized Light",
                        "#fdf6e3",
                        "#657b83",
                        List.of(
                                "#073642", "#dc322f", "#859900", "#b58900",
                                "#268bd2", "#d33682", "#2aa198", "#eee8d5",
                                "#002b36", "#cb4b16", "#586e75", "#657b83",
                                "#839496", "#6c71c4", "#93a1a1", "#fdf6e3"),
                        "#eee8d5",
                        "#586e75"),
                lightTheme(
                        "GitHub Light",
                        "#ffffff",
                        "#24292f",
                        List.of(
                                "#24292f", "#cf222e", "#116329", "#4d2d00",
                                "#0969da", "#8250df", "#1b7c83", "#6e7781",
                                "#57606a", "#a40e26", "#1a7f37", "#633c01",
                                "#218bff", "#a475f9", "#3192aa", "#8c959f"),
                        "#d0d7de",
                        "#24292f"));
    }

    private static ThemeOption darkTheme(
            String name,
            String background,
            String foreground,
            List<String> palette,
            String selection,
            String cursorText) {
        return theme(name, background, foreground, palette, selection, cursorText);
    }

    private static ThemeOption lightTheme(
            String name,
            String background,
            String foreground,
            List<String> palette,
            String selection,
            String cursorText) {
        return theme(name, background, foreground, palette, selection, cursorText);
    }

    private static ThemeOption theme(
            String name,
            String background,
            String foreground,
            List<String> palette,
            String selection,
            String cursorText) {
        var fg = color(foreground);
        return new ThemeOption(name, new TerminalTheme(
                color(background),
                fg,
                palette.stream().map(GhosttyFxManualApp::color).toList(),
                fg,
                color(cursorText),
                color(selection),
                fg,
                fg.deriveColor(0, 1, 1, 0.45)));
    }

    private static Color color(String value) {
        return Color.web(value);
    }

    private static List<TerminalOption> detectTerminals() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? detectWindowsTerminals()
                : detectUnixTerminals();
    }

    private static List<TerminalOption> detectWindowsTerminals() {
        var result = new ArrayList<TerminalOption>();
        var seen = new LinkedHashSet<Path>();
        addTerminal(result, seen, "PowerShell", resolveExecutable("pwsh.exe"));
        addTerminal(result, seen, "Windows PowerShell", resolveExecutable("powershell.exe"));
        addTerminal(result, seen, "Command Prompt", resolveExecutable("cmd.exe"));

        var comspec = System.getenv("COMSPEC");
        if (comspec != null && !comspec.isBlank()) {
            addTerminal(result, seen, "COMSPEC", resolveExecutable(comspec));
        }
        return List.copyOf(result);
    }

    private static List<TerminalOption> detectUnixTerminals() {
        var result = new ArrayList<TerminalOption>();
        var seen = new LinkedHashSet<Path>();

        var shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            var resolved = resolveExecutable(shell);
            addTerminal(result, seen, Path.of(shell).getFileName().toString(), resolved);
        }

        addTerminal(result, seen, "bash", resolveExecutable("bash"));
        addTerminal(result, seen, "zsh", resolveExecutable("zsh"));
        addTerminal(result, seen, "fish", resolveExecutable("fish"));
        addTerminal(result, seen, "sh", resolveExecutable("sh"));
        return List.copyOf(result);
    }

    private static void addTerminal(
            List<TerminalOption> terminals,
            LinkedHashSet<Path> seen,
            String label,
            Path executable) {
        if (executable == null || !seen.add(executable)) {
            return;
        }
        terminals.add(new TerminalOption(label, List.of(executable.toString())));
    }

    private static Path resolveExecutable(String candidate) {
        var path = Path.of(candidate);
        if (path.isAbsolute()) {
            return Files.isRegularFile(path) ? path : null;
        }
        if (candidate.indexOf('/') >= 0 || candidate.indexOf('\\') >= 0) {
            var absolute = path.toAbsolutePath().normalize();
            return Files.isRegularFile(absolute) ? absolute : null;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
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
        var pathEntries = System.getenv().getOrDefault("PATH", "").split(File.pathSeparator);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            var pathext = System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD")
                    .toLowerCase(Locale.ROOT)
                    .split(";");
            var hasExtension = candidate.contains(".");
            for (var entry : pathEntries) {
                if (entry.isBlank()) {
                    continue;
                }
                var directory = Path.of(entry);
                if (hasExtension) {
                    var resolved = directory.resolve(candidate);
                    if (Files.isRegularFile(resolved)) {
                        return resolved.toAbsolutePath().normalize();
                    }
                    continue;
                }
                for (var extension : pathext) {
                    var resolved = directory.resolve(candidate + extension);
                    if (Files.isRegularFile(resolved)) {
                        return resolved.toAbsolutePath().normalize();
                    }
                }
            }
            return null;
        }
        for (var entry : pathEntries) {
            if (entry.isBlank()) {
                continue;
            }
            var resolved = Path.of(entry).resolve(candidate);
            if (Files.isExecutable(resolved)) {
                return resolved.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private record TerminalOption(String label, List<String> command) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record ThemeOption(String label, TerminalTheme theme) {
        @Override
        public String toString() {
            return label;
        }
    }
}
