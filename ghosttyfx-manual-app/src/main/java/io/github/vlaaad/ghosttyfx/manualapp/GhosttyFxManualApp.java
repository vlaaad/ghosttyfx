package io.github.vlaaad.ghosttyfx.manualapp;

import io.github.vlaaad.ghosttyfx.GhosttyCanvas;
import io.github.vlaaad.ghosttyfx.GhosttyFx;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

            var cwdField = new TextField(DEFAULT_CWD.toString());
            var chooseDirectory = new Button("Browse...");
            var startTerminal = new Button("New Terminal");
            var tabs = new TabPane();

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

                final GhosttyCanvas canvas;
                try {
                    canvas = GhosttyFx.create(terminal.command(), cwd, System.getenv());
                } catch (RuntimeException e) {
                    var alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("GhosttyFX");
                    alert.setHeaderText("Unable to start terminal");
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                    return;
                }

                var tab = new Tab(terminal.label(), canvas);
                tab.setClosable(true);
                tab.setOnClosed(_2 -> Thread.ofVirtual().name("ghosttyfx-tab-close").start(canvas::close));
                tabs.getTabs().add(tab);
                tabs.getSelectionModel().select(tab);
                canvas.requestFocus();
            });

            HBox.setHgrow(cwdField, Priority.ALWAYS);
            var controls = new HBox(8, terminalPicker, cwdField, chooseDirectory, startTerminal);
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
}
