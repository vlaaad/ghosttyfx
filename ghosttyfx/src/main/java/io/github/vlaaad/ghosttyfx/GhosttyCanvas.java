package io.github.vlaaad.ghosttyfx;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import io.github.vlaaad.ghostty.bindings.GhosttyColorRgb;
import io.github.vlaaad.ghostty.bindings.GhosttyRenderStateColors;
import io.github.vlaaad.ghostty.bindings.GhosttyStyle;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalOptions;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollbar;
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
    private static final int MAX_GRAPHEME_CODEPOINTS = 16;
    private static final long INITIAL_MAX_SCROLLBACK = 1_000;
    private static final double SCROLLBAR_WIDTH_PX = 6;
    private static final double SCROLLBAR_MARGIN_PX = 2;
    private static final double MIN_SCROLLBAR_HEIGHT_PX = 10;
    private static final double BLOCK_CURSOR_ALPHA = 0.5;
    private static final int CURSOR_STYLE_BAR = 0;
    private static final int CURSOR_STYLE_UNDERLINE = 2;
    private static final int CURSOR_STYLE_BLOCK_HOLLOW = 3;
    private static final Font DEFAULT_FONT = Font.font("Monospaced", 14);

    private final MemorySegment terminal;
    private final MemorySegment renderState;
    private final MemorySegment rowIterator;
    private final MemorySegment rowCells;
    private final Future<?> ioTask;
    private final BlockingQueue<ProcCommand> procCommands = new ArrayBlockingQueue<>(16_384);

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

        try (var arena = Arena.ofConfined()) {
            GhosttyFx.NativeLibraryHolder.ensureLoaded();

            var terminalPointer = arena.allocate(ValueLayout.ADDRESS);
            var options = GhosttyTerminalOptions.allocate(arena);
            GhosttyTerminalOptions.cols(options, (short) INITIAL_COLUMNS);
            GhosttyTerminalOptions.rows(options, (short) INITIAL_ROWS);
            GhosttyTerminalOptions.max_scrollback(options, INITIAL_MAX_SCROLLBACK);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_new(MemorySegment.NULL, terminalPointer, options),
                    "ghostty_terminal_new");
            terminal = terminalPointer.get(ValueLayout.ADDRESS, 0);
            resizeGhosttyTerminal(terminal, INITIAL_COLUMNS, INITIAL_ROWS, initialCellMetrics.cellWidthPx(), initialCellMetrics.cellHeightPx());

            var renderStatePointer = arena.allocate(ValueLayout.ADDRESS);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_new(MemorySegment.NULL, renderStatePointer),
                    "ghostty_render_state_new");
            renderState = renderStatePointer.get(ValueLayout.ADDRESS, 0);

            var rowIterator = arena.allocate(ValueLayout.ADDRESS);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_row_iterator_new(MemorySegment.NULL, rowIterator),
                    "ghostty_render_state_row_iterator_new");
            this.rowIterator = rowIterator.get(ValueLayout.ADDRESS, 0);

            var rowCells = arena.allocate(ValueLayout.ADDRESS);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_row_cells_new(MemorySegment.NULL, rowCells),
                    "ghostty_render_state_row_cells_new");
            this.rowCells = rowCells.get(ValueLayout.ADDRESS, 0);
        }
        updateGhosttyRenderState(renderState, terminal);
        CLEANER.register(this, () -> {
            ghostty_vt_h.ghostty_render_state_row_cells_free(rowCells);
            ghostty_vt_h.ghostty_render_state_row_iterator_free(rowIterator);
            ghostty_vt_h.ghostty_render_state_free(renderState);
            ghostty_vt_h.ghostty_terminal_free(terminal);
        });

        setFocusTraversable(true);
        setWidth(prefWidth(-1));
        setHeight(prefHeight(-1));
        cellMetrics.addListener((_, _, _) -> handleCanvasResize());
        widthProperty().addListener((_, _, _) -> handleCanvasResize());
        heightProperty().addListener((_, _, _) -> handleCanvasResize());
        focusedProperty().addListener((_, _, focused) -> handleFocusChange(focused));
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
                // input task, no cleanup since we want to consume the proc commands even after the process exits
                IO.submit(() -> {
                    try (var output = process.getOutputStream()) {
                        while (true) {
                            switch (procCommands.take()) {
                                case WriteInput(var bytes) ->
                                    output.write(bytes);
                                case ResizePty(var columns, var rows) ->
                                    process.setWinSize(new WinSize(columns, rows));
                            }
                        }
                    } catch (Exception _) {
                        while (true) {
                            procCommands.take();
                        }
                    }
                });
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
            return;
        }
        var cellMetrics = this.cellMetrics.get();
        var nextColumns = Math.clamp((int) Math.floor(getWidth() / cellMetrics.cellWidthPx()), 1, MAX_GHOSTTY_DIMENSION);
        var nextRows = Math.clamp((int) Math.floor(getHeight() / cellMetrics.cellHeightPx()), 1, MAX_GHOSTTY_DIMENSION);
        resizeGhosttyTerminal(terminal, nextColumns, nextRows, cellMetrics.cellWidthPx(), cellMetrics.cellHeightPx());
        writeCommand(new ResizePty(nextColumns, nextRows));
        updateGhosttyRenderState(renderState, terminal);
        redraw();
    }

    private void handleFocusChange(boolean focused) {
        try (var arena = Arena.ofConfined()) {
            var focusMode = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            if (ghostty_vt_h.ghostty_terminal_mode_get(
                    terminal,
                    (short) 1004,
                    focusMode) != GHOSTTY_SUCCESS
                    || !focusMode.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                return;
            }

            var buffer = arena.allocate(8);
            var written = arena.allocate(ValueLayout.JAVA_LONG);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_focus_encode(
                            focused ? ghostty_vt_h.GHOSTTY_FOCUS_GAINED() : ghostty_vt_h.GHOSTTY_FOCUS_LOST(),
                            buffer,
                            buffer.byteSize(),
                            written),
                    "ghostty_focus_encode");
            var length = Math.toIntExact(written.get(ValueLayout.JAVA_LONG, 0));
            if (length > 0) {
                writeCommand(new WriteInput(buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE)));
            }
        }
    }

    private void redraw() {
        var width = getWidth();
        var height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        var graphics = getGraphicsContext2D();
        var cellMetrics = this.cellMetrics.get();
        graphics.setFont(font.get());

        try (var arena = Arena.ofConfined()) {
            var colors = GhosttyRenderStateColors.allocate(arena);
            GhosttyRenderStateColors.size(colors, GhosttyRenderStateColors.sizeof());
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_colors_get(renderState, colors),
                    "ghostty_render_state_colors_get");

            var defaultBackground = toFxColor(GhosttyRenderStateColors.background(colors));
            graphics.setFill(defaultBackground);
            graphics.fillRect(0, 0, width, height);

            var rowIteratorPointer = arena.allocate(ValueLayout.ADDRESS);
            rowIteratorPointer.set(ValueLayout.ADDRESS, 0, rowIterator);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_get(
                            renderState,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR(),
                            rowIteratorPointer),
                    "ghostty_render_state_get(row_iterator)");

            var rowCellsPointer = arena.allocate(ValueLayout.ADDRESS);
            rowCellsPointer.set(ValueLayout.ADDRESS, 0, rowCells);
            var graphemeLength = arena.allocate(ValueLayout.JAVA_INT);
            var style = GhosttyStyle.allocate(arena);
            GhosttyStyle.size(style, GhosttyStyle.sizeof());
            var foreground = GhosttyColorRgb.allocate(arena);
            var background = GhosttyColorRgb.allocate(arena);
            var swappedColor = GhosttyColorRgb.allocate(arena);
            var text = new StringBuilder(MAX_GRAPHEME_CODEPOINTS * 2);
            var codepointsCapacity = MAX_GRAPHEME_CODEPOINTS;
            var codepoints = arena.allocate(MemoryLayout.sequenceLayout(codepointsCapacity, ValueLayout.JAVA_INT));
            var y = 0.0;

            while (ghostty_vt_h.ghostty_render_state_row_iterator_next(rowIterator)) {
                requireGhosttySuccess(
                        ghostty_vt_h.ghostty_render_state_row_get(
                                rowIterator,
                                ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS(),
                                rowCellsPointer),
                        "ghostty_render_state_row_get(cells)");

                var x = 0.0;
                while (ghostty_vt_h.ghostty_render_state_row_cells_next(rowCells)) {
                    requireGhosttySuccess(
                            ghostty_vt_h.ghostty_render_state_row_cells_get(
                                    rowCells,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN(),
                                    graphemeLength),
                            "ghostty_render_state_row_cells_get(graphemes_len)");
                    var codePointCount = graphemeLength.get(ValueLayout.JAVA_INT, 0);
                    if (codePointCount == 0) {
                        if (ghostty_vt_h.ghostty_render_state_row_cells_get(
                                rowCells,
                                ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR(),
                                background) == GHOSTTY_SUCCESS) {
                            graphics.setFill(toFxColor(background));
                            graphics.fillRect(x, y, cellMetrics.cellWidthPx(), cellMetrics.cellHeightPx());
                        }
                        x += cellMetrics.cellWidthPx();
                        continue;
                    }

                    if (codePointCount > codepointsCapacity) {
                        codepointsCapacity = codePointCount;
                        codepoints = arena.allocate(MemoryLayout.sequenceLayout(codepointsCapacity, ValueLayout.JAVA_INT));
                    }

                    requireGhosttySuccess(
                            ghostty_vt_h.ghostty_render_state_row_cells_get(
                                    rowCells,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF(),
                                    codepoints),
                            "ghostty_render_state_row_cells_get(graphemes_buf)");
                    GhosttyStyle.size(style, GhosttyStyle.sizeof());
                    requireGhosttySuccess(
                            ghostty_vt_h.ghostty_render_state_row_cells_get(
                                    rowCells,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE(),
                                    style),
                            "ghostty_render_state_row_cells_get(style)");

                    MemorySegment.copy(
                            GhosttyRenderStateColors.foreground(colors),
                            0,
                            foreground,
                            0,
                            GhosttyColorRgb.sizeof());
                    ghostty_vt_h.ghostty_render_state_row_cells_get(
                            rowCells,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR(),
                            foreground);

                    var hasBackground = ghostty_vt_h.ghostty_render_state_row_cells_get(
                            rowCells,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR(),
                            background) == GHOSTTY_SUCCESS;
                    if (!hasBackground) {
                        MemorySegment.copy(
                                GhosttyRenderStateColors.background(colors),
                                0,
                                background,
                                0,
                                GhosttyColorRgb.sizeof());
                    }

                    if (GhosttyStyle.inverse(style)) {
                        MemorySegment.copy(background, 0, swappedColor, 0, GhosttyColorRgb.sizeof());
                        MemorySegment.copy(foreground, 0, background, 0, GhosttyColorRgb.sizeof());
                        MemorySegment.copy(swappedColor, 0, foreground, 0, GhosttyColorRgb.sizeof());
                        hasBackground = true;
                    }

                    if (hasBackground) {
                        graphics.setFill(toFxColor(background));
                        graphics.fillRect(x, y, cellMetrics.cellWidthPx(), cellMetrics.cellHeightPx());
                    }

                    if (!GhosttyStyle.invisible(style)) {
                        text.setLength(0);
                        for (var i = 0; i < codePointCount; i++) {
                            var codePoint = codepoints.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES);
                            text.appendCodePoint(Character.isValidCodePoint(codePoint) ? codePoint : 0xFFFD);
                        }
                        var renderedText = text.toString();
                        var baseline = y + cellMetrics.baselineOffsetPx();
                        var foregroundColor = toFxColor(foreground);
                        graphics.setFill(foregroundColor);

                        // Text rendering: fake italic with a shear and fake bold with a second pass.
                        if (GhosttyStyle.italic(style)) {
                            graphics.save();
                            graphics.translate(x, y);
                            graphics.transform(1, 0, 0.2, 1, 0, 0);
                            graphics.fillText(renderedText, 0, cellMetrics.baselineOffsetPx());
                            if (GhosttyStyle.bold(style)) {
                                graphics.fillText(renderedText, 1, cellMetrics.baselineOffsetPx());
                            }
                            graphics.restore();
                        } else {
                            graphics.fillText(renderedText, x, baseline);
                            if (GhosttyStyle.bold(style)) {
                                graphics.fillText(renderedText, x + 1, baseline);
                            }
                        }
                    }

                    x += cellMetrics.cellWidthPx();
                }

                y += cellMetrics.cellHeightPx();
            }

            // Cursor rendering.
            var cursorVisible = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_get(
                            renderState,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE(),
                            cursorVisible),
                    "ghostty_render_state_get(cursor_visible)");
            if (cursorVisible.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                var cursorInViewport = arena.allocate(ValueLayout.JAVA_BOOLEAN);
                requireGhosttySuccess(
                        ghostty_vt_h.ghostty_render_state_get(
                                renderState,
                                ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE(),
                                cursorInViewport),
                        "ghostty_render_state_get(cursor_viewport_has_value)");
                if (cursorInViewport.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                    var cursorX = arena.allocate(ValueLayout.JAVA_SHORT);
                    var cursorY = arena.allocate(ValueLayout.JAVA_SHORT);
                    var cursorStyle = arena.allocate(ValueLayout.JAVA_INT);
                    requireGhosttySuccess(
                            ghostty_vt_h.ghostty_render_state_get(
                                    renderState,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X(),
                                    cursorX),
                            "ghostty_render_state_get(cursor_viewport_x)");
                    requireGhosttySuccess(
                            ghostty_vt_h.ghostty_render_state_get(
                                    renderState,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y(),
                                    cursorY),
                            "ghostty_render_state_get(cursor_viewport_y)");
                    requireGhosttySuccess(
                            ghostty_vt_h.ghostty_render_state_get(
                                    renderState,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE(),
                                    cursorStyle),
                            "ghostty_render_state_get(cursor_visual_style)");

                    var cursorColor = GhosttyRenderStateColors.cursor_has_value(colors)
                            ? toFxColor(GhosttyRenderStateColors.cursor(colors))
                            : toFxColor(GhosttyRenderStateColors.foreground(colors));
                    var cursorCellX = Short.toUnsignedInt(cursorX.get(ValueLayout.JAVA_SHORT, 0));
                    var cursorCellY = Short.toUnsignedInt(cursorY.get(ValueLayout.JAVA_SHORT, 0));
                    var cursorPixelX = cursorCellX * (double) cellMetrics.cellWidthPx();
                    var cursorPixelY = cursorCellY * (double) cellMetrics.cellHeightPx();
                    var cursorWidth = cellMetrics.cellWidthPx();
                    var cursorHeight = cellMetrics.cellHeightPx();

                    switch (cursorStyle.get(ValueLayout.JAVA_INT, 0)) {
                        case CURSOR_STYLE_BAR -> {
                            graphics.setFill(cursorColor);
                            graphics.fillRect(cursorPixelX, cursorPixelY, Math.max(1, Math.ceil(cursorWidth / 6.0)), cursorHeight);
                        }
                        case CURSOR_STYLE_UNDERLINE -> {
                            graphics.setFill(cursorColor);
                            graphics.fillRect(
                                    cursorPixelX,
                                    cursorPixelY + cursorHeight - Math.max(1, cursorHeight / 8.0),
                                    cursorWidth,
                                    Math.max(1, cursorHeight / 8.0));
                        }
                        case CURSOR_STYLE_BLOCK_HOLLOW -> {
                            graphics.setStroke(cursorColor);
                            graphics.strokeRect(
                                    cursorPixelX + 0.5,
                                    cursorPixelY + 0.5,
                                    Math.max(0, cursorWidth - 1),
                                    Math.max(0, cursorHeight - 1));
                        }
                        default -> {
                            graphics.setFill(cursorColor.deriveColor(0, 1, 1, BLOCK_CURSOR_ALPHA));
                            graphics.fillRect(cursorPixelX, cursorPixelY, cursorWidth, cursorHeight);
                        }
                    }
                }
            }

            // Scrollbar rendering.
            var scrollbar = GhosttyTerminalScrollbar.allocate(arena);
            if (ghostty_vt_h.ghostty_terminal_get(
                    terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_SCROLLBAR(),
                    scrollbar) == GHOSTTY_SUCCESS) {
                var total = GhosttyTerminalScrollbar.total(scrollbar);
                var visible = GhosttyTerminalScrollbar.len(scrollbar);
                if (total > visible && visible > 0) {
                    var canvasHeight = getHeight();
                    var thumbHeight = Math.max(MIN_SCROLLBAR_HEIGHT_PX, canvasHeight * ((double) visible / total));
                    var scrollable = total - visible;
                    var thumbY = scrollable == 0
                            ? canvasHeight - thumbHeight
                            : (canvasHeight - thumbHeight) * ((double) GhosttyTerminalScrollbar.offset(scrollbar) / scrollable);
                    var thumbX = getWidth() - SCROLLBAR_WIDTH_PX - SCROLLBAR_MARGIN_PX;
                    if (thumbX >= 0) {
                        graphics.setFill(Color.rgb(200, 200, 200, 0.5));
                        graphics.fillRect(thumbX, thumbY, SCROLLBAR_WIDTH_PX, thumbHeight);
                    }
                }
            }
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

    private static Color toFxColor(MemorySegment color) {
        return Color.rgb(
                Byte.toUnsignedInt(GhosttyColorRgb.r(color)),
                Byte.toUnsignedInt(GhosttyColorRgb.g(color)),
                Byte.toUnsignedInt(GhosttyColorRgb.b(color)));
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

    private void writeCommand(ProcCommand command) {
        try {
            procCommands.put(command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private sealed interface ProcCommand permits WriteInput, ResizePty {

    }

    private record WriteInput(byte[] bytes) implements ProcCommand {

    }

    private record ResizePty(int columns, int rows) implements ProcCommand {

    }

    private record CellMetrics(int cellWidthPx, int cellHeightPx, int baselineOffsetPx) {

    }
}
