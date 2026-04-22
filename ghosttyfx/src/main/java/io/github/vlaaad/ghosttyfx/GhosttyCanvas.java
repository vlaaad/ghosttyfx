package io.github.vlaaad.ghosttyfx;

import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;

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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ScrollEvent.VerticalTextScrollUnits;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;

public final class GhosttyCanvas extends Canvas implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();
    private static final int INITIAL_COLUMNS = 80;
    private static final int INITIAL_ROWS = 24;
    private static final short FOCUS_EVENT_MODE = 1004;
    private static final short BRACKETED_PASTE_MODE = 2004;
    private static final double DEFAULT_SCROLL_MULTIPLIER_Y = 40;
    private static final double SCROLLBAR_WIDTH_PX = 6;
    private static final double SCROLLBAR_MARGIN_PX = 2;
    private static final double MIN_SCROLLBAR_HEIGHT_PX = 10;
    private static final double SCROLL_TOTAL_DELTA_EPSILON = 1e-6;
    private static final byte[] PROCESS_OUTPUT_COMPLETE = new byte[0];
    private static final Color SELECTION_COLOR = Color.rgb(74, 144, 226, 0.25);
    private static final Color PREEDIT_FILL = Color.rgb(255, 255, 255, 0.95);
    private static final Color PREEDIT_BACKGROUND = Color.rgb(74, 144, 226, 0.18);
    private static final Color PREEDIT_STROKE = Color.rgb(74, 144, 226, 0.9);
    private static final Font DEFAULT_FONT = Font.font("Monospaced", 14);

    private final TerminalSession terminalSession;
    private final Future<?> ioTask;
    private final BlockingQueue<ProcCommand> procCommands = new ArrayBlockingQueue<>(16_384);
    private final BlockingQueue<byte[]> processOutputChunks = new ArrayBlockingQueue<>(256);
    private final AnimationTimer processOutputDrain;
    private final InputModel.Platform inputPlatform = InputModel.Platform.current();

    private InputModel.InputState inputState = InputModel.initialState();

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
            inputPlatform == InputModel.Platform.MACOS
                    ? new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN)
                    : new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN));
    private final ObjectProperty<KeyCombination> pasteShortcut = new SimpleObjectProperty<>(
            this,
            "pasteShortcut",
            inputPlatform == InputModel.Platform.MACOS
                    ? new KeyCodeCombination(KeyCode.V, KeyCombination.META_DOWN)
                    : new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN));
    private final ObjectProperty<KeyCombination> selectAllShortcut = new SimpleObjectProperty<>(
            this,
            "selectAllShortcut",
            inputPlatform == InputModel.Platform.MACOS
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
        terminalSession = new TerminalSession(INITIAL_COLUMNS, INITIAL_ROWS, initialCellMetrics);

        setFocusTraversable(true);
        setWidth(prefWidth(-1));
        setHeight(prefHeight(-1));
        cellMetrics.addListener((_, _, _) -> handleCanvasResize());
        widthProperty().addListener((_, _, _) -> handleCanvasResize());
        heightProperty().addListener((_, _, _) -> handleCanvasResize());
        focusedProperty().addListener((_, _, focused) -> handleFocusChange(focused));
        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseReleased(this::handleMouseReleased);
        setOnMouseClicked(this::handleMouseClicked);
        addEventHandler(ScrollEvent.SCROLL_STARTED, this::handleScrollStarted);
        addEventHandler(ScrollEvent.SCROLL_FINISHED, this::handleScrollFinished);
        setOnScroll(this::handleScroll);
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyReleased(this::handleKeyReleased);
        setOnKeyTyped(this::handleKeyTyped);
        setOnInputMethodTextChanged(this::handleInputMethodTextChanged);
        setInputMethodRequests(new CanvasInputMethodRequests());
        redraw();
        processOutputDrain.start();

        ioTask = IO.submit(() -> runProcess(command, cwd, environment));
        // The canvas only owns the process lifecycle directly. Native terminal resources stay alive as long as the
        // terminal session is reachable so an already-rendered view can still be queried or shown after close().
        CLEANER.register(terminalSession, () -> {
            try (terminalSession) {
                ioTask.cancel(true);
            }
        });
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public double prefWidth(double height) {
        return INITIAL_COLUMNS * cellMetrics.get().cellWidthPx() + scrollbarReservedWidthPx();
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
        // Closing the canvas is a process-lifecycle operation only. Native terminal state stays available until the
        // canvas itself becomes unreachable so the last rendered view can still be queried or shown.
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
        var metrics = cellMetrics.get();
        var size = terminalSession.resize(getWidth(), getHeight(), metrics, scrollbarReservedWidthPx());
        if (size == null) {
            return;
        }

        writeCommand(new ResizePty(size.columns(), size.rows()));
        redraw();
    }

    private void handleFocusChange(boolean focused) {
        if (!focused) {
            var nextInputState = InputModel.onFocusLost(inputState);
            if (!nextInputState.equals(inputState)) {
                inputState = nextInputState;
                redraw();
            }
        }

        if (!terminalSession.readMode(FOCUS_EVENT_MODE)) {
            return;
        }

        var bytes = terminalSession.encodeFocus(focused);
        if (bytes.length > 0) {
            writeCommand(new WriteInput(bytes));
        }
    }

    private void handleKeyPressed(KeyEvent event) {
        if (handleShortcut(event) || applyTransition(InputModel.onKeyPressed(
                inputState,
                inputPlatform,
                isMacOptionAsAlt(),
                snapshot(event)))) {
            event.consume();
        }
    }

    private void handleKeyReleased(KeyEvent event) {
        if (applyTransition(InputModel.onKeyReleased(inputState, snapshot(event)))) {
            event.consume();
        }
    }

    private void handleKeyTyped(KeyEvent event) {
        if (applyTransition(InputModel.onKeyTyped(
                inputState,
                inputPlatform,
                event.isMetaDown(),
                event.getCharacter()))) {
            event.consume();
        }
    }

    private void handleInputMethodTextChanged(InputMethodEvent event) {
        var composedText = new StringBuilder();
        for (var run : event.getComposed()) {
            composedText.append(run.getText());
        }
        if (applyTransition(InputModel.onInputMethodTextChanged(
                inputState,
                composedText.toString(),
                event.getCaretPosition(),
                event.getCommitted()))) {
            event.consume();
        }
    }

    private void handleMousePressed(MouseEvent event) {
        requestFocus();
        event.consume();
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        if (isInScrollbar(event.getX())) {
            handleScrollbarPress(event.getY());
        }
    }

    private void handleMouseDragged(MouseEvent event) {
        event.consume();
        if (!inputState.mouseState().scrollbarDragging()) {
            return;
        }

        if (!event.isPrimaryButtonDown()) {
            inputState = InputModel.stopScrollbarDrag(inputState);
            return;
        }

        dragScrollbarTo(event.getY());
    }

    private void handleMouseReleased(MouseEvent event) {
        event.consume();
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        var nextInputState = InputModel.stopScrollbarDrag(inputState);
        if (!nextInputState.equals(inputState)) {
            inputState = nextInputState;
        }
    }

    private void handleMouseClicked(MouseEvent event) {
        event.consume();
    }

    private void handleScrollStarted(ScrollEvent event) {
        inputState = InputModel.startScrollGesture(inputState);
        event.consume();
    }

    private void handleScrollFinished(ScrollEvent event) {
        inputState = InputModel.stopScrollGesture(inputState);
        event.consume();
    }

    private void handleScroll(ScrollEvent event) {
        event.consume();
        var overContent = !isInScrollbar(event.getX());
        var discrete = isDiscreteWheelScroll(inputState.mouseState().scrollGestureActive(), event);
        if (discrete) {
            var tickDelta = discreteScrollDeltaTicks(event);
            if (tickDelta == 0) {
                return;
            }

            var scrollUpdate = InputModel.accumulateDiscreteScroll(inputState, tickDelta);
            inputState = scrollUpdate.state();
            var wholeTicks = scrollUpdate.lineDelta();
            if (wholeTicks == 0) {
                return;
            }

            var mouseTrackingEnabled = overContent && terminalSession.mouseTrackingEnabled();
            var wroteToApplication = false;
            if (mouseTrackingEnabled) {
                wroteToApplication = writeBytes(terminalSession.encodeMouseScroll(
                        event.getX(),
                        event.getY(),
                        wholeTicks,
                        eventModifiers(event),
                        getWidth(),
                        getHeight(),
                        cellMetrics.get(),
                        scrollbarReservedWidthPx()));
            }
            if (!wroteToApplication) {
                scrollViewportBy(-wholeTicks);
            }
            return;
        }

        var deltaRows = smoothScrollDeltaRows(event);
        if (deltaRows == 0) {
            return;
        }

        var scrollUpdate = InputModel.accumulateSmoothScroll(inputState, deltaRows);
        inputState = scrollUpdate.state();
        var wholeRows = scrollUpdate.lineDelta();
        if (wholeRows == 0) {
            return;
        }

        var mouseTrackingEnabled = overContent && terminalSession.mouseTrackingEnabled();
        var wroteToApplication = false;
        if (mouseTrackingEnabled) {
            wroteToApplication = writeBytes(terminalSession.encodeMouseScroll(
                    event.getX(),
                    event.getY(),
                    wholeRows,
                    eventModifiers(event),
                    getWidth(),
                    getHeight(),
                    cellMetrics.get(),
                    scrollbarReservedWidthPx()));
        }
        if (!wroteToApplication) {
            scrollViewportBy(-wholeRows);
        }
    }

    private boolean handleScrollbarPress(double y) {
        var scrollbar = scrollbarInfo();
        if (scrollbar == null || !scrollbar.scrollable()) {
            return false;
        }

        if (scrollbar.containsThumb(y)) {
            inputState = InputModel.startScrollbarDrag(inputState, scrollbar.thumbGrabRatio(y));
            return true;
        }

        scrollViewportTo(scrollbar.targetOffsetForTrackPress(y), scrollbar);
        var updatedScrollbar = scrollbarInfo();
        if (updatedScrollbar != null && updatedScrollbar.scrollable()) {
            inputState = InputModel.startScrollbarDrag(inputState, updatedScrollbar.thumbGrabRatio(y));
        }
        return true;
    }

    private boolean dragScrollbarTo(double y) {
        var scrollbar = scrollbarInfo();
        if (scrollbar == null || !scrollbar.scrollable()) {
            inputState = InputModel.stopScrollbarDrag(inputState);
            return false;
        }

        scrollViewportTo(scrollbar.targetOffsetForDrag(y, inputState.mouseState().scrollbarThumbGrabRatio()), scrollbar);
        return true;
    }

    private void scrollViewportTo(long targetOffset, TerminalSession.ScrollbarInfo scrollbar) {
        var clampedOffset = Math.clamp(targetOffset, 0, scrollbar.scrollableRows());
        scrollViewportBy(clampedOffset - scrollbar.offset());
    }

    private void scrollViewportBy(long deltaRows) {
        if (deltaRows == 0) {
            return;
        }

        terminalSession.scrollViewportBy(deltaRows);
        redraw();
    }

    private static boolean isDiscreteWheelScroll(boolean scrollGestureActive, ScrollEvent event) {
        if (scrollGestureActive || event.getTouchCount() != 0 || event.isInertia()) {
            return false;
        }

        if (event.getTextDeltaYUnits() != VerticalTextScrollUnits.NONE) {
            return Math.abs(event.getTextDeltaY()) >= 1;
        }

        if (Math.abs(event.getTotalDeltaY() - event.getDeltaY()) <= SCROLL_TOTAL_DELTA_EPSILON) {
            return true;
        }

        return Math.abs(event.getTotalDeltaY()) <= SCROLL_TOTAL_DELTA_EPSILON;
    }

    private static double discreteScrollDeltaTicks(ScrollEvent event) {
        if (event.getDeltaY() == 0) {
            return 0;
        }

        var multiplierY = event.getMultiplierY();
        var deltaTicks = multiplierY != 0
                ? event.getDeltaY() / multiplierY
                : event.getDeltaY() / DEFAULT_SCROLL_MULTIPLIER_Y;
        if (!Double.isFinite(deltaTicks) || deltaTicks == 0) {
            return 0;
        }

        return deltaTicks > 0
                ? Math.max(deltaTicks, 1.0)
                : Math.min(deltaTicks, -1.0);
    }

    private double smoothScrollDeltaRows(ScrollEvent event) {
        if (event.getTextDeltaYUnits() == VerticalTextScrollUnits.LINES && event.getTextDeltaY() != 0) {
            return event.getTextDeltaY();
        }
        if (event.getTextDeltaYUnits() == VerticalTextScrollUnits.PAGES && event.getTextDeltaY() != 0) {
            return event.getTextDeltaY() * viewportRowCount();
        }
        if (event.getDeltaY() == 0) {
            return 0;
        }
        return event.getDeltaY() / cellMetrics.get().cellHeightPx();
    }

    private static short eventModifiers(MouseEvent event) {
        return eventModifiers(event.isShiftDown(), event.isControlDown(), event.isAltDown(), event.isMetaDown());
    }

    private static short eventModifiers(ScrollEvent event) {
        return eventModifiers(event.isShiftDown(), event.isControlDown(), event.isAltDown(), event.isMetaDown());
    }

    private static short eventModifiers(boolean shiftDown, boolean controlDown, boolean altDown, boolean metaDown) {
        var mods = 0;
        if (shiftDown) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_SHIFT();
        }
        if (controlDown) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_CTRL();
        }
        if (altDown) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_ALT();
        }
        if (metaDown) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_SUPER();
        }
        return (short) mods;
    }

    private boolean isInScrollbar(double x) {
        return x >= Math.max(0, getWidth() - scrollbarReservedWidthPx());
    }

    private static double scrollbarReservedWidthPx() {
        return SCROLLBAR_WIDTH_PX + 2 * SCROLLBAR_MARGIN_PX;
    }

    private TerminalSession.ScrollbarInfo scrollbarInfo() {
        return terminalSession.scrollbarInfo(getWidth(), getHeight(), scrollbarReservedWidthPx(), MIN_SCROLLBAR_HEIGHT_PX);
    }

    private int viewportRowCount() {
        return terminalSession.viewportRowCount(0, cellMetrics.get().cellHeightPx(), getHeight());
    }

    private boolean handleShortcut(KeyEvent event) {
        var copy = getCopyShortcut();
        if (copy != null && copy.match(event) && !inputState.selection().isEmpty()) {
            var content = new ClipboardContent();
            content.putString(selectedText());
            if (Clipboard.getSystemClipboard().setContent(content)) {
                clearSelection();
            }
            return true;
        }
        var paste = getPasteShortcut();
        if (paste != null && paste.match(event)) {
            var text = Clipboard.getSystemClipboard().getString();
            if (text == null || text.isEmpty()) {
                return false;
            }

            writeCommand(new WriteInput(terminalSession.encodePaste(text, terminalSession.readMode(BRACKETED_PASTE_MODE))));
            clearSelection();
            return true;
        }
        var selectAll = getSelectAllShortcut();
        if (selectAll != null && selectAll.match(event)) {
            var nextInputState = InputModel.select(inputState, terminalSession.selectAllSelection());
            if (!nextInputState.equals(inputState)) {
                inputState = nextInputState;
                redraw();
            }
            return true;
        }
        return false;
    }

    private void clearSelection() {
        var nextInputState = InputModel.clearSelection(inputState);
        if (!nextInputState.equals(inputState)) {
            inputState = nextInputState;
            redraw();
        }
    }

    private boolean applyTransition(InputModel.Transition transition) {
        var previousInputState = inputState;
        inputState = transition.state();

        var wroteToPty = false;
        for (var output : transition.outputs()) {
            switch (output) {
                case InputModel.EncodeOutput encodeOutput -> {
                    var producedBytes = terminalSession.encodeAndWrite(encodeOutput, isMacOptionAsAlt(), this::writeBytes);
                    inputState = InputModel.acknowledgeEncode(
                            inputState,
                            encodeOutput.code(),
                            encodeOutput.action(),
                            producedBytes);
                    wroteToPty |= producedBytes;
                }
                case InputModel.RawTextOutput(var text) -> {
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

    private String selectedText() {
        return terminalSession.selectedText(inputState.selection());
    }

    private InputModel.KeySnapshot snapshot(KeyEvent event) {
        return new InputModel.KeySnapshot(event.getCode(), event.isShiftDown(), event.isControlDown(), event.isAltDown(), event.isMetaDown());
    }

    private void redraw() {
        var width = getWidth();
        var height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        terminalSession.render(
                getGraphicsContext2D(),
                width,
                height,
                font.get(),
                cellMetrics.get(),
                inputState,
                scrollbarReservedWidthPx(),
                MIN_SCROLLBAR_HEIGHT_PX,
                SELECTION_COLOR,
                PREEDIT_FILL,
                PREEDIT_BACKGROUND,
                PREEDIT_STROKE);
    }

    private CursorLocation currentCursorLocation() {
        return terminalSession.currentCursorLocation(cellMetrics.get());
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

                canvas.terminalSession.writeToTerminal(bytes);
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

    static record CellMetrics(int cellWidthPx, int cellHeightPx, int baselineOffsetPx) {

    }

    static record CursorLocation(int cellX, int cellY, double pixelX, double pixelY) {

    }

    private boolean writeBytes(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        writeCommand(new WriteInput(bytes));
        return true;
    }

}
