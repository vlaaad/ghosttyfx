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
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;

public final class GhosttyCanvas extends Canvas implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();
    private static final int GHOSTTY_SUCCESS = 0;
    private static final int INITIAL_COLUMNS = 80;
    private static final int INITIAL_ROWS = 24;
    private static final int MAX_GHOSTTY_DIMENSION = 0xFFFF;
    private static final long INITIAL_MAX_SCROLLBACK = 1_000;
    private static final Font DEFAULT_FONT = Font.font("Monospaced", 14);

    private final MemorySegment terminal;
    private final MemorySegment renderState;
    private final Future<?> ioTask;

    private final ObjectProperty<Font> font = new SimpleObjectProperty<>(this, "font", DEFAULT_FONT) {
        @Override
        public void set(Font value) {
            super.set(java.util.Objects.requireNonNull(value, "font"));
        }
    };

    private final ObjectBinding<CellMetrics> cellMetrics = Bindings.createObjectBinding(() -> {
        var text = new Text();
        text.setBoundsType(TextBoundsType.LOGICAL);
        text.setFont(font.get());

        var maxWidth = 0.0;
        for (var c = 32; c < 127; c++) {
            text.setText(Character.toString((char) c));
            maxWidth = Math.max(maxWidth, text.getLayoutBounds().getWidth());
        }

        text.setText("M_");
        var bounds = text.getLayoutBounds();
        var cellWidthPx = Math.max(1, (int) Math.round(maxWidth));
        var cellHeightPx = Math.max(1, (int) Math.round(bounds.getHeight()));
        var baselineOffsetPx = (int) Math.round(-bounds.getMinY());
        baselineOffsetPx = Math.max(0, Math.min(cellHeightPx, baselineOffsetPx));
        return new CellMetrics(cellWidthPx, cellHeightPx, baselineOffsetPx);
    }, font);

    GhosttyCanvas(List<String> command, Path cwd, Map<String, String> environment) {
        var initialCellMetrics = cellMetrics.get();

        terminal = createGhosttyTerminal(INITIAL_COLUMNS, INITIAL_ROWS);
        resizeGhosttyTerminal(
                terminal,
                INITIAL_COLUMNS,
                INITIAL_ROWS,
                initialCellMetrics.cellWidthPx(),
                initialCellMetrics.cellHeightPx());
        renderState = createGhosttyRenderState();
        updateGhosttyRenderState(renderState, terminal);
        CLEANER.register(this, () -> {
            ghostty_vt_h.ghostty_render_state_free(renderState);
            ghostty_vt_h.ghostty_terminal_free(terminal);
        });

        setFocusTraversable(true);
        setWidth(prefWidth(-1));
        setHeight(prefHeight(-1));
        cellMetrics.addListener((_, _, _) -> handleCanvasResize());
        widthProperty().addListener((_, _, _) -> handleCanvasResize());
        heightProperty().addListener((_, _, _) -> handleCanvasResize());
        redraw();

        ioTask = IO.submit(() -> runProcess(command, cwd, environment));
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public double prefWidth(double height) {
        return INITIAL_COLUMNS * cellMetrics.get().cellWidthPx();
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
        return INITIAL_ROWS * cellMetrics.get().cellHeightPx();
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
    }

    public ObjectProperty<Font> fontProperty() {
        return font;
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
        if (getWidth() <= 0 || getHeight() <= 0) {
            redraw();
            return;
        }
        var cellMetrics = this.cellMetrics.get();
        var nextColumns = Math.clamp((int) Math.floor(getWidth() / cellMetrics.cellWidthPx()), 1, MAX_GHOSTTY_DIMENSION);
        var nextRows = Math.clamp((int) Math.floor(getHeight() / cellMetrics.cellHeightPx()), 1, MAX_GHOSTTY_DIMENSION);
        resizeGhosttyTerminal(
                terminal,
                nextColumns,
                nextRows,
                cellMetrics.cellWidthPx(),
                cellMetrics.cellHeightPx());
        updateGhosttyRenderState(renderState, terminal);
        redraw();
    }

    private void redraw() {
        var graphics = getGraphicsContext2D();
        var cellMetrics = this.cellMetrics.get();
        graphics.setFill(Color.rgb(20, 20, 20));
        graphics.fillRect(0, 0, getWidth(), getHeight());
        graphics.setFill(Color.rgb(230, 230, 230));
        graphics.setFont(font.get());
        graphics.fillText("TODO", 12, 12 + cellMetrics.baselineOffsetPx());
    }

    private static MemorySegment createGhosttyTerminal(int columns, int rows) {
        GhosttyFx.NativeLibraryHolder.ensureLoaded();
        try (var arena = Arena.ofConfined()) {
            var terminal = arena.allocate(ValueLayout.ADDRESS);
            var options = GhosttyTerminalOptions.allocate(arena);
            GhosttyTerminalOptions.cols(options, (short) columns);
            GhosttyTerminalOptions.rows(options, (short) rows);
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
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_resize(
                        terminal,
                        (short) columns,
                        (short) rows,
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

    private record CellMetrics(int cellWidthPx, int cellHeightPx, int baselineOffsetPx) {}
}
