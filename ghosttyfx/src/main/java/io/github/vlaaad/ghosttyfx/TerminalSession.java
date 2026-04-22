package io.github.vlaaad.ghosttyfx;

import io.github.vlaaad.ghostty.bindings.GhosttyColorRgb;
import io.github.vlaaad.ghostty.bindings.GhosttyFormatterTerminalOptions;
import io.github.vlaaad.ghostty.bindings.GhosttyMouseEncoderSize;
import io.github.vlaaad.ghostty.bindings.GhosttyMousePosition;
import io.github.vlaaad.ghostty.bindings.GhosttyPoint;
import io.github.vlaaad.ghostty.bindings.GhosttyPointCoordinate;
import io.github.vlaaad.ghostty.bindings.GhosttyPointValue;
import io.github.vlaaad.ghostty.bindings.GhosttyRenderStateColors;
import io.github.vlaaad.ghostty.bindings.GhosttySelection;
import io.github.vlaaad.ghostty.bindings.GhosttyStyle;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalOptions;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollbar;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollViewport;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollViewportValue;
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

final class TerminalSession implements AutoCloseable {

    private static final int GHOSTTY_SUCCESS = 0;
    private static final int MAX_GRAPHEME_CODEPOINTS = 16;
    private static final int KEY_BUFFER_SIZE = 256;
    private static final int CURSOR_STYLE_BAR = 0;
    private static final int CURSOR_STYLE_UNDERLINE = 2;
    private static final int CURSOR_STYLE_BLOCK_HOLLOW = 3;
    private static final int MAX_GHOSTTY_DIMENSION = 0xFFFF;
    private static final long INITIAL_MAX_SCROLLBACK = 1_000;
    private static final double BLOCK_CURSOR_ALPHA = 0.5;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final MemorySegment terminal;
    private final MemorySegment renderState;
    private final MemorySegment rowIterator;
    private final MemorySegment rowCells;
    private final MemorySegment keyEncoder;
    private final MemorySegment keyEvent;
    private final MemorySegment mouseEncoder;
    private final MemorySegment mouseEvent;

