package io.github.vlaaad.ghosttyfx;

import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import io.github.vlaaad.ghostty.bindings.GhosttyColorRgb;
import io.github.vlaaad.ghostty.bindings.GhosttyFormatterTerminalOptions;
import io.github.vlaaad.ghostty.bindings.GhosttyPoint;
import io.github.vlaaad.ghostty.bindings.GhosttyPointCoordinate;
import io.github.vlaaad.ghostty.bindings.GhosttyPointValue;
import io.github.vlaaad.ghostty.bindings.GhosttyRenderStateColors;
import io.github.vlaaad.ghostty.bindings.GhosttySelection;
import io.github.vlaaad.ghostty.bindings.GhosttyStyle;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalOptions;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollbar;
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javafx.animation.AnimationTimer;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodRequests;
import javafx.scene.input.InputMethodTextRun;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
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
    private static final int KEY_BUFFER_SIZE = 256;
    private static final short FOCUS_EVENT_MODE = 1004;
    private static final short BRACKETED_PASTE_MODE = 2004;
    private static final long INITIAL_MAX_SCROLLBACK = 1_000;
    private static final double SCROLLBAR_WIDTH_PX = 6;
    private static final double SCROLLBAR_MARGIN_PX = 2;
    private static final double MIN_SCROLLBAR_HEIGHT_PX = 10;
    private static final double BLOCK_CURSOR_ALPHA = 0.5;
    private static final int CURSOR_STYLE_BAR = 0;
    private static final int CURSOR_STYLE_UNDERLINE = 2;
    private static final int CURSOR_STYLE_BLOCK_HOLLOW = 3;
    private static final byte[] PROCESS_OUTPUT_COMPLETE = new byte[0];
    private static final Color SELECTION_COLOR = Color.rgb(74, 144, 226, 0.25);
    private static final Color PREEDIT_FILL = Color.rgb(255, 255, 255, 0.95);
    private static final Color PREEDIT_BACKGROUND = Color.rgb(74, 144, 226, 0.18);
    private static final Color PREEDIT_STROKE = Color.rgb(74, 144, 226, 0.9);
    private static final Font DEFAULT_FONT = Font.font("Monospaced", 14);

    private final MemorySegment terminal;
    private final MemorySegment renderState;
    private final MemorySegment rowIterator;
    private final MemorySegment rowCells;
    private final MemorySegment keyEncoder;
    private final MemorySegment keyEvent;
    private final Future<?> ioTask;
    private final BlockingQueue<ProcCommand> procCommands = new ArrayBlockingQueue<>(16_384);
    private final BlockingQueue<byte[]> processOutputChunks = new ArrayBlockingQueue<>(256);
    private final AnimationTimer processOutputDrain;
    private final GhosttyInputModel.Platform inputPlatform = GhosttyInputModel.Platform.current();

    private GhosttyInputModel.InputState inputState = GhosttyInputModel.initialState();

    private final ObjectProperty<Font> font = new SimpleObjectProperty<>(this, "font", DEFAULT_FONT) {
        @Override
        public void set(Font value) {
            super.set(java.util.Objects.requireNonNull(value, "font"));
        }
    };
    private final BooleanProperty macOptionAsAlt = new SimpleBooleanProperty(this, "macOptionAsAlt", false);
    private final ObjectProperty<KeyCombination> copyShortcut = new SimpleObjectProperty<>(
            this,
            "copyShortcut",
            inputPlatform == GhosttyInputModel.Platform.MACOS
                    ? new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN)
                    : new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN));
    private final ObjectProperty<KeyCombination> pasteShortcut = new SimpleObjectProperty<>(
            this,
            "pasteShortcut",
            inputPlatform == GhosttyInputModel.Platform.MACOS
                    ? new KeyCodeCombination(KeyCode.V, KeyCombination.META_DOWN)
                    : new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN));
    private final ObjectProperty<KeyCombination> selectAllShortcut = new SimpleObjectProperty<>(
            this,
            "selectAllShortcut",
            inputPlatform == GhosttyInputModel.Platform.MACOS
                    ? new KeyCodeCombination(KeyCode.A, KeyCombination.META_DOWN)
                    : new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));

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
        processOutputDrain = new ProcessOutputDrain(this);
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

            var rowIteratorPointer = arena.allocate(ValueLayout.ADDRESS);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_row_iterator_new(MemorySegment.NULL, rowIteratorPointer),
                    "ghostty_render_state_row_iterator_new");
            rowIterator = rowIteratorPointer.get(ValueLayout.ADDRESS, 0);

            var rowCellsPointer = arena.allocate(ValueLayout.ADDRESS);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_row_cells_new(MemorySegment.NULL, rowCellsPointer),
                    "ghostty_render_state_row_cells_new");
            rowCells = rowCellsPointer.get(ValueLayout.ADDRESS, 0);

            var keyEncoderPointer = arena.allocate(ValueLayout.ADDRESS);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_key_encoder_new(MemorySegment.NULL, keyEncoderPointer),
                    "ghostty_key_encoder_new");
            keyEncoder = keyEncoderPointer.get(ValueLayout.ADDRESS, 0);

            var keyEventPointer = arena.allocate(ValueLayout.ADDRESS);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_key_event_new(MemorySegment.NULL, keyEventPointer),
                    "ghostty_key_event_new");
            keyEvent = keyEventPointer.get(ValueLayout.ADDRESS, 0);
        }
        updateGhosttyRenderState(renderState, terminal);

        setFocusTraversable(true);
        setWidth(prefWidth(-1));
        setHeight(prefHeight(-1));
        cellMetrics.addListener((_, _, _) -> handleCanvasResize());
        widthProperty().addListener((_, _, _) -> handleCanvasResize());
        heightProperty().addListener((_, _, _) -> handleCanvasResize());
        focusedProperty().addListener((_, _, focused) -> handleFocusChange(focused));
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyReleased(this::handleKeyReleased);
        setOnKeyTyped(this::handleKeyTyped);
        setOnInputMethodTextChanged(this::handleInputMethodTextChanged);
        setInputMethodRequests(new CanvasInputMethodRequests());
        redraw();
        processOutputDrain.start();

        ioTask = IO.submit(() -> runProcess(command, cwd, environment));
        CLEANER.register(this, () -> {
            ioTask.cancel(true);
            ghostty_vt_h.ghostty_key_event_free(keyEvent);
            ghostty_vt_h.ghostty_key_encoder_free(keyEncoder);
            ghostty_vt_h.ghostty_render_state_row_cells_free(rowCells);
            ghostty_vt_h.ghostty_render_state_row_iterator_free(rowIterator);
            ghostty_vt_h.ghostty_render_state_free(renderState);
            ghostty_vt_h.ghostty_terminal_free(terminal);
        });
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

    public Font getFont() {
        return font.get();
    }

    public void setFont(Font value) {
        font.set(value);
    }

    public ObjectProperty<Font> fontProperty() {
        return font;
    }

    public boolean isMacOptionAsAlt() {
        return macOptionAsAlt.get();
    }

    public void setMacOptionAsAlt(boolean value) {
        macOptionAsAlt.set(value);
    }

    public BooleanProperty macOptionAsAltProperty() {
        return macOptionAsAlt;
    }

    public KeyCombination getCopyShortcut() {
        return copyShortcut.get();
    }

    public void setCopyShortcut(KeyCombination value) {
        copyShortcut.set(value);
    }

    public ObjectProperty<KeyCombination> copyShortcutProperty() {
        return copyShortcut;
    }

    public KeyCombination getPasteShortcut() {
        return pasteShortcut.get();
    }

    public void setPasteShortcut(KeyCombination value) {
        pasteShortcut.set(value);
    }

    public ObjectProperty<KeyCombination> pasteShortcutProperty() {
        return pasteShortcut;
    }

    public KeyCombination getSelectAllShortcut() {
        return selectAllShortcut.get();
    }

    public void setSelectAllShortcut(KeyCombination value) {
        selectAllShortcut.set(value);
    }

    public ObjectProperty<KeyCombination> selectAllShortcutProperty() {
        return selectAllShortcut;
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
                        processOutputChunks.put(Arrays.copyOf(buffer, read));
                    }
                } finally {
                    processOutputChunks.put(PROCESS_OUTPUT_COMPLETE);
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
                } catch (InterruptedException _) {
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

    private void handleCanvasResize() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        var metrics = cellMetrics.get();
        var nextColumns = Math.clamp((int) Math.floor(getWidth() / metrics.cellWidthPx()), 1, MAX_GHOSTTY_DIMENSION);
        var nextRows = Math.clamp((int) Math.floor(getHeight() / metrics.cellHeightPx()), 1, MAX_GHOSTTY_DIMENSION);
        resizeGhosttyTerminal(terminal, nextColumns, nextRows, metrics.cellWidthPx(), metrics.cellHeightPx());
        writeCommand(new ResizePty(nextColumns, nextRows));
        updateGhosttyRenderState(renderState, terminal);
        redraw();
    }

    private void handleFocusChange(boolean focused) {
        if (!focused) {
            var nextInputState = GhosttyInputModel.onFocusLost(inputState);
            if (!nextInputState.equals(inputState)) {
                inputState = nextInputState;
                redraw();
            }
        }

        if (!readMode(FOCUS_EVENT_MODE)) {
            return;
        }

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
            if (length > 0) {
                writeCommand(new WriteInput(buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE)));
            }
        }
    }

    private void handleKeyPressed(KeyEvent event) {
        if (handleShortcut(event) || applyTransition(GhosttyInputModel.onKeyPressed(
                inputState,
                inputPlatform,
                isMacOptionAsAlt(),
                snapshot(event)))) {
            event.consume();
        }
    }

    private void handleKeyReleased(KeyEvent event) {
        if (applyTransition(GhosttyInputModel.onKeyReleased(inputState, snapshot(event)))) {
            event.consume();
        }
    }

    private void handleKeyTyped(KeyEvent event) {
        if (applyTransition(GhosttyInputModel.onKeyTyped(inputState, event.getCharacter()))) {
            event.consume();
        }
    }

    private void handleInputMethodTextChanged(InputMethodEvent event) {
        if (applyTransition(GhosttyInputModel.onInputMethodTextChanged(
                inputState,
                composedText(event),
                event.getCaretPosition(),
                event.getCommitted()))) {
            event.consume();
        }
    }

    private boolean handleShortcut(KeyEvent event) {
        var copy = getCopyShortcut();
        if (copy != null && copy.match(event) && !inputState.selection().isEmpty()) {
            var content = new ClipboardContent();
            content.putString(selectedText());
            Clipboard.getSystemClipboard().setContent(content);
            return true;
        }
        var paste = getPasteShortcut();
        if (paste != null && paste.match(event)) {
            return pasteClipboard();
        }
        var selectAll = getSelectAllShortcut();
        if (selectAll != null && selectAll.match(event)) {
            var nextInputState = GhosttyInputModel.select(inputState, selectAllSelection());
            if (!nextInputState.equals(inputState)) {
                inputState = nextInputState;
                redraw();
            }
            return true;
        }
        return false;
    }

    private boolean pasteClipboard() {
        var text = Clipboard.getSystemClipboard().getString();
        if (text == null || text.isEmpty()) {
            return false;
        }

        writeCommand(new WriteInput(encodePaste(text)));
        var nextInputState = GhosttyInputModel.clearSelection(inputState);
        if (!nextInputState.equals(inputState)) {
            inputState = nextInputState;
            redraw();
        }
        return true;
    }

    private boolean applyTransition(GhosttyInputModel.Transition transition) {
        var previousInputState = inputState;
        inputState = transition.state();

        var encoderReady = false;
        var wroteToPty = false;
        for (var output : transition.outputs()) {
            switch (output) {
                case GhosttyInputModel.EncodeOutput encodeOutput -> {
                    if (!encoderReady) {
                        refreshKeyEncoder();
                        encoderReady = true;
                    }
                    var producedBytes = encodeAndWrite(encodeOutput);
                    inputState = GhosttyInputModel.acknowledgeEncode(
                            inputState,
                            encodeOutput.code(),
                            encodeOutput.action(),
                            producedBytes);
                    wroteToPty |= producedBytes;
                }
                case GhosttyInputModel.RawTextOutput(var text) -> {
                    if (text != null && !text.isEmpty()) {
                        writeCommand(new WriteInput(text.getBytes(StandardCharsets.UTF_8)));
                        wroteToPty = true;
                    }
                }
            }
        }

        var redraw = transition.redraw()
                || !previousInputState.selection().equals(inputState.selection())
                || !previousInputState.preedit().equals(inputState.preedit());
        if (redraw) {
            redraw();
        }
        return wroteToPty || redraw || !previousInputState.equals(inputState);
    }

    private void refreshKeyEncoder() {
        ghostty_vt_h.ghostty_key_encoder_setopt_from_terminal(keyEncoder, terminal);
        try (var arena = Arena.ofConfined()) {
            var option = arena.allocate(ValueLayout.JAVA_INT);
            option.set(
                    ValueLayout.JAVA_INT,
                    0,
                    isMacOptionAsAlt()
                            ? ghostty_vt_h.GHOSTTY_OPTION_AS_ALT_TRUE()
                            : ghostty_vt_h.GHOSTTY_OPTION_AS_ALT_FALSE());
            ghostty_vt_h.ghostty_key_encoder_setopt(
                    keyEncoder,
                    ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT(),
                    option);
        }
    }

    private boolean encodeAndWrite(GhosttyInputModel.EncodeOutput output) {
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
            writeCommand(new WriteInput(buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE)));
            return true;
        }
    }

    private byte[] encodePaste(String text) {
        var input = text.getBytes(StandardCharsets.UTF_8);
        try (var arena = Arena.ofConfined()) {
            var data = arena.allocateFrom(ValueLayout.JAVA_BYTE, input);
            var written = arena.allocate(ValueLayout.JAVA_LONG);
            var bracketed = readMode(BRACKETED_PASTE_MODE);
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

    private boolean readMode(short mode) {
        try (var arena = Arena.ofConfined()) {
            var value = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            return ghostty_vt_h.ghostty_terminal_mode_get(terminal, mode, value) == GHOSTTY_SUCCESS
                    && value.get(ValueLayout.JAVA_BOOLEAN, 0);
        }
    }

    private String selectedText() {
        var selection = inputState.selection();
        if (selection.isEmpty()) {
            return "";
        }
        return formatTerminalText(selection);
    }

    private String formatTerminalText(GhosttyInputModel.Selection selection) {
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

    private GhosttyInputModel.KeySnapshot snapshot(KeyEvent event) {
        return new GhosttyInputModel.KeySnapshot(event.getCode(), event.isShiftDown(), event.isControlDown(), event.isAltDown(), event.isMetaDown());
    }

    private static String composedText(InputMethodEvent event) {
        var builder = new StringBuilder();
        for (InputMethodTextRun run : event.getComposed()) {
            builder.append(run.getText());
        }
        return builder.toString();
    }

    private void redraw() {
        var width = getWidth();
        var height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        var graphics = getGraphicsContext2D();
        var metrics = cellMetrics.get();
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

                        // Text rendering: fake italic with a shear and fake bold with a second pass.
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

            renderSelectionOverlay(graphics, metrics);

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

                    var preedit = inputState.preedit();
                    if (!preedit.text().isEmpty()) {
                        var codePointCount = preedit.text().codePointCount(0, preedit.text().length());
                        var caret = Math.clamp(preedit.caretPosition(), 0, codePointCount);
                        var preeditWidth = Math.max(metrics.cellWidthPx(), codePointCount * (double) metrics.cellWidthPx());
                        graphics.setFill(PREEDIT_BACKGROUND);
                        graphics.fillRect(cursorPixelX, cursorPixelY, preeditWidth, metrics.cellHeightPx());
                        graphics.setFill(PREEDIT_FILL);
                        graphics.fillText(preedit.text(), cursorPixelX, cursorPixelY + metrics.baselineOffsetPx());
                        graphics.setStroke(PREEDIT_STROKE);
                        graphics.strokeLine(
                                cursorPixelX,
                                cursorPixelY + metrics.cellHeightPx() - 1,
                                cursorPixelX + preeditWidth,
                                cursorPixelY + metrics.cellHeightPx() - 1);
                        var caretX = cursorPixelX + caret * (double) metrics.cellWidthPx();
                        graphics.strokeLine(caretX, cursorPixelY + 2, caretX, cursorPixelY + metrics.cellHeightPx() - 2);
                    }
                }
            }

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

    private void renderSelectionOverlay(javafx.scene.canvas.GraphicsContext graphics, CellMetrics metrics) {
        var selection = inputState.selection().normalized();
        if (selection.isEmpty()) {
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
            var from = selection.from();
            var to = selection.to();
            graphics.setFill(SELECTION_COLOR);
            for (var screenRow = Math.max(from.y(), viewportTop); screenRow <= Math.min(to.y(), viewportBottom); screenRow++) {
                var startColumn = selection.rectangle() || screenRow != from.y() ? 0 : from.x();
                var endColumn = selection.rectangle() || screenRow != to.y() ? columnCount - 1 : to.x();
                if (selection.rectangle()) {
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

    private CursorLocation currentCursorLocation() {
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

            var metrics = cellMetrics.get();
            var cellX = Short.toUnsignedInt(cursorX.get(ValueLayout.JAVA_SHORT, 0));
            var cellY = Short.toUnsignedInt(cursorY.get(ValueLayout.JAVA_SHORT, 0));
            return new CursorLocation(
                    cellX,
                    cellY,
                    cellX * (double) metrics.cellWidthPx(),
                    cellY * (double) metrics.cellHeightPx());
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

    private static final class ProcessOutputDrain extends AnimationTimer {

        private final WeakReference<GhosttyCanvas> canvasRef;
        private final BlockingQueue<byte[]> processOutputChunks;

        private ProcessOutputDrain(GhosttyCanvas canvas) {
            canvasRef = new WeakReference<>(canvas);
            processOutputChunks = canvas.processOutputChunks;
        }

        @Override
        public void handle(long now) {
            var firstChunk = processOutputChunks.poll();
            if (firstChunk == null) {
                return;
            }

            var chunks = new ArrayList<byte[]>(1 + processOutputChunks.size());
            chunks.add(firstChunk);
            processOutputChunks.drainTo(chunks);

            var canvas = canvasRef.get();
            if (canvas != null) {
                var totalBytes = 0;
                for (var chunk : chunks) {
                    totalBytes += chunk.length;
                }

                var bytes = new byte[totalBytes];
                var offset = 0;
                for (var chunk : chunks) {
                    System.arraycopy(chunk, 0, bytes, offset, chunk.length);
                    offset += chunk.length;
                }

                try (var arena = Arena.ofConfined()) {
                    var nativeBytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
                    ghostty_vt_h.ghostty_terminal_vt_write(canvas.terminal, nativeBytes, bytes.length);
                }
                updateGhosttyRenderState(canvas.renderState, canvas.terminal);
                canvas.redraw();
            }
            if (chunks.get(chunks.size() - 1) == PROCESS_OUTPUT_COMPLETE) {
                stop();
            }
        }
    }

    private void writeCommand(ProcCommand command) {
        try {
            procCommands.put(command);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private final class CanvasInputMethodRequests implements InputMethodRequests {

        @Override
        public Point2D getTextLocation(int offset) {
            var cursorLocation = currentCursorLocation();
            if (cursorLocation == null) {
                return new Point2D(0, 0);
            }

            var codePointCount = inputState.preedit().text().codePointCount(0, inputState.preedit().text().length());
            var clampedOffset = Math.clamp(offset, 0, codePointCount);
            var metrics = cellMetrics.get();
            var screenPoint = localToScreen(
                    cursorLocation.pixelX() + clampedOffset * (double) metrics.cellWidthPx(),
                    cursorLocation.pixelY() + metrics.cellHeightPx());
            return screenPoint != null
                    ? screenPoint
                    : new Point2D(cursorLocation.pixelX(), cursorLocation.pixelY() + metrics.cellHeightPx());
        }

        @Override
        public int getLocationOffset(int x, int y) {
            var cursorLocation = currentCursorLocation();
            if (cursorLocation == null) {
                return 0;
            }

            var localPoint = screenToLocal(x, y);
            var dx = Math.max(0, localPoint.getX() - cursorLocation.pixelX());
            var codePointCount = inputState.preedit().text().codePointCount(0, inputState.preedit().text().length());
            return Math.clamp((int) Math.floor(dx / cellMetrics.get().cellWidthPx()), 0, codePointCount);
        }

        @Override
        public void cancelLatestCommittedText() {
        }

        @Override
        public String getSelectedText() {
            return selectedText();
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

    private record CursorLocation(int cellX, int cellY, double pixelX, double pixelY) {

    }

    private GhosttyInputModel.Selection selectAllSelection() {
        try (var arena = Arena.ofConfined()) {
            var cols = arena.allocate(ValueLayout.JAVA_SHORT);
            var totalRows = arena.allocate(ValueLayout.JAVA_LONG);
            if (ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_COLS(), cols) != GHOSTTY_SUCCESS
                    || ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_TOTAL_ROWS(), totalRows) != GHOSTTY_SUCCESS) {
                return GhosttyInputModel.Selection.empty();
            }

            var columnCount = Short.toUnsignedInt(cols.get(ValueLayout.JAVA_SHORT, 0));
            var rowCount = Math.toIntExact(totalRows.get(ValueLayout.JAVA_LONG, 0));
            return columnCount == 0 || rowCount == 0
                    ? GhosttyInputModel.Selection.empty()
                    : GhosttyInputModel.Selection.linear(
                            new GhosttyInputModel.ScreenPoint(0, 0),
                            new GhosttyInputModel.ScreenPoint(columnCount - 1, rowCount - 1));
        }
    }

    private MemorySegment formatterSelection(Arena arena, GhosttyInputModel.Selection selection) {
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

    private boolean writeGridRef(Arena arena, GhosttyInputModel.ScreenPoint point, MemorySegment outGridRef) {
        var coordinate = GhosttyPointCoordinate.allocate(arena);
        GhosttyPointCoordinate.x(coordinate, (short) point.x());
        GhosttyPointCoordinate.y(coordinate, point.y());
        var ghosttyPoint = GhosttyPoint.allocate(arena);
        GhosttyPoint.tag(ghosttyPoint, ghostty_vt_h.GHOSTTY_POINT_TAG_SCREEN());
        GhosttyPointValue.coordinate(GhosttyPoint.value(ghosttyPoint), coordinate);
        return ghostty_vt_h.ghostty_terminal_grid_ref(terminal, ghosttyPoint, outGridRef) == GHOSTTY_SUCCESS;
    }

}
