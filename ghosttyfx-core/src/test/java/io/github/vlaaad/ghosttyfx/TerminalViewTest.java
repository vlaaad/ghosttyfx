package io.github.vlaaad.ghosttyfx;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.event.EventType;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodHighlight;
import javafx.scene.input.InputMethodTextRun;
import javafx.scene.input.ScrollEvent;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

final class TerminalViewTest {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @BeforeAll
    static void initializeJavaFxRuntime() throws Exception {
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

        try (var _ = createView(shell, tempDirectory)) {
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

        try (var view = createView(shell, tempDirectory)) {
            var initialPrefWidth = view.prefWidth(-1);
            var initialPrefHeight = view.prefHeight(-1);
            view.fontProperty().set(Font.font("Monospaced", view.fontProperty().get().getSize() + 6));
            assertTrue(view.prefWidth(-1) != initialPrefWidth || view.prefHeight(-1) != initialPrefHeight,
                    "Expected font change to update preferred size");
            assertThrows(NullPointerException.class, () -> view.fontProperty().set(null));
        }
    }

    @Test
    void usesSameOsc22FallbackForBatchedAndSeparateRequests() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            for (var terminator : List.of("\u001B\\", "\u0007")) {
                terminal.emit("\u001B]22;text\u001B\\\u001B]2;cursor-reset-" + terminator.length() + "\u001B\\");
                awaitTitle(view, "cursor-reset-" + terminator.length());
                assertSame(Cursor.TEXT, runOnFxThread(view::getCursor));
                terminal.emit("\u001B]22;pointer" + terminator
                        + "\u001B]22;zoom-in" + terminator
                        + "\u001B]2;cursor-combined-" + terminator.length() + "\u001B\\");
                awaitTitle(view, "cursor-combined-" + terminator.length());
                assertSame(Cursor.TEXT, runOnFxThread(view::getCursor));
                terminal.emit("\u001B]22;pointer" + terminator
                        + "\u001B]2;cursor-supported-" + terminator.length() + "\u001B\\");
                awaitTitle(view, "cursor-supported-" + terminator.length());
                assertSame(Cursor.HAND, runOnFxThread(view::getCursor));
                terminal.emit("\u001B]22;zoom-in" + terminator
                        + "\u001B]2;cursor-unsupported-" + terminator.length() + "\u001B\\");
                awaitTitle(view, "cursor-unsupported-" + terminator.length());
                assertSame(Cursor.TEXT, runOnFxThread(view::getCursor));
            }
        }
    }

    @Test
    void appliesOsc22CursorRequestsWithoutPointerMovement() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            assertSame(Cursor.TEXT, runOnFxThread(view::getCursor));
            var requests = Map.of(
                    "pointer", Cursor.HAND,
                    "text", Cursor.TEXT,
                    "ew-resize", Cursor.H_RESIZE,
                    "ns-resize", Cursor.V_RESIZE,
                    "ne-resize", Cursor.NE_RESIZE,
                    "grab", Cursor.OPEN_HAND,
                    "grabbing", Cursor.CLOSED_HAND,
                    "wait", Cursor.WAIT,
                    "default", Cursor.DEFAULT);
            for (var request : requests.entrySet()) {
                terminal.emit("\u001B]22;" + request.getKey() + "\u001B\\\u001B]2;" + request.getKey() + "\u001B\\");
                awaitTitle(view, request.getKey());
                assertSame(request.getValue(), runOnFxThread(view::getCursor));
            }
        }
    }

    @Test
    void fallsBackForUnsupportedOsc22ShapesAndHonorsReset() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            terminal.emit("\u001B]22;crosshair\u0007\u001B]2;crosshair-ready\u001B\\");
            awaitTitle(view, "crosshair-ready");
            assertSame(Cursor.CROSSHAIR, runOnFxThread(view::getCursor));
            for (var shape : List.of("zoom-in", "help")) {
                terminal.emit("\u001B]22;" + shape + "\u001B\\\u001B]2;" + shape + "\u001B\\");
                awaitTitle(view, shape);
                assertSame(Cursor.TEXT, runOnFxThread(view::getCursor));
            }
            terminal.emit("\u001B]22;crosshair\u0007\u001B]22;unknown-shape\u0007\u001B]2;unknown-shape\u001B\\");
            awaitTitle(view, "unknown-shape");
            assertSame(Cursor.CROSSHAIR, runOnFxThread(view::getCursor));
            terminal.emit("\u001B]22;\u001B\\\u001B]2;shape-reset\u001B\\");
            awaitTitle(view, "shape-reset");
            assertSame(Cursor.TEXT, runOnFxThread(view::getCursor));
        }
    }

    @Test
    void restoresApplicationCursorAfterLinkHoverAndMouseExit() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            terminal.emit("\u001B]22;crosshair\u001B\\\u001B]8;;https://example.test\u001B\\link\u001B]8;;\u001B\\\r\nplain"
                    + "\u001B]2;cursor-link-ready\u001B\\");
            awaitTitle(view, "cursor-link-ready");
            runOnFxThread(() -> {
                cellColor(view, 0, 0);
                moveToCell(view, 1, 0);
                assertSame(Cursor.HAND, view.getCursor());
                return null;
            });
            terminal.emit("\u001B]22;ew-resize\u001B\\\u001B]2;resize-ready\u001B\\");
            awaitTitle(view, "resize-ready");
            runOnFxThread(() -> {
                assertSame(Cursor.HAND, view.getCursor());
                moveToCell(view, 1, 1);
                assertSame(Cursor.H_RESIZE, view.getCursor());
                moveToCell(view, 1, 0);
                assertSame(Cursor.HAND, view.getCursor());
                Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_EXITED, -1, -1, false));
                assertSame(Cursor.H_RESIZE, view.getCursor());
                assertNull(view.getHoveredLink());
                return null;
            });
        }
    }

    @Test
    void honorsApplicationCursorWhileMouseReportingSuppressesLinkHover() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            terminal.emit("\u001B[?1000h\u001B]22;crosshair\u001B\\\u001B]8;;https://example.test\u001B\\link\u001B]8;;\u001B\\"
                    + "\u001B]2;mouse-cursor-ready\u001B\\");
            awaitTitle(view, "mouse-cursor-ready");
            runOnFxThread(() -> {
                cellColor(view, 0, 0);
                moveToCell(view, 1, 0);
                assertSame(Cursor.CROSSHAIR, view.getCursor());
                assertNull(view.getHoveredLink());
                return null;
            });
            terminal.emit("\u001B[?1000l\u001B]2;mouse-cursor-released\u001B\\");
            awaitTitle(view, "mouse-cursor-released");
            assertSame(Cursor.HAND, runOnFxThread(view::getCursor));
        }
    }

    @Test
    void cursorBlinkingPropertyStoresValue() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-cursor-blinking-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = createView(shell, tempDirectory)) {
            runOnFxThread(() -> {
                assertTrue(view.isCursorBlinking());

                view.setCursorBlinking(false);
                assertFalse(view.isCursorBlinking());
                assertFalse(view.cursorBlinkingProperty().get());

                view.cursorBlinkingProperty().set(true);
                assertTrue(view.isCursorBlinking());
                return null;
            });
        }
    }

    @Test
    void mouseTrackingEnabledReflectsTerminalMode() throws Exception {
        try (var view = createView("")) {
            runOnFxThread(() -> {
                assertFalse(view.isMouseTrackingEnabled());
                return null;
            });
        }

        try (var view = createView("\u001B[?1000h")) {
            await("mouse tracking to enable", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.isMouseTrackingEnabled() ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                assertTrue(view.isMouseTrackingEnabled());
                return null;
            });
        }

        try (var view = createView("\u001B[?1000h\u001B[?1000l")) {
            await("terminal output to close", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.getTerminalState() instanceof TerminalState.Closed ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                assertFalse(view.isMouseTrackingEnabled());
                return null;
            });
        }
    }

    @Test
    void terminalSizePropertyTracksLayoutResize() throws Exception {
        try (var view = createView("")) {
            runOnFxThread(() -> {
                assertEquals(new TerminalSize(80, 24), view.getTerminalSize());
                assertEquals(view.getTerminalSize(), view.terminalSizeProperty().get());

                var observed = new AtomicReference<TerminalSize>();
                view.terminalSizeProperty().addListener((_, _, size) -> observed.set(size));
                view.resize(
                        40 * cellWidth(view) + TerminalView.SCROLLBAR_WIDTH_PX + 2 * TerminalView.SCROLLBAR_MARGIN_PX,
                        12 * cellHeight(view));

                var expected = new TerminalSize(40, 12);
                assertEquals(expected, view.getTerminalSize());
                assertEquals(expected, view.terminalSizeProperty().get());
                assertEquals(expected, observed.get());
                return null;
            });
        }
    }

    @Test
    void terminalSizeRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(1, 0));
    }

    @Test
    void rendersDirectRgbRgbaAndPngImages() throws Exception {
        var output = "\u001B[?25l"
                + "\u001B[1;1H\u001B_Ga=T,t=d,f=24,i=1,p=1,s=1,v=1,c=1,r=1,C=1,q=2;/wAA\u001B\\"
                + "\u001B[1;2H\u001B[48;2;0;0;255m \u001B[0m"
                + "\u001B[1;2H\u001B_Ga=T,t=d,f=32,i=2,p=2,s=1,v=1,c=1,r=1,C=1,q=2;/wAAgA==\u001B\\"
                + "\u001B[1;3H\u001B_Ga=T,t=d,f=100,i=3,p=3,c=1,r=1,C=1,q=2;"
                + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==\u001B\\";
        try (var view = createView(output)) {
            awaitTerminalClosed(view);
            var colors = runOnFxThread(() -> List.of(cellColor(view, 0, 0), cellColor(view, 1, 0), cellColor(view, 2, 0)));
            assertColor(Color.RED, colors.get(0));
            assertColor(Color.rgb(128, 0, 127), colors.get(1));
            assertColor(Color.RED, colors.get(2));
        }
    }

    @Test
    void synchronizedOutputCapturesTheFrameBeforeTheHoldAndReleasesItTogether() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit("\u001B[?25l\u001B[48;2;255;0;0m "
                    + "\u001B[?2026h\u001B[1;1H\u001B[48;2;0;0;255m "
                    + "\u001B]2;held-frame\u001B\\");
            awaitTitle(view, "held-frame");
            runOnFxThread(() -> {
                assertColor(Color.RED, cellColor(view, 0, 0));
                view.setCursorBlinking(false);
                assertColor(Color.RED, cellColor(view, 0, 0));
                return null;
            });

            terminal.emit("\u001B[?2026l");
            awaitCellColor(view, 0, 0, Color.BLUE);
        }
    }

    @Test
    void synchronizedOutputCapturesCompletedFramesBetweenHoldsInOneWrite() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit("\u001B[?25l\u001B[48;2;255;0;0m \u001B[?2026h"
                    + "\u001B[1;1H\u001B[48;2;0;0;255m \u001B[?2026l\u001B[?2026h"
                    + "\u001B[1;1H\u001B[48;2;0;255;0m \u001B]2;second-hold\u001B\\");
            awaitTitle(view, "second-hold");
            assertColor(Color.BLUE, runOnFxThread(() -> cellColor(view, 0, 0)));

            terminal.emit("\u001B[?2026l");
            awaitCellColor(view, 0, 0, Color.LIME);
        }
    }

    @Test
    void synchronizedOutputKeepsKittyImagesFrozenUntilRelease() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            terminal.emit("\u001B[?25l\u001B_Ga=T,t=d,f=24,i=1,p=1,s=1,v=1,c=1,r=1,C=1,q=2;/wAA\u001B\\");
            awaitCellColor(view, 0, 0, Color.RED);

            terminal.emit("\u001B[?2026h\u001B[1;1H"
                    + "\u001B_Ga=T,t=d,f=24,i=1,p=1,s=1,v=1,c=1,r=1,C=1,q=2;AAD/\u001B\\"
                    + "\u001B]2;held-image\u001B\\");
            awaitTitle(view, "held-image");
            runOnFxThread(() -> {
                view.setCursorBlinking(false);
                assertColor(Color.RED, cellColor(view, 0, 0));
                return null;
            });

            terminal.emit("\u001B[?2026l");
            awaitCellColor(view, 0, 0, Color.BLUE);
        }
    }

    @Test
    void synchronizedOutputTimesOutAndAllowsAnotherHold() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit("\u001B[?25l\u001B[48;2;255;0;0m \u001B[?2026h"
                    + "\u001B[1;1H\u001B[48;2;0;0;255m \u001B]2;hold-timeout\u001B\\");
            awaitTitle(view, "hold-timeout");
            assertColor(Color.RED, runOnFxThread(() -> cellColor(view, 0, 0)));
            await("synchronized output timeout", Duration.ofSeconds(5), () -> runOnFxThread(() ->
                    colorsEqual(Color.BLUE, cellColor(view, 0, 0)) ? Optional.of(Boolean.TRUE) : Optional.empty()));

            terminal.emit("\u001B[1;1H\u001B[48;2;255;0;0m \u001B[?2026h"
                    + "\u001B[1;1H\u001B[48;2;0;255;0m \u001B]2;hold-after-timeout\u001B\\");
            awaitTitle(view, "hold-after-timeout");
            assertColor(Color.RED, runOnFxThread(() -> cellColor(view, 0, 0)));
            terminal.emit("\u001B[?2026l");
            awaitCellColor(view, 0, 0, Color.LIME);
        }
    }

    @Test
    void synchronizedOutputEndsOnResizeAndFullReset() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit("\u001B[?25l\u001B[48;2;255;0;0m \u001B[?2026h"
                    + "\u001B[1;1H\u001B[48;2;0;0;255m \u001B]2;hold-before-resize\u001B\\");
            awaitTitle(view, "hold-before-resize");
            assertColor(Color.RED, runOnFxThread(() -> cellColor(view, 0, 0)));
            runOnFxThread(() -> {
                view.resize(view.getWidth() + cellWidth(view), view.getHeight());
                assertColor(Color.BLUE, cellColor(view, 0, 0));
                return null;
            });

            terminal.emit("\u001B[?2026h\u001B[1;1H\u001B[48;2;255;0;0m "
                    + "\u001B]2;hold-before-reset\u001B\\");
            awaitTitle(view, "hold-before-reset");
            assertColor(Color.BLUE, runOnFxThread(() -> cellColor(view, 0, 0)));
            terminal.emit("\u001Bc\u001B[?25l\u001B[48;2;0;255;0m ");
            awaitCellColor(view, 0, 0, Color.LIME);
        }
    }

    @Test
    void smoothScrollingDrawsPartialRowsAndClampsAtBothEnds() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit(stripedOutput(30) + "\u001B]2;scroll-ready\u001B\\");
            awaitTitle(view, "scroll-ready");
            runOnFxThread(() -> {
                assertTrue(view.scrollViewportToTop());
                smoothScroll(view, 0.5);
                assertColor(Color.RED, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.25)));
                assertColor(Color.BLUE, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.75)));
                assertColor(Color.BLUE, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 23, 0.25)));
                assertColor(Color.RED, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 23, 0.75)));
                smoothScroll(view, 0.5);
                assertColor(Color.BLUE, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.25)));
                smoothScroll(view, -0.25);
                assertColor(Color.RED, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.1)));
                assertColor(Color.BLUE, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.5)));
                smoothScroll(view, -100);
                assertColor(Color.RED, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.75)));
                smoothScroll(view, 0.5);
                assertTrue(view.scrollViewportToTop());
                assertColor(Color.RED, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.75)));
                smoothScroll(view, 100);
                assertColor(Color.BLUE, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 23, 0.75)));
                smoothScroll(view, 0.5);
                smoothScroll(view, -0.5);
                assertColor(Color.RED, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 23, 0.25)));
                assertColor(Color.BLUE, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 23, 0.75)));
                return null;
            });
        }
    }

    @Test
    void smoothScrollingKeepsLinksAndSelectionAlignedIncludingOverscan() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            var lines = new ArrayList<String>();
            for (var i = 0; i < 30; i++) {
                lines.add("\u001B]8;;https://row-" + i + ".test\u001B\\row-" + i + "\u001B]8;;\u001B\\");
            }
            terminal.emit(String.join("\r\n", lines) + "\u001B]2;links-ready\u001B\\");
            awaitTitle(view, "links-ready");
            runOnFxThread(() -> {
                assertTrue(view.scrollViewportToTop());
                smoothScroll(view, 0.75);
                moveToCell(view, 1, 0);
                assertEquals(new TerminalLink.Osc8("https://row-1.test"), view.getHoveredLink());
                moveToCell(view, 1, 23);
                assertEquals(new TerminalLink.Osc8("https://row-24.test"), view.getHoveredLink());
                dragSelection(view, 0, 4);
                assertEquals("row-1", view.getInputMethodRequests().getSelectedText());
                return null;
            });
        }
    }

    @Test
    void selectionAutoscrollIncludesThePartialBottomRowAndReachesTheLastRow() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> {
                cellColor(view, 0, 0);
                view.resize(view.getWidth(), view.getHeight() + 0.75 * cellHeight(view));
                return null;
            });
            var lines = new ArrayList<String>();
            for (var row = 0; row < 100; row++) {
                lines.add("\u001B]8;;https://row-" + row + ".test\u001B\\          row-" + row + "\u001B]8;;\u001B\\");
            }
            terminal.emit("\u001B[?25l" + String.join("\r\n", lines) + "\u001B]2;autoscroll-ready\u001B\\");
            awaitTitle(view, "autoscroll-ready");
            runOnFxThread(() -> {
                view.scrollViewportToTop();
                smoothScroll(view, 0.75);
                Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_PRESSED, cellX(view, 0, 0.1), cellY(view, 0, 0.1), true));
                Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_DRAGGED, cellX(view, 17, 0.5), view.getHeight() + 10, true));
                return null;
            });
            await("selection autoscroll beyond the first viewport", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.getInputMethodRequests().getSelectedText().contains("row-26") ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                assertColor(view.getTheme().selectionColor(), pixelColor(view, cellX(view, 1, 0.5), cellY(view, 23, 0.75)));
                return null;
            });
            await("selection autoscroll to the last full row", START_TIMEOUT, () -> runOnFxThread(() -> {
                Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_MOVED, cellX(view, 11, 0.5), cellY(view, 23, 0.1), true));
                return new TerminalLink.Osc8("https://row-99.test").equals(view.getHoveredLink())
                        ? Optional.of(Boolean.TRUE) : Optional.empty();
            }));
            runOnFxThread(() -> {
                assertTrue(view.getInputMethodRequests().getSelectedText().contains("row-99"));
                assertColor(view.getTheme().selectionColor(), pixelColor(view, cellX(view, 1, 0.5), cellY(view, 23, 0.75)));
                Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_RELEASED, cellX(view, 17, 0.5), view.getHeight() + 10, false));
                return null;
            });
        }
    }

    @Test
    void selectionAutoscrollAtTheTopClearsTheFractionalOffset() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit(stripedOutput(100) + "\u001B]2;top-autoscroll-ready\u001B\\");
            awaitTitle(view, "top-autoscroll-ready");
            runOnFxThread(() -> {
                view.scrollViewportToTop();
                smoothScroll(view, 0.75);
                Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_PRESSED, cellX(view, 5, 0.5), cellY(view, 10, 0.5), true));
                Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_DRAGGED, cellX(view, 5, 0.5), -10, true));
                return null;
            });
            await("selection autoscroll to the first full row", START_TIMEOUT, () -> runOnFxThread(() ->
                    colorsEqual(Color.RED, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.75)))
                            ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_RELEASED, cellX(view, 5, 0.5), -10, false));
                return null;
            });
        }
    }

    @Test
    void smoothScrollingRevealsPinnedAndRelativeImages() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> {
                cellColor(view, 0, 0);
                view.resize(view.getWidth(), view.getHeight() + 0.75 * cellHeight(view));
                return null;
            });
            terminal.emit("\u001B[?25l" + "line\r\n".repeat(23)
                    + "\u001B_Ga=T,t=d,f=24,i=1,p=1,s=1,v=1,c=1,r=1,C=1,q=2;/wAA\u001B\\"
                    + "\u001B_Ga=T,t=d,f=24,i=2,p=1,P=1,Q=1,H=1,V=1,s=1,v=1,c=1,r=1,q=2;AAD/\u001B\\"
                    + "\r\n\r\n\u001B[4G\u001B_Ga=T,t=d,f=24,i=3,p=1,s=1,v=1,c=100,r=1,C=1,q=2;AP8A\u001B\\"
                    + "\r\n" + "line\r\n".repeat(5) + "\u001B]2;images-ready\u001B\\");
            awaitTitle(view, "images-ready");
            runOnFxThread(() -> {
                assertTrue(view.scrollViewportToTop());
                smoothScroll(view, 0.75);
                assertColor(Color.RED, pixelColor(view, cellX(view, 0, 0.5), cellY(view, 22, 0.5)));
                assertColor(Color.BLUE, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 23, 0.5)));
                assertColor(Color.LIME, pixelColor(view, cellX(view, 3, 0.5), cellY(view, 24, 0.5)));
                return null;
            });
        }
    }

    @Test
    void smoothScrollingKeepsTheOverscanCursorAndPreeditVisible() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> {
                cellColor(view, 0, 0);
                view.setCursorBlinking(false);
                return null;
            });
            terminal.emit("line\r\n".repeat(29) + "prompt> \u001B]2;cursor-ready\u001B\\");
            awaitTitle(view, "cursor-ready");
            runOnFxThread(() -> {
                view.scrollViewportToBottom();
                var location = view.getInputMethodRequests().getTextLocation(0);
                smoothScroll(view, -4 / cellHeight(view));
                assertEquals(location.getX(), view.getInputMethodRequests().getTextLocation(0).getX());
                assertEquals(location.getY() + 4, view.getInputMethodRequests().getTextLocation(0).getY());
                assertColor(view.getTheme().cursorColor(), pixelColor(view, cellX(view, 8, 0), cellY(view, 23, 0.5) + 4));

                view.scrollViewportToBottom();
                Event.fireEvent(view, new InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                        List.of(new InputMethodTextRun("xy", InputMethodHighlight.UNSELECTED_RAW)), "", 1));
                var reference = view.snapshot(null, null);
                smoothScroll(view, -4 / cellHeight(view));
                var scrolled = view.snapshot(null, null);
                var hasPreeditInk = false;
                for (var y = 0; y < (int) cellHeight(view) - 4; y++) {
                    for (var x = (int) cellX(view, 9, 0) + 1; x < (int) cellX(view, 10, 0); x++) {
                        var color = reference.getPixelReader().getColor(x, 23 * (int) cellHeight(view) + y);
                        hasPreeditInk |= !colorsEqual(view.getTheme().background(), color);
                        assertColor(color, scrolled.getPixelReader().getColor(x, 23 * (int) cellHeight(view) + y + 4));
                    }
                }
                assertTrue(hasPreeditInk, "Expected the composed text to be drawn");
                smoothScroll(view, -1);
                assertEquals(new javafx.geometry.Point2D(0, 0), view.getInputMethodRequests().getTextLocation(0));
                return null;
            });
        }
    }

    @Test
    void synchronizedOutputKeepsTheOverscanImeLocationFrozenUntilRelease() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> {
                cellColor(view, 0, 0);
                return null;
            });
            terminal.emit("line\r\n".repeat(29) + "prompt> \u001B]2;ime-ready\u001B\\");
            awaitTitle(view, "ime-ready");
            var location = runOnFxThread(() -> {
                view.scrollViewportToBottom();
                smoothScroll(view, -4 / cellHeight(view));
                return view.getInputMethodRequests().getTextLocation(0);
            });
            terminal.emit("\u001B[?2026h\u001B[1;1H\u001B]2;ime-held\u001B\\");
            awaitTitle(view, "ime-held");
            assertEquals(location, runOnFxThread(() -> view.getInputMethodRequests().getTextLocation(0)));
            terminal.emit("\u001B[?2026l\u001B]2;ime-released\u001B\\");
            awaitTitle(view, "ime-released");
            assertEquals(0, runOnFxThread(() -> view.getInputMethodRequests().getTextLocation(0).getX()));
        }
    }

    @Test
    void smoothScrollingShowsTheFullBottomRowWhenItFitsInTheCanvas() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> {
                cellColor(view, 0, 0);
                view.resize(view.getWidth(), view.getHeight() + cellHeight(view) - 4);
                return null;
            });
            terminal.emit("\u001B[?25l\u001B[48;2;255;0;0m\u001B[38;2;255;255;255m"
                    + "\u001B]8;;https://example.test\u001B\\"
                    + String.join("\r\n", java.util.Collections.nCopies(30, "gyp_PROMPT █"))
                    + "\u001B]8;;\u001B\\\u001B]2;strip-ready\u001B\\");
            awaitTitle(view, "strip-ready");
            runOnFxThread(() -> {
                view.scrollViewportToBottom();
                var reference = view.snapshot(null, null);
                smoothScroll(view, -1 + 4 / cellHeight(view));
                var scrolled = view.snapshot(null, null);
                var rowTop = 24 * (int) cellHeight(view) - 4;
                for (var y = 0; y < (int) cellHeight(view); y++) {
                    for (var x = 0; x < (int) (10 * cellWidth(view)); x++) {
                        assertColor(reference.getPixelReader().getColor(x, y),
                                scrolled.getPixelReader().getColor(x, rowTop + y));
                    }
                }
                moveToCell(view, 0, 24);
                assertEquals(new TerminalLink.Osc8("https://example.test"), view.getHoveredLink());
                view.scrollViewportToBottom();
                assertColor(view.getTheme().background(), pixelColor(view, cellX(view, 1, 0.5), cellY(view, 24, 0.5)));
                moveToCell(view, 0, 24);
                assertNull(view.getHoveredLink());
                view.resize(view.getWidth(), 24.75 * cellHeight(view));
                view.scrollViewportToTop();
                smoothScroll(view, 0.75);
                assertColor(Color.WHITE, pixelColor(view, cellX(view, 11, 0.5), cellY(view, 24, 0.5)));
                moveToCell(view, 0, 24);
                assertEquals(new TerminalLink.Osc8("https://example.test"), view.getHoveredLink());
                return null;
            });
        }
    }

    @Test
    void smoothScrollingReportsOnlyWholeWheelStepsToMouseTrackingApplications() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit("\u001B[?1000h\u001B[?1006h\u001B]2;mouse-ready\u001B\\");
            awaitTitle(view, "mouse-ready");
            runOnFxThread(() -> {
                smoothScroll(view, 0.5);
                assertEquals(0, terminal.input.size());
                smoothScroll(view, 0.5);
                return null;
            });
            var input = await("mouse wheel report", START_TIMEOUT, () -> {
                var text = terminal.input.toString(StandardCharsets.UTF_8);
                return text.endsWith("m") ? Optional.of(text) : Optional.empty();
            });
            assertEquals("\u001B[<65;2;1M\u001B[<65;2;1m", input);
        }
    }

    @Test
    void smoothScrollingOverScrollbarStaysLocalWithMouseTrackingEnabled() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit(stripedOutput(30) + "\u001B[?1000h\u001B[?1006h\u001B]2;scrollbar-ready\u001B\\");
            awaitTitle(view, "scrollbar-ready");
            runOnFxThread(() -> {
                view.scrollViewportToTop();
                var deltaY = -0.75 * cellHeight(view);
                Event.fireEvent(view, scrollEvent(view.getWidth() - 1, cellY(view, 0, 0.5), deltaY));
                assertColor(Color.BLUE, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.5)));
                assertEquals(0, terminal.input.size());
                clickCell(view, 1, 0, 1);
                return null;
            });
            var input = await("shifted mouse click report", START_TIMEOUT, () -> {
                var text = terminal.input.toString(StandardCharsets.UTF_8);
                return text.endsWith("m") ? Optional.of(text) : Optional.empty();
            });
            assertEquals("\u001B[<0;2;2M\u001B[<0;2;2m", input);
        }
    }

    @Test
    void smoothScrollOffsetResetsOnResizeAndAlternateScreen() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit(stripedOutput(30) + "\u001B]2;resize-ready\u001B\\");
            awaitTitle(view, "resize-ready");
            runOnFxThread(() -> {
                view.scrollViewportToTop();
                smoothScroll(view, 0.75);
                view.resize(view.getWidth() + cellWidth(view), view.getHeight());
                assertColor(Color.RED, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.5)));
                smoothScroll(view, 0.75);
                return null;
            });
            terminal.emit("\u001B[?1049h\u001B[?25l\u001B[48;2;0;255;0m\u001B[2J\u001B]2;alternate-ready\u001B\\");
            awaitTitle(view, "alternate-ready");
            runOnFxThread(() -> {
                assertColor(Color.LIME, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.5)));
                smoothScroll(view, 0.75);
                assertColor(Color.LIME, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.5)));
                return null;
            });
        }
    }

    @Test
    void smoothScrollingKeepsHeldFramesFrozenUntilRelease() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> cellColor(view, 0, 0));
            terminal.emit(stripedOutput(30) + "\u001B]2;hold-scroll-ready\u001B\\");
            awaitTitle(view, "hold-scroll-ready");
            runOnFxThread(() -> {
                view.scrollViewportToTop();
                smoothScroll(view, 0.5);
                return null;
            });
            terminal.emit("\u001B[?2026h\u001B]2;scroll-held\u001B\\");
            awaitTitle(view, "scroll-held");
            runOnFxThread(() -> {
                smoothScroll(view, 0.5);
                assertColor(Color.RED, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.25)));
                assertColor(Color.BLUE, pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.75)));
                return null;
            });
            terminal.emit("\u001B[?2026l\u001B]2;scroll-released\u001B\\");
            awaitTitle(view, "scroll-released");
            assertColor(Color.BLUE, runOnFxThread(() -> pixelColor(view, cellX(view, 1, 0.5), cellY(view, 0, 0.25))));
        }
    }

    @Test
    void rendersLargeUnchunkedDirectImage() throws Exception {
        var pixels = new byte[150 * 150 * 3];
        for (var i = 0; i < pixels.length; i += 3) {
            pixels[i] = (byte) 0xFF;
        }
        var output = "\u001B[?25l\u001B_Ga=T,t=d,f=24,s=150,v=150,c=1,r=1,C=1,q=2;"
                + Base64.getEncoder().encodeToString(pixels)
                + "\u001B\\";

        try (var view = createView(output)) {
            awaitTerminalClosed(view);
            assertColor(Color.RED, runOnFxThread(() -> cellColor(view, 0, 0)));
        }
    }

    @Test
    void rendersKittyImagesInAllZLayers() throws Exception {
        var output = "\u001B[?25l"
                + "\u001B[38;2;0;255;0;48;2;0;0;255m ██\u001B[0m"
                + "\u001B_Ga=t,t=d,f=24,i=20,s=1,v=1,q=2;/wAA\u001B\\"
                + "\u001B[1;1H\u001B_Ga=p,i=20,p=1,c=1,r=1,C=1,q=2,z=-1073741825;\u001B\\"
                + "\u001B[1;2H\u001B_Ga=p,i=20,p=2,c=1,r=1,C=1,q=2,z=-1;\u001B\\"
                + "\u001B[1;3H\u001B_Ga=p,i=20,p=3,c=1,r=1,C=1,q=2,z=0;\u001B\\";
        try (var view = createView(output)) {
            awaitTerminalClosed(view);
            var colors = runOnFxThread(() -> List.of(cellColor(view, 0, 0), cellColor(view, 1, 0), cellColor(view, 2, 0)));
            assertColor(Color.BLUE, colors.get(0));
            assertColor(Color.LIME, colors.get(1));
            assertColor(Color.RED, colors.get(2));
        }
    }

    @Test
    void rendersImageOnlyAfterFinalCompressedChunk() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("\u001B[?25l"
                    + "\u001B_Ga=T,t=d,f=24,o=z,i=4,p=4,s=1,v=1,c=1,r=1,C=1,q=2,m=1;eAEBAwD8\u001B\\"
                    + "\u001B]0;first-chunk\u001B\\");
            awaitTitle(view, "first-chunk");
            assertColor(Color.BLACK, runOnFxThread(() -> cellColor(view, 0, 0)));

            terminal.emit("\u001B_Gm=0;//8AAAMAAQA=\u001B\\\u001B]0;final-chunk\u001B\\");
            awaitTitle(view, "final-chunk");
            awaitCellColor(view, 0, 0, Color.RED);
        }
    }

    @Test
    void clearsPlacementsAndRefreshesCachedImageOnSameIdRetransmission() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("\u001B[?25l"
                    + "\u001B_Ga=T,t=d,f=24,i=31,p=31,s=1,v=1,c=1,r=1,C=1,q=2;/wAA\u001B\\");
            awaitCellColor(view, 0, 0, Color.RED);

            terminal.emit("\u001B_Ga=t,t=d,f=24,i=31,s=1,v=1,q=2;AAD/\u001B\\");
            awaitCellColor(view, 0, 0, Color.BLACK);

            terminal.emit("\u001B_Ga=p,i=31,p=31,c=1,r=1,C=1,q=2;\u001B\\");
            awaitCellColor(view, 0, 0, Color.BLUE);
        }
    }

    @Test
    void themePropertyRejectsNullAndStoresTheme() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-theme-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = createView(shell, tempDirectory)) {
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
    void exposesTerminalShortcutList() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-selection-terminalShortcuts-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = createView(shell, tempDirectory)) {
            runOnFxThread(() -> {
                var defaultShortcuts = view.defaultTerminalShortcuts();
                var combinations = view.getTerminalShortcuts().stream()
                        .map(TerminalShortcut::combination)
                        .toList();
                assertEquals(
                        defaultShortcuts.stream().map(TerminalShortcut::combination).toList(),
                        combinations);
                for (var code : List.of(KeyCode.ENTER, KeyCode.UP, KeyCode.DOWN, KeyCode.ESCAPE)) {
                    assertTrue(combinations.contains(new KeyCodeCombination(code)));
                }
                assertTrue(combinations.contains(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHIFT_DOWN)));
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

                var shortcut = new TerminalShortcut(
                        new KeyCodeCombination(KeyCode.B, KeyCombination.SHIFT_DOWN),
                        () -> false);
                assertThrows(UnsupportedOperationException.class, () -> defaultShortcuts.add(shortcut));
                view.getTerminalShortcuts().add(shortcut);
                assertTrue(view.getTerminalShortcuts().contains(shortcut));
                return null;
            });
        }
    }

    @Test
    void exposesLinkMatcherList() throws Exception {
        try (var view = createView("")) {
            runOnFxThread(() -> {
                var link = new TerminalLinkMatcher(Pattern.compile("issue-(\\d+)"), _ -> {});
                var defaultLinkMatchers = view.defaultLinkMatchers();
                assertFalse(defaultLinkMatchers.isEmpty());
                assertEquals(defaultLinkMatchers, List.copyOf(view.getLinkMatchers()));
                assertThrows(UnsupportedOperationException.class, () -> defaultLinkMatchers.add(link));
                assertFalse(view.getLinkMatchers().isEmpty());
                view.getLinkMatchers().add(link);
                assertTrue(view.getLinkMatchers().contains(link));
                assertThrows(NullPointerException.class, () -> new TerminalLinkMatcher(null, _ -> {}));
                assertThrows(NullPointerException.class, () -> new TerminalLinkMatcher(Pattern.compile("x"), null));
                return null;
            });
        }
    }

    @Test
    void terminalLinksRejectNullComponents() {
        var pattern = Pattern.compile("x");
        var matcher = pattern.matcher("x");
        assertTrue(matcher.find());
        var match = matcher.toMatchResult();
        var linkMatcher = new TerminalLinkMatcher(pattern, _ -> {});

        assertThrows(NullPointerException.class, () -> new TerminalLink.Osc8(null));
        assertThrows(NullPointerException.class, () -> new TerminalLink.Regex(null, match));
        assertThrows(NullPointerException.class, () -> new TerminalLink.Regex(linkMatcher, null));
    }

    @Test
    void exposesHoveredOsc8Link() throws Exception {
        var target = "https://example.test";
        var output = "\u001B]8;;" + target + "\u001B\\abc\u001B]8;;\u001B\\";
        try (var view = createView(output)) {
            awaitText(view, "abc");
            runOnFxThread(() -> {
                assertNull(view.getHoveredLink());
                assertSame(view.getHoveredLink(), view.hoveredLinkProperty().get());

                moveToCell(view, 1, 0);
                assertEquals(new TerminalLink.Osc8(target), view.getHoveredLink());
                assertSame(view.getHoveredLink(), view.hoveredLinkProperty().get());

                Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_EXITED, 0, 0, false));
                assertNull(view.getHoveredLink());
                assertSame(view.getHoveredLink(), view.hoveredLinkProperty().get());
                return null;
            });
        }
    }

    @Test
    void keepsHoveredLinkWhileItIsClicked() throws Exception {
        var target = "https://example.test";
        var output = "\u001B]8;;" + target + "\u001B\\abc\u001B]8;;\u001B\\";
        try (var view = createView(output)) {
            awaitText(view, "abc");
            runOnFxThread(() -> {
                view.setOsc8LinkAction(_ -> {});
                var nullTransitions = new AtomicInteger();
                view.hoveredLinkProperty().addListener((_, oldLink, newLink) -> {
                    if (oldLink != null && newLink == null) {
                        nullTransitions.incrementAndGet();
                    }
                });
                moveToCell(view, 1, 0);

                clickCell(view, 1, 0);

                assertEquals(new TerminalLink.Osc8(target), view.getHoveredLink());
                assertEquals(0, nullTransitions.get());
                return null;
            });
        }
    }

    @Test
    void updatesHoveredLinkWhenTerminalOutputChangesUnderPointer() throws Exception {
        var terminal = new ControlledTerminal();
        var firstTarget = "https://first.test";
        var secondTarget = "https://second.test";
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("\u001B]8;;" + firstTarget + "\u001B\\abc\u001B]8;;\u001B\\\u001B]2;first\u001B\\");
            awaitTitle(view, "first");
            runOnFxThread(() -> {
                moveToCell(view, 1, 0);
                assertEquals(new TerminalLink.Osc8(firstTarget), view.getHoveredLink());
                return null;
            });

            terminal.emit("\r\u001B[2K\u001B]8;;" + secondTarget + "\u001B\\xyz\u001B]8;;\u001B\\\u001B]2;second\u001B\\");
            awaitTitle(view, "second");
            runOnFxThread(() -> {
                assertEquals(new TerminalLink.Osc8(secondTarget), view.getHoveredLink());
                return null;
            });
        }
    }

    @Test
    void updatesHoveredLinkWhenViewportScrollsUnderPointer() throws Exception {
        var firstTarget = "https://first.test";
        var secondTarget = "https://second.test";
        var lines = new ArrayList<String>();
        lines.add("\u001B]8;;" + firstTarget + "\u001B\\aaa\u001B]8;;\u001B\\");
        for (var i = 1; i < 6; i++) {
            lines.add("line-" + i);
        }
        lines.add("\u001B]8;;" + secondTarget + "\u001B\\bbb\u001B]8;;\u001B\\");
        for (var i = 7; i < 30; i++) {
            lines.add("line-" + i);
        }

        try (var view = createView(String.join("\r\n", lines))) {
            awaitText(view, "line-29");
            runOnFxThread(() -> {
                assertTrue(view.scrollViewportToTop());
                moveToCell(view, 1, 0);
                assertEquals(new TerminalLink.Osc8(firstTarget), view.getHoveredLink());

                assertTrue(view.scrollViewportToBottom());
                assertEquals(new TerminalLink.Osc8(secondTarget), view.getHoveredLink());
                return null;
            });
        }
    }

    @Test
    void updatesHoveredRegexLinkWhenMatcherChanges() throws Exception {
        try (var view = createView("issue-123")) {
            awaitText(view, "issue-123");
            runOnFxThread(() -> {
                var first = new TerminalLinkMatcher(Pattern.compile("issue-(\\d+)"), _ -> {});
                view.getLinkMatchers().setAll(first);
                moveToCell(view, 2, 0);
                assertSame(first, ((TerminalLink.Regex) view.getHoveredLink()).matcher());

                var second = new TerminalLinkMatcher(Pattern.compile("issue-(\\d+)"), _ -> {});
                view.getLinkMatchers().set(0, second);
                assertSame(second, ((TerminalLink.Regex) view.getHoveredLink()).matcher());

                view.getLinkMatchers().clear();
                assertNull(view.getHoveredLink());
                return null;
            });
        }
    }

    @Test
    void customLinkMatcherClickReceivesMatchResult() throws Exception {
        var group = new AtomicReference<String>();
        try (var view = createView("issue-123")) {
            awaitText(view, "issue-123");
            runOnFxThread(() -> {
                view.getLinkMatchers().add(new TerminalLinkMatcher(Pattern.compile("issue-(\\d+)"), match -> group.set(match.group(1))));
                clickCell(view, 2, 0);
                assertEquals("123", group.get());
                return null;
            });
        }
    }

    @Test
    void osc8LinkWinsOverCustomLinkMatcher() throws Exception {
        var clicks = new AtomicInteger();
        var output = "\u001B]8;;https://example.test\u001B\\abc\u001B]8;;\u001B\\";
        try (var view = createView(output)) {
            awaitText(view, "abc");
            runOnFxThread(() -> {
                view.getLinkMatchers().add(new TerminalLinkMatcher(Pattern.compile("abc"), _ -> clicks.incrementAndGet()));
                moveToCell(view, 1, 0);
                assertSame(Cursor.HAND, view.getCursor());
                assertEquals(0, clicks.get());
                return null;
            });
        }
    }

    @Test
    void rendersOnlyOsc8CellsWithOsc8Underline() throws Exception {
        var output = "\u001B]8;;https://example.test\u001B\\abc\u001B]8;;\u001B\\\r\nplain";
        try (var view = createView(output)) {
            awaitText(view, "plain");
            runOnFxThread(() -> {
                var theme = view.getTheme();
                view.setTheme(new TerminalTheme(
                        theme.background(),
                        Color.RED,
                        theme.palette(),
                        theme.cursorColor(),
                        theme.cursorText(),
                        theme.selectionColor(),
                        theme.selectionText(),
                        theme.faintOpacity(),
                        theme.scrollbarColor(),
                        theme.scrollbarActiveColor(),
                        theme.searchMatchColor(),
                        theme.searchMatchBorderColor(),
                        theme.searchCurrentMatchColor(),
                        theme.searchCurrentMatchBorderColor(),
                        0.0,
                        1.0,
                        theme.hoveredLinkUnderlineOpacity()));
                view.getLinkMatchers().clear();
                assertTrue(cellHasLinkUnderline(view, 1, 0));
                assertFalse(cellHasLinkUnderline(view, 1, 1));
                return null;
            });
        }
    }

    @Test
    void customLinkMatchersUseUserOrder() throws Exception {
        var clicks = new AtomicReference<String>();
        try (var view = createView("abc")) {
            awaitText(view, "abc");
            runOnFxThread(() -> {
                view.getLinkMatchers().add(new TerminalLinkMatcher(Pattern.compile("ab"), _ -> clicks.set("first")));
                view.getLinkMatchers().add(new TerminalLinkMatcher(Pattern.compile("bc"), _ -> clicks.set("second")));
                clickCell(view, 1, 0);
                assertEquals("first", clicks.get());
                return null;
            });
        }
    }

    @Test
    void linkMatchersMatchAcrossSoftWrappedLogicalLine() throws Exception {
        var clicks = new AtomicReference<String>();
        var output = "a".repeat(79) + "XY";
        try (var view = createView(output)) {
            awaitText(view, "XY");
            runOnFxThread(() -> {
                view.getLinkMatchers().add(new TerminalLinkMatcher(Pattern.compile("XY"), match -> clicks.set(match.group())));
                clickCell(view, 79, 0);
                assertEquals("XY", clicks.get());
                return null;
            });
        }
    }

    @Test
    void refreshesVisibleLinkUnderlineWhenTerminalTextChanges() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("A".repeat(80) + "X");
            awaitText(view, "X");
            runOnFxThread(() -> {
                var theme = view.getTheme();
                view.setTheme(new TerminalTheme(
                        theme.background(),
                        Color.RED,
                        theme.palette(),
                        theme.cursorColor(),
                        theme.cursorText(),
                        theme.selectionColor(),
                        theme.selectionText(),
                        theme.faintOpacity(),
                        theme.scrollbarColor(),
                        theme.scrollbarActiveColor(),
                        theme.searchMatchColor(),
                        theme.searchMatchBorderColor(),
                        theme.searchCurrentMatchColor(),
                        theme.searchCurrentMatchBorderColor(),
                        1.0,
                        theme.osc8LinkUnderlineOpacity(),
                        theme.hoveredLinkUnderlineOpacity()));
                view.getLinkMatchers().setAll(new TerminalLinkMatcher(Pattern.compile(".*X"), _ -> {}));
                assertTrue(cellHasLinkUnderline(view, 0, 1));
                return null;
            });

            terminal.emit("\u001B[2;1HY\u001B[K\u001B]0;row-changed\u001B\\");
            awaitTitle(view, "row-changed");
            await("changed row to lose its link underline", START_TIMEOUT, () -> runOnFxThread(() ->
                    !cellHasLinkUnderline(view, 0, 1)
                            ? Optional.of(Boolean.TRUE)
                            : Optional.empty()));
        }
    }

    @Test
    void rendersLinkUnderlineAcrossCombiningAndWideGraphemes() throws Exception {
        try (var view = createView("e\u0301界X")) {
            awaitText(view, "e\u0301界X");
            runOnFxThread(() -> {
                var theme = view.getTheme();
                view.setTheme(new TerminalTheme(
                        theme.background(),
                        Color.RED,
                        theme.palette(),
                        theme.cursorColor(),
                        theme.cursorText(),
                        theme.selectionColor(),
                        theme.selectionText(),
                        theme.faintOpacity(),
                        theme.scrollbarColor(),
                        theme.scrollbarActiveColor(),
                        theme.searchMatchColor(),
                        theme.searchMatchBorderColor(),
                        theme.searchCurrentMatchColor(),
                        theme.searchCurrentMatchBorderColor(),
                        1.0,
                        theme.osc8LinkUnderlineOpacity(),
                        theme.hoveredLinkUnderlineOpacity()));
                view.getLinkMatchers().setAll(new TerminalLinkMatcher(Pattern.compile("e\u0301界X"), _ -> {}));
                assertTrue(cellHasLinkUnderline(view, 3, 0));
                return null;
            });
        }
    }

    @Test
    void invalidatesVisibleLinksAfterReentrantTitleCallbackRedraw() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("X");
            awaitText(view, "X");
            runOnFxThread(() -> {
                var theme = view.getTheme();
                view.setTheme(new TerminalTheme(
                        theme.background(),
                        Color.RED,
                        theme.palette(),
                        theme.cursorColor(),
                        theme.cursorText(),
                        theme.selectionColor(),
                        theme.selectionText(),
                        theme.faintOpacity(),
                        theme.scrollbarColor(),
                        theme.scrollbarActiveColor(),
                        theme.searchMatchColor(),
                        theme.searchMatchBorderColor(),
                        theme.searchCurrentMatchColor(),
                        theme.searchCurrentMatchBorderColor(),
                        1.0,
                        theme.osc8LinkUnderlineOpacity(),
                        theme.hoveredLinkUnderlineOpacity()));
                view.getLinkMatchers().setAll(new TerminalLinkMatcher(Pattern.compile("X"), _ -> {}));
                view.titleProperty().addListener((_, _, title) -> {
                    if ("mid-write".equals(title)) {
                        view.getLinkMatchers().setAll(new TerminalLinkMatcher(Pattern.compile("X"), _ -> {}));
                    }
                });
                assertTrue(cellHasLinkUnderline(view, 0, 0));
                return null;
            });

            terminal.emit("\u001B]0;mid-write\u001B\\\u001B[1;1HY\u001B[K");
            awaitTitle(view, "mid-write");
            await("overwritten link to lose its underline", START_TIMEOUT, () -> runOnFxThread(() ->
                    !cellHasLinkUnderline(view, 0, 0)
                            ? Optional.of(Boolean.TRUE)
                            : Optional.empty()));
        }
    }

    @Test
    void builtInUrlLinkMatcherIsAvailableByDefault() throws Exception {
        try (var view = createView("https://example.test")) {
            awaitText(view, "https://example.test");
            runOnFxThread(() -> {
                moveToCell(view, 4, 0);
                assertSame(Cursor.HAND, view.getCursor());
                view.getLinkMatchers().clear();
                moveToCell(view, 4, 0);
                assertSame(Cursor.TEXT, view.getCursor());
                view.getLinkMatchers().setAll(view.defaultLinkMatchers());
                moveToCell(view, 4, 0);
                assertSame(Cursor.HAND, view.getCursor());
                return null;
            });
        }
    }

    @Test
    void clearingLinkMatchersDisablesBuiltInUrlLinkMatcher() throws Exception {
        try (var view = createView("https://example.test")) {
            awaitText(view, "https://example.test");
            runOnFxThread(() -> {
                view.getLinkMatchers().clear();
                moveToCell(view, 4, 0);
                assertSame(Cursor.TEXT, view.getCursor());
                return null;
            });
        }
    }

    @Test
    void builtInUrlLinkMatcherExcludesTrailingSentencePunctuation() throws Exception {
        try (var view = createView("https://example.test/, foo")) {
            awaitText(view, "https://example.test/");
            runOnFxThread(() -> {
                moveToCell(view, 4, 0);
                assertSame(Cursor.HAND, view.getCursor());
                moveToCell(view, "https://example.test/".length(), 0);
                assertSame(Cursor.TEXT, view.getCursor());
                return null;
            });
        }
    }

    @Test
    void builtInUrlLinkMatcherHandlesMarkdownClosingParenthesis() throws Exception {
        var output = "[docs](https://example.test/readme)";
        try (var view = createView(output)) {
            awaitText(view, "https://example.test/readme");
            runOnFxThread(() -> {
                moveToCell(view, output.indexOf("example"), 0);
                assertSame(Cursor.HAND, view.getCursor());
                moveToCell(view, output.length() - 1, 0);
                assertSame(Cursor.TEXT, view.getCursor());
                return null;
            });
        }
    }

    @Test
    void builtInUrlLinkMatcherKeepsBalancedParenthesis() throws Exception {
        var output = "https://en.wikipedia.org/wiki/Rust_(video_game)";
        try (var view = createView(output)) {
            awaitText(view, output);
            runOnFxThread(() -> {
                moveToCell(view, output.length() - 1, 0);
                assertSame(Cursor.HAND, view.getCursor());
                return null;
            });
        }
    }

    @Test
    void builtInUrlLinkMatcherDoesNotMatchFilePaths() throws Exception {
        try (var view = createView("file:///tmp/a C:\\tmp\\a /tmp/a")) {
            awaitText(view, "file:///tmp/a");
            runOnFxThread(() -> {
                moveToCell(view, 2, 0);
                assertSame(Cursor.TEXT, view.getCursor());
                moveToCell(view, 15, 0);
                assertSame(Cursor.TEXT, view.getCursor());
                moveToCell(view, 24, 0);
                assertSame(Cursor.TEXT, view.getCursor());
                return null;
            });
        }
    }

    @Test
    void builtInUrlLinkMatcherMatchesShellInputRegion() throws Exception {
        var output = "\u001B]133;A\u0007> \u001B]133;B\u0007https://example.test";
        try (var view = createView(output)) {
            awaitText(view, "https://example.test");
            runOnFxThread(() -> {
                moveToCell(view, 5, 0);
                assertSame(Cursor.HAND, view.getCursor());
                return null;
            });
        }
    }

    @Test
    void customLinkMatchersMatchShellInputRegion() throws Exception {
        var clicks = new AtomicInteger();
        var output = "\u001B]133;A\u0007> \u001B]133;B\u0007issue-123";
        try (var view = createView(output)) {
            awaitText(view, "issue-123");
            runOnFxThread(() -> {
                view.getLinkMatchers().add(new TerminalLinkMatcher(Pattern.compile("issue-(\\d+)"), _ -> clicks.incrementAndGet()));
                moveToCell(view, 5, 0);
                assertSame(Cursor.HAND, view.getCursor());
                clickCell(view, 5, 0);
                assertEquals(1, clicks.get());
                return null;
            });
        }
    }

    @Test
    void doubleClickSelectsWord() throws Exception {
        try (var view = createView("alpha beta")) {
            awaitText(view, "alpha beta");
            runOnFxThread(() -> {
                clickCell(view, 1, 0, 2);
                assertEquals("alpha", view.getInputMethodRequests().getSelectedText());
                return null;
            });
        }
    }

    @Test
    void copyDoesNotCopyLinkUnderCursorWithoutSelection() throws Exception {
        var clicks = new AtomicInteger();
        var clipboardContents = runOnFxThread(TerminalViewTest::snapshotClipboardContents);
        try {
            try (var view = createView("issue-123")) {
                awaitText(view, "issue-123");
                runOnFxThread(() -> {
                    view.getLinkMatchers().add(new TerminalLinkMatcher(Pattern.compile("issue-(\\d+)"), _ -> clicks.incrementAndGet()));
                    var clipboard = Clipboard.getSystemClipboard();
                    var content = new javafx.scene.input.ClipboardContent();
                    content.putString("unchanged");
                    clipboard.setContent(content);
                    moveToCell(view, 2, 0);
                    fireTerminalShortcut(view, copyTerminalShortcut());
                    assertEquals("unchanged", clipboard.getString());
                    assertEquals(0, clicks.get());
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
    void exposesBellAndTitleEffectsFromTerminalOutput() throws Exception {
        var bells = new AtomicInteger();
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            view.setOnBell(bells::incrementAndGet);
            terminal.emit("\u0007\u001B]2;ghosttyfx title\u001B\\");

            await("terminal bell and title effects", START_TIMEOUT, () -> runOnFxThread(() ->
                    bells.get() == 1 && "ghosttyfx title".equals(view.getTitle())
                            ? Optional.of(Boolean.TRUE)
                            : Optional.empty()));
        }
    }

    @Test
    void exposesNotificationRequestsFromTerminalOutput() throws Exception {
        var observed = new AtomicReference<Notification>();
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            runOnFxThread(() -> {
                view.setOnNotification(observed::set);
                assertSame(view.getOnNotification(), view.onNotificationProperty().get());
                return null;
            });

            terminal.emit("\u001B]9;Build complete\u001B\\");
            await("OSC 9 notification", START_TIMEOUT, () -> runOnFxThread(() ->
                    new Notification("", "Build complete").equals(observed.get())
                            ? Optional.of(Boolean.TRUE)
                            : Optional.empty()));

            terminal.emit("\u001B]777;notify;Codex;Needs attention\u001B\\");
            await("OSC 777 notification", START_TIMEOUT, () -> runOnFxThread(() ->
                    new Notification("Codex", "Needs attention").equals(observed.get())
                            ? Optional.of(Boolean.TRUE)
                            : Optional.empty()));
        }
    }

    @Test
    void notificationRejectsNullFields() {
        assertThrows(NullPointerException.class, () -> new Notification(null, ""));
        assertThrows(NullPointerException.class, () -> new Notification("", null));
    }

    @Test
    void exposesReportedShellLifecycleStatesInOrder() throws Exception {
        var terminal = new ControlledTerminal();
        var observed = new ArrayList<ShellState>();
        var callbacksOnFxThread = new AtomicInteger();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            runOnFxThread(() -> {
                assertNull(view.getShellState());
                assertNull(view.shellStateProperty().get());
                assertEquals("shellState", view.shellStateProperty().getName());
                assertSame(view, view.shellStateProperty().getBean());
                view.shellStateProperty().addListener((_, _, state) -> {
                    observed.add(state);
                    if (Platform.isFxApplicationThread()) {
                        callbacksOnFxThread.incrementAndGet();
                    }
                });
                return null;
            });
            terminal.emit("\u001B]133;A\u0007"
                    + "\u001B]133;P;k=r\u0007"
                    + "\u001B]133;P;k=c\u0007"
                    + "\u001B]133;P;k=s\u0007"
                    + "\u001B]133;B\u0007"
                    + "\u001B]133;C;cmdline_url=printf%20%E2%9C%93\u0007"
                    + "\u001B]133;D;-1;err=failed\u0007"
                    + "\u001B]2;shell-lifecycle-ready\u001B\\");
            awaitTitle(view, "shell-lifecycle-ready");
            runOnFxThread(() -> {
                var expected = List.of(
                        new ShellState.Prompt(ShellState.PromptKind.PRIMARY),
                        new ShellState.Prompt(ShellState.PromptKind.RIGHT),
                        new ShellState.Prompt(ShellState.PromptKind.CONTINUATION),
                        new ShellState.Prompt(ShellState.PromptKind.SECONDARY),
                        new ShellState.InputReady(),
                        new ShellState.Running("printf \u2713"),
                        new ShellState.Finished(-1, "failed"));
                assertEquals(expected, observed);
                assertEquals(expected.size(), callbacksOnFxThread.get());
                assertEquals(expected.getLast(), view.getShellState());
                assertSame(view.getShellState(), view.shellStateProperty().get());
                return null;
            });
        }
    }

    @Test
    void exposesCurrentShellStateToLateSubscribers() throws Exception {
        var terminal = new ControlledTerminal();
        var observed = new AtomicReference<ShellState>();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            terminal.emit("\u001B]133;C;cmdline_url=make%20test\u001B\\\u001B]2;shell-running\u001B\\");
            awaitTitle(view, "shell-running");
            var subscription = runOnFxThread(() -> view.shellStateProperty().subscribe(observed::set));
            try {
                assertEquals(new ShellState.Running("make test"), observed.get());
                terminal.emit("\u001B]133;D;0\u001B\\\u001B]2;shell-finished\u001B\\");
                awaitTitle(view, "shell-finished");
                assertEquals(new ShellState.Finished(0, ""), observed.get());
                terminal.emit("\u001B]133;B\u001B\\\u001B]2;shell-input-ready\u001B\\");
                awaitTitle(view, "shell-input-ready");
                assertEquals(new ShellState.InputReady(), observed.get());
            } finally {
                runOnFxThread(() -> {
                    subscription.unsubscribe();
                    return null;
                });
            }
        }
    }

    @Test
    void preservesMissingShellMetadataAndAcceptsSkippedSteps() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = runOnFxThread(() -> new TerminalView((_, _) -> terminal))) {
            var reports = List.of(
                    Map.entry("\u001B]133;D\u0007", new ShellState.Finished(null, "")),
                    Map.entry("\u001B]133;C\u0007", new ShellState.Running("")),
                    Map.entry("\u001B]133;D;-2147483648\u0007", new ShellState.Finished(Integer.MIN_VALUE, "")));
            var index = 0;
            for (var report : reports) {
                var marker = "shell-metadata-" + index++;
                terminal.emit(report.getKey() + "\u001B]2;" + marker + "\u001B\\");
                awaitTitle(view, marker);
                assertEquals(report.getValue(), runOnFxThread(view::getShellState));
            }
            terminal.emit("\u001B]133;Z\u0007\u001B]2;shell-invalid-marker\u001B\\");
            awaitTitle(view, "shell-invalid-marker");
            assertEquals(new ShellState.Finished(Integer.MIN_VALUE, ""), runOnFxThread(view::getShellState));
        }
    }

    @Test
    void shellStateRejectsNullRequiredFields() {
        assertThrows(NullPointerException.class, () -> new ShellState.Prompt(null));
        assertThrows(NullPointerException.class, () -> new ShellState.Running(null));
        assertThrows(NullPointerException.class, () -> new ShellState.Finished(null, null));
    }

    @Test
    void exposesProgressFromTerminalOutput() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            assertSame(view.getProgress(), view.progressProperty().get());

            assertProgress(
                    terminal,
                    view,
                    "\u001B]9;4;1;42\u001B\\",
                    new Progress.Determinate(Progress.State.ACTIVE, 42));
            assertProgress(
                    terminal,
                    view,
                    "\u001B]9;4;3\u001B\\",
                    new Progress.Indeterminate(Progress.State.ACTIVE));
            assertProgress(
                    terminal,
                    view,
                    "\u001B]9;4;4;75\u001B\\",
                    new Progress.Determinate(Progress.State.PAUSED, 75));
            assertProgress(
                    terminal,
                    view,
                    "\u001B]9;4;4\u001B\\",
                    new Progress.Determinate(Progress.State.PAUSED, 75));
            assertProgress(
                    terminal,
                    view,
                    "\u001B]9;4;2;7\u001B\\",
                    new Progress.Determinate(Progress.State.FAILED, 7));
            assertProgress(
                    terminal,
                    view,
                    "\u001B]9;4;2\u001B\\",
                    new Progress.Indeterminate(Progress.State.FAILED));
            assertProgress(terminal, view, "\u001B]9;4;0\u001B\\", null);
        }
    }

    @Test
    void progressRejectsInvalidValues() {
        assertThrows(NullPointerException.class, () -> new Progress.Determinate(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new Progress.Determinate(Progress.State.ACTIVE, -1));
        assertThrows(IllegalArgumentException.class, () -> new Progress.Determinate(Progress.State.ACTIVE, 101));
        assertThrows(NullPointerException.class, () -> new Progress.Indeterminate(null));
    }

    @Test
    void clearsProgressWhenBackendStops() throws Exception {
        try (var view = createView("\u001B]9;4;1;42\u001B\\")) {
            awaitTerminalClosed(view);
            runOnFxThread(() -> {
                assertEquals(null, view.getProgress());
                assertEquals(null, view.progressProperty().get());
                return null;
            });
        }

        try (var view = new TerminalView((_, _) ->
                new StaticTerminal("\u001B]9;4;1;42\u001B\\", new IOException("backend failed")))) {
            await("terminal output to fail", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.getTerminalState() instanceof TerminalState.Failed
                            ? Optional.of(Boolean.TRUE)
                            : Optional.empty()));
            runOnFxThread(() -> {
                assertEquals(null, view.getProgress());
                assertEquals(null, view.progressProperty().get());
                return null;
            });
        }
    }

    @Test
    void reportsTerminalEffectHandlerExceptions() throws Exception {
        var uncaughtExceptions = new AtomicInteger();
        var previousUncaughtExceptionHandler = new AtomicReference<Thread.UncaughtExceptionHandler>();
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            runOnFxThread(() -> {
                var thread = Thread.currentThread();
                previousUncaughtExceptionHandler.set(thread.getUncaughtExceptionHandler());
                thread.setUncaughtExceptionHandler((_, _) -> uncaughtExceptions.incrementAndGet());
                view.setOnNotification(_ -> {
                    throw new RuntimeException("notification handler failed");
                });
                view.setOnBell(() -> {
                    throw new RuntimeException("bell handler failed");
                });
                view.shellStateProperty().addListener((_, _, _) -> {
                    throw new RuntimeException("shell state listener failed");
                });
                return null;
            });

            try {
                terminal.emit("\u001B]9;notification\u001B\\\u001B]2;after notification\u001B\\");
                await("notification exception reporting", START_TIMEOUT, () -> runOnFxThread(() ->
                        uncaughtExceptions.get() == 1 && "after notification".equals(view.getTitle())
                                ? Optional.of(Boolean.TRUE)
                                : Optional.empty()));

                terminal.emit("\u0007\u001B]2;after bell\u001B\\");
                await("bell exception reporting", START_TIMEOUT, () -> runOnFxThread(() ->
                        uncaughtExceptions.get() == 2 && "after bell".equals(view.getTitle())
                                ? Optional.of(Boolean.TRUE)
                                : Optional.empty()));
                terminal.emit("\u001B]133;B\u0007\u001B]2;after shell state\u001B\\");
                await("shell state listener exception reporting", START_TIMEOUT, () -> runOnFxThread(() ->
                        uncaughtExceptions.get() == 3 && "after shell state".equals(view.getTitle())
                                && view.getShellState() instanceof ShellState.InputReady
                                ? Optional.of(Boolean.TRUE)
                                : Optional.empty()));
            } finally {
                runOnFxThread(() -> {
                    Thread.currentThread().setUncaughtExceptionHandler(previousUncaughtExceptionHandler.get());
                    return null;
                });
            }
        }
    }

    @Test
    void terminalQueriesAreNotPacedByRenderingPulses() throws Exception {
        var terminal = new QueryTerminal(180);
        try (var view = new TerminalView((_, _) -> terminal)) {
            await("180 terminal query round trips", Duration.ofSeconds(2), () -> runOnFxThread(() ->
                    QueryTerminal.COMPLETED_TITLE.equals(view.getTitle())
                            ? Optional.of(Boolean.TRUE)
                            : Optional.empty()));
        }
    }

    @Test
    void exposesCurrentDirectoryFromTerminalOutput() throws Exception {
        assertCurrentDirectoryFromOutput("\u001B]7;file:///tmp/ghosttyfx\u001B\\", "file:///tmp/ghosttyfx");
        assertCurrentDirectoryFromOutput("\u001B]9;9;C:\\tmp\\ghosttyfx\u001B\\", "C:\\tmp\\ghosttyfx");
        assertCurrentDirectoryFromOutput("\u001B]1337;CurrentDir=/tmp/ghosttyfx\u001B\\", "/tmp/ghosttyfx");

        try (var view = createView("\u001B]7;file:///tmp/ghosttyfx\u001B\\\u001B]7;\u001B\\\u001B]2;pwd-cleared\u001B\\")) {
            await("terminal current directory to clear", START_TIMEOUT, () -> runOnFxThread(() ->
                    "pwd-cleared".equals(view.getTitle()) && view.getCurrentDirectory().isEmpty()
                            ? Optional.of(Boolean.TRUE)
                            : Optional.empty()));
        }
    }

    @Test
    void sendTextAndSendEscExposeTerminalShortcutActions() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-send-text-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = createView(shell, tempDirectory)) {
            runOnFxThread(() -> {
                assertFalse(view.sendText(""));
                assertTrue(view.sendText("A"));
                assertTrue(view.sendEsc("b"));
                assertThrows(NullPointerException.class, () -> view.sendText(null));
                assertThrows(NullPointerException.class, () -> view.sendEsc(null));

                var textTerminalShortcut = new KeyCodeCombination(KeyCode.B, KeyCombination.ALT_DOWN);
                var sendTextTerminalShortcut = new TerminalShortcut(textTerminalShortcut, () -> view.sendText("B"));
                view.getTerminalShortcuts().add(sendTextTerminalShortcut);
                assertTrue(view.getTerminalShortcuts().contains(sendTextTerminalShortcut));
                assertTrue(sendTextTerminalShortcut.action().getAsBoolean());

                var escTerminalShortcut = new KeyCodeCombination(KeyCode.F, KeyCombination.ALT_DOWN);
                var sendEscTerminalShortcut = new TerminalShortcut(escTerminalShortcut, () -> view.sendEsc("f"));
                view.getTerminalShortcuts().add(sendEscTerminalShortcut);
                assertTrue(view.getTerminalShortcuts().contains(sendEscTerminalShortcut));
                assertTrue(sendEscTerminalShortcut.action().getAsBoolean());
                return null;
            });
        }
    }

    @Test
    void shiftArrowTerminalShortcutsExtendExistingSelection() throws Exception {
        var marker = "ghosttyfx-selection";
        var tempDirectory = Files.createTempDirectory("ghosttyfx-selection-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, marker);

        try (var view = createView(shell, tempDirectory)) {
            await("terminal output to be addressable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireTerminalShortcut(view, selectAllTerminalShortcut());
                return marker.equals(view.getInputMethodRequests().getSelectedText())
                        ? Optional.of(Boolean.TRUE)
                        : Optional.empty();
            }));

            runOnFxThread(() -> {
                dragSelection(view, 1, 1);
                fireTerminalShortcut(view, new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(1, 3), view.getInputMethodRequests().getSelectedText());

                fireTerminalShortcut(view, new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(1, 2), view.getInputMethodRequests().getSelectedText());

                fireTerminalShortcut(view, new KeyCodeCombination(KeyCode.HOME, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(0, 2), view.getInputMethodRequests().getSelectedText());

                fireTerminalShortcut(view, new KeyCodeCombination(KeyCode.END, KeyCombination.SHIFT_DOWN));
                assertEquals(marker.substring(1), view.getInputMethodRequests().getSelectedText());
                return null;
            });
        }
    }

    @Test
    void selectionAndClipboardCapabilityGuardsDoNotPerformActions() throws Exception {
        var marker = "ghosttyfx-capability-guards";
        var clipboardContents = runOnFxThread(TerminalViewTest::snapshotClipboardContents);
        try {
            try (var view = createView(marker)) {
                awaitText(view, marker);
                runOnFxThread(() -> {
                    var clipboard = Clipboard.getSystemClipboard();
                    clipboard.clear();
                    assertFalse(view.canCopySelection());
                    assertFalse(view.canExtendSelection());
                    assertFalse(view.canPasteClipboard());

                    view.selectAll();
                    var selectedText = view.getInputMethodRequests().getSelectedText();
                    assertTrue(view.canCopySelection());
                    assertTrue(view.canExtendSelection());
                    assertEquals(selectedText, view.getInputMethodRequests().getSelectedText());

                    var content = new javafx.scene.input.ClipboardContent();
                    content.putString("clipboard text");
                    clipboard.setContent(content);
                    assertTrue(view.canPasteClipboard());
                    assertEquals("clipboard text", clipboard.getString());
                    assertEquals(selectedText, view.getInputMethodRequests().getSelectedText());
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
    void viewportScrollTerminalShortcutsReportUnavailableWithoutScrollableViewport() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-viewport-scroll-terminalShortcuts-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = createView(shell, tempDirectory)) {
            runOnFxThread(() -> {
                assertFalse(view.canScrollViewport());
                assertFalse(view.scrollViewportPageUp());
                assertFalse(view.scrollViewportPageDown());
                assertFalse(view.scrollViewportToTop());
                assertFalse(view.scrollViewportToBottom());
                return null;
            });
        }
    }

    @Test
    void viewportScrollTerminalShortcutsConsumeAtScrollableBoundaries() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-viewport-scroll-boundary-terminalShortcuts-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, lineOutput(80));

        try (var view = createView(shell, tempDirectory)) {
            await("scrollable terminal output", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.scrollViewportToTop() ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertTrue(view.canScrollViewport());
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
    void viewportScrollTerminalShortcutsWorkWithSelection() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-viewport-scroll-selection-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, lineOutput(80));

        try (var view = createView(shell, tempDirectory)) {
            await("scrollable terminal output", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.scrollViewportToTop() ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                dragSelection(view, 0, 5);
                assertEquals("line-0", view.getInputMethodRequests().getSelectedText());

                assertTrue(view.scrollViewportPageDown());
                assertTrue(view.scrollViewportToBottom());
                assertEquals("line-0", view.getInputMethodRequests().getSelectedText());

                assertTrue(view.scrollViewportPageUp());
                assertTrue(view.scrollViewportToTop());
                assertEquals("line-0", view.getInputMethodRequests().getSelectedText());
                return null;
            });
        }
    }

    @Test
    void promptNavigationReportsUnavailableWithoutSemanticPrompts() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-prompt-navigation-unavailable-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, lineOutput(80));

        try (var view = createView(shell, tempDirectory)) {
            await("scrollable terminal output", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.scrollViewportToTop() ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertTrue(view.canScrollViewport());
                assertFalse(view.canNavigateToPreviousPrompt());
                assertFalse(view.canNavigateToNextPrompt());
                assertFalse(view.scrollViewportToPreviousPrompt());
                assertFalse(view.scrollViewportToNextPrompt());
                return null;
            });
        }
    }

    @Test
    void promptNavigationWorksWithSelection() throws Exception {
        try (var view = createView(promptOutput(40))) {
            await("terminal prompt output", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.scrollViewportToNextPrompt() ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                dragSelection(view, 0, 1);
                assertFalse(view.getInputMethodRequests().getSelectedText().isEmpty());
                assertTrue(view.scrollViewportToNextPrompt());
                assertTrue(view.scrollViewportToPreviousPrompt());
                assertFalse(view.getInputMethodRequests().getSelectedText().isEmpty());
                return null;
            });
        }
    }

    @Test
    void promptNavigationMovesBetweenSemanticPrompts() throws Exception {
        try (var view = createView(promptOutput(40))) {
            await("terminal prompt output", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.scrollViewportToNextPrompt() ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertTrue(view.scrollViewportToNextPrompt());
                assertTrue(view.scrollViewportToPreviousPrompt());
                return null;
            });
        }
    }

    @Test
    void promptNavigationNextStartsAtFirstSemanticPrompt() throws Exception {
        try (var view = createView(promptOutput(3))) {
            await("terminal prompt output", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.scrollViewportToNextPrompt() ? Optional.of(Boolean.TRUE) : Optional.empty()));
        }
    }

    @Test
    void promptNavigationHighlightsVisiblePromptsWithoutScrollbarAndDoesNotWrap() throws Exception {
        try (var view = createView(promptOutput(3))) {
            await("terminal prompt output", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.canNavigateToPreviousPrompt() ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertFalse(view.canScrollViewport());
                assertTrue(view.canNavigateToPreviousPrompt());
                assertTrue(view.canNavigateToNextPrompt());
                assertTrue(view.scrollViewportToPreviousPrompt());
                assertTrue(view.scrollViewportToPreviousPrompt());
                assertTrue(view.scrollViewportToPreviousPrompt());
                assertTrue(view.scrollViewportToPreviousPrompt());
                assertTrue(view.scrollViewportToPreviousPrompt());
                assertTrue(view.scrollViewportToNextPrompt());
                assertTrue(view.scrollViewportToNextPrompt());
                assertTrue(view.scrollViewportToNextPrompt());
                assertTrue(view.scrollViewportToNextPrompt());
                assertTrue(view.scrollViewportToNextPrompt());
                return null;
            });
            Thread.sleep(900);
            runOnFxThread(() -> {
                assertTrue(view.scrollViewportToNextPrompt());
                return null;
            });
        }
    }

    @Test
    void openingSearchUsesSelectionAndNavigatesNewestFirst() throws Exception {
        var marker = "ghosttyfx-search";
        var output = marker + "\nother\n" + marker;
        var tempDirectory = Files.createTempDirectory("ghosttyfx-search-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverOutputShell(pidFile, output);

        try (var view = createView(shell, tempDirectory)) {
            await("terminal output to become searchable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireTerminalShortcut(view, selectAllTerminalShortcut());
                var text = view.getInputMethodRequests().getSelectedText();
                return text != null && text.contains(marker) ? Optional.of(Boolean.TRUE) : Optional.empty();
            }));

            runOnFxThread(() -> {
                attachToScene(view);
                dragSelection(view, 0, marker.length() - 1);
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());

                assertTrue(view.openSearch());
                assertEquals(marker, view.searchText());
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());
                return null;
            });

            await("search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.searchMatchCount() == 2 ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertEquals(2, view.searchMatchCount());
                assertEquals(-1, view.selectedSearchMatchIndex());

                assertEquals("-/2", ((Label) view.lookup("#ghosttyfx-search-count")).getText());
                assertTrue(view.searchNext());
                assertEquals(0, view.selectedSearchMatchIndex());
                assertTrue(view.searchNext());
                assertEquals(1, view.selectedSearchMatchIndex());
                assertTrue(view.searchNext());
                assertEquals(1, view.selectedSearchMatchIndex());
                assertTrue(view.searchPrevious());
                assertEquals(0, view.selectedSearchMatchIndex());
                assertTrue(view.searchPrevious());
                assertEquals(0, view.selectedSearchMatchIndex());

                fireTerminalShortcut(view, new KeyCodeCombination(KeyCode.ESCAPE));
                assertEquals(-1, view.selectedSearchMatchIndex());
                return null;
            });
        }
    }

    @Test
    void searchMatchesSelectedCombiningGraphemeText() throws Exception {
        var marker = "e\u0301a";
        var output = marker + "\nother\n" + marker;

        try (var view = createView(output)) {
            await("terminal grapheme output to become searchable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireTerminalShortcut(view, selectAllTerminalShortcut());
                var text = view.getInputMethodRequests().getSelectedText();
                return text != null && text.contains(marker) ? Optional.of(Boolean.TRUE) : Optional.empty();
            }));

            runOnFxThread(() -> {
                attachToScene(view);
                dragSelection(view, 0, 1);
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());
                assertTrue(view.openSearch());
                assertEquals(marker, view.searchText());
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());
                return null;
            });

            await("grapheme search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.searchMatchCount() == 2 ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertEquals(2, view.searchMatchCount());
                assertEquals(-1, view.selectedSearchMatchIndex());
                return null;
            });
        }
    }

    @Test
    void searchMatchesSelectedWideGraphemeText() throws Exception {
        var marker = "界a";
        var output = marker + "\nother\n" + marker;

        try (var view = createView(output)) {
            await("terminal wide output to become searchable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireTerminalShortcut(view, selectAllTerminalShortcut());
                var text = view.getInputMethodRequests().getSelectedText();
                return text != null && text.contains(marker) ? Optional.of(Boolean.TRUE) : Optional.empty();
            }));

            runOnFxThread(() -> {
                attachToScene(view);
                dragSelection(view, 0, 2);
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());
                assertTrue(view.openSearch());
                assertEquals(marker, view.searchText());
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());
                return null;
            });

            await("wide search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.searchMatchCount() == 2 ? Optional.of(Boolean.TRUE) : Optional.empty()));

            runOnFxThread(() -> {
                assertEquals(2, view.searchMatchCount());
                assertEquals(-1, view.selectedSearchMatchIndex());
                return null;
            });
        }
    }

    @Test
    void familyEmojiOccupiesOneWideGrapheme() throws Exception {
        var marker = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66a";

        try (var view = createView(marker)) {
            await("terminal family emoji output to become selectable", START_TIMEOUT, () -> runOnFxThread(() -> {
                fireTerminalShortcut(view, selectAllTerminalShortcut());
                var text = view.getInputMethodRequests().getSelectedText();
                return marker.equals(text) ? Optional.of(Boolean.TRUE) : Optional.empty();
            }));

            runOnFxThread(() -> {
                attachToScene(view);
                dragSelection(view, 0, 2);
                assertEquals(marker, view.getInputMethodRequests().getSelectedText());
                return null;
            });
        }
    }

    @Test
    void searchFieldShowsLayoutAndBoundProperties() throws Exception {
        var tempDirectory = Files.createTempDirectory("ghosttyfx-search-field-test-");
        var pidFile = tempDirectory.resolve("shell.pid");
        var shell = discoverShell(pidFile);

        try (var view = createView(shell, tempDirectory)) {
            runOnFxThread(() -> {
                assertTrue(view.toggleSearch());
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
                assertEquals(Label.USE_PREF_SIZE, count.getMinWidth());
                assertEquals(Label.USE_COMPUTED_SIZE, count.getMaxWidth());

                var font = Font.font("Monospaced", field.getFont().getSize() + 3);
                view.setFont(font);
                assertEquals(font, field.getFont());
                assertEquals(font, count.getFont());
                return null;
            });
        }
    }

    @Test
    void searchFieldNavigationStopsAtBoundariesAndEscapeCloses() throws Exception {
        try (var view = createView("alpha\nbeta\nalpha")) {
            awaitText(view, "beta");
            runOnFxThread(() -> {
                attachToScene(view);
                view.openSearch();
                ((TextField) view.lookup("#ghosttyfx-search-field")).setText("alpha");
                return null;
            });
            await("search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    view.searchMatchCount() == 2 ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                var count = (Label) view.lookup("#ghosttyfx-search-count");
                var field = (TextField) view.lookup("#ghosttyfx-search-field");
                assertEquals(-1, view.selectedSearchMatchIndex());
                assertEquals("-/2", count.getText());
                for (var expected : List.of(0, 1, 1)) {
                    field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.ENTER)));
                    assertEquals(expected, view.selectedSearchMatchIndex());
                }
                for (var expected : List.of(0, 0, 0)) {
                    field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHIFT_DOWN)));
                    assertEquals(expected, view.selectedSearchMatchIndex());
                }
                field.positionCaret(2);
                for (var expected : List.of(1, 1, 1)) {
                    field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.UP)));
                    assertEquals(expected, view.selectedSearchMatchIndex());
                    assertEquals(2, field.getCaretPosition());
                }
                for (var expected : List.of(0, 0, 0)) {
                    field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.DOWN)));
                    assertEquals(expected, view.selectedSearchMatchIndex());
                    assertEquals(2, field.getCaretPosition());
                }
                assertNull(view.lookup("#ghosttyfx-search-next"));
                assertNull(view.lookup("#ghosttyfx-search-previous"));
                assertNull(view.lookup("#ghosttyfx-search-close"));
                field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.ESCAPE)));
                assertFalse(view.lookup("#ghosttyfx-search").isVisible());
                return null;
            });
        }
    }

    @Test
    void customSearchShortcutsWorkInTheFieldAndPreserveFieldEditing() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("alpha\r\nbeta\r\nalpha");
            awaitText(view, "beta");
            var modifier = isMac() ? KeyCombination.META_DOWN : KeyCombination.CONTROL_DOWN;
            var next = new KeyCodeCombination(KeyCode.N, modifier);
            var previous = new KeyCodeCombination(KeyCode.N, modifier, KeyCombination.SHIFT_DOWN);
            runOnFxThread(() -> {
                attachToScene(view);
                dragSelection(view, 0, 4);
                view.getTerminalShortcuts().add(new TerminalShortcut(next, view::searchNext));
                view.getTerminalShortcuts().add(new TerminalShortcut(previous, view::searchPrevious));
                view.openSearch();
                ((TextField) view.lookup("#ghosttyfx-search-field")).setText("alpha");
                return null;
            });
            await("search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/2".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText())
                            ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                var field = (TextField) view.lookup("#ghosttyfx-search-field");
                var count = (Label) view.lookup("#ghosttyfx-search-count");
                var before = terminal.input.toByteArray();
                fireTerminalShortcut(field, next);
                assertEquals("1/2", count.getText());
                fireTerminalShortcut(field, next);
                assertEquals("2/2", count.getText());
                fireTerminalShortcut(field, previous);
                assertEquals("1/2", count.getText());
                fireTerminalShortcut(view, next);
                assertEquals("2/2", count.getText());
                fireTerminalShortcut(field, new KeyCodeCombination(KeyCode.A, modifier));
                assertEquals("alpha", field.getSelectedText());
                assertEquals("alpha", view.getInputMethodRequests().getSelectedText());
                field.positionCaret(2);
                fireTerminalShortcut(field, new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHIFT_DOWN));
                assertEquals("p", field.getSelectedText());
                assertEquals("alpha", view.getInputMethodRequests().getSelectedText());
                assertTrue(java.util.Arrays.equals(before, terminal.input.toByteArray()));
                return null;
            });
        }
    }

    @Test
    void searchNavigationBindingsRespectListOrderRemovalAndFocusAvailability() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("alpha\r\nbeta\r\nalpha");
            awaitText(view, "beta");
            runOnFxThread(() -> {
                attachToScene(view);
                view.openSearch();
                ((TextField) view.lookup("#ghosttyfx-search-field")).setText("alpha");
                return null;
            });
            await("search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/2".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText())
                            ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                var field = (TextField) view.lookup("#ghosttyfx-search-field");
                var count = (Label) view.lookup("#ghosttyfx-search-count");
                var up = new KeyCodeCombination(KeyCode.UP);
                var escape = new KeyCodeCombination(KeyCode.ESCAPE);
                view.getTerminalShortcuts().addFirst(new TerminalShortcut(up, () -> false));
                fireTerminalShortcut(field, up);
                assertEquals("1/2", count.getText());
                view.getTerminalShortcuts().addFirst(new TerminalShortcut(up, view::searchPrevious));
                fireTerminalShortcut(field, up);
                assertEquals("1/2", count.getText());
                view.getTerminalShortcuts().removeIf(shortcut -> shortcut.combination().equals(up));
                field.positionCaret(2);
                fireTerminalShortcut(field, up);
                assertEquals("1/2", count.getText());
                assertEquals(0, field.getCaretPosition());
                view.getTerminalShortcuts().removeIf(shortcut -> shortcut.combination().equals(escape));
                fireTerminalShortcut(field, escape);
                assertTrue(view.lookup("#ghosttyfx-search").isVisible());
                view.requestFocus();
                var before = terminal.input.size();
                fireTerminalShortcut(view, new KeyCodeCombination(KeyCode.DOWN));
                assertEquals("\u001B[B", terminal.input.toString(StandardCharsets.UTF_8).substring(before));
                assertEquals("1/2", count.getText());
                view.openSearch();
                field.setText("missing");
                field.positionCaret(3);
                fireTerminalShortcut(field, new KeyCodeCombination(KeyCode.DOWN));
                assertEquals("-/0", count.getText());
                assertEquals(3, field.getCaretPosition());
                view.getTerminalShortcuts().add(new TerminalShortcut(escape, view::closeSearch));
                fireTerminalShortcut(field, escape);
                assertFalse(view.lookup("#ghosttyfx-search").isVisible());
                return null;
            });
        }
    }

    @Test
    void publicFieldSearchActionsSupportCustomBindingsAndFocusAvailability() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("alpha\r\nbeta\r\nalpha");
            awaitText(view, "beta");
            runOnFxThread(() -> {
                attachToScene(view);
                assertFalse(view.searchNextInField());
                assertFalse(view.searchPreviousInField());
                view.getTerminalShortcuts().setAll(
                        new TerminalShortcut(new KeyCodeCombination(KeyCode.UP), view::searchNextInField),
                        new TerminalShortcut(new KeyCodeCombination(KeyCode.DOWN), view::searchPreviousInField));
                view.openSearch();
                ((TextField) view.lookup("#ghosttyfx-search-field")).setText("alpha");
                return null;
            });
            await("search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/2".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText())
                            ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                var field = (TextField) view.lookup("#ghosttyfx-search-field");
                var count = (Label) view.lookup("#ghosttyfx-search-count");
                fireTerminalShortcut(field, new KeyCodeCombination(KeyCode.UP));
                assertEquals("1/2", count.getText());
                fireTerminalShortcut(field, new KeyCodeCombination(KeyCode.UP));
                assertEquals("2/2", count.getText());
                fireTerminalShortcut(field, new KeyCodeCombination(KeyCode.DOWN));
                assertEquals("1/2", count.getText());
                view.requestFocus();
                assertFalse(view.searchNextInField());
                assertFalse(view.searchPreviousInField());
                assertEquals("1/2", count.getText());
                var before = terminal.input.size();
                fireTerminalShortcut(view, new KeyCodeCombination(KeyCode.UP));
                assertEquals("\u001B[A", terminal.input.toString(StandardCharsets.UTF_8).substring(before));
                view.openSearch();
                field.setText("missing");
                assertTrue(view.searchNextInField());
                assertTrue(view.searchPreviousInField());
                assertEquals("-/0", count.getText());
                return null;
            });
        }
    }

    @Test
    void publicFocusChecksAllowExplicitShortcutAvailability() throws Exception {
        var terminal = new ControlledTerminal();
        var clipboardContents = runOnFxThread(TerminalViewTest::snapshotClipboardContents);
        try (var view = new TerminalView((_, _) -> terminal)) {
            runOnFxThread(() -> {
                assertFalse(view.isTerminalFocused());
                assertFalse(view.isSearchFieldFocused());
                return null;
            });
            terminal.emit("alpha");
            awaitText(view, "alpha");
            runOnFxThread(() -> {
                assertTrue(view.isTerminalFocused());
                assertFalse(view.isSearchFieldFocused());
                dragSelection(view, 0, 4);
                view.getTerminalShortcuts().add(new TerminalShortcut(
                        new KeyCodeCombination(KeyCode.F3), view::copySelection));
                view.getTerminalShortcuts().add(new TerminalShortcut(
                        new KeyCodeCombination(KeyCode.F4),
                        () -> view.isTerminalFocused() && view.sendText("gated")));
                view.openSearch();
                var field = (TextField) view.lookup("#ghosttyfx-search-field");
                field.setText("query");
                assertFalse(view.isTerminalFocused());
                assertTrue(view.isSearchFieldFocused());
                var before = terminal.input.size();
                fireTerminalShortcut(field, new KeyCodeCombination(KeyCode.F4));
                assertEquals(before, terminal.input.size());
                assertEquals("alpha", view.getInputMethodRequests().getSelectedText());
                fireTerminalShortcut(field, new KeyCodeCombination(KeyCode.F3));
                assertEquals("alpha", Clipboard.getSystemClipboard().getString());
                assertTrue(view.isSearchFieldFocused());
                fireTerminalShortcut(view, new KeyCodeCombination(KeyCode.F4));
                assertEquals("gated", terminal.input.toString(StandardCharsets.UTF_8).substring(before));
                assertTrue(view.isTerminalFocused());
                assertFalse(view.isSearchFieldFocused());
                view.closeSearch();
                assertTrue(view.isTerminalFocused());
                assertFalse(view.isSearchFieldFocused());
                return null;
            });
        } finally {
            runOnFxThread(() -> {
                restoreClipboardContents(clipboardContents);
                return null;
            });
        }
    }

    @Test
    void searchOnlyScrollsOnNavigationAndKeepsFractionalScrollForVisibleMatches() throws Exception {
        var lines = new ArrayList<String>();
        for (var i = 0; i < 1000; i++) {
            var text = i == 0 ? "old-marker" : i == 1 ? "visible-marker" : "line-" + i;
            lines.add((i == 0 ? "\u001B[48;2;255;0;0m\u001B[K" : "")
                    + "\u001B]8;;ghosttyfx-test:search-row-" + i + "\u001B\\" + text + "\u001B]8;;\u001B\\\u001B[0m");
        }
        try (var view = runOnFxThread(() -> {
            var terminalView = createView(String.join("\r\n", lines) + "\u001B]2;search-ready\u001B\\");
            terminalView.setOsc8LinkAction(_ -> fail("Hovering and searching must not activate a link"));
            return terminalView;
        })) {
            awaitTitle(view, "search-ready");
            var initialLink = runOnFxThread(() -> {
                attachToScene(view);
                view.scrollViewportToBottom();
                moveToCell(view, 1, 0);
                var link = view.getHoveredLink();
                assertTrue(link instanceof TerminalLink.Osc8);
                view.openSearch();
                ((TextField) view.lookup("#ghosttyfx-search-field")).setText("old-marker");
                return link;
            });
            await("scrollback match", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/1".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText()) ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                moveToCell(view, 1, 0);
                assertEquals(initialLink, view.getHoveredLink());
                assertTrue(view.searchNext());
                assertEquals(new TerminalLink.Osc8("ghosttyfx-test:search-row-0"), view.getHoveredLink());
                assertColor(Color.RED, cellColor(view, 20, 0));
                smoothScroll(view, 0.75);
                moveToCell(view, 1, 0);
                assertEquals(new TerminalLink.Osc8("ghosttyfx-test:search-row-1"), view.getHoveredLink());
                ((TextField) view.lookup("#ghosttyfx-search-field")).setText("visible-marker");
                assertTrue(view.searchNext());
                moveToCell(view, 1, 0);
                assertEquals(new TerminalLink.Osc8("ghosttyfx-test:search-row-1"), view.getHoveredLink());
                return null;
            });
        }
    }

    @Test
    void nativeSearchUsesGhosttyMatchingRules() throws Exception {
        try (var view = createView("aaaa\r\nÄ ä\r\nABC abc\r\nfoo   \r\n界e\u0301")) {
            awaitText(view, "界e\u0301");
            runOnFxThread(() -> {
                attachToScene(view);
                view.openSearch();
                return null;
            });
            for (var test : List.of(Map.entry("aaa", 2), Map.entry("ä", 1), Map.entry("abc", 2),
                    Map.entry("foo ", 0), Map.entry("界e\u0301", 1))) {
                runOnFxThread(() -> {
                    ((TextField) view.lookup("#ghosttyfx-search-field")).setText(test.getKey());
                    return null;
                });
                await("native query " + test.getKey(), START_TIMEOUT, () -> runOnFxThread(() ->
                        ((Label) view.lookup("#ghosttyfx-search-count")).getText().equals("-/" + test.getValue())
                                ? Optional.of(Boolean.TRUE) : Optional.empty()));
                runOnFxThread(() -> {
                    assertEquals(-1, view.selectedSearchMatchIndex());
                    assertTrue(view.searchNext());
                    assertEquals(test.getValue() > 0 ? 0 : -1, view.selectedSearchMatchIndex());
                    return null;
                });
            }
        }
    }

    @Test
    void searchNavigationConsumesNoMatchShortcutsUntilSearchCloses() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("\u001B[>1u\u001B]2;search-command-ready\u0007");
            awaitTitle(view, "search-command-ready");
            runOnFxThread(() -> {
                attachToScene(view);
                assertFalse(view.searchNext());
                assertFalse(view.searchPrevious());
                var modifier = isMac() ? KeyCombination.META_DOWN : KeyCombination.CONTROL_DOWN;
                var next = new KeyCodeCombination(KeyCode.G, modifier);
                var previous = new KeyCodeCombination(KeyCode.G, modifier, KeyCombination.SHIFT_DOWN);
                if (!isMac()) {
                    view.getTerminalShortcuts().add(new TerminalShortcut(next, view::searchNext));
                    view.getTerminalShortcuts().add(new TerminalShortcut(previous, view::searchPrevious));
                }
                view.openSearch();
                var field = (TextField) view.lookup("#ghosttyfx-search-field");
                var before = terminal.input.size();
                for (var query : List.of("", "missing")) {
                    field.setText(query);
                    view.requestFocus();
                    assertTrue(view.searchNext());
                    assertTrue(view.searchPrevious());
                    assertEquals(-1, view.selectedSearchMatchIndex());
                    fireTerminalShortcut(view, next);
                    fireTerminalShortcut(view, previous);
                    fireTerminalShortcut(field, next);
                    fireTerminalShortcut(field, previous);
                    assertEquals(before, terminal.input.size());
                }
                assertTrue(view.closeSearch());
                assertFalse(view.searchNext());
                assertFalse(view.searchPrevious());
                fireTerminalShortcut(view, next);
                assertTrue(terminal.input.size() > before);
                return null;
            });
        }
    }

    @Test
    void searchPreservesQueryAndFocusAndDoesNotSendFieldInputToTerminal() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("alpha\r\nbeta\r\nalpha");
            awaitText(view, "beta");
            runOnFxThread(() -> {
                attachToScene(view);
                dragSelection(view, 0, 4);
                var selection = view.getInputMethodRequests().getSelectedText();
                assertEquals("alpha", selection);
                view.openSearch();
                var field = (TextField) view.lookup("#ghosttyfx-search-field");
                field.setText("alpha");
                assertEquals(selection, view.getInputMethodRequests().getSelectedText());
                return null;
            });
            await("search matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/2".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText()) ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                var field = (TextField) view.lookup("#ghosttyfx-search-field");
                var before = terminal.input.toByteArray();
                field.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.A, false, false, false, false));
                field.fireEvent(new InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, List.of(), "z", 0));
                assertTrue(java.util.Arrays.equals(before, terminal.input.toByteArray()));
                field.setText("alpha");
                assertTrue(view.searchNext());
                var selected = view.selectedSearchMatchIndex();
                field.positionCaret(2);
                fireTerminalShortcut(view, searchTerminalShortcut());
                assertTrue(view.lookup("#ghosttyfx-search").isVisible());
                assertEquals("alpha", field.getText());
                assertEquals("alpha", field.getSelectedText());
                assertEquals(selected, view.selectedSearchMatchIndex());
                field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.ESCAPE)));
                assertFalse(view.lookup("#ghosttyfx-search").isVisible());
                view.openSearch();
                fireTerminalShortcut(view, new KeyCodeCombination(KeyCode.ESCAPE));
                assertFalse(view.lookup("#ghosttyfx-search").isVisible());
                view.openSearch();
                assertEquals("alpha", field.getText());
                field.setText("");
                field.fireEvent(keyEvent(new KeyCodeCombination(KeyCode.ESCAPE)));
                assertFalse(view.lookup("#ghosttyfx-search").isVisible());
                return null;
            });
        }
    }

    @Test
    void openingSearchUsesSelectionAndStartsFreshAfterClosing() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("alpha beta");
            awaitText(view, "alpha beta");
            runOnFxThread(() -> {
                fireTerminalShortcut(view, searchTerminalShortcut());
                var field = (TextField) view.lookup("#ghosttyfx-search-field");
                assertEquals("", field.getText());
                field.setText("old-query");
                fireTerminalShortcut(view, searchTerminalShortcut());
                assertEquals("old-query", field.getText());
                assertTrue(view.isSearchFieldFocused());
                assertTrue(view.closeSearch());
                fireTerminalShortcut(view, searchTerminalShortcut());
                assertEquals("", field.getText());
                assertEquals("0/0", ((Label) view.lookup("#ghosttyfx-search-count")).getText());
                dragSelection(view, 0, 4);
                fireTerminalShortcut(view, searchTerminalShortcut());
                assertEquals("alpha", field.getText());
                assertEquals("alpha", view.getInputMethodRequests().getSelectedText());
                field.setText("old-query");
                dragSelection(view, 6, 9);
                fireTerminalShortcut(view, searchTerminalShortcut());
                assertEquals("beta", field.getText());
                assertEquals("beta", view.getInputMethodRequests().getSelectedText());
                assertTrue(view.closeSearch());
                assertTrue(view.toggleSearch());
                assertEquals("beta", field.getText());
                clickCell(view, 11, 0, 1);
                assertFalse(view.canCopySelection());
                assertTrue(view.closeSearch());
                assertTrue(view.toggleSearch());
                assertEquals("", field.getText());
                return null;
            });
        }
    }

    @Test
    void nativeSearchRetainsSelectedMatchAcrossOutputAndScreenSwitchesAndRecoversFromResize() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("hit\r\nmiddle\r\nhit");
            awaitText(view, "middle");
            runOnFxThread(() -> {
                attachToScene(view);
                view.openSearch();
                ((TextField) view.lookup("#ghosttyfx-search-field")).setText("hit");
                return null;
            });
            await("initial matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/2".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText()) ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                assertTrue(view.searchNext());
                assertEquals(0, view.selectedSearchMatchIndex());
                return null;
            });
            terminal.emit("\r\nhit");
            await("new output retains match", START_TIMEOUT, () -> runOnFxThread(() ->
                    "2/3".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText()) ? Optional.of(Boolean.TRUE) : Optional.empty()));
            terminal.emit("\u001B[?1049h\u001B[Hhit");
            await("alternate screen matches", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/1".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText()) ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                assertTrue(view.searchNext());
                return null;
            });
            terminal.emit("\u001B[?1049l");
            await("primary search restored", START_TIMEOUT, () -> runOnFxThread(() ->
                    "2/3".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText()) ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                view.resize(view.getWidth() / 2, view.getHeight());
                return null;
            });
            await("search after resize", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/3".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText()) ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                assertTrue(view.searchNext());
                assertEquals("1/3", ((Label) view.lookup("#ghosttyfx-search-count")).getText());
                return null;
            });
        }
    }

    @Test
    void completedSearchResumesForNewScrollbackOutput() throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            terminal.emit("search-hit\r\n".repeat(2000) + "\u001B]2;search-history-ready\u001B\\");
            awaitTitle(view, "search-history-ready");
            runOnFxThread(() -> {
                attachToScene(view);
                view.openSearch();
                ((TextField) view.lookup("#ghosttyfx-search-field")).setText("search-hit");
                return null;
            });
            await("completed history search", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/2000".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText())
                            ? Optional.of(Boolean.TRUE) : Optional.empty()));
            terminal.emit("search-hit\r\n".repeat(1000));
            await("search resumed for new history", START_TIMEOUT, () -> runOnFxThread(() ->
                    "-/3000".equals(((Label) view.lookup("#ghosttyfx-search-count")).getText())
                            ? Optional.of(Boolean.TRUE) : Optional.empty()));
            runOnFxThread(() -> {
                assertTrue(view.searchNext());
                assertEquals("1/3000", ((Label) view.lookup("#ghosttyfx-search-count")).getText());
                return null;
            });
        }
    }

    @Test
    void reopeningSearchStartsFreshWithoutChangingOtherFindBars() throws Exception {
        try (var first = createView("first"); var second = createView("second")) {
            runOnFxThread(() -> {
                first.openSearch();
                ((TextField) first.lookup("#ghosttyfx-search-field")).setText("first query 界");
                first.closeSearch();
                second.openSearch();
                var secondField = (TextField) second.lookup("#ghosttyfx-search-field");
                assertEquals("", secondField.getText());
                secondField.setText("second query");
                first.openSearch();
                assertEquals("", ((TextField) first.lookup("#ghosttyfx-search-field")).getText());
                assertEquals("second query", secondField.getText());
                return null;
            });
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
            try (var view = createView(shell, tempDirectory)) {
                var selectedText = await("terminal output to become selectable", START_TIMEOUT, () -> runOnFxThread(() -> {
                    fireTerminalShortcut(view, selectAllTerminalShortcut());
                    var text = view.getInputMethodRequests().getSelectedText();
                    return text != null && text.contains(marker) ? Optional.of(text) : Optional.empty();
                }));

                runOnFxThread(() -> {
                    fireTerminalShortcut(view, copyTerminalShortcut());
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
        try (var view = createView(shell, tempDirectory)) {
            var handle = await("shell process to start", START_TIMEOUT, () -> readAliveProcess(pidFile));
            try {
                var selectedText = await("terminal output to become selectable", START_TIMEOUT, () -> runOnFxThread(() -> {
                    fireTerminalShortcut(view, selectAllTerminalShortcut());
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
        try (var view = createView(shell, tempDirectory)) {
            var handle = await("shell process to start", START_TIMEOUT, () -> readAliveProcess(pidFile));
            try {
                await("terminal output to become searchable", START_TIMEOUT, () -> runOnFxThread(() -> {
                    fireTerminalShortcut(view, selectAllTerminalShortcut());
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

    @Test
    void closedViewAndResourcesAreCollected() throws Exception {
        assertClosedViewAndResourcesAreCollected(new CollectibleTerminalFactory(), false);
    }

    @Test
    void inputTaskContinuesAfterTerminalClose() throws Exception {
        assertClosedViewAndResourcesAreCollected(new CollectibleTerminalFactory(), true);
    }

    private static void assertClosedViewAndResourcesAreCollected(CollectibleTerminalFactory factory, boolean sendInput) throws Exception {
        var references = closeUnreferencedView(factory, sendInput);

        await("closed terminal view to be collected", START_TIMEOUT, () -> {
            System.gc();
            return references.view().get() == null ? Optional.of(Boolean.TRUE) : Optional.empty();
        });
        assertTrue(factory.inputClosed.await(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "Timed out waiting for terminal input to close");
        await("closed PTY session to be collected", START_TIMEOUT, () -> {
            System.gc();
            return references.ptySession().get() == null ? Optional.of(Boolean.TRUE) : Optional.empty();
        });
        await("command queue to be collected", START_TIMEOUT, () -> {
            System.gc();
            return references.commands().get() == null ? Optional.of(Boolean.TRUE) : Optional.empty();
        });
        await("terminal resources to be collected", START_TIMEOUT, () -> {
            System.gc();
            return factory.terminalRef.get() == null ? Optional.of(Boolean.TRUE) : Optional.empty();
        });
    }

    private static CollectedReferences closeUnreferencedView(CollectibleTerminalFactory factory, boolean sendInput) throws Exception {
        var view = runOnFxThread(() -> new TerminalView(factory));
        var ptySessionField = TerminalView.class.getDeclaredField("ptySession");
        ptySessionField.setAccessible(true);
        var ptySession = (PtySession) ptySessionField.get(view);
        var commandsField = PtySession.class.getDeclaredField("commands");
        commandsField.setAccessible(true);
        var commands = (BlockingQueue<?>) commandsField.get(ptySession);
        assertTrue(factory.opened.await(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "Timed out waiting for terminal to open");
        view.close();
        awaitTerminalClosed(view);
        if (sendInput) {
            assertTrue(runOnFxThread(() -> view.sendText("x")));
            assertTrue(factory.inputFailed.await(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "Timed out waiting for terminal input to fail");
            assertTrue(runOnFxThread(() -> view.sendText("y")));
            await("input task to continue consuming", START_TIMEOUT, () ->
                    factory.inputAttempts.get() >= 2 ? Optional.of(Boolean.TRUE) : Optional.empty());
        }
        return new CollectedReferences(
                new WeakReference<>(view),
                new WeakReference<>(ptySession),
                new WeakReference<>(commands));
    }

    private static void awaitProcessStop(ProcessHandle handle) throws Exception {
        await("shell process to stop", STOP_TIMEOUT, () -> handle.isAlive() ? Optional.empty() : Optional.of(Boolean.TRUE));
    }

    private static TerminalView createView(ShellCommand shell, Path cwd) throws IOException {
        return new TerminalView((_, _) -> new ProcessTerminal(shell.command(), Map.of(), cwd));
    }

    private static TerminalView createView(Shell.Launcher launcher, Path cwd) throws IOException {
        return new TerminalView((_, _) -> new ProcessTerminal(launcher.command(), launcher.environment(), cwd));
    }

    private static TerminalView createPtyView(Shell.Launcher launcher, Path cwd) throws IOException {
        return new TerminalView((columns, rows) -> new PtyTestTerminal(launcher.command(), launcher.environment(), cwd, columns, rows));
    }

    private static TerminalView createView(String output) throws IOException {
        return new TerminalView((_, _) -> new StaticTerminal(output));
    }

    private static void assertCurrentDirectoryFromOutput(String output, String expected) throws Exception {
        var terminal = new ControlledTerminal();
        try (var view = new TerminalView((_, _) -> terminal)) {
            assertEquals("", view.getCurrentDirectory());
            assertEquals(view.getCurrentDirectory(), view.currentDirectoryProperty().get());
            terminal.emit(output);
            await("terminal current directory " + expected, START_TIMEOUT, () -> runOnFxThread(() ->
                    expected.equals(view.getCurrentDirectory()) && expected.equals(view.currentDirectoryProperty().get())
                            ? Optional.of(Boolean.TRUE)
                            : Optional.empty()));
        }
    }

    private static void assertProgress(
            ControlledTerminal terminal,
            TerminalView view,
            String output,
            Progress expected) throws Exception {
        var marker = "progress-" + Integer.toUnsignedString(output.hashCode());
        terminal.emit(output + "\u001B]2;" + marker + "\u001B\\");
        await("terminal progress " + expected, START_TIMEOUT, () -> runOnFxThread(() -> {
            var actual = view.getProgress();
            var matches = expected == null ? actual == null : expected.equals(actual);
            return matches && actual == view.progressProperty().get() && marker.equals(view.getTitle())
                    ? Optional.of(Boolean.TRUE)
                    : Optional.empty();
        }));
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

    private static KeyCombination copyTerminalShortcut() {
        return isMac()
                ? new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN)
                : new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN);
    }

    private static KeyCombination selectAllTerminalShortcut() {
        return isMac()
                ? new KeyCodeCombination(KeyCode.A, KeyCombination.META_DOWN)
                : new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
    }

    private static KeyCombination searchTerminalShortcut() {
        return isMac()
                ? new KeyCodeCombination(KeyCode.F, KeyCombination.META_DOWN)
                : new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
    }

    private static KeyEvent fireTerminalShortcut(EventTarget target, KeyCombination shortcut) {
        if (target instanceof TerminalView view && view.getScene() == null) {
            new Scene(view, view.getWidth(), view.getHeight());
        }
        if (target instanceof Node node) {
            node.requestFocus();
        }
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

    private static void awaitText(TerminalView view, String expected) throws Exception {
        await("terminal output containing " + expected, START_TIMEOUT, () -> runOnFxThread(() -> {
            fireTerminalShortcut(view, selectAllTerminalShortcut());
            var text = view.getInputMethodRequests().getSelectedText();
            return text != null && text.contains(expected) ? Optional.of(Boolean.TRUE) : Optional.empty();
        }));
        runOnFxThread(() -> {
            Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_PRESSED, cellX(view, 0, 0.1), cellY(view, 5, 0.5), true));
            Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_RELEASED, cellX(view, 0, 0.1), cellY(view, 5, 0.5), false));
            return null;
        });
    }

    private static void awaitTerminalClosed(TerminalView view) throws Exception {
        await("terminal output to close", START_TIMEOUT, () -> runOnFxThread(() ->
                view.getTerminalState() instanceof TerminalState.Closed
                        ? Optional.of(Boolean.TRUE)
                        : Optional.empty()));
    }

    private static void awaitTitle(TerminalView view, String title) throws Exception {
        await("terminal title " + title, START_TIMEOUT, () -> runOnFxThread(() ->
                title.equals(view.getTitle()) ? Optional.of(Boolean.TRUE) : Optional.empty()));
    }

    private static void awaitCellColor(TerminalView view, int column, int row, Color expected) throws Exception {
        await("cell color " + expected, START_TIMEOUT, () -> runOnFxThread(() ->
                colorsEqual(expected, cellColor(view, column, row))
                        ? Optional.of(Boolean.TRUE)
                        : Optional.empty()));
    }

    private static String stripedOutput(int rows) {
        var output = new StringBuilder("\u001B[?25l");
        for (var row = 0; row < rows; row++) {
            if (row != 0) {
                output.append("\r\n");
            }
            output.append(row % 2 == 0 ? "\u001B[48;2;255;0;0m" : "\u001B[48;2;0;0;255m");
            output.append("\u001B[2K ");
        }
        return output.toString();
    }

    private static ScrollEvent scrollEvent(double x, double y, double deltaY) {
        return new ScrollEvent(ScrollEvent.SCROLL, x, y, 0, 0,
                false, false, false, false, true, false,
                0, deltaY, 0, deltaY,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 1, null);
    }

    private static void smoothScroll(TerminalView view, double rows) {
        Event.fireEvent(view, scrollEvent(cellX(view, 1, 0.5), cellY(view, 0, 0.5), -rows * cellHeight(view)));
    }

    private static Color pixelColor(TerminalView view, double x, double y) {
        var snapshot = new WritableImage((int) Math.ceil(view.getWidth()), (int) Math.ceil(view.getHeight()));
        view.snapshot(null, snapshot);
        return snapshot.getPixelReader().getColor((int) Math.floor(x), (int) Math.floor(y));
    }

    private static Color cellColor(TerminalView view, int column, int row) {
        attachToScene(view);
        var width = Math.ceil(view.prefWidth(-1));
        var height = Math.ceil(view.prefHeight(-1));
        view.resize(width, height);
        view.applyCss();
        view.layout();
        var snapshot = new WritableImage((int) width, (int) height);
        view.snapshot(null, snapshot);
        return snapshot.getPixelReader().getColor(
                (int) Math.floor(cellX(view, column, 0.5)),
                (int) Math.floor(cellY(view, row, 0.5)));
    }

    private static boolean cellHasLinkUnderline(TerminalView view, int column, int row) {
        attachToScene(view);
        var width = Math.ceil(view.prefWidth(-1));
        var height = Math.ceil(view.prefHeight(-1));
        view.resize(width, height);
        view.applyCss();
        view.layout();
        var withUnderline = new WritableImage((int) width, (int) height);
        view.snapshot(null, withUnderline);
        var theme = view.getTheme();
        view.setTheme(new TerminalTheme(
                theme.background(),
                theme.foreground(),
                theme.palette(),
                theme.cursorColor(),
                theme.cursorText(),
                theme.selectionColor(),
                theme.selectionText(),
                theme.faintOpacity(),
                theme.scrollbarColor(),
                theme.scrollbarActiveColor(),
                theme.searchMatchColor(),
                theme.searchMatchBorderColor(),
                theme.searchCurrentMatchColor(),
                theme.searchCurrentMatchBorderColor(),
                0.0,
                0.0,
                0.0));
        var withoutUnderline = new WritableImage((int) width, (int) height);
        view.snapshot(null, withoutUnderline);
        view.setTheme(theme);
        var withUnderlinePixels = withUnderline.getPixelReader();
        var withoutUnderlinePixels = withoutUnderline.getPixelReader();
        var minX = (int) Math.floor(cellX(view, column, 0.0));
        var maxX = (int) Math.ceil(cellX(view, column, 1.0));
        var minY = (int) Math.floor(cellY(view, row, 1.0)) - 4;
        var maxY = (int) Math.ceil(cellY(view, row, 1.0));
        for (var y = minY; y < maxY; y++) {
            for (var x = minX; x < maxX; x++) {
                if (!colorsEqual(withUnderlinePixels.getColor(x, y), withoutUnderlinePixels.getColor(x, y))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertColor(Color expected, Color actual) {
        assertEquals(expected.getRed(), actual.getRed(), 0.01);
        assertEquals(expected.getGreen(), actual.getGreen(), 0.01);
        assertEquals(expected.getBlue(), actual.getBlue(), 0.01);
        assertEquals(expected.getOpacity(), actual.getOpacity(), 0.01);
    }

    private static boolean colorsEqual(Color expected, Color actual) {
        return Math.abs(expected.getRed() - actual.getRed()) <= 0.01
                && Math.abs(expected.getGreen() - actual.getGreen()) <= 0.01
                && Math.abs(expected.getBlue() - actual.getBlue()) <= 0.01
                && Math.abs(expected.getOpacity() - actual.getOpacity()) <= 0.01;
    }

    private static void clickCell(TerminalView view, int column, int row) {
        clickCell(view, column, row, 1);
    }

    private static void clickCell(TerminalView view, int column, int row, int clickCount) {
        var x = cellX(view, column, 0.5);
        var y = cellY(view, row, 0.5);
        for (var i = 1; i <= clickCount; i++) {
            Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_PRESSED, x, y, true, i));
            Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_RELEASED, x, y, false, i));
        }
    }

    private static void moveToCell(TerminalView view, int column, int row) {
        Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_MOVED, cellX(view, column, 0.5), cellY(view, row, 0.5), false));
    }

    private static void dragSelection(TerminalView view, int fromColumn, int toColumn) {
        var fromX = cellX(view, fromColumn, 0.1);
        var toX = cellX(view, toColumn, 0.8);
        var y = cellY(view, 0, 0.5);
        Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_PRESSED, fromX, y, true));
        Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_DRAGGED, toX, y, true));
        Event.fireEvent(view, mouseEvent(MouseEvent.MOUSE_RELEASED, toX, y, false));
    }

    private static double cellX(TerminalView view, int column, double offset) {
        return column * cellWidth(view) + cellWidth(view) * offset;
    }

    private static double cellY(TerminalView view, int row, double offset) {
        return row * cellHeight(view) + cellHeight(view) * offset;
    }

    private static double cellWidth(TerminalView view) {
        return (view.prefWidth(-1) - 10) / 80;
    }

    private static double cellHeight(TerminalView view) {
        return view.prefHeight(-1) / 24;
    }

    private static MouseEvent mouseEvent(EventType<MouseEvent> eventType, double x, double y, boolean primaryButtonDown) {
        return mouseEvent(eventType, x, y, primaryButtonDown, 1);
    }

    private static MouseEvent mouseEvent(EventType<MouseEvent> eventType, double x, double y, boolean primaryButtonDown, int clickCount) {
        return new MouseEvent(
                eventType,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                clickCount,
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

    private static String promptOutput(int count) {
        var lines = new StringBuilder();
        for (var i = 0; i < count; i++) {
            lines.append("\u001B]133;A\u0007$ command-").append(i).append("\u001B]133;B\u0007\n");
            lines.append("\u001B]133;C\u0007output-").append(i).append("\n\u001B]133;D;0\u0007");
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

    private static void attachToScene(TerminalView view) {
        if (view.getScene() == null) {
            new Scene(view, 800, 600);
        }
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

    private record CollectedReferences(
            WeakReference<TerminalView> view,
            WeakReference<PtySession> ptySession,
            WeakReference<BlockingQueue<?>> commands) {}

    private static final class ProcessTerminal implements Terminal {
        private final Process process;

        private ProcessTerminal(List<String> command, Map<String, String> environment, Path cwd) throws IOException {
            var builder = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true);
            builder.environment().putAll(environment);
            process = builder.start();
        }

        @Override
        public InputStream output() {
            return process.getInputStream();
        }

        @Override
        public OutputStream input() {
            return process.getOutputStream();
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class PtyTestTerminal implements Terminal {
        private final PtyProcess process;

        private PtyTestTerminal(List<String> command, Map<String, String> environment, Path cwd, int columns, int rows) throws IOException {
            process = (PtyProcess) new PtyProcessBuilder()
                    .setCommand(command.toArray(String[]::new))
                    .setConsole(false)
                    .setRedirectErrorStream(true)
                    .setDirectory(cwd.toString())
                    .setEnvironment(environment)
                    .setInitialColumns(columns)
                    .setInitialRows(rows)
                    .setUseWinConPty(true)
                    .start();
        }

        @Override
        public InputStream output() {
            return process.getInputStream();
        }

        @Override
        public OutputStream input() {
            return process.getOutputStream();
        }

        @Override
        public void resize(int columns, int rows) {
            process.setWinSize(new WinSize(columns, rows));
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class StaticTerminal implements Terminal {
        private final InputStream output;
        private final IOException closeFailure;

        private StaticTerminal(String output) {
            this(output, null);
        }

        private StaticTerminal(String output, IOException closeFailure) {
            this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            this.closeFailure = closeFailure;
        }

        @Override
        public InputStream output() {
            return output;
        }

        @Override
        public OutputStream input() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public void close() throws IOException {
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class CollectibleTerminalFactory implements TerminalFactory {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch terminalClosed = new CountDownLatch(1);
        private final CountDownLatch inputClosed = new CountDownLatch(1);
        private final CountDownLatch inputFailed = new CountDownLatch(1);
        private final AtomicInteger inputAttempts = new AtomicInteger();
        private volatile WeakReference<Terminal> terminalRef;

        @Override
        public Terminal open(int columns, int rows) throws IOException {
            var output = new PipedInputStream();
            var outputWriter = new PipedOutputStream(output);
            var input = new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    inputAttempts.incrementAndGet();
                    if (terminalClosed.getCount() == 0) {
                        inputFailed.countDown();
                        throw new IOException("expected input failure");
                    }
                }

                @Override
                public void close() {
                    inputClosed.countDown();
                }
            };
            var terminal = new Terminal() {
                @Override
                public InputStream output() {
                    return output;
                }

                @Override
                public OutputStream input() {
                    return input;
                }

                @Override
                public void resize(int columns, int rows, int widthPx, int heightPx) {
                }

                @Override
                public void close() throws IOException {
                    terminalClosed.countDown();
                    outputWriter.close();
                    output.close();
                }
            };
            terminalRef = new WeakReference<>(terminal);
            opened.countDown();
            return terminal;
        }
    }

    private static final class ControlledTerminal implements Terminal {
        private final PipedInputStream output = new PipedInputStream();
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private final PipedOutputStream outputWriter;

        private ControlledTerminal() throws IOException {
            outputWriter = new PipedOutputStream(output);
        }

        private void emit(String output) throws IOException {
            outputWriter.write(output.getBytes(StandardCharsets.UTF_8));
            outputWriter.flush();
        }

        @Override
        public InputStream output() {
            return output;
        }

        @Override
        public OutputStream input() {
            return input;
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public void close() throws IOException {
            outputWriter.close();
            output.close();
        }
    }

    private static final class QueryTerminal implements Terminal {
        private static final String COMPLETED_TITLE = "terminal-queries-completed";
        private static final byte[] CURSOR_POSITION_QUERY = "\u001B[6n".getBytes(StandardCharsets.UTF_8);

        private final PipedInputStream output = new PipedInputStream();
        private final PipedOutputStream outputWriter;
        private final OutputStream input = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                synchronized (QueryTerminal.this) {
                    if (value == 'R') {
                        if (--remainingQueries == 0) {
                            emit("\u001B]2;" + COMPLETED_TITLE + "\u001B\\");
                        } else {
                            outputWriter.write(CURSOR_POSITION_QUERY);
                            outputWriter.flush();
                        }
                    }
                }
            }
        };
        private int remainingQueries;

        private QueryTerminal(int queryCount) throws IOException {
            remainingQueries = queryCount;
            outputWriter = new PipedOutputStream(output);
            outputWriter.write(CURSOR_POSITION_QUERY);
            outputWriter.flush();
        }

        private void emit(String text) throws IOException {
            outputWriter.write(text.getBytes(StandardCharsets.UTF_8));
            outputWriter.flush();
        }

        @Override
        public InputStream output() {
            return output;
        }

        @Override
        public OutputStream input() {
            return input;
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public void close() throws IOException {
            outputWriter.close();
            output.close();
        }
    }
}
