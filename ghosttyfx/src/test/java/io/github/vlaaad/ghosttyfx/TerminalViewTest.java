package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import javafx.event.EventTarget;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

final class TerminalViewTest {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
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
        assertTrue(started.await(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Timed out waiting for JavaFX runtime startup");
    }

    @Test
    void startsProcessAndStopsItOnClose() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-test-");
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
        var tempDirectory = Files.createTempDirectory("ghosttyfx-font-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            var initialPrefWidth = view.prefWidth(-1);
            var initialPrefHeight = view.prefHeight(-1);
            view.fontProperty().set(Font.font("Monospaced", view.fontProperty().get().getSize() + 6));
            assertTrue(view.prefWidth(-1) != initialPrefWidth || view.prefHeight(-1) != initialPrefHeight,
                    "Expected font change to update preferred size");
            assertThrows(NullPointerException.class, () -> view.fontProperty().set(null));
        }
    }

    @Test
    void themePropertyRejectsNullAndStoresTheme() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-theme-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            var theme = new TerminalTheme(
                    Color.WHITE,
                    Color.BLACK,
                    List.of(),
                    Color.BLACK,
                    Color.WHITE,
                    Color.DARKBLUE,
                    Color.WHITE,
                    0.5,
                    Color.gray(0.25),
                    Color.YELLOW,
                    Color.ORANGE);
            runOnFxThread(() -> {
                view.setTheme(theme);
                assertEquals(theme, view.getTheme());
                assertThrows(NullPointerException.class, () -> view.setTheme(null));
                assertThrows(NullPointerException.class, () -> view.themeProperty().set(null));
                return null;
            });
        }
    }

    @Test
    void exposesShortcutListProperty() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-selection-shortcuts-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            runOnFxThread(() -> {
                var combinations = view.getShortcuts().stream()
                        .map(Shortcut::combination)
                        .toList();
                assertTrue(combinations.contains(isMac()
                        ? new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN)
                        : new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN)));
                assertTrue(combinations.contains(isMac()
                        ? new KeyCodeCombination(KeyCode.A, KeyCombination.META_DOWN)
                        : new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN)));
                assertTrue(combinations.contains(isMac()
                        ? new KeyCodeCombination(KeyCode.F, KeyCombination.META_DOWN)
                        : new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN)));
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
                view.getShortcuts().add(shortcut);
                assertTrue(view.getShortcuts().contains(shortcut));
                assertThrows(NullPointerException.class, () -> view.setShortcuts(null));
                return null;
            });
        }
    }

    @Test
    void sendTextAndSendEscExposeShortcutActions() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-send-text-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            runOnFxThread(() -> {
                assertFalse(view.sendText(""));
                assertTrue(view.sendText("A"));
                assertTrue(view.sendEsc("b"));
                assertThrows(NullPointerException.class, () -> view.sendText(null));
                assertThrows(NullPointerException.class, () -> view.sendEsc(null));

                var textShortcut = new KeyCodeCombination(KeyCode.B, KeyCombination.ALT_DOWN);
                var sendTextShortcut = new Shortcut(textShortcut, () -> view.sendText("B"));
                view.getShortcuts().add(sendTextShortcut);
                assertTrue(view.getShortcuts().contains(sendTextShortcut));
                assertTrue(sendTextShortcut.action().getAsBoolean());

                var escShortcut = new KeyCodeCombination(KeyCode.F, KeyCombination.ALT_DOWN);
                var sendEscShortcut = new Shortcut(escShortcut, () -> view.sendEsc("f"));
                view.getShortcuts().add(sendEscShortcut);
                assertTrue(view.getShortcuts().contains(sendEscShortcut));
                assertTrue(sendEscShortcut.action().getAsBoolean());
                return null;
            });
        }
    }

    @Test
    void shiftArrowShortcutsExtendExistingSelection() throws Exception {
        var marker = "ghosttyfx-selection";
        var tempDirectory = Files.createTempDirectory("ghosttyfx-selection-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, marker);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            await("terminal output to be addressable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireShortcut(view, selectAllShortcut());
                return marker.equals(view.getInputMethodRequests().getSelectedText())
                        ? Optional.of(Boolean.TRUE)
                        : Optional.empty();
            }));

            runOnFxThread(() -> {
                dragSelection(view, 1, 1);
                fireShortcut(view, new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(1, 3), view.getInputMethodRequests().getSelectedText());

                fireShortcut(view, new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(1, 2), view.getInputMethodRequests().getSelectedText());

                fireShortcut(view, new KeyCodeCombination(KeyCode.HOME, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(0, 2), view.getInputMethodRequests().getSelectedText());

                fireShortcut(view, new KeyCodeCombination(KeyCode.END, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(1), view.getInputMethodRequests().getSelectedText());
                return null;
            });
        }
    }

    @Test
    void viewportScrollShortcutsReportUnavailableWithoutScrollableViewport() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-viewport-scroll-shortcuts-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            runOnFxThread(() -> {
                assertFalse(view.scrollViewportPageUp());
                assertFalse(view.scrollViewportPageDown());
                assertFalse(view.scrollViewportToTop());
                assertFalse(view.scrollViewportToBottom());
                return null;
            });
        }
    }

    @Test
    void viewportScrollShortcutsConsumeAtScrollableBoundaries() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-viewport-scroll-boundary-shortcuts-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, lineOutput(80));

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            await("scrollable terminal output", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.scrollViewportToTop() ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertTrue(view.scrollViewportPageUp());
                assertTrue(view.scrollViewportToTop());
                assertTrue(view.scrollViewportToBottom());
                assertTrue(view.scrollViewportPageDown());
                assertTrue(view.scrollViewportToBottom());
                return null;
            });
        }
    }

    @Test
    void searchUsesSelectionAsInitialQueryAndNavigatesMatches() throws Exception {
        var marker = "ghosttyfx-search";
        var output = marker + "\nother\n" + marker;
        var tempDirectory = Files.createTempDirectory("ghosttyfx-search-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, output);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            await("terminal output to become searchable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireShortcut(view, selectAllShortcut());
                var text = view.getInputMethodRequests().getSelectedText();
                return text != null && text.contains(marker) ? Optional.of(Boolean.TRUE) : Optional.empty();
            }));

            runOnFxThread(() -> {
                dragSelection(view, 0, marker.length() - 1);
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());

                assertTrue(view.toggleSearch());
                assertEquals(marker, view.getSearchText());
                assertEquals("...", ((Label) view.lookup("#ghosttyfx-search-count")).getText());
                return null;
            });

            await("search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.getSearchMatchCount() == 2 ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertEquals(2, view.getSearchMatchCount());
                assertEquals(0, view.getSelectedSearchMatchIndex());

                assertTrue(view.searchNext());
                assertEquals(1, view.getSelectedSearchMatchIndex());
                assertTrue(view.searchPrevious());
                assertEquals(0, view.getSelectedSearchMatchIndex());

                fireShortcut(view, new KeyCodeCombination(KeyCode.ESCAPE));
                assertEquals(-1, view.getSelectedSearchMatchIndex());
                return null;
            });
        }
    }

    @Test
    void searchMatchesSelectedCombiningGraphemeText() throws Exception {
        var marker = "e\u0301a";
        var output = marker + "\nother\n" + marker;
        var tempDirectory = Files.createTempDirectory("ghosttyfx-search-grapheme-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, output);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            await("terminal grapheme output to become searchable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireShortcut(view, selectAllShortcut());
                var text = view.getInputMethodRequests().getSelectedText();
                return text != null && text.contains(marker) ? Optional.of(Boolean.TRUE) : Optional.empty();
            }));

            runOnFxThread(() -> {
                dragSelection(view, 0, 1);
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());
                assertTrue(view.toggleSearch());
                assertEquals(marker, view.getSearchText());
                assertEquals("...", ((Label) view.lookup("#ghosttyfx-search-count")).getText());
                return null;
            });

            await("grapheme search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.getSearchMatchCount() == 2 ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertEquals(2, view.getSearchMatchCount());
                assertEquals(0, view.getSelectedSearchMatchIndex());
                return null;
            });
        }
    }

    @Test
    void searchMatchesSelectedWideGraphemeText() throws Exception {
        var marker = "界a";
        var output = marker + "\nother\n" + marker;
        var tempDirectory = Files.createTempDirectory("ghosttyfx-search-wide-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, output);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            await("terminal wide output to become searchable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireShortcut(view, selectAllShortcut());
                var text = view.getInputMethodRequests().getSelectedText();
                return text != null && text.contains(marker) ? Optional.of(Boolean.TRUE) : Optional.empty();
            }));

            runOnFxThread(() -> {
                dragSelection(view, 0, 2);
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());
                assertTrue(view.toggleSearch());
                assertEquals(marker, view.getSearchText());
                assertEquals("...", ((Label) view.lookup("#ghosttyfx-search-count")).getText());
                return null;
            });

            await("wide search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.getSearchMatchCount() == 2 ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertEquals(2, view.getSearchMatchCount());
                assertEquals(0, view.getSelectedSearchMatchIndex());
                return null;
            });
        }
    }

    @Test
    void searchFieldUpdatesMatchesAndConsumesEnterShortcuts() throws Exception {
        var output = "alpha\nbeta\nalpha";
        var tempDirectory = Files.createTempDirectory("ghosttyfx-search-field-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, output);

        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            await("terminal output to become searchable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireShortcut(view, selectAllShortcut());
                var text = view.getInputMethodRequests().getSelectedText();
                return text != null && text.contains("beta") ? Optional.of(Boolean.TRUE) : Optional.empty();
            }));

            var stageRef = new AtomicReference<Stage>();
            try {
                runOnFxThread(() -> {
                    var other = new Button("Other");
                    var stage = new Stage();
                    stageRef.set(stage);
                    stage.setScene(new Scene(new VBox(view, other), view.prefWidth(-1), view.prefHeight(-1) + 40));
                    stage.show();
                    fireShortcut(view, searchShortcut());
                    var search = (HBox) view.lookup("#ghosttyfx-search");
                    var count = (Label) view.lookup("#ghosttyfx-search-count");
                    var field = (TextField) view.lookup("#ghosttyfx-search-field");
                    assertSame(field, search.getChildren().getFirst());
                    assertSame(count, search.getChildren().get(1));
                    assertEquals(0, search.getMinWidth());
                    assertEquals(search.getPrefWidth(), search.getMaxWidth());
                    assertTrue(search.getPrefWidth() <= 320);
                    view.resize(220, view.getHeight());
                    assertTrue(search.getPrefWidth() < 320);
                    assertTrue(field.getBorder().getStrokes().isEmpty());
                    assertEquals("Type to search...", view.getSearchPromptText());
                    assertEquals("Type to search...", field.getPromptText());
                    view.setSearchPromptText("Find in terminal");
                    assertEquals("Find in terminal", field.getPromptText());
                    assertThrows(NullPointerException.class, () -> view.setSearchPromptText(null));
                    assertThrows(NullPointerException.class, () -> view.searchPromptTextProperty().set(null));
                    field.setText("");
                    assertTrue(count.isManaged());
                    assertTrue(count.isVisible());
                    assertEquals("0/0", count.getText());
                    field.setText("alpha");
                    assertEquals("...", count.getText());
                    return null;
                });

                await("search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                        view.getSearchMatchCount() == 2 ? Optional.of(Boolean.TRUE) : Optional.empty()));

                runOnFxThread(() -> {
                    var other = (Button) ((VBox) view.getParent()).getChildren().get(1);
                    var count = (Label) view.lookup("#ghosttyfx-search-count");
                    var field = (TextField) view.lookup("#ghosttyfx-search-field");
                    var search = (HBox) view.lookup("#ghosttyfx-search");
                    assertEquals(2, view.getSearchMatchCount());
                    assertEquals(0, view.getSelectedSearchMatchIndex());
                    assertEquals("1/2", count.getText());
                    assertTrue(count.isManaged());
                    assertTrue(count.isVisible());
                    assertEquals(Label.USE_PREF_SIZE, count.getMinWidth());
                    assertEquals(Label.USE_COMPUTED_SIZE, count.getMaxWidth());

                    var font = Font.font("Monospaced", field.getFont().getSize() + 3);
                    view.setFont(font);
                    assertEquals(font, field.getFont());
                    assertEquals(font, count.getFont());

                    var next = keyEvent(new KeyCodeCombination(KeyCode.ENTER));
                    field.fireEvent(next);
                    assertEquals(1, view.getSelectedSearchMatchIndex());
                    field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.ENTER)));
                    assertEquals(1, view.getSelectedSearchMatchIndex());

                    var previous = keyEvent(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHIFT_DOWN));
                    field.fireEvent(previous);
                    assertEquals(0, view.getSelectedSearchMatchIndex());
                    field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHIFT_DOWN)));
                    assertEquals(0, view.getSelectedSearchMatchIndex());

                    field.positionCaret(2);
                    var down = keyEvent(new KeyCodeCombination(KeyCode.DOWN));
                    field.fireEvent(down);
                    assertEquals(1, view.getSelectedSearchMatchIndex());
                    assertEquals(2, field.getCaretPosition());
                    field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.DOWN)));
                    assertEquals(1, view.getSelectedSearchMatchIndex());
                    assertEquals(2, field.getCaretPosition());

                    var up = keyEvent(new KeyCodeCombination(KeyCode.UP));
                    field.fireEvent(up);
                    assertEquals(0, view.getSelectedSearchMatchIndex());
                    assertEquals(2, field.getCaretPosition());
                    field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.UP)));
                    assertEquals(0, view.getSelectedSearchMatchIndex());
                    assertEquals(2, field.getCaretPosition());
                    other.requestFocus();
                    assertFalse(field.isFocused());
                    assertFalse(search.isVisible());
                    return null;
                });
            } finally {
                runOnFxThread(() -> {
                    var stage = stageRef.get();
                    if (stage != null) {
                        stage.close();
                    }
                    return null;
                });
            }
        }
    }

    @Test
    void ctrlCCopyClearsSelection() throws Exception {
        var marker = "ghosttyfx-copy-shortcut";
        var tempDirectory = Files.createTempDirectory("ghosttyfx-copy-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, marker);
        var clipboardContents = runOnFxThread(TerminalViewTest::snapshotClipboardContents);

        try {
            try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
                var selectedText = await("terminal output to become selectable", START_TIMEOUT, () -> runOnFxThread(() -> {
                    fireShortcut(view, selectAllShortcut());
                    var text = view.getInputMethodRequests().getSelectedText();
                    return text != null && text.contains(marker) ? Optional.of(text) : Optional.empty();
                }));

                runOnFxThread(() -> {
                    fireShortcut(view, copyShortcut());
                    var clipboard = Clipboard.getSystemClipboard();
                    assertTrue(selectedText.equals(clipboard.getString()), "Expected copied text to match current selection");
                    var remainingSelection = view.getInputMethodRequests().getSelectedText();
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
        var tempDirectory = Files.createTempDirectory("ghosttyfx-close-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, marker);
        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            var handle = await("shell process to start", START_TIMEOUT, () -> readAliveProcess(pidFile));
            try {
                var selectedText = await("terminal output to become selectable", START_TIMEOUT, () -> runOnFxThread(() -> {
                    fireShortcut(view, selectAllShortcut());
                    var text = view.getInputMethodRequests().getSelectedText();
                    return text != null && text.contains(marker) ? Optional.of(text) : Optional.empty();
                }));

                view.close();

                var remainingSelection = runOnFxThread(() -> view.getInputMethodRequests().getSelectedText());
                assertTrue(selectedText.equals(remainingSelection), "Expected terminal contents to remain readable after close()");
                awaitProcessStop(handle);
            } finally {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            }
        }
    }

    @Test
    void closeCanRunOffFxThreadWhileSearchIsVisible() throws Exception {
        var marker = "ghosttyfx-close-search";
        var tempDirectory = Files.createTempDirectory("ghosttyfx-close-search-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, marker);
        try (var view = GhosttyFx.create(shell.command(), tempDirectory, System.getenv())) {
            var handle = await("shell process to start", START_TIMEOUT, () -> readAliveProcess(pidFile));
            try {
                await("terminal output to become searchable", START_TIMEOUT, () -> runOnFxThread(() -> {
                    fireShortcut(view, selectAllShortcut());
                    var text = view.getInputMethodRequests().getSelectedText();
                    return text != null && text.contains(marker) ? Optional.of(Boolean.TRUE) : Optional.empty();
                }));
                runOnFxThread(() -> {
                    assertTrue(view.toggleSearch());
                    assertTrue(view.lookup("#ghosttyfx-search").isVisible());
                    return null;
                });

                var failure = new AtomicReference<Throwable>();
                var thread = Thread.ofVirtual().name("ghosttyfx-stage-close-test").start(() -> {
                    try {
                        view.close();
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });
                thread.join(STOP_TIMEOUT);
                if (failure.get() != null) {
                    fail(failure.get());
                }
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
        assertTrue(completed.await(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Timed out waiting for JavaFX task");

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

    private static KeyCombination searchShortcut() {
        return isMac()
                ? new KeyCodeCombination(KeyCode.F, KeyCombination.META_DOWN)
                : new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
    }

    private static KeyEvent fireShortcut(EventTarget target, KeyCombination shortcut) {
        var event = keyEvent(shortcut);
        Event.fireEvent(target, event);
        return event;
    }

    private static KeyEvent keyEvent(KeyCombination shortcut) {
        if (!(shortcut instanceof KeyCodeCombination combination)) {
            throw new IllegalArgumentException("Expected key-code shortcut, got: " + shortcut);
        }

        return new KeyEvent(
                KeyEvent.KEY_PRESSED,
                "",
                "",
                combination.getCode(),
                modifierDown(combination.getShift()),
                modifierDown(combination.getControl()) || (shortcutDownOnCurrentPlatform(combination.getShortcut()) && !isMac()),
                modifierDown(combination.getAlt()),
                modifierDown(combination.getMeta()) || (shortcutDownOnCurrentPlatform(combination.getShortcut()) && isMac()));
    }

    private static void dragSelection(TerminalView view, int fromColumn, int toColumn) {
        var cellWidth = (view.prefWidth(-1) - 10) / 80;
        var cellHeight = view.prefHeight(-1) / 24;
        var fromX = fromColumn * cellWidth + cellWidth * 0.1;
        var toX = toColumn * cellWidth + cellWidth * 0.8;
        var y = cellHeight * 0.5;
        Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_PRESSED, fromX, y, true));
        Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_DRAGGED, toX, y, true));
        Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_RELEASED, toX, y, false));
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
