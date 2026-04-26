package io.github.vlaaad.ghosttyfx;

import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;

import java.awt.Desktop;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javafx.animation.AnimationTimer;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;

public final class GhosttyCanvas extends Canvas implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final int INITIAL_COLUMNS = 80;
    private static final int INITIAL_ROWS = 24;
    private static final short FOCUS_EVENT_MODE = 1004;
    private static final short MOUSE_ALTERNATE_SCROLL_MODE = 1007;
    private static final short BRACKETED_PASTE_MODE = 2004;
    private static final double DEFAULT_SCROLL_MULTIPLIER_Y = 40;
    private static final double SCROLLBAR_WIDTH_PX = 6;
    private static final double SCROLLBAR_MARGIN_PX = 2;
    private static final double MIN_SCROLLBAR_HEIGHT_PX = 10;
    private static final double SCROLL_TOTAL_DELTA_EPSILON = 1e-6;
    private static final Font DEFAULT_FONT = Font.font("Monospaced", 14);

    private final TerminalSession terminalSession;
    private final PtySession ptySession;
    private final AnimationTimer processOutputDrain;
    private final KeyInput.Platform inputPlatform = KeyInput.Platform.current();

    private KeyInput.State keyInputState = KeyInput.initialState();
    private MouseInput.State mouseInputState = MouseInput.initialState();
    private Selection selection = Selection.empty();
    private Selection hoveredHyperlink = Selection.empty();
    private final StringProperty title;
    private final ObjectProperty<Runnable> onBell = new SimpleObjectProperty<>(this, "onBell");
    private final ObjectProperty<TerminalTheme> theme = new SimpleObjectProperty<>(this, "theme", TerminalTheme.defaults()) {
        @Override
        public void set(TerminalTheme value) {
            super.set(Objects.requireNonNull(value, "theme"));
        }
    };

    private final ObjectProperty<Font> font = new SimpleObjectProperty<>(this, "font", DEFAULT_FONT) {
        @Override
        public void set(Font value) {
            super.set(java.util.Objects.requireNonNull(value, "font"));
        }
    };
    private final ObjectBinding<TerminalFonts> terminalFonts = Bindings.createObjectBinding(() -> {
        var font = this.font.get();
        return new TerminalFonts(
                font,
                Font.font(font.getFamily(), FontWeight.BOLD, font.getSize()),
                Font.font(font.getFamily(), FontPosture.ITALIC, font.getSize()),
                Font.font(font.getFamily(), FontWeight.BOLD, FontPosture.ITALIC, font.getSize()));
    }, font);
    private final BooleanProperty macOptionAsAlt = new SimpleBooleanProperty(this, "macOptionAsAlt", false);
    private final ObjectProperty<KeyCombination> copyShortcut = new SimpleObjectProperty<>(
            this,
            "copyShortcut",
            inputPlatform == KeyInput.Platform.MACOS
                    ? new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN)
                    : new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN));
    private final ObjectProperty<KeyCombination> pasteShortcut = new SimpleObjectProperty<>(
            this,
            "pasteShortcut",
            inputPlatform == KeyInput.Platform.MACOS
                    ? new KeyCodeCombination(KeyCode.V, KeyCombination.META_DOWN)
                    : new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN));
    private final ObjectProperty<KeyCombination> selectAllShortcut = new SimpleObjectProperty<>(
            this,
            "selectAllShortcut",
            inputPlatform == KeyInput.Platform.MACOS
                    ? new KeyCodeCombination(KeyCode.A, KeyCombination.META_DOWN)
                    : new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
    private final ReadOnlyBooleanWrapper processExited = new ReadOnlyBooleanWrapper(this, "processExited", false);

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
        title = new SimpleStringProperty(this, "title", command.getFirst());
        ptySession = new PtySession(command, cwd, environment, INITIAL_COLUMNS, INITIAL_ROWS);
        processOutputDrain = new ProcessOutputDrain(this);
        var initialCellMetrics = cellMetrics.get();
        terminalSession = new TerminalSession(
                INITIAL_COLUMNS,
                INITIAL_ROWS,
                initialCellMetrics,
                ptySession,
                title::set,
                () -> {
                    var handler = onBell.get();
                    if (handler != null) {
                        handler.run();
                    }
                });

        setFocusTraversable(true);
        setWidth(prefWidth(-1));
        setHeight(prefHeight(-1));
        cellMetrics.addListener((_, _, _) -> handleCanvasResize());
        terminalFonts.addListener((_, _, _) -> redraw());
        theme.addListener((_, _, value) -> {
            terminalSession.applyTheme(value);
            redraw();
        });
        widthProperty().addListener((_, _, _) -> handleCanvasResize());
        heightProperty().addListener((_, _, _) -> handleCanvasResize());
        focusedProperty().addListener((_, _, focused) -> handleFocusChange(focused));
        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseMoved(this::handleMouseMoved);
        setOnMouseReleased(this::handleMouseReleased);
        setOnMouseExited(this::handleMouseExited);
        setOnMouseClicked(this::handleMouseClicked);
        addEventHandler(ScrollEvent.SCROLL_STARTED, this::handleScrollStarted);
        addEventHandler(ScrollEvent.SCROLL_FINISHED, this::handleScrollFinished);
        setOnScroll(this::handleScroll);
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyReleased(this::handleKeyReleased);
        setOnKeyTyped(this::handleKeyTyped);
        setOnInputMethodTextChanged(this::handleInputMethodTextChanged);
        setInputMethodRequests(new CanvasInputMethodRequests());
        setCursor(Cursor.DEFAULT);
        terminalSession.applyTheme(getTheme());
        redraw();
        processOutputDrain.start();

        // The canvas only owns the process lifecycle directly. Native terminal resources stay alive as long as the
        // terminal session is reachable so an already-rendered view can still be queried or shown after close().
        CLEANER.register(terminalSession, () -> {
            try (terminalSession) {
                ptySession.close();
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

    public TerminalTheme getTheme() {
        return theme.get();
    }

    public void setTheme(TerminalTheme value) {
        theme.set(value);
    }

    public ObjectProperty<TerminalTheme> themeProperty() {
        return theme;
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

    public String getTitle() {
        return title.get();
    }

    public StringProperty titleProperty() {
        return title;
    }

    public Runnable getOnBell() {
        return onBell.get();
    }

    public void setOnBell(Runnable value) {
        onBell.set(value);
    }

    public ObjectProperty<Runnable> onBellProperty() {
        return onBell;
    }

    public boolean isProcessExited() {
        return processExited.get();
    }

    public ReadOnlyBooleanProperty processExitedProperty() {
        return processExited.getReadOnlyProperty();
    }

    @Override
    public void close() {
        // Closing the canvas is a process-lifecycle operation only. Native terminal state stays available until the
        // canvas itself becomes unreachable so the last rendered view can still be queried or shown.
        ptySession.close();
    }

    private void handleCanvasResize() {
        var metrics = cellMetrics.get();
        var size = terminalSession.resize(getWidth(), getHeight(), metrics, scrollbarReservedWidthPx());
        if (size == null) {
            return;
        }

        writeCommand(new PtySession.ResizePty(size.columns(), size.rows()));
        redraw();
    }

    private void handleFocusChange(boolean focused) {
        if (!focused) {
            var nextKeyInputState = KeyInput.onFocusLost(keyInputState);
            var nextMouseInputState = MouseInput.onFocusLost(mouseInputState);
            clearHover(false);
            if (!nextKeyInputState.equals(keyInputState) || !nextMouseInputState.equals(mouseInputState)) {
                keyInputState = nextKeyInputState;
                mouseInputState = nextMouseInputState;
            }
        }
        redraw();

        if (!terminalSession.readMode(FOCUS_EVENT_MODE)) {
            return;
        }

        writeBytes(terminalSession.encodeFocus(focused));
    }

    private void handleKeyPressed(KeyEvent event) {
        if (handleShortcut(event) || applyTransition(KeyInput.onKeyPressed(
                keyInputState,
                inputPlatform,
                isMacOptionAsAlt(),
                snapshot(event)))) {
            event.consume();
        }
    }

    private void handleKeyReleased(KeyEvent event) {
        if (applyTransition(KeyInput.onKeyReleased(keyInputState, snapshot(event)))) {
            event.consume();
        }
    }

    private void handleKeyTyped(KeyEvent event) {
        if (applyTransition(KeyInput.onKeyTyped(
                keyInputState,
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
        if (applyTransition(KeyInput.onInputMethodTextChanged(
                keyInputState,
                composedText.toString(),
                event.getCaretPosition(),
                event.getCommitted()))) {
            event.consume();
        }
    }

    private void handleMousePressed(MouseEvent event) {
        requestFocus();
        event.consume();
        if (event.getButton() == MouseButton.PRIMARY && isInScrollbar(event.getX())) {
            handleScrollbarPress(event.getY());
            return;
        }

        if (terminalSession.mouseTrackingEnabled() && !isInScrollbar(event.getX())) {
            clearHover(true);
            clearSelection();
            mouseInputState = mouseInputState.withPressGesture(null);
            writeReportedMousePress(event);
            return;
        }

        if (event.getButton() != MouseButton.PRIMARY || isInScrollbar(event.getX())) {
            return;
        }

        var hit = contentHit(event);
        if (hit == null) {
            mouseInputState = mouseInputState.withPressGesture(null);
            clearHover(true);
            return;
        }

        var clickCount = MouseInput.normalizeClickCount(event.getClickCount());
        var rectangle = isRectangleSelection(event);
        mouseInputState = mouseInputState.withPressGesture(new MouseInput.PressGesture(
                TerminalSession.MouseButton.LEFT,
                hit.screenPoint(),
                hit.cellOffsetX(),
                clickCount,
                rectangle,
                false,
                hit.hyperlinkUri()));
        clearHover(false);
        switch (clickCount) {
            case 1 -> clearSelection();
            case 2 -> applySelection(terminalSession.wordSelection(hit.screenPoint()));
            case 3 -> applySelection(terminalSession.lineSelection(hit.screenPoint()));
            default -> throw new IllegalStateException("unsupported click count: " + clickCount);
        }
    }

    private void handleMouseDragged(MouseEvent event) {
        event.consume();
        if (mouseInputState.scrollbarDragging()) {
            if (!event.isPrimaryButtonDown()) {
                mouseInputState = MouseInput.stopScrollbarDrag(mouseInputState);
                return;
            }

            dragScrollbarTo(event.getY());
            return;
        }

        if (terminalSession.mouseTrackingEnabled() && !isInScrollbar(event.getX())) {
            clearHover(true);
            writeReportedMouseMotion(event);
            return;
        }

        var pressGesture = mouseInputState.pressGesture();
        if (!event.isPrimaryButtonDown() || pressGesture == null || pressGesture.button() != TerminalSession.MouseButton.LEFT) {
            return;
        }

        var hit = contentHit(event);
        if (hit == null) {
            clearHover(true);
            return;
        }

        var movedOffOrigin = !pressGesture.anchor().equals(hit.screenPoint());
        pressGesture = pressGesture.withDrag(movedOffOrigin || !selection.isEmpty());
        mouseInputState = mouseInputState.withPressGesture(pressGesture);
        clearHover(false);
        switch (pressGesture.clickCount()) {
            case 1 -> applySelection(MouseInput.selectionForDrag(
                    pressGesture.anchor(),
                    hit.screenPoint(),
                    pressGesture.anchorCellOffsetX(),
                    hit.cellOffsetX(),
                    pressGesture.rectangleSelection(),
                    terminalSession.columnCount(),
                    cellMetrics.get().cellWidthPx()));
            case 2 -> applyWordDragSelection(pressGesture, hit.screenPoint());
            case 3 -> applyLineDragSelection(pressGesture, hit.screenPoint());
            default -> throw new IllegalStateException("unsupported click count: " + pressGesture.clickCount());
        }
    }

    private void handleMouseMoved(MouseEvent event) {
        event.consume();
        if (terminalSession.mouseTrackingEnabled() && !isInScrollbar(event.getX())) {
            clearHover(true);
            writeReportedMouseMotion(event);
            return;
        }

        refreshHover(event);
    }

    private void handleMouseReleased(MouseEvent event) {
        event.consume();
        var nextMouseInputState = MouseInput.stopScrollbarDrag(mouseInputState);
        if (!nextMouseInputState.equals(mouseInputState)) {
            mouseInputState = nextMouseInputState;
        }

        if (terminalSession.mouseTrackingEnabled() && !isInScrollbar(event.getX())) {
            clearHover(true);
            writeReportedMouseRelease(event);
            mouseInputState = mouseInputState.withPressGesture(null);
            return;
        }

        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        var releasedGesture = mouseInputState.pressGesture();
        mouseInputState = mouseInputState.withPressGesture(null);
        if (releasedGesture == null) {
            refreshHover(event);
            return;
        }

        var hit = contentHit(event);
        if (releasedGesture.clickCount() == 1
                && !releasedGesture.dragged()
                && hit != null
                && releasedGesture.hyperlinkUri() != null
                && releasedGesture.anchor().equals(hit.screenPoint())
                && releasedGesture.hyperlinkUri().equals(hit.hyperlinkUri())) {
            openHyperlink(releasedGesture.hyperlinkUri());
        }
        refreshHover(event);
    }

    private void handleMouseExited(MouseEvent event) {
        event.consume();
        clearHover(true);
        if (terminalSession.mouseTrackingEnabled() && anyMouseButtonDown(event)) {
            writeReportedMouseMotion(event);
        }
    }

    private void handleMouseClicked(MouseEvent event) {
        event.consume();
    }

    private void handleScrollStarted(ScrollEvent event) {
        mouseInputState = MouseInput.startScrollGesture(mouseInputState);
        event.consume();
    }

    private void handleScrollFinished(ScrollEvent event) {
        mouseInputState = MouseInput.stopScrollGesture(mouseInputState);
        event.consume();
    }

    private void handleScroll(ScrollEvent event) {
        event.consume();
        var overContent = !isInScrollbar(event.getX());
        var discrete = isDiscreteWheelScroll(mouseInputState.scrollGestureActive(), event);
        if (discrete) {
            var tickDelta = discreteScrollDeltaTicks(event);
            if (tickDelta == 0) {
                return;
            }

            var scrollUpdate = MouseInput.accumulateDiscreteScroll(mouseInputState, tickDelta);
            mouseInputState = scrollUpdate.state();
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
            if (!wroteToApplication && alternateScrollEnabled(overContent)) {
                wroteToApplication = writeAlternateScrollKeys(wholeTicks, eventModifiers(event));
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

        var scrollUpdate = MouseInput.accumulateSmoothScroll(mouseInputState, deltaRows);
        mouseInputState = scrollUpdate.state();
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
        if (!wroteToApplication && alternateScrollEnabled(overContent)) {
            wroteToApplication = writeAlternateScrollKeys(wholeRows, eventModifiers(event));
        }
        if (!wroteToApplication) {
            scrollViewportBy(-wholeRows);
        }
    }

    private boolean alternateScrollEnabled(boolean overContent) {
        return overContent
                && terminalSession.alternateScreenActive()
                && terminalSession.readMode(MOUSE_ALTERNATE_SCROLL_MODE);
    }

    private boolean writeAlternateScrollKeys(int deltaRows, short mods) {
        var code = deltaRows > 0 ? KeyCode.UP : KeyCode.DOWN;
        var classification = KeyInput.classify(code);
        var output = new KeyInput.EncodeOutput(
                code,
                ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                classification.ghosttyKey(),
                mods,
                (short) 0,
                classification.unshiftedCodepoint(),
                "",
                false);
        var wroteToPty = false;
        for (var i = 0; i < Math.abs(deltaRows); i++) {
            wroteToPty |= writeBytes(terminalSession.encode(output, isMacOptionAsAlt()));
        }
        if (wroteToPty) {
            clearSelection();
            terminalSession.scrollViewportToBottom();
            redraw();
        }
        return wroteToPty;
    }

    private boolean handleScrollbarPress(double y) {
        var scrollbar = scrollbarInfo();
        if (scrollbar == null || !scrollbar.scrollable()) {
            return false;
        }

        if (scrollbar.containsThumb(y)) {
            mouseInputState = MouseInput.startScrollbarDrag(mouseInputState, scrollbar.thumbGrabRatio(y));
            return true;
        }

        scrollViewportTo(scrollbar.targetOffsetForTrackPress(y), scrollbar);
        var updatedScrollbar = scrollbarInfo();
        if (updatedScrollbar != null && updatedScrollbar.scrollable()) {
            mouseInputState = MouseInput.startScrollbarDrag(mouseInputState, updatedScrollbar.thumbGrabRatio(y));
        }
        return true;
    }

    private boolean dragScrollbarTo(double y) {
        var scrollbar = scrollbarInfo();
        if (scrollbar == null || !scrollbar.scrollable()) {
            mouseInputState = MouseInput.stopScrollbarDrag(mouseInputState);
            return false;
        }

        scrollViewportTo(scrollbar.targetOffsetForDrag(y, mouseInputState.scrollbarThumbGrabRatio()), scrollbar);
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

    private TerminalSession.CellHit contentHit(MouseEvent event) {
        return terminalSession.hitTest(
                event.getX(),
                event.getY(),
                getWidth(),
                getHeight(),
                cellMetrics.get(),
                scrollbarReservedWidthPx());
    }

    private void refreshHover(MouseEvent event) {
        var pressGesture = mouseInputState.pressGesture();
        if (pressGesture != null && pressGesture.button() == TerminalSession.MouseButton.LEFT) {
            var hit = contentHit(event);
            if (hit == null || !pressGesture.anchor().equals(hit.screenPoint())) {
                clearHover(true);
                return;
            }
        }

        var hit = contentHit(event);
        if (hit == null || hit.hyperlinkUri() == null || hit.hyperlinkUri().isEmpty()) {
            clearHover(true);
            return;
        }

        var nextHover = terminalSession.hyperlinkSelection(hit.screenPoint(), hit.hyperlinkUri());
        if (nextHover.isEmpty()) {
            clearHover(true);
            return;
        }

        if (!nextHover.equals(hoveredHyperlink)) {
            hoveredHyperlink = nextHover;
            redraw();
        }
        setCursor(Cursor.HAND);
    }

    private void clearHover(boolean redraw) {
        var changed = !hoveredHyperlink.isEmpty() || getCursor() != Cursor.DEFAULT;
        hoveredHyperlink = Selection.empty();
        setCursor(Cursor.DEFAULT);
        if (changed && redraw) {
            redraw();
        }
    }

    private void applySelection(Selection nextSelection) {
        if (!selection.equals(nextSelection)) {
            if (!nextSelection.isEmpty()) {
                clearHover(false);
            }
            selection = nextSelection;
            redraw();
        }
    }

    private void applyWordDragSelection(MouseInput.PressGesture pressGesture, Selection.ScreenPoint dragPoint) {
        var anchorWord = terminalSession.wordSelection(pressGesture.anchor());
        var currentWord = terminalSession.wordSelectionBetween(dragPoint, pressGesture.anchor());
        if (anchorWord.isEmpty() || currentWord.isEmpty()) {
            applySelection(Selection.empty());
            return;
        }

        applySelection(compareScreenPoints(dragPoint, pressGesture.anchor()) < 0
                ? Selection.linear(currentWord.normalized().from(), anchorWord.normalized().to())
                : Selection.linear(anchorWord.normalized().from(), currentWord.normalized().to()));
    }

    private void applyLineDragSelection(MouseInput.PressGesture pressGesture, Selection.ScreenPoint dragPoint) {
        var anchorLine = terminalSession.lineSelection(pressGesture.anchor());
        var currentLine = terminalSession.lineSelection(dragPoint);
        if (anchorLine.isEmpty() || currentLine.isEmpty()) {
            applySelection(Selection.empty());
            return;
        }

        applySelection(compareScreenPoints(dragPoint, pressGesture.anchor()) < 0
                ? Selection.linear(currentLine.normalized().from(), anchorLine.normalized().to())
                : Selection.linear(anchorLine.normalized().from(), currentLine.normalized().to()));
    }

    private void writeReportedMousePress(MouseEvent event) {
        writeBytes(terminalSession.encodeMousePress(
                toTerminalMouseButton(event.getButton()),
                event.getX(),
                event.getY(),
                eventModifiers(event),
                getWidth(),
                getHeight(),
                cellMetrics.get(),
                scrollbarReservedWidthPx(),
                anyMouseButtonDown(event)));
    }

    private void writeReportedMouseRelease(MouseEvent event) {
        writeBytes(terminalSession.encodeMouseRelease(
                toTerminalMouseButton(event.getButton()),
                event.getX(),
                event.getY(),
                eventModifiers(event),
                getWidth(),
                getHeight(),
                cellMetrics.get(),
                scrollbarReservedWidthPx(),
                anyMouseButtonDown(event)));
    }

    private void writeReportedMouseMotion(MouseEvent event) {
        writeBytes(terminalSession.encodeMouseMotion(
                currentPressedMouseButton(event),
                event.getX(),
                event.getY(),
                eventModifiers(event),
                getWidth(),
                getHeight(),
                cellMetrics.get(),
                scrollbarReservedWidthPx(),
                anyMouseButtonDown(event)));
    }

    private static TerminalSession.MouseButton toTerminalMouseButton(MouseButton button) {
        return switch (button) {
            case PRIMARY -> TerminalSession.MouseButton.LEFT;
            case SECONDARY -> TerminalSession.MouseButton.RIGHT;
            case MIDDLE -> TerminalSession.MouseButton.MIDDLE;
            default -> TerminalSession.MouseButton.UNKNOWN;
        };
    }

    private static TerminalSession.MouseButton currentPressedMouseButton(MouseEvent event) {
        if (event.isPrimaryButtonDown()) {
            return TerminalSession.MouseButton.LEFT;
        }
        if (event.isSecondaryButtonDown()) {
            return TerminalSession.MouseButton.RIGHT;
        }
        if (event.isMiddleButtonDown()) {
            return TerminalSession.MouseButton.MIDDLE;
        }
        return TerminalSession.MouseButton.UNKNOWN;
    }

    private static boolean anyMouseButtonDown(MouseEvent event) {
        return event.isPrimaryButtonDown() || event.isSecondaryButtonDown() || event.isMiddleButtonDown();
    }

    private boolean isRectangleSelection(MouseEvent event) {
        return inputPlatform == KeyInput.Platform.MACOS
                ? event.isAltDown()
                : event.isAltDown() && (event.isControlDown() || event.isMetaDown());
    }

    private static int compareScreenPoints(Selection.ScreenPoint left, Selection.ScreenPoint right) {
        var byY = Integer.compare(left.y(), right.y());
        return byY != 0 ? byY : Integer.compare(left.x(), right.x());
    }

    private static void openHyperlink(String hyperlinkUri) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(hyperlinkUri));
            }
        } catch (Exception _) {
        }
    }

    private boolean handleShortcut(KeyEvent event) {
        var copy = getCopyShortcut();
        if (copy != null && copy.match(event) && !selection.isEmpty()) {
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

            writeBytes(terminalSession.encodePaste(text, terminalSession.readMode(BRACKETED_PASTE_MODE)));
            clearSelection();
            return true;
        }
        var selectAll = getSelectAllShortcut();
        if (selectAll != null && selectAll.match(event)) {
            var nextSelection = terminalSession.selectAllSelection();
            if (!nextSelection.equals(selection)) {
                clearHover(false);
                selection = nextSelection;
                redraw();
            }
            return true;
        }
        return false;
    }

    private void clearSelection() {
        if (!selection.isEmpty()) {
            clearHover(false);
            selection = Selection.empty();
            redraw();
        }
    }

    private boolean applyTransition(KeyInput.Transition transition) {
        var previousKeyInputState = keyInputState;
        var previousSelection = selection;
        keyInputState = transition.state();
        if (transition.clearSelection()) {
            clearHover(false);
            selection = Selection.empty();
        }

        var wroteToPty = false;
        for (var output : transition.outputs()) {
            switch (output) {
                case KeyInput.EncodeOutput encodeOutput -> {
                    var producedBytes = writeBytes(terminalSession.encode(encodeOutput, isMacOptionAsAlt()));
                    keyInputState = KeyInput.acknowledgeEncode(
                            keyInputState,
                            encodeOutput.code(),
                            encodeOutput.action(),
                            producedBytes);
                    wroteToPty |= producedBytes;
                }
                case KeyInput.RawTextOutput(var text) -> {
                    if (!text.isEmpty()) {
                        wroteToPty |= writeBytes(text.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }

        if (wroteToPty) {
            terminalSession.scrollViewportToBottom();
        }

        var redraw = transition.redraw()
                || wroteToPty
                || !previousSelection.equals(selection)
                || !previousKeyInputState.preedit().equals(keyInputState.preedit());
        if (redraw) {
            redraw();
        }
        return wroteToPty || redraw || !previousKeyInputState.equals(keyInputState) || !previousSelection.equals(selection);
    }

    private String selectedText() {
        return terminalSession.selectedText(selection);
    }

    private KeyInput.KeySnapshot snapshot(KeyEvent event) {
        return new KeyInput.KeySnapshot(event.getCode(), event.isShiftDown(), event.isControlDown(), event.isAltDown(), event.isMetaDown());
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
                terminalFonts.get(),
                cellMetrics.get(),
                keyInputState.preedit(),
                selection,
                hoveredHyperlink,
                isFocused(),
                getTheme(),
                scrollbarReservedWidthPx(),
                MIN_SCROLLBAR_HEIGHT_PX);
    }

    private CursorLocation currentCursorLocation() {
        return terminalSession.currentCursorLocation(cellMetrics.get());
    }

    private static final class ProcessOutputDrain extends AnimationTimer {

        private final WeakReference<GhosttyCanvas> canvasRef;
        private final PtySession ptySession;

        private ProcessOutputDrain(GhosttyCanvas canvas) {
            canvasRef = new WeakReference<>(canvas);
            ptySession = canvas.ptySession;
        }

        @Override
        public void handle(long now) {
            var outputs = ptySession.pollProcessOutputs();
            if (outputs.isEmpty()) {
                return;
            }

            var canvas = canvasRef.get();
            if (canvas != null) {
                var totalBytes = 0;
                for (var output : outputs) {
                    if (output instanceof PtySession.Chunk(var bytes)) {
                        totalBytes += bytes.length;
                    }
                }

                var bytes = new byte[totalBytes];
                var offset = 0;
                for (var output : outputs) {
                    if (output instanceof PtySession.Chunk(var chunk)) {
                        System.arraycopy(chunk, 0, bytes, offset, chunk.length);
                        offset += chunk.length;
                    }
                }

                if (bytes.length != 0) {
                    canvas.terminalSession.writeToTerminal(bytes);
                    canvas.redraw();
                }
            }
            if (outputs.get(outputs.size() - 1) instanceof PtySession.Closed) {
                stop();
                if (canvas != null) {
                    canvas.processExited.set(true);
                }
            }
        }
    }

    private void writeCommand(PtySession.Command command) {
        try {
            ptySession.putCommand(command);
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

            var codePointCount = keyInputState.preedit().text().codePointCount(0, keyInputState.preedit().text().length());
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
            var codePointCount = keyInputState.preedit().text().codePointCount(0, keyInputState.preedit().text().length());
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

    static record CellMetrics(int cellWidthPx, int cellHeightPx, int baselineOffsetPx) {

    }

    static record CursorLocation(int cellX, int cellY, double pixelX, double pixelY) {

    }

    static record TerminalFonts(Font regular, Font bold, Font italic, Font boldItalic) {

        Font forStyle(boolean bold, boolean italic) {
            if (bold && italic) {
                return boldItalic;
            }
            if (bold) {
                return this.bold;
            }
            return italic ? this.italic : regular;
        }

    }

    private boolean writeBytes(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        writeCommand(new PtySession.WriteInput(bytes));
        return true;
    }

}