    TerminalSession(int initialColumns, int initialRows, GhosttyCanvas.CellMetrics initialCellMetrics) {
        try (var arena = Arena.ofConfined()) {
            GhosttyFx.NativeLibraryHolder.ensureLoaded();

            var terminalPointer = arena.allocate(ValueLayout.ADDRESS);
            var options = GhosttyTerminalOptions.allocate(arena);
            GhosttyTerminalOptions.cols(options, (short) initialColumns);
            GhosttyTerminalOptions.rows(options, (short) initialRows);
            GhosttyTerminalOptions.max_scrollback(options, INITIAL_MAX_SCROLLBACK);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_new(MemorySegment.NULL, terminalPointer, options),
                    "ghostty_terminal_new");
            terminal = terminalPointer.get(ValueLayout.ADDRESS, 0);

            renderState = newAddress(arena, "ghostty_render_state_new", ghostty_vt_h::ghostty_render_state_new);
            rowIterator = newAddress(arena, "ghostty_render_state_row_iterator_new", ghostty_vt_h::ghostty_render_state_row_iterator_new);
            rowCells = newAddress(arena, "ghostty_render_state_row_cells_new", ghostty_vt_h::ghostty_render_state_row_cells_new);
            keyEncoder = newAddress(arena, "ghostty_key_encoder_new", ghostty_vt_h::ghostty_key_encoder_new);
            keyEvent = newAddress(arena, "ghostty_key_event_new", ghostty_vt_h::ghostty_key_event_new);
            mouseEncoder = newAddress(arena, "ghostty_mouse_encoder_new", ghostty_vt_h::ghostty_mouse_encoder_new);
            mouseEvent = newAddress(arena, "ghostty_mouse_event_new", ghostty_vt_h::ghostty_mouse_event_new);

            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_resize(
                            terminal,
                            (short) initialColumns,
                            (short) initialRows,
                            initialCellMetrics.cellWidthPx(),
                            initialCellMetrics.cellHeightPx()),
                    "ghostty_terminal_resize");
        }
        updateRenderState();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        ghostty_vt_h.ghostty_mouse_event_free(mouseEvent);
        ghostty_vt_h.ghostty_mouse_encoder_free(mouseEncoder);
        ghostty_vt_h.ghostty_key_event_free(keyEvent);
        ghostty_vt_h.ghostty_key_encoder_free(keyEncoder);
        ghostty_vt_h.ghostty_render_state_row_cells_free(rowCells);
        ghostty_vt_h.ghostty_render_state_row_iterator_free(rowIterator);
        ghostty_vt_h.ghostty_render_state_free(renderState);
        ghostty_vt_h.ghostty_terminal_free(terminal);
    }

    Size resize(double widthPx, double heightPx, GhosttyCanvas.CellMetrics metrics, double scrollbarReservedWidthPx) {
        if (widthPx <= 0 || heightPx <= 0) {
            return null;
        }

        var columns = Math.clamp((int) Math.floor(Math.max(0, widthPx - scrollbarReservedWidthPx) / metrics.cellWidthPx()), 1, MAX_GHOSTTY_DIMENSION);
        var rows = Math.clamp((int) Math.floor(heightPx / metrics.cellHeightPx()), 1, MAX_GHOSTTY_DIMENSION);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_resize(
                        terminal,
                        (short) columns,
                        (short) rows,
                        metrics.cellWidthPx(),
                        metrics.cellHeightPx()),
                "ghostty_terminal_resize");
        updateRenderState();
        return new Size(columns, rows);
    }

    void writeToTerminal(byte[] bytes) {
        try (var arena = Arena.ofConfined()) {
            var nativeBytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
            ghostty_vt_h.ghostty_terminal_vt_write(terminal, nativeBytes, bytes.length);
        }
        updateRenderState();
    }

    byte[] encodeFocus(boolean focused) {
        try (var arena = Arena.ofConfined()) {
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
            return length == 0
                    ? new byte[0]
                    : buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    void scrollViewportBy(long deltaRows) {
        if (deltaRows == 0) {
            return;
        }

        try (var arena = Arena.ofConfined()) {
            var behavior = GhosttyTerminalScrollViewport.allocate(arena);
            GhosttyTerminalScrollViewport.tag(behavior, ghostty_vt_h.GHOSTTY_SCROLL_VIEWPORT_DELTA());
            GhosttyTerminalScrollViewportValue.delta(GhosttyTerminalScrollViewport.value(behavior), deltaRows);
            ghostty_vt_h.ghostty_terminal_scroll_viewport(terminal, behavior);
        }
        updateRenderState();
    }

    boolean mouseTrackingEnabled() {
        try (var arena = Arena.ofConfined()) {
            var mouseTracking = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            return ghostty_vt_h.ghostty_terminal_get(
                    terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING(),
                    mouseTracking) == GHOSTTY_SUCCESS && mouseTracking.get(ValueLayout.JAVA_BOOLEAN, 0);
        }
    }

    byte[] encodeMouseScroll(
            double x,
            double y,
            int lineDelta,
            short mods,
            double widthPx,
            double heightPx,
            GhosttyCanvas.CellMetrics metrics,
            double scrollbarReservedWidthPx) {
        var button = lineDelta > 0
                ? ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_FOUR()
                : ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_FIVE();
        var count = Math.abs(lineDelta);
        var chunks = new byte[count * 2][];
        var chunkCount = 0;
        var totalLength = 0;
        refreshMouseEncoder(false, widthPx, heightPx, metrics, scrollbarReservedWidthPx);
        for (var i = 0; i < count; i++) {
            var press = encodeMouseButton(
                    ghostty_vt_h.GHOSTTY_MOUSE_ACTION_PRESS(),
                    button,
                    x,
                    y,
                    mods);
            if (press.length > 0) {
                chunks[chunkCount++] = press;
                totalLength += press.length;
            }

            var release = encodeMouseButton(
                    ghostty_vt_h.GHOSTTY_MOUSE_ACTION_RELEASE(),
                    button,
                    x,
                    y,
                    mods);
            if (release.length > 0) {
                chunks[chunkCount++] = release;
                totalLength += release.length;
            }
        }
        if (totalLength == 0) {
            return new byte[0];
        }

        var bytes = new byte[totalLength];
        var offset = 0;
        for (var i = 0; i < chunkCount; i++) {
            var chunk = chunks[i];
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            offset += chunk.length;
        }
        return bytes;
    }

    int viewportRowCount(int fallback, int cellHeightPx, double heightPx) {
        try (var arena = Arena.ofConfined()) {
            var rows = arena.allocate(ValueLayout.JAVA_SHORT);
            if (ghostty_vt_h.ghostty_terminal_get(
                    terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_ROWS(),
                    rows) != GHOSTTY_SUCCESS) {
                return Math.max(1, fallback > 0 ? fallback : (int) Math.floor(heightPx / cellHeightPx));
            }
            return Math.max(1, Short.toUnsignedInt(rows.get(ValueLayout.JAVA_SHORT, 0)));
        }
    }

    ScrollbarInfo scrollbarInfo(double widthPx, double heightPx, double scrollbarReservedWidthPx, double minScrollbarHeightPx) {
        try (var arena = Arena.ofConfined()) {
            var scrollbar = GhosttyTerminalScrollbar.allocate(arena);
            if (ghostty_vt_h.ghostty_terminal_get(
                    terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_SCROLLBAR(),
                    scrollbar) != GHOSTTY_SUCCESS) {
                return null;
            }

            var total = GhosttyTerminalScrollbar.total(scrollbar);
            var visible = GhosttyTerminalScrollbar.len(scrollbar);
            if (visible <= 0) {
                return null;
            }

            var scrollableRows = Math.max(0, total - visible);
            var thumbHeight = scrollableRows == 0
                    ? 0
                    : Math.max(minScrollbarHeightPx, heightPx * ((double) visible / total));
            var thumbY = scrollableRows == 0
                    ? 0
                    : (heightPx - thumbHeight) * ((double) GhosttyTerminalScrollbar.offset(scrollbar) / scrollableRows);
            return new ScrollbarInfo(
                    total,
                    visible,
                    GhosttyTerminalScrollbar.offset(scrollbar),
                    Math.max(0, widthPx - scrollbarReservedWidthPx),
                    Math.max(0, heightPx),
                    thumbY,
                    thumbHeight);
        }
    }

    String selectedText(InputModel.Selection selection) {
        if (selection.isEmpty()) {
            return "";
        }
        try (var arena = Arena.ofConfined()) {
            var formatterSelection = formatterSelection(arena, selection);
            if (formatterSelection.address() == 0) {
                return "";
            }

            var options = GhosttyFormatterTerminalOptions.allocate(arena);
            GhosttyFormatterTerminalOptions.size(options, GhosttyFormatterTerminalOptions.sizeof());
            GhosttyFormatterTerminalOptions.emit(options, ghostty_vt_h.GHOSTTY_FORMATTER_FORMAT_PLAIN());
            GhosttyFormatterTerminalOptions.unwrap(options, true);
            GhosttyFormatterTerminalOptions.trim(options, true);
            GhosttyFormatterTerminalOptions.selection(options, formatterSelection);

            var formatterPointer = arena.allocate(ValueLayout.ADDRESS);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_formatter_terminal_new(MemorySegment.NULL, formatterPointer, terminal, options),
                    "ghostty_formatter_terminal_new");
            var formatter = formatterPointer.get(ValueLayout.ADDRESS, 0);
            try {
                var outputPointer = arena.allocate(ValueLayout.ADDRESS);
                var outputLength = arena.allocate(ValueLayout.JAVA_LONG);
                requireGhosttySuccess(
                        ghostty_vt_h.ghostty_formatter_format_alloc(
                                formatter,
                                MemorySegment.NULL,
                                outputPointer,
                                outputLength),
                        "ghostty_formatter_format_alloc");
                var length = outputLength.get(ValueLayout.JAVA_LONG, 0);
                if (length == 0) {
                    return "";
                }

                var output = outputPointer.get(ValueLayout.ADDRESS, 0);
                try {
                    return new String(output.reinterpret(length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
                } finally {
                    ghostty_vt_h.ghostty_free(MemorySegment.NULL, output, length);
                }
            } finally {
                ghostty_vt_h.ghostty_formatter_free(formatter);
            }
        }
    }

    InputModel.Selection selectAllSelection() {
        try (var arena = Arena.ofConfined()) {
            var cols = arena.allocate(ValueLayout.JAVA_SHORT);
            var totalRows = arena.allocate(ValueLayout.JAVA_LONG);
            if (ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_COLS(), cols) != GHOSTTY_SUCCESS
                    || ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_TOTAL_ROWS(), totalRows) != GHOSTTY_SUCCESS) {
                return InputModel.Selection.empty();
            }

            var columnCount = Short.toUnsignedInt(cols.get(ValueLayout.JAVA_SHORT, 0));
            var rowCount = Math.toIntExact(totalRows.get(ValueLayout.JAVA_LONG, 0));
            return columnCount == 0 || rowCount == 0
                    ? InputModel.Selection.empty()
                    : InputModel.Selection.linear(
                            new InputModel.ScreenPoint(0, 0),
                            new InputModel.ScreenPoint(columnCount - 1, rowCount - 1));
        }
    }

    boolean readMode(short mode) {
        try (var arena = Arena.ofConfined()) {
            var value = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            return ghostty_vt_h.ghostty_terminal_mode_get(terminal, mode, value) == GHOSTTY_SUCCESS
                    && value.get(ValueLayout.JAVA_BOOLEAN, 0);
        }
    }

    boolean encodeAndWrite(InputModel.EncodeOutput output, boolean macOptionAsAlt, Consumer<byte[]> sink) {
        refreshKeyEncoder(macOptionAsAlt);
        try (var arena = Arena.ofConfined()) {
            ghostty_vt_h.ghostty_key_event_set_action(keyEvent, output.action());
            ghostty_vt_h.ghostty_key_event_set_key(keyEvent, output.ghosttyKey());
            ghostty_vt_h.ghostty_key_event_set_mods(keyEvent, output.mods());
            ghostty_vt_h.ghostty_key_event_set_consumed_mods(keyEvent, output.consumedMods());
            ghostty_vt_h.ghostty_key_event_set_unshifted_codepoint(keyEvent, output.unshiftedCodepoint());
            ghostty_vt_h.ghostty_key_event_set_composing(keyEvent, output.composing());

            if (output.utf8().isEmpty()) {
                ghostty_vt_h.ghostty_key_event_set_utf8(keyEvent, MemorySegment.NULL, 0);
            } else {
                var utf8 = output.utf8().getBytes(StandardCharsets.UTF_8);
                ghostty_vt_h.ghostty_key_event_set_utf8(
                        keyEvent,
                        arena.allocateFrom(ValueLayout.JAVA_BYTE, utf8),
                        utf8.length);
            }

            var written = arena.allocate(ValueLayout.JAVA_LONG);
            var buffer = arena.allocate(KEY_BUFFER_SIZE);
            var result = ghostty_vt_h.ghostty_key_encoder_encode(
                    keyEncoder,
                    keyEvent,
                    buffer,
                    buffer.byteSize(),
                    written);
            if (result == ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
                var required = written.get(ValueLayout.JAVA_LONG, 0);
                buffer = arena.allocate(required);
                requireGhosttySuccess(
                        ghostty_vt_h.ghostty_key_encoder_encode(
                                keyEncoder,
                                keyEvent,
                                buffer,
                                buffer.byteSize(),
                                written),
                        "ghostty_key_encoder_encode");
            } else {
                requireGhosttySuccess(result, "ghostty_key_encoder_encode");
            }

            var length = Math.toIntExact(written.get(ValueLayout.JAVA_LONG, 0));
            if (length == 0) {
                return false;
            }
            sink.accept(buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE));
            return true;
        }
    }

    byte[] encodePaste(String text, boolean bracketed) {
        var input = text.getBytes(StandardCharsets.UTF_8);
        try (var arena = Arena.ofConfined()) {
            var data = arena.allocateFrom(ValueLayout.JAVA_BYTE, input);
            var written = arena.allocate(ValueLayout.JAVA_LONG);
            var result = ghostty_vt_h.ghostty_paste_encode(
                    data,
                    input.length,
                    bracketed,
                    MemorySegment.NULL,
                    0,
                    written);
            if (result != GHOSTTY_SUCCESS && result != ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
                requireGhosttySuccess(result, "ghostty_paste_encode");
            }

            var buffer = arena.allocate(Math.max(1, written.get(ValueLayout.JAVA_LONG, 0)));
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_paste_encode(
                            data,
                            input.length,
                            bracketed,
                            buffer,
                            buffer.byteSize(),
                            written),
                    "ghostty_paste_encode");
            var length = Math.toIntExact(written.get(ValueLayout.JAVA_LONG, 0));
            return buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    void render(
            GraphicsContext graphics,
            double width,
            double height,
            Font font,
            GhosttyCanvas.CellMetrics metrics,
            InputModel.InputState inputState,
            double scrollbarWidthPx,
            Color selectionColor,
            Color preeditFill,
            Color preeditBackground,
            Color preeditStroke) {
        graphics.setFont(font);

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
                            graphics.fillRect(x, y, metrics.cellWidthPx(), metrics.cellHeightPx());
                        }
                        x += metrics.cellWidthPx();
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
                        graphics.fillRect(x, y, metrics.cellWidthPx(), metrics.cellHeightPx());
                    }

                    if (!GhosttyStyle.invisible(style)) {
                        text.setLength(0);
                        for (var i = 0; i < codePointCount; i++) {
                            var codePoint = codepoints.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES);
                            text.appendCodePoint(Character.isValidCodePoint(codePoint) ? codePoint : 0xFFFD);
                        }
                        var renderedText = text.toString();
                        var baseline = y + metrics.baselineOffsetPx();
                        graphics.setFill(toFxColor(foreground));

                        if (GhosttyStyle.italic(style)) {
                            graphics.save();
                            graphics.translate(x, y);
                            graphics.transform(1, 0, 0.2, 1, 0, 0);
                            graphics.fillText(renderedText, 0, metrics.baselineOffsetPx());
                            if (GhosttyStyle.bold(style)) {
                                graphics.fillText(renderedText, 1, metrics.baselineOffsetPx());
                            }
                            graphics.restore();
                        } else {
                            graphics.fillText(renderedText, x, baseline);
                            if (GhosttyStyle.bold(style)) {
                                graphics.fillText(renderedText, x + 1, baseline);
                            }
                        }
                    }

                    x += metrics.cellWidthPx();
                }

                y += metrics.cellHeightPx();
            }

            renderSelectionOverlay(graphics, metrics, inputState.selection(), selectionColor);
            renderCursor(graphics, metrics, inputState.preedit(), colors, preeditFill, preeditBackground, preeditStroke);

            var scrollbar = scrollbarInfo(width, height, scrollbarWidthPx, 10);
            if (scrollbar != null && scrollbar.scrollable()) {
                graphics.setFill(Color.rgb(200, 200, 200, 0.5));
                graphics.fillRect(scrollbar.thumbX(), scrollbar.thumbY(), 6, scrollbar.thumbHeight());
            }
        }
    }

    GhosttyCanvas.CursorLocation currentCursorLocation(GhosttyCanvas.CellMetrics metrics) {
        try (var arena = Arena.ofConfined()) {
            var cursorVisible = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            if (ghostty_vt_h.ghostty_render_state_get(
                    renderState,
                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE(),
                    cursorVisible) != GHOSTTY_SUCCESS
                    || !cursorVisible.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                return null;
            }

            var cursorInViewport = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            if (ghostty_vt_h.ghostty_render_state_get(
                    renderState,
                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE(),
                    cursorInViewport) != GHOSTTY_SUCCESS
                    || !cursorInViewport.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                return null;
            }

            var cursorX = arena.allocate(ValueLayout.JAVA_SHORT);
            var cursorY = arena.allocate(ValueLayout.JAVA_SHORT);
            if (ghostty_vt_h.ghostty_render_state_get(
                    renderState,
                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X(),
                    cursorX) != GHOSTTY_SUCCESS
                    || ghostty_vt_h.ghostty_render_state_get(
                            renderState,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y(),
                            cursorY) != GHOSTTY_SUCCESS) {
                return null;
            }

            var cellX = Short.toUnsignedInt(cursorX.get(ValueLayout.JAVA_SHORT, 0));
            var cellY = Short.toUnsignedInt(cursorY.get(ValueLayout.JAVA_SHORT, 0));
            return new GhosttyCanvas.CursorLocation(
                    cellX,
                    cellY,
                    cellX * (double) metrics.cellWidthPx(),
                    cellY * (double) metrics.cellHeightPx());
        }
    }

    private byte[] encodeMouseButton(int action, int button, double x, double y, short mods) {
        try (var arena = Arena.ofConfined()) {
            ghostty_vt_h.ghostty_mouse_event_set_action(mouseEvent, action);
            ghostty_vt_h.ghostty_mouse_event_set_button(mouseEvent, button);
            ghostty_vt_h.ghostty_mouse_event_set_mods(mouseEvent, mods);
            var position = GhosttyMousePosition.allocate(arena);
            GhosttyMousePosition.x(position, (float) x);
            GhosttyMousePosition.y(position, (float) y);
            ghostty_vt_h.ghostty_mouse_event_set_position(mouseEvent, position);

            var written = arena.allocate(ValueLayout.JAVA_LONG);
            var buffer = arena.allocate(KEY_BUFFER_SIZE);
            var result = ghostty_vt_h.ghostty_mouse_encoder_encode(
                    mouseEncoder,
                    mouseEvent,
                    buffer,
                    buffer.byteSize(),
                    written);
            if (result == ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
                var required = written.get(ValueLayout.JAVA_LONG, 0);
                buffer = arena.allocate(required);
                requireGhosttySuccess(
                        ghostty_vt_h.ghostty_mouse_encoder_encode(
                                mouseEncoder,
                                mouseEvent,
                                buffer,
                                buffer.byteSize(),
                                written),
                        "ghostty_mouse_encoder_encode");
            } else {
                requireGhosttySuccess(result, "ghostty_mouse_encoder_encode");
            }

            var length = Math.toIntExact(written.get(ValueLayout.JAVA_LONG, 0));
            if (length == 0) {
                return new byte[0];
            }
            return buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    private void refreshMouseEncoder(
            boolean anyButtonPressed,
            double widthPx,
            double heightPx,
            GhosttyCanvas.CellMetrics metrics,
            double scrollbarReservedWidthPx) {
        ghostty_vt_h.ghostty_mouse_encoder_setopt_from_terminal(mouseEncoder, terminal);
        try (var arena = Arena.ofConfined()) {
            var size = GhosttyMouseEncoderSize.allocate(arena);
            GhosttyMouseEncoderSize.size(size, GhosttyMouseEncoderSize.sizeof());
            GhosttyMouseEncoderSize.screen_width(size, Math.max(1, (int) Math.ceil(widthPx)));
            GhosttyMouseEncoderSize.screen_height(size, Math.max(1, (int) Math.ceil(heightPx)));
            GhosttyMouseEncoderSize.cell_width(size, metrics.cellWidthPx());
            GhosttyMouseEncoderSize.cell_height(size, metrics.cellHeightPx());
            GhosttyMouseEncoderSize.padding_top(size, 0);
            GhosttyMouseEncoderSize.padding_bottom(size, 0);
            GhosttyMouseEncoderSize.padding_right(size, (int) Math.ceil(scrollbarReservedWidthPx));
            GhosttyMouseEncoderSize.padding_left(size, 0);
            ghostty_vt_h.ghostty_mouse_encoder_setopt(
                    mouseEncoder,
                    ghostty_vt_h.GHOSTTY_MOUSE_ENCODER_OPT_SIZE(),
                    size);

            var anyPressed = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            anyPressed.set(ValueLayout.JAVA_BOOLEAN, 0, anyButtonPressed);
            ghostty_vt_h.ghostty_mouse_encoder_setopt(
                    mouseEncoder,
                    ghostty_vt_h.GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED(),
                    anyPressed);
        }
    }

    private void refreshKeyEncoder(boolean macOptionAsAlt) {
        ghostty_vt_h.ghostty_key_encoder_setopt_from_terminal(keyEncoder, terminal);
        try (var arena = Arena.ofConfined()) {
            var option = arena.allocate(ValueLayout.JAVA_INT);
            option.set(
                    ValueLayout.JAVA_INT,
                    0,
                    macOptionAsAlt
                            ? ghostty_vt_h.GHOSTTY_OPTION_AS_ALT_TRUE()
                            : ghostty_vt_h.GHOSTTY_OPTION_AS_ALT_FALSE());
            ghostty_vt_h.ghostty_key_encoder_setopt(
                    keyEncoder,
                    ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT(),
                    option);
        }
    }

    private MemorySegment formatterSelection(Arena arena, InputModel.Selection selection) {
        var normalized = selection.normalized();
        if (normalized.isEmpty()) {
            return MemorySegment.NULL;
        }

        var ghosttySelection = GhosttySelection.allocate(arena);
        GhosttySelection.size(ghosttySelection, GhosttySelection.sizeof());
        GhosttySelection.rectangle(ghosttySelection, normalized.rectangle());
        return writeGridRef(arena, normalized.from(), GhosttySelection.start(ghosttySelection))
                && writeGridRef(arena, normalized.to(), GhosttySelection.end(ghosttySelection))
                ? ghosttySelection
                : MemorySegment.NULL;
    }

    private boolean writeGridRef(Arena arena, InputModel.ScreenPoint point, MemorySegment outGridRef) {
        var coordinate = GhosttyPointCoordinate.allocate(arena);
        GhosttyPointCoordinate.x(coordinate, (short) point.x());
        GhosttyPointCoordinate.y(coordinate, point.y());
        var ghosttyPoint = GhosttyPoint.allocate(arena);
        GhosttyPoint.tag(ghosttyPoint, ghostty_vt_h.GHOSTTY_POINT_TAG_SCREEN());
        GhosttyPointValue.coordinate(GhosttyPoint.value(ghosttyPoint), coordinate);
        return ghostty_vt_h.ghostty_terminal_grid_ref(terminal, ghosttyPoint, outGridRef) == GHOSTTY_SUCCESS;
    }

    private void renderSelectionOverlay(
            GraphicsContext graphics,
            GhosttyCanvas.CellMetrics metrics,
            InputModel.Selection selection,
            Color selectionColor) {
        var normalized = selection.normalized();
        if (normalized.isEmpty()) {
            return;
        }

        try (var arena = Arena.ofConfined()) {
            var cols = arena.allocate(ValueLayout.JAVA_SHORT);
            var rows = arena.allocate(ValueLayout.JAVA_SHORT);
            var scrollbar = GhosttyTerminalScrollbar.allocate(arena);
            if (ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_COLS(), cols) != GHOSTTY_SUCCESS
                    || ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_ROWS(), rows) != GHOSTTY_SUCCESS
                    || ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_SCROLLBAR(), scrollbar) != GHOSTTY_SUCCESS) {
                return;
            }

            var columnCount = Short.toUnsignedInt(cols.get(ValueLayout.JAVA_SHORT, 0));
            var rowCount = Short.toUnsignedInt(rows.get(ValueLayout.JAVA_SHORT, 0));
            var viewportTop = Math.toIntExact(GhosttyTerminalScrollbar.offset(scrollbar));
            var viewportBottom = viewportTop + rowCount - 1;
            var from = normalized.from();
            var to = normalized.to();
            graphics.setFill(selectionColor);
            for (var screenRow = Math.max(from.y(), viewportTop); screenRow <= Math.min(to.y(), viewportBottom); screenRow++) {
                var startColumn = normalized.rectangle() || screenRow != from.y() ? 0 : from.x();
                var endColumn = normalized.rectangle() || screenRow != to.y() ? columnCount - 1 : to.x();
                if (normalized.rectangle()) {
                    startColumn = Math.min(from.x(), to.x());
                    endColumn = Math.max(from.x(), to.x());
                }
                startColumn = Math.max(0, Math.min(startColumn, columnCount - 1));
                endColumn = Math.max(0, Math.min(endColumn, columnCount - 1));
                if (startColumn > endColumn) {
                    continue;
                }

                graphics.fillRect(
                        startColumn * (double) metrics.cellWidthPx(),
                        (screenRow - viewportTop) * (double) metrics.cellHeightPx(),
                        (endColumn - startColumn + 1) * (double) metrics.cellWidthPx(),
                        metrics.cellHeightPx());
            }
        }
    }

    private void renderCursor(
            GraphicsContext graphics,
            GhosttyCanvas.CellMetrics metrics,
            InputModel.Preedit preedit,
            MemorySegment colors,
            Color preeditFill,
            Color preeditBackground,
            Color preeditStroke) {
        try (var arena = Arena.ofConfined()) {
            var cursorVisible = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_get(
                            renderState,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE(),
                            cursorVisible),
                    "ghostty_render_state_get(cursor_visible)");
            if (!cursorVisible.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                return;
            }

            var cursorInViewport = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_get(
                            renderState,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE(),
                            cursorInViewport),
                    "ghostty_render_state_get(cursor_viewport_has_value)");
            if (!cursorInViewport.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                return;
            }

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
            var cursorPixelX = cursorCellX * (double) metrics.cellWidthPx();
            var cursorPixelY = cursorCellY * (double) metrics.cellHeightPx();
            var cursorWidth = metrics.cellWidthPx();
            var cursorHeight = metrics.cellHeightPx();

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

            if (preedit.text().isEmpty()) {
                return;
            }

            var codePointCount = preedit.text().codePointCount(0, preedit.text().length());
            var caret = Math.clamp(preedit.caretPosition(), 0, codePointCount);
            var preeditWidth = Math.max(metrics.cellWidthPx(), codePointCount * (double) metrics.cellWidthPx());
            graphics.setFill(preeditBackground);
            graphics.fillRect(cursorPixelX, cursorPixelY, preeditWidth, metrics.cellHeightPx());
            graphics.setFill(preeditFill);
            graphics.fillText(preedit.text(), cursorPixelX, cursorPixelY + metrics.baselineOffsetPx());
            graphics.setStroke(preeditStroke);
            graphics.strokeLine(
                    cursorPixelX,
                    cursorPixelY + metrics.cellHeightPx() - 1,
                    cursorPixelX + preeditWidth,
                    cursorPixelY + metrics.cellHeightPx() - 1);
            var caretX = cursorPixelX + caret * (double) metrics.cellWidthPx();
            graphics.strokeLine(caretX, cursorPixelY + 2, caretX, cursorPixelY + metrics.cellHeightPx() - 2);
        }
    }

    private void updateRenderState() {
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_render_state_update(renderState, terminal),
                "ghostty_render_state_update");
    }

    private static MemorySegment newAddress(Arena arena, String operation, Allocator allocator) {
        var pointer = arena.allocate(ValueLayout.ADDRESS);
        requireGhosttySuccess(allocator.allocate(MemorySegment.NULL, pointer), operation);
        return pointer.get(ValueLayout.ADDRESS, 0);
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

    @FunctionalInterface
    private interface Allocator {

        int allocate(MemorySegment allocator, MemorySegment out);
    }

    record ScrollbarInfo(
            long total,
            long visible,
            long offset,
            double thumbLeft,
            double height,
            double thumbY,
            double thumbHeight) {

        boolean scrollable() {
            return total > visible && visible > 0 && height > 0;
        }

        long scrollableRows() {
            return Math.max(0, total - visible);
        }

        double movableHeight() {
            return Math.max(0, height - thumbHeight);
        }

        boolean containsThumb(double y) {
            return thumbHeight > 0 && y >= thumbY && y <= thumbY + thumbHeight;
        }

        double thumbGrabRatio(double y) {
            if (thumbHeight <= 0) {
                return 0;
            }
            return Math.clamp((y - thumbY) / thumbHeight, 0.0, 1.0);
        }

        long targetOffsetForTrackPress(double y) {
            if (!scrollable()) {
                return offset;
            }

            var movableHeight = movableHeight();
            if (movableHeight == 0) {
                return 0;
            }

            var thumbTop = Math.clamp(y - thumbHeight / 2.0, 0.0, movableHeight);
            return (long) ((thumbTop / movableHeight) * scrollableRows());
        }

        long targetOffsetForDrag(double y, double thumbGrabRatio) {
            if (!scrollable()) {
                return offset;
            }

            var movableHeight = movableHeight();
            if (movableHeight == 0) {
                return 0;
            }

            var grabOffset = Math.clamp(thumbGrabRatio, 0.0, 1.0) * thumbHeight;
            var thumbTop = Math.clamp(y - grabOffset, 0.0, movableHeight);
            return (long) ((thumbTop / movableHeight) * scrollableRows());
        }

        double thumbX() {
            return thumbLeft + 2;
        }
    }

    record Size(int columns, int rows) {

    }
}
