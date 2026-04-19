package io.github.vlaaad.ghosttyfx;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.pty4j.PtyProcessBuilder;

import io.github.vlaaad.ghostty.bindings.GhosttyTerminalOptions;
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.paint.Color;

public final class GhosttyCanvas extends Canvas implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();
    private static final int GHOSTTY_SUCCESS = 0;
    private static final int INITIAL_COLUMNS = 80;
    private static final int INITIAL_ROWS = 24;
    private static final int INITIAL_CELL_WIDTH_PX = 9;
    private static final int INITIAL_CELL_HEIGHT_PX = 18;
    private static final double DEFAULT_WIDTH = INITIAL_COLUMNS * INITIAL_CELL_WIDTH_PX;
    private static final double DEFAULT_HEIGHT = INITIAL_ROWS * INITIAL_CELL_HEIGHT_PX;
    private static final long INITIAL_MAX_SCROLLBACK = 1_000;
    private final MemorySegment terminal;
    private final MemorySegment renderState;
    private final Future<?> ioTask;

    private GhosttyCanvas(List<String> command, Path cwd, Map<String, String> environment) {
        var normalizedCwd = cwd.toAbsolutePath().normalize();

        terminal = createGhosttyTerminal(INITIAL_COLUMNS, INITIAL_ROWS);
        resizeGhosttyTerminal(terminal, INITIAL_COLUMNS, INITIAL_ROWS, INITIAL_CELL_WIDTH_PX, INITIAL_CELL_HEIGHT_PX);
        renderState = createGhosttyRenderState();
        updateGhosttyRenderState(renderState, terminal);
        CLEANER.register(this, () -> {
            ghostty_vt_h.ghostty_render_state_free(renderState);
            ghostty_vt_h.ghostty_terminal_free(terminal);
        });

        setFocusTraversable(true);
        setWidth(DEFAULT_WIDTH);
        setHeight(DEFAULT_HEIGHT);
        widthProperty().addListener((_, _, _) -> handleCanvasResize());
        heightProperty().addListener((_, _, _) -> handleCanvasResize());
        redraw();

        ioTask = IO.submit(() -> runProcess(command, normalizedCwd, environment));
    }

    static GhosttyCanvas create(List<String> command, Path cwd, Map<String, String> environment) {
        var copiedCommand = List.copyOf(command);
        if (copiedCommand.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        return new GhosttyCanvas(copiedCommand, cwd, Map.copyOf(environment));
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public double prefWidth(double height) {
        return DEFAULT_WIDTH;
    }

    @Override
    public double minWidth(double height) {
        return 0;
    }

    @Override
    public double maxWidth(double height) {
        return Double.MAX_VALUE;
    }

    @Override
    public double prefHeight(double width) {
        return DEFAULT_HEIGHT;
    }

    @Override
    public double minHeight(double width) {
        return 0;
    }

    @Override
    public double maxHeight(double width) {
        return Double.MAX_VALUE;
    }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        redraw();
    }

    @Override
    public void close() {
        ioTask.cancel(true);
    }

    private int runProcess(List<String> command, Path cwd, Map<String, String> environment) throws Exception {
        if (!Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("cwd must be an existing directory: " + cwd);
        }

        var process = new PtyProcessBuilder()
                .setCommand(command.toArray(String[]::new))
                .setConsole(false)
                .setRedirectErrorStream(true)
                .setDirectory(cwd.toString())
                .setEnvironment(environment)
                .setInitialColumns(INITIAL_COLUMNS)
                .setInitialRows(INITIAL_ROWS)
                .setUseWinConPty(true)
                .start();
        try {
            var outputTask = IO.submit(() -> {
                try (var input = process.getInputStream()) {
                    var buffer = new byte[8 * 1024];
                    while (true) {
                        var read = input.read(buffer);
                        if (read < 0) {
                            return null;
                        }
                        var bytes = Arrays.copyOf(buffer, read);
                        runOnUiThread(() -> applyProcessOutput(bytes));
                    }
                }
            });
            try {
                try {
                    return process.waitFor();
                } catch (InterruptedException e) {
                    // exit requested
                    outputTask.cancel(true);
                    return destroyProcess(process);
                }
            } finally {
                // outputTask cleanup
                outputTask.cancel(true);
            }
        } finally {
            // process cleanup
            if (process.isAlive()) {
                destroyProcess(process);
            }
        }
    }

    private int destroyProcess(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return process.waitFor();
        }
        return process.exitValue();
    }

    private void applyProcessOutput(byte[] bytes) {
        try (var arena = Arena.ofConfined()) {
            var nativeBytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
            ghostty_vt_h.ghostty_terminal_vt_write(terminal, nativeBytes, bytes.length);
        }
        updateGhosttyRenderState(renderState, terminal);
        redraw();
    }

    private void handleCanvasResize() {
        var nextColumns = Math.max(1, (int) Math.floor(getWidth() / INITIAL_CELL_WIDTH_PX));
        var nextRows = Math.max(1, (int) Math.floor(getHeight() / INITIAL_CELL_HEIGHT_PX));
        resizeGhosttyTerminal(terminal, nextColumns, nextRows, INITIAL_CELL_WIDTH_PX, INITIAL_CELL_HEIGHT_PX);
        updateGhosttyRenderState(renderState, terminal);
        redraw();
    }

    private void redraw() {
        var graphics = getGraphicsContext2D();
        graphics.setFill(Color.rgb(20, 20, 20));
        graphics.fillRect(0, 0, getWidth(), getHeight());
        graphics.setFill(Color.rgb(230, 230, 230));
        graphics.fillText("TODO", 12, 24);
    }

    private static MemorySegment createGhosttyTerminal(int columns, int rows) {
        GhosttyFx.NativeLibraryHolder.ensureLoaded();
        var cols = toGhosttyDimension(columns, "columns");
        var rowsValue = toGhosttyDimension(rows, "rows");
        try (var arena = Arena.ofConfined()) {
            var terminal = arena.allocate(ValueLayout.ADDRESS);
            var options = GhosttyTerminalOptions.allocate(arena);
            GhosttyTerminalOptions.cols(options, cols);
            GhosttyTerminalOptions.rows(options, rowsValue);
            GhosttyTerminalOptions.max_scrollback(options, INITIAL_MAX_SCROLLBACK);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_new(MemorySegment.NULL, terminal, options),
                    "ghostty_terminal_new");
            return terminal.get(ValueLayout.ADDRESS, 0);
        }
    }

    private static MemorySegment createGhosttyRenderState() {
        try (var arena = Arena.ofConfined()) {
            var renderState = arena.allocate(ValueLayout.ADDRESS);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_new(MemorySegment.NULL, renderState),
                    "ghostty_render_state_new");
            return renderState.get(ValueLayout.ADDRESS, 0);
        }
    }

    private static void resizeGhosttyTerminal(
            MemorySegment terminal,
            int columns,
            int rows,
            int cellWidthPx,
            int cellHeightPx) {
        if (cellWidthPx <= 0) {
            throw new IllegalArgumentException("cellWidthPx must be positive");
        }
        if (cellHeightPx <= 0) {
            throw new IllegalArgumentException("cellHeightPx must be positive");
        }
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_resize(
                        terminal,
                        toGhosttyDimension(columns, "columns"),
                        toGhosttyDimension(rows, "rows"),
                        cellWidthPx,
                        cellHeightPx),
                "ghostty_terminal_resize");
    }

    // TODO: inline into redraw?
    private static void updateGhosttyRenderState(MemorySegment renderState, MemorySegment terminal) {
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_render_state_update(renderState, terminal),
                "ghostty_render_state_update");
    }

    private static short toGhosttyDimension(int value, String name) {
        if (value <= 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(name + " must be in range 1..65535");
        }
        return (short) value;
    }

    private static void requireGhosttySuccess(int result, String operation) {
        if (result != GHOSTTY_SUCCESS) {
            throw new IllegalStateException(operation + " failed with result=" + result);
        }
    }

    private static void runOnUiThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        try {
            Platform.runLater(action);
        } catch (IllegalStateException _) {
        }
    }
}
