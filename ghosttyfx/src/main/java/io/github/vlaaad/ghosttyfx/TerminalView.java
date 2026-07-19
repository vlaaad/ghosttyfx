package io.github.vlaaad.ghosttyfx;

import java.awt.Desktop;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
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
import javafx.scene.layout.AnchorPane;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;
import javafx.stage.Window;
import javafx.util.Duration;

/// A JavaFX terminal control backed by Ghostty's terminal emulator.
///
/// `TerminalView` starts a [Terminal] through a [TerminalFactory], renders its
/// output, and sends keyboard, mouse, paste, and resize input back to the
/// terminal backend.
public final class TerminalView extends AnchorPane implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final int INITIAL_COLUMNS = 80;
    private static final int INITIAL_ROWS = 24;
    private static final short FOCUS_EVENT_MODE = 1004;
    private static final short MOUSE_ALTERNATE_SCROLL_MODE = 1007;
    private static final short BRACKETED_PASTE_MODE = 2004;
    private static final double DEFAULT_SCROLL_MULTIPLIER_Y = 40;
    static final double SCROLLBAR_WIDTH_PX = 8;
    static final double SCROLLBAR_MARGIN_PX = 1;
    private static final double MIN_SCROLLBAR_HEIGHT_PX = 30;
    static final double SCROLLBAR_ARC_PX = SCROLLBAR_WIDTH_PX;
    private static final double SCROLL_TOTAL_DELTA_EPSILON = 1e-6;
    private static final Duration BLINK_INTERVAL = Duration.millis(600);
    private static final Duration PROMPT_NAVIGATION_HIGHLIGHT_DURATION = Duration.millis(700);
    private static final Font DEFAULT_FONT = Font.font("Monospaced", 14);
    private static final TerminalLinkMatcher BUILT_IN_LINK_MATCHER = new TerminalLinkMatcher(
            Pattern.compile("(?i)\\bhttps?://(?:\\[[0-9a-f:]+(?:[:0-9a-f]*)+\\](?::[0-9]+)?|[\\w\\-.~:/?#@!$&*+,;=%]+(?:[\\(\\[]\\w*[\\)\\]])?)+(?<![,.])"),
            match -> openBuiltInWebPageUrl(match.group()));

    private final TerminalSession terminalSession;
    private final PtySession ptySession;
    private final ProcessOutputDrain processOutputDrain;
    private final AnimationTimer selectionAutoscroll;
    private final Timeline cursorBlinkTimeline;
    private final Timeline textBlinkTimeline;
    private final Timeline promptNavigationHighlightTimeline;
    private KeyInput.State keyInputState = KeyInput.initialState();
    private MouseInput.State mouseInputState = MouseInput.initialState();
    private SelectionDrag selectionDrag;
    private ActiveLink hoveredLink;
    private int promptNavigationRow = -1;
    private int promptNavigationHighlightRow = -1;
    private boolean cursorBlinkVisible = true;
    private boolean textBlinkVisible = true;
    private TerminalSession.BlinkState blinkState = TerminalSession.BlinkState.none();
    private final SearchUi searchUi;
    private final ReadOnlyStringWrapper title;
    private final ReadOnlyStringWrapper currentDirectory;
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
    private final BooleanProperty cursorBlinking = new SimpleBooleanProperty(this, "cursorBlinking", true);
    private final StringProperty searchPromptText = new SimpleStringProperty(this, "searchPromptText", "Type to search...") {
        @Override
        public void set(String value) {
            super.set(Objects.requireNonNull(value, "searchPromptText"));
        }
    };
    private final BooleanProperty macOptionAsAlt = new SimpleBooleanProperty(this, "macOptionAsAlt", false);
    private final ObservableList<TerminalShortcut> terminalShortcuts = FXCollections.observableArrayList();
    private final ObservableList<TerminalLinkMatcher> linkMatchers = FXCollections.observableArrayList();
    private final ReadOnlyObjectWrapper<TerminalState> terminalState =
            new ReadOnlyObjectWrapper<>(this, "terminalState", new TerminalState.Running());
    private final ReadOnlyObjectWrapper<TerminalSize> terminalSize =
            new ReadOnlyObjectWrapper<>(this, "terminalSize", new TerminalSize(INITIAL_COLUMNS, INITIAL_ROWS));
    private final ObservableValue<Window> outputScaleWindow = sceneProperty().flatMap(Scene::windowProperty);
    private final ObservableValue<Number> outputScaleX =
            outputScaleWindow.flatMap(Window::outputScaleXProperty).orElse(1.0);
    private final ObservableValue<Number> outputScaleY =
            outputScaleWindow.flatMap(Window::outputScaleYProperty).orElse(1.0);
    private final ChangeListener<Number> outputScaleListener = (_, _, _) -> scheduleOutputScaleResize();
    private boolean outputScaleResizeScheduled;

    private final ObjectBinding<FontMetrics> fontMetrics = Bindings.createObjectBinding(() -> {
        var font = this.font.get();
        var text = new Text();
        text.setBoundsType(TextBoundsType.LOGICAL);
        text.setFont(font);

        var minWidth = Double.POSITIVE_INFINITY;
        var maxWidth = 0.0;
        for (var c = 32; c < 127; c++) {
            text.setText(Character.toString((char) c));
            var width = text.getLayoutBounds().getWidth();
            minWidth = Math.min(minWidth, width);
            maxWidth = Math.max(maxWidth, width);
        }

        text.setText("M_");
        var bounds = text.getLayoutBounds();
        var cellWidthPx = Math.max(1, (int) Math.round(maxWidth));
        var cellHeightPx = Math.max(1, (int) Math.round(bounds.getHeight()));
        var baselineOffsetPx = (int) Math.round(-bounds.getMinY());
        baselineOffsetPx = Math.max(0, Math.min(cellHeightPx, baselineOffsetPx));
        var monospace = maxWidth > 0 && maxWidth - minWidth <= 0.01;
        var fontSize = monospace ? font.getSize() * cellWidthPx / maxWidth : font.getSize();
        var regular = new Font(font.getName(), fontSize);
        var lookupFamilies = Font.getFontNames(font.getFamily()).contains(font.getName())
                ? List.of(font.getFamily())
                : FontIndex.lookupFamilies(font.getName());
        var bold = regular;
        var italic = regular;
        var boldItalic = regular;
        for (var lookupFamily : lookupFamilies) {
            var faceNames = Font.getFontNames(lookupFamily);
            if (bold.equals(regular)) {
                var match = Font.font(lookupFamily, FontWeight.BOLD, fontSize);
                if (faceNames.contains(match.getName())) {
                    bold = match;
                }
            }
            if (italic.equals(regular)) {
                var match = Font.font(lookupFamily, FontPosture.ITALIC, fontSize);
                if (faceNames.contains(match.getName())) {
                    italic = match;
                }
            }
            if (boldItalic.equals(regular)) {
                var match = Font.font(lookupFamily, FontWeight.BOLD, FontPosture.ITALIC, fontSize);
                if (faceNames.contains(match.getName())) {
                    boldItalic = match;
                }
            }
        }
        return new FontMetrics(
                regular,
                bold,
                italic,
                boldItalic,
                cellWidthPx,
                cellHeightPx,
                baselineOffsetPx);
    }, font);
    private final Canvas canvas = new ResizableCanvas(
            () -> INITIAL_COLUMNS * fontMetrics.get().cellWidthPx() + scrollbarReservedWidthPx(),
            () -> INITIAL_ROWS * fontMetrics.get().cellHeightPx());

    /// Creates a terminal view and opens its terminal backend.
    ///
    /// The supplied factory is invoked on a background thread.
    ///
    /// @param terminalFactory the factory used to open the terminal backend
    /// @throws NullPointerException if `terminalFactory` is `null`
    public TerminalView(TerminalFactory terminalFactory) {
        NativeLibrary.ensureLoaded();
        title = new ReadOnlyStringWrapper(this, "title", "Terminal");
        currentDirectory = new ReadOnlyStringWrapper(this, "currentDirectory", "");
        processOutputDrain = new ProcessOutputDrain(this);
        Objects.requireNonNull(terminalFactory, "terminalFactory");
        selectionAutoscroll = new AnimationTimer() {
            @Override
            public void handle(long now) {
                handleSelectionAutoscroll();
            }
        };
        cursorBlinkTimeline = new Timeline(new KeyFrame(BLINK_INTERVAL, _ -> tickCursorBlink()));
        cursorBlinkTimeline.setCycleCount(Animation.INDEFINITE);
        textBlinkTimeline = new Timeline(new KeyFrame(BLINK_INTERVAL, _ -> tickTextBlink()));
        textBlinkTimeline.setCycleCount(Animation.INDEFINITE);
        promptNavigationHighlightTimeline = new Timeline(new KeyFrame(PROMPT_NAVIGATION_HIGHLIGHT_DURATION, _ -> clearPromptNavigationHighlight()));
        var initialFontMetrics = fontMetrics.get();
        var thisRef = new WeakReference<>(this);
        terminalSession = new TerminalSession(
                INITIAL_COLUMNS,
                INITIAL_ROWS,
                initialFontMetrics,
                bytes -> writeBytes(bytes),
                (newTitle) -> {
                    var view = thisRef.get();
                    if (view != null) {
                        view.title.set(newTitle);
                    }
                },
                (newCurrentDirectory) -> {
                    var view = thisRef.get();
                    if (view != null) {
                        view.currentDirectory.set(newCurrentDirectory);
                    }
                },
                () -> {
                    var view = thisRef.get();
                    if (view != null) {
                        var handler = view.onBell.get();
                        if (handler != null) {
                            handler.run();
                        }
                    }
                });
        terminalShortcuts.addAll(defaultTerminalShortcuts());
        linkMatchers.addAll(defaultLinkMatchers());

        setFocusTraversable(true);
        searchUi = new SearchUi(
                terminalSession,
                font,
                searchPromptText,
                widthProperty(),
                () -> closeSearch(),
                this::redraw,
                this::searchMatchesAffectViewport,
                this::scrollSearchMatchIntoView);
        applySearchTheme();
        getChildren().add(canvas);
        AnchorPane.setTopAnchor(canvas, 0.0);
        AnchorPane.setRightAnchor(canvas, 0.0);
        AnchorPane.setBottomAnchor(canvas, 0.0);
        AnchorPane.setLeftAnchor(canvas, 0.0);
        getChildren().add(searchUi.view());
        AnchorPane.setTopAnchor(searchUi.view(), 8.0);
        AnchorPane.setRightAnchor(searchUi.view(), 8.0);
        widthProperty().addListener((_, _, _) -> handleResize());
        heightProperty().addListener((_, _, _) -> handleResize());
        outputScaleX.addListener(outputScaleListener);
        outputScaleY.addListener(outputScaleListener);
        fontMetrics.addListener((_, oldMetrics, newMetrics) -> {
            if (oldMetrics == null
                    || oldMetrics.cellWidthPx() != newMetrics.cellWidthPx()
                    || oldMetrics.cellHeightPx() != newMetrics.cellHeightPx()) {
                handleResize();
            } else {
                redraw();
            }
        });
        linkMatchers.addListener((ListChangeListener<TerminalLinkMatcher>) _ -> redraw());
        cursorBlinking.addListener((_, _, value) -> {
            terminalSession.setCursorBlinking(value);
            cursorBlinkVisible = true;
            updateBlinkTimelines();
            redraw();
        });
        theme.addListener((_, _, value) -> {
            terminalSession.applyTheme(value);
            applySearchTheme();
            redraw();
        });
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
        setInputMethodRequests(new TerminalInputMethodRequests());
        setCursor(Cursor.DEFAULT);
        sceneProperty().addListener((_, _, _) -> {
            updateBlinkTimelines();
            updatePromptNavigationHighlightTimeline();
        });
        terminalSession.applyTheme(getTheme());
        ptySession = new PtySession(
                terminalFactory,
                INITIAL_COLUMNS,
                INITIAL_ROWS,
                processOutputDrain::requestDrain);
        resize(prefWidth(-1), prefHeight(-1));
        redraw();
        processOutputDrain.start();

        // The view only owns the process lifecycle directly. Native terminal resources stay alive as long as the
        // terminal view is reachable so an already-rendered view can still be queried/filtered/interacted with
        // after close().
        CLEANER.register(this, new Cleanup(terminalSession, ptySession));
    }

    /// Returns the font used to measure and render terminal cells.
    ///
    /// @return the terminal font
    public Font getFont() {
        return font.get();
    }

    /// Sets the font used to measure and render terminal cells.
    ///
    /// @param value the terminal font
    /// @throws NullPointerException if `value` is `null`
    public void setFont(Font value) {
        font.set(value);
    }

    /// The font used to measure and render terminal cells.
    ///
    /// @return the font property
    public ObjectProperty<Font> fontProperty() {
        return font;
    }

    /// Returns whether the cursor may blink when the terminal requests blinking.
    ///
    /// @return whether cursor blinking is enabled
    public boolean isCursorBlinking() {
        return cursorBlinking.get();
    }

    /// Sets whether the cursor may blink when the terminal requests blinking.
    ///
    /// @param value whether cursor blinking is enabled
    public void setCursorBlinking(boolean value) {
        cursorBlinking.set(value);
    }

    /// Whether the cursor may blink when the terminal requests blinking.
    ///
    /// @return the cursor blinking property
    public BooleanProperty cursorBlinkingProperty() {
        return cursorBlinking;
    }

    /// Returns the placeholder text shown in the search field.
    ///
    /// @return the search prompt text
    public String getSearchPromptText() {
        return searchPromptText.get();
    }

    /// Sets the placeholder text shown in the search field.
    ///
    /// @param value the search prompt text
    /// @throws NullPointerException if `value` is `null`
    public void setSearchPromptText(String value) {
        searchPromptText.set(value);
    }

    /// The placeholder text shown in the search field.
    ///
    /// @return the search prompt text property
    public StringProperty searchPromptTextProperty() {
        return searchPromptText;
    }

    /// Returns the terminal theme.
    ///
    /// @return the terminal theme
    public TerminalTheme getTheme() {
        return theme.get();
    }

    /// Sets the terminal theme.
    ///
    /// @param value the terminal theme
    /// @throws NullPointerException if `value` is `null`
    public void setTheme(TerminalTheme value) {
        theme.set(value);
    }

    /// The terminal theme.
    ///
    /// @return the theme property
    public ObjectProperty<TerminalTheme> themeProperty() {
        return theme;
    }

    /// Returns whether Option is sent as Alt on macOS.
    ///
    /// @return whether Option is sent as Alt on macOS
    public boolean isMacOptionAsAlt() {
        return macOptionAsAlt.get();
    }

    /// Sets whether Option is sent as Alt on macOS.
    ///
    /// @param value whether Option is sent as Alt on macOS
    public void setMacOptionAsAlt(boolean value) {
        macOptionAsAlt.set(value);
    }

    /// Whether Option is sent as Alt on macOS.
    ///
    /// @return the macOS Option-as-Alt property
    public BooleanProperty macOptionAsAltProperty() {
        return macOptionAsAlt;
    }

    /// Returns whether the terminal application has enabled mouse tracking.
    ///
    /// @return whether mouse tracking is enabled
    public boolean isMouseTrackingEnabled() {
        return terminalSession.mouseTrackingEnabled();
    }

    /// Returns the default terminal shortcuts for this view.
    ///
    /// The returned list is immutable. Shortcut actions are bound to this
    /// `TerminalView` instance.
    ///
    /// @return the immutable default terminal shortcut list
    public List<TerminalShortcut> defaultTerminalShortcuts() {
        if (HostPlatform.CURRENT.os() == HostPlatform.OS.MACOS) {
            return List.of(
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN), this::copySelection),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.V, KeyCombination.META_DOWN), this::pasteClipboard),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.A, KeyCombination.META_DOWN), this::selectAll),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.F, KeyCombination.META_DOWN), this::toggleSearch),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHIFT_DOWN), this::extendSelectionLeft),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHIFT_DOWN), this::extendSelectionRight),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.UP, KeyCombination.SHIFT_DOWN), this::extendSelectionUp),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.DOWN, KeyCombination.SHIFT_DOWN), this::extendSelectionDown),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.PAGE_UP, KeyCombination.SHIFT_DOWN), this::extendSelectionPageUp),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.PAGE_DOWN, KeyCombination.SHIFT_DOWN), this::extendSelectionPageDown),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.HOME, KeyCombination.SHIFT_DOWN), this::extendSelectionHome),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.END, KeyCombination.SHIFT_DOWN), this::extendSelectionEnd),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.PAGE_UP, KeyCombination.META_DOWN), this::scrollViewportPageUp),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.PAGE_DOWN, KeyCombination.META_DOWN), this::scrollViewportPageDown),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.HOME, KeyCombination.META_DOWN), this::scrollViewportToTop),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.END, KeyCombination.META_DOWN), this::scrollViewportToBottom),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.UP, KeyCombination.META_DOWN), this::scrollViewportToPreviousPrompt),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.DOWN, KeyCombination.META_DOWN), this::scrollViewportToNextPrompt),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN), () -> sendEsc("b")),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.ALT_DOWN), () -> sendEsc("f")),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.META_DOWN), () -> sendText("\u0001")),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.META_DOWN), () -> sendText("\u0005")),
                    new TerminalShortcut(new KeyCodeCombination(KeyCode.BACK_SPACE, KeyCombination.META_DOWN), () -> sendText("\u0015")));
        }
        return List.of(
                new TerminalShortcut(new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN), this::copySelection),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN), this::pasteClipboard),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), this::selectAll),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), this::toggleSearch),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHIFT_DOWN), this::extendSelectionLeft),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHIFT_DOWN), this::extendSelectionRight),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.UP, KeyCombination.SHIFT_DOWN), this::extendSelectionUp),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.DOWN, KeyCombination.SHIFT_DOWN), this::extendSelectionDown),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.PAGE_UP, KeyCombination.SHIFT_DOWN), this::extendSelectionPageUp),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.PAGE_DOWN, KeyCombination.SHIFT_DOWN), this::extendSelectionPageDown),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.HOME, KeyCombination.SHIFT_DOWN), this::extendSelectionHome),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.END, KeyCombination.SHIFT_DOWN), this::extendSelectionEnd),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.PAGE_UP, KeyCombination.SHIFT_DOWN), this::scrollViewportPageUp),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.PAGE_DOWN, KeyCombination.SHIFT_DOWN), this::scrollViewportPageDown),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.HOME, KeyCombination.SHIFT_DOWN), this::scrollViewportToTop),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.END, KeyCombination.SHIFT_DOWN), this::scrollViewportToBottom),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.UP, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), this::scrollViewportToPreviousPrompt),
                new TerminalShortcut(new KeyCodeCombination(KeyCode.DOWN, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), this::scrollViewportToNextPrompt));
    }

    /// Returns the default terminal link matchers for this view.
    ///
    /// The returned list is immutable. The default list includes the built-in
    /// web URL matcher.
    ///
    /// @return the immutable default terminal link matcher list
    public List<TerminalLinkMatcher> defaultLinkMatchers() {
        return List.of(BUILT_IN_LINK_MATCHER);
    }

    /// Returns the terminal shortcuts.
    ///
    /// The list is mutable. Shortcuts are tried in list order.
    ///
    /// @return the mutable terminal shortcut list
    public ObservableList<TerminalShortcut> getTerminalShortcuts() {
        return terminalShortcuts;
    }

    /// Returns the terminal link matchers.
    ///
    /// The list is mutable. Matchers are tried in list order. The default list
    /// includes the built-in web URL matcher.
    ///
    /// @return the mutable terminal link matcher list
    public ObservableList<TerminalLinkMatcher> getLinkMatchers() {
        return linkMatchers;
    }

    /// Returns the terminal title.
    ///
    /// @return the terminal title
    public String getTitle() {
        return title.get();
    }

    /// The terminal title reported by the terminal backend.
    ///
    /// @return the read-only title property
    public ReadOnlyStringProperty titleProperty() {
        return title.getReadOnlyProperty();
    }

    /// Returns the terminal current directory reported by OSC 7/9/1337.
    ///
    /// The value is the raw string stored by Ghostty. OSC 7 usually reports a
    /// `file://` URI, while OSC 9 and OSC 1337 usually report a bare path.
    ///
    /// @return the terminal current directory, or an empty string when unset
    public String getCurrentDirectory() {
        return currentDirectory.get();
    }

    /// The raw terminal current directory reported by OSC 7/9/1337.
    ///
    /// @return the read-only current directory property
    public ReadOnlyStringProperty currentDirectoryProperty() {
        return currentDirectory.getReadOnlyProperty();
    }

    /// Returns the handler invoked when the terminal rings the bell.
    ///
    /// @return the bell handler, or `null`
    public Runnable getOnBell() {
        return onBell.get();
    }

    /// Sets the handler invoked when the terminal rings the bell.
    ///
    /// @param value the bell handler, or `null`
    public void setOnBell(Runnable value) {
        onBell.set(value);
    }

    /// The handler invoked when the terminal rings the bell.
    ///
    /// @return the bell handler property
    public ObjectProperty<Runnable> onBellProperty() {
        return onBell;
    }

    /// Returns the terminal backend state.
    ///
    /// @return the terminal backend state
    public TerminalState getTerminalState() {
        return terminalState.get();
    }

    /// The terminal backend state.
    ///
    /// @return the read-only terminal state property
    public ReadOnlyObjectProperty<TerminalState> terminalStateProperty() {
        return terminalState.getReadOnlyProperty();
    }

    /// Returns the terminal grid size.
    ///
    /// @return the terminal grid size
    public TerminalSize getTerminalSize() {
        return terminalSize.get();
    }

    /// The terminal grid size measured in character cells.
    ///
    /// @return the read-only terminal size property
    public ReadOnlyObjectProperty<TerminalSize> terminalSizeProperty() {
        return terminalSize.getReadOnlyProperty();
    }

    /// Closes the terminal backend owned by this view.
    ///
    /// This stops the terminal process or backend opened through the
    /// [TerminalFactory]. It does not remove this node from the scene graph and
    /// does not immediately discard the rendered terminal state; the last
    /// terminal contents remain available while the view is reachable.
    ///
    /// Repeated calls have the same effect as a single close.
    @Override
    public void close() {
        // Closing the view is a process-lifecycle operation only. Native terminal state stays available until the
        // view itself becomes unreachable so the last rendered view can still be queried or shown.
        ptySession.close();
    }

    private void scheduleOutputScaleResize() {
        if (outputScaleResizeScheduled) {
            return;
        }
        outputScaleResizeScheduled = true;
        Platform.runLater(() -> {
            outputScaleResizeScheduled = false;
            handleResize();
        });
    }

    private void handleResize() {
        var metrics = fontMetrics.get();
        var size = terminalSession.resize(
                getWidth(),
                getHeight(),
                metrics,
                scrollbarReservedWidthPx(),
                outputScaleX.getValue().doubleValue(),
                outputScaleY.getValue().doubleValue());
        if (size == null) {
            return;
        }

        var currentTerminalSize = terminalSize.get();
        if (currentTerminalSize.columns() != size.columns() || currentTerminalSize.rows() != size.rows()) {
            terminalSize.set(new TerminalSize(size.columns(), size.rows()));
        }
        writeCommand(new PtySession.ResizePty(
                size.columns(),
                size.rows(),
                size.widthPx(),
                size.heightPx()));
        if (searchUi.visible()) {
            searchUi.refresh(false);
            return;
        }
        redraw();
    }

    private void handleFocusChange(boolean focused) {
        if (!focused) {
            var nextKeyInputState = KeyInput.onFocusLost(keyInputState);
            var nextMouseInputState = MouseInput.onFocusLost(mouseInputState);
            clearHover(false);
            terminalSession.resetSelectionGesture();
            stopSelectionAutoscroll();
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
        if (searchUi.visible() && event.getCode() == KeyCode.ESCAPE) {
            closeSearch();
            event.consume();
            return;
        }
        var jfrEvent = JfrEvents.keyInput();
        if (jfrEvent != null) {
            jfrEvent.action = "pressed";
            jfrEvent.begin();
        }
        try {
            if (handleTerminalShortcut(event) || applyTransition(KeyInput.onKeyPressed(
                    keyInputState,
                    HostPlatform.CURRENT,
                    isMacOptionAsAlt(),
                    snapshot(event)))) {
                event.consume();
            }
        } finally {
            if (jfrEvent != null) {
                jfrEvent.commit();
            }
        }
    }

    private void handleKeyReleased(KeyEvent event) {
        var jfrEvent = JfrEvents.keyInput();
        if (jfrEvent != null) {
            jfrEvent.action = "released";
            jfrEvent.begin();
        }
        try {
            if (applyTransition(KeyInput.onKeyReleased(keyInputState, snapshot(event)))) {
                event.consume();
            }
        } finally {
            if (jfrEvent != null) {
                jfrEvent.commit();
            }
        }
    }

    private void handleKeyTyped(KeyEvent event) {
        var jfrEvent = JfrEvents.keyInput();
        if (jfrEvent != null) {
            jfrEvent.action = "typed";
            jfrEvent.begin();
        }
        try {
            if (applyTransition(KeyInput.onKeyTyped(
                    keyInputState,
                    HostPlatform.CURRENT,
                    event.isMetaDown(),
                    event.getCharacter()))) {
                event.consume();
            }
        } finally {
            if (jfrEvent != null) {
                jfrEvent.commit();
            }
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

        if (isMouseTrackingEnabled() && !isInScrollbar(event.getX())) {
            setScrollbarHovered(false);
            clearHover(true);
            clearSelection();
            terminalSession.resetSelectionGesture();
            stopSelectionAutoscroll();
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
            terminalSession.resetSelectionGesture();
            stopSelectionAutoscroll();
            return;
        }

        mouseInputState = mouseInputState.withPressGesture(new MouseInput.PressGesture(
                TerminalSession.MouseButton.LEFT,
                hit.screenPoint(),
                activeLink(hit)));
        clearHover(false);
        selectionDrag = null;
        terminalSession.selectionGesturePress(hit, event.getX(), event.getY(), fontMetrics.get());
        updateSelectionAutoscroll();
        redraw();
    }

    private void handleMouseDragged(MouseEvent event) {
        event.consume();
        if (mouseInputState.scrollbarDragging()) {
            if (!event.isPrimaryButtonDown()) {
                stopScrollbarDrag();
                refreshHover(event);
                return;
            }

            dragScrollbarTo(event.getY());
            return;
        }

        if (isMouseTrackingEnabled() && !isInScrollbar(event.getX())) {
            setScrollbarHovered(false);
            clearHover(true);
            writeReportedMouseMotion(event);
            return;
        }

        var pressGesture = mouseInputState.pressGesture();
        if (!event.isPrimaryButtonDown() || pressGesture == null || pressGesture.button() != TerminalSession.MouseButton.LEFT) {
            return;
        }

        var hit = selectionHit(event);
        if (hit == null) {
            clearHover(true);
            return;
        }

        clearHover(false);
        var rectangle = isRectangleSelection(event);
        selectionDrag = new SelectionDrag(event.getX(), event.getY(), rectangle);
        terminalSession.selectionGestureDrag(hit, event.getX(), event.getY(), rectangle, fontMetrics.get(), getHeight());
        updateSelectionAutoscroll();
        redraw();
    }

    private void handleMouseMoved(MouseEvent event) {
        event.consume();
        if (isMouseTrackingEnabled() && !isInScrollbar(event.getX())) {
            setScrollbarHovered(false);
            clearHover(true);
            writeReportedMouseMotion(event);
            return;
        }

        refreshHover(event);
    }

    private void handleMouseReleased(MouseEvent event) {
        event.consume();
        stopScrollbarDrag();

        if (isMouseTrackingEnabled() && !isInScrollbar(event.getX())) {
            setScrollbarHovered(false);
            clearHover(true);
            writeReportedMouseRelease(event);
            mouseInputState = mouseInputState.withPressGesture(null);
            terminalSession.resetSelectionGesture();
            stopSelectionAutoscroll();
            return;
        }

        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        var releasedGesture = mouseInputState.pressGesture();
        mouseInputState = mouseInputState.withPressGesture(null);
        stopSelectionAutoscroll();
        if (releasedGesture == null) {
            refreshHover(event);
            return;
        }

        var hit = contentHit(event);
        terminalSession.selectionGestureRelease(hit);
        if (terminalSession.selectionGestureClickCount() == 1
                && !terminalSession.selectionGestureDragged()
                && hit != null
                && releasedGesture.link() != null
                && releasedGesture.anchor().equals(hit.screenPoint())
                && releasedGesture.link().sameTarget(activeLink(hit))) {
            releasedGesture.link().action().run();
        }
        refreshHover(event);
    }

    private void handleMouseExited(MouseEvent event) {
        event.consume();
        setScrollbarHovered(false);
        clearHover(true);
        if (isMouseTrackingEnabled() && anyMouseButtonDown(event)) {
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

            var mouseTrackingEnabled = overContent && isMouseTrackingEnabled();
            var wroteToApplication = false;
            if (mouseTrackingEnabled) {
                wroteToApplication = writeBytes(terminalSession.encodeMouseScroll(
                        event.getX(),
                        event.getY(),
                        wholeTicks,
                        eventModifiers(event),
                        getWidth(),
                        getHeight(),
                        fontMetrics.get(),
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

        var mouseTrackingEnabled = overContent && isMouseTrackingEnabled();
        var wroteToApplication = false;
        if (mouseTrackingEnabled) {
            wroteToApplication = writeBytes(terminalSession.encodeMouseScroll(
                    event.getX(),
                    event.getY(),
                    wholeRows,
                    eventModifiers(event),
                    getWidth(),
                    getHeight(),
                    fontMetrics.get(),
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

        scrollViewportTo(scrollbar.targetOffsetForTrackPress(y));
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

        scrollViewportTo(scrollbar.targetOffsetForDrag(y, mouseInputState.scrollbarThumbGrabRatio()));
        return true;
    }

    private void scrollViewportTo(long row) {
        promptNavigationRow = -1;
        terminalSession.scrollViewportTo(row);
        redraw();
    }

    private void scrollViewportBy(long deltaRows) {
        if (deltaRows == 0) {
            return;
        }

        promptNavigationRow = -1;
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
        return event.getDeltaY() / fontMetrics.get().cellHeightPx();
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
        return terminalSession.viewportRowCount(0, fontMetrics.get().cellHeightPx(), getHeight());
    }

    private TerminalSession.CellHit contentHit(MouseEvent event) {
        return terminalSession.hitTest(
                event.getX(),
                event.getY(),
                getWidth(),
                getHeight(),
                fontMetrics.get(),
                scrollbarReservedWidthPx());
    }

    private TerminalSession.CellHit selectionHit(MouseEvent event) {
        return terminalSession.clampedHitTest(
                event.getX(),
                event.getY(),
                getWidth(),
                getHeight(),
                fontMetrics.get(),
                scrollbarReservedWidthPx());
    }

    private ActiveLink activeLink(TerminalSession.CellHit hit) {
        if (hit.hyperlinkUri() != null && !hit.hyperlinkUri().isEmpty()) {
            var selection = terminalSession.hyperlinkSelection(hit.screenPoint(), hit.hyperlinkUri());
            if (selection.isEmpty()) {
                return null;
            }
            return new ActiveLink(new ActiveLink.Osc8(hit.hyperlinkUri()), selection, () -> openHyperlink(hit.hyperlinkUri()));
        }

        var match = terminalSession.linkMatcherAt(hit.screenPoint(), getLinkMatchers());
        if (match == null || match.selection().isEmpty()) {
            return null;
        }

        return new ActiveLink(
                new ActiveLink.Regex(match.index(), match.match().group()),
                match.selection(),
                () -> match.link().action().accept(match.match()));
    }

    private void refreshHover(MouseEvent event) {
        var scrollbar = scrollbarInfo();
        setScrollbarHovered(isInScrollbar(event.getX())
                && event.getY() >= 0
                && event.getY() <= getHeight()
                && scrollbar != null
                && scrollbar.scrollable());
        var pressGesture = mouseInputState.pressGesture();
        if (pressGesture != null && pressGesture.button() == TerminalSession.MouseButton.LEFT) {
            var hit = contentHit(event);
            if (hit == null || !pressGesture.anchor().equals(hit.screenPoint())) {
                clearHover(true);
                return;
            }
        }

        var hit = contentHit(event);
        if (hit == null) {
            clearHover(true);
            return;
        }

        var nextHover = activeLink(hit);
        if (nextHover == null) {
            clearHover(true);
            return;
        }

        if (hoveredLink == null || !hoveredLink.sameTarget(nextHover)) {
            hoveredLink = nextHover;
            redraw();
        }
        setCursor(Cursor.HAND);
    }

    private void setScrollbarHovered(boolean scrollbarHovered) {
        var nextMouseInputState = MouseInput.setScrollbarHovered(mouseInputState, scrollbarHovered);
        if (!nextMouseInputState.equals(mouseInputState)) {
            mouseInputState = nextMouseInputState;
            redraw();
        }
    }

    private void stopScrollbarDrag() {
        var nextMouseInputState = MouseInput.stopScrollbarDrag(mouseInputState);
        if (!nextMouseInputState.equals(mouseInputState)) {
            mouseInputState = nextMouseInputState;
            redraw();
        }
    }

    private void clearHover(boolean redraw) {
        var changed = hoveredLink != null || getCursor() != Cursor.DEFAULT;
        hoveredLink = null;
        setCursor(Cursor.DEFAULT);
        if (changed && redraw) {
            redraw();
        }
    }

    private void updateSelectionAutoscroll() {
        if (selectionDrag == null || terminalSession.selectionGestureAutoscroll() == TerminalSession.SelectionAutoscroll.NONE) {
            selectionAutoscroll.stop();
            return;
        }

        selectionAutoscroll.start();
    }

    private void stopSelectionAutoscroll() {
        selectionDrag = null;
        selectionAutoscroll.stop();
    }

    private void handleSelectionAutoscroll() {
        var drag = selectionDrag;
        if (drag == null) {
            selectionAutoscroll.stop();
            return;
        }

        var hit = terminalSession.clampedHitTest(
                drag.x(),
                drag.y(),
                getWidth(),
                getHeight(),
                fontMetrics.get(),
                scrollbarReservedWidthPx());
        if (hit == null) {
            selectionAutoscroll.stop();
            return;
        }

        terminalSession.selectionGestureAutoscrollTick(hit, drag.x(), drag.y(), drag.rectangle(), fontMetrics.get(), getHeight());
        if (terminalSession.selectionGestureAutoscroll() == TerminalSession.SelectionAutoscroll.NONE) {
            selectionAutoscroll.stop();
        }
        redraw();
    }

    private void writeReportedMousePress(MouseEvent event) {
        writeBytes(terminalSession.encodeMousePress(
                toTerminalMouseButton(event.getButton()),
                event.getX(),
                event.getY(),
                eventModifiers(event),
                getWidth(),
                getHeight(),
                fontMetrics.get(),
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
                fontMetrics.get(),
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
                fontMetrics.get(),
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
        return HostPlatform.CURRENT.os() == HostPlatform.OS.MACOS
                ? event.isAltDown()
                : event.isAltDown() && (event.isControlDown() || event.isMetaDown());
    }

    private static void openHyperlink(String hyperlinkUri) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(hyperlinkUri));
            }
        } catch (Exception _) {
        }
    }

    private static void openBuiltInWebPageUrl(String text) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return;
            }

            var desktop = Desktop.getDesktop();
            var uri = URI.create(text);
            var scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(uri);
            }
        } catch (Exception _) {
        }
    }

    private boolean handleTerminalShortcut(KeyEvent event) {
        for (var shortcut : getTerminalShortcuts()) {
            if (shortcut.combination().match(event) && shortcut.action().getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    /// Copies the current selection to the system clipboard.
    ///
    /// @return `true` if there was a selection to copy; otherwise `false`
    public boolean copySelection() {
        if (!terminalSession.hasSelection()) {
            return false;
        }

        var content = new ClipboardContent();
        content.putString(selectedText());
        if (Clipboard.getSystemClipboard().setContent(content)) {
            clearSelection();
        }
        return true;
    }

    /// Pastes the system clipboard text into the terminal.
    ///
    /// @return `true` if clipboard text was sent; otherwise `false`
    public boolean pasteClipboard() {
        var text = Clipboard.getSystemClipboard().getString();
        if (text == null || text.isEmpty()) {
            return false;
        }

        writeBytes(terminalSession.encodePaste(text, terminalSession.readMode(BRACKETED_PASTE_MODE)));
        clearSelection();
        return true;
    }

    /// Sends an escape-prefixed text sequence to the terminal.
    ///
    /// @param text the text to send after the escape character
    /// @return `true` if bytes were sent; otherwise `false`
    /// @throws NullPointerException if `text` is `null`
    public boolean sendEsc(String text) {
        Objects.requireNonNull(text, "text");
        return sendText("\u001B" + text);
    }

    /// Sends text to the terminal as UTF-8 bytes.
    ///
    /// @param text the text to send
    /// @return `true` if bytes were sent; otherwise `false`
    /// @throws NullPointerException if `text` is `null`
    public boolean sendText(String text) {
        Objects.requireNonNull(text, "text");
        if (!writeBytes(text.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        clearSelection();
        terminalSession.scrollViewportToBottom();
        redraw();
        return true;
    }

    /// Selects all terminal text.
    ///
    /// @return `true`
    public boolean selectAll() {
        clearHover(false);
        terminalSession.selectAll();
        redraw();
        return true;
    }

    /// Extends the current selection one cell to the left.
    ///
    /// @return `true` if there was a selection to extend; otherwise `false`
    public boolean extendSelectionLeft() {
        return adjustSelection(ghostty_vt_h.GHOSTTY_SELECTION_ADJUST_LEFT());
    }

    /// Extends the current selection one cell to the right.
    ///
    /// @return `true` if there was a selection to extend; otherwise `false`
    public boolean extendSelectionRight() {
        return adjustSelection(ghostty_vt_h.GHOSTTY_SELECTION_ADJUST_RIGHT());
    }

    /// Extends the current selection one row up.
    ///
    /// @return `true` if there was a selection to extend; otherwise `false`
    public boolean extendSelectionUp() {
        return adjustSelection(ghostty_vt_h.GHOSTTY_SELECTION_ADJUST_UP());
    }

    /// Extends the current selection one row down.
    ///
    /// @return `true` if there was a selection to extend; otherwise `false`
    public boolean extendSelectionDown() {
        return adjustSelection(ghostty_vt_h.GHOSTTY_SELECTION_ADJUST_DOWN());
    }

    /// Extends the current selection one viewport page up.
    ///
    /// @return `true` if there was a selection to extend; otherwise `false`
    public boolean extendSelectionPageUp() {
        return adjustSelection(ghostty_vt_h.GHOSTTY_SELECTION_ADJUST_PAGE_UP());
    }

    /// Extends the current selection one viewport page down.
    ///
    /// @return `true` if there was a selection to extend; otherwise `false`
    public boolean extendSelectionPageDown() {
        return adjustSelection(ghostty_vt_h.GHOSTTY_SELECTION_ADJUST_PAGE_DOWN());
    }

    /// Extends the current selection to the top-left cell of the terminal screen.
    ///
    /// @return `true` if there was a selection to extend; otherwise `false`
    public boolean extendSelectionHome() {
        return adjustSelection(ghostty_vt_h.GHOSTTY_SELECTION_ADJUST_HOME());
    }

    /// Extends the current selection to the right edge of the last non-blank terminal row.
    ///
    /// @return `true` if there was a selection to extend; otherwise `false`
    public boolean extendSelectionEnd() {
        return adjustSelection(ghostty_vt_h.GHOSTTY_SELECTION_ADJUST_END());
    }

    /// Scrolls the viewport one page up.
    ///
    /// @return `true` if scrolling was available; otherwise `false`
    public boolean scrollViewportPageUp() {
        return scrollViewportByRows(-viewportRowCount());
    }

    /// Scrolls the viewport one page down.
    ///
    /// @return `true` if scrolling was available; otherwise `false`
    public boolean scrollViewportPageDown() {
        return scrollViewportByRows(viewportRowCount());
    }

    /// Scrolls the viewport to the top of the scrollback.
    ///
    /// @return `true` if scrolling was available; otherwise `false`
    public boolean scrollViewportToTop() {
        var scrollbar = scrollbarInfo();
        if (!viewportScrollAvailable(scrollbar)) {
            return false;
        }
        if (scrollbar.offset() == 0) {
            return true;
        }
        scrollViewportTo(0);
        return true;
    }

    /// Scrolls the viewport to the bottom of the scrollback.
    ///
    /// @return `true` if scrolling was available; otherwise `false`
    public boolean scrollViewportToBottom() {
        var scrollbar = scrollbarInfo();
        if (!viewportScrollAvailable(scrollbar)) {
            return false;
        }
        if (scrollbar.offset() == scrollbar.scrollableRows()) {
            return true;
        }
        scrollViewportTo(scrollbar.scrollableRows());
        return true;
    }

    /// Scrolls the viewport to the previous shell prompt.
    ///
    /// @return `true` if a prompt target was found; otherwise `false`
    public boolean scrollViewportToPreviousPrompt() {
        return scrollViewportToPrompt(-1);
    }

    /// Scrolls the viewport to the next shell prompt.
    ///
    /// @return `true` if a prompt target was found; otherwise `false`
    public boolean scrollViewportToNextPrompt() {
        return scrollViewportToPrompt(1);
    }

    /// Opens search, or closes it if it is already open.
    ///
    /// @return `true`
    public boolean toggleSearch() {
        if (searchUi.visible()) {
            closeSearch();
            return true;
        }

        var selected = terminalSession.hasSelection() ? selectedText() : "";
        clearSelection();
        searchUi.open(selected);
        return true;
    }

    /// Closes search.
    ///
    /// @return `true` if search was open; otherwise `false`
    public boolean closeSearch() {
        if (!searchUi.visible()) {
            return false;
        }
        searchUi.close();
        requestFocus();
        redraw();
        return true;
    }

    /// Selects the next search match.
    ///
    /// @return `true` if the selected match changed; otherwise `false`
    public boolean searchNext() {
        return searchUi.selectNext(true);
    }

    /// Selects the previous search match.
    ///
    /// @return `true` if the selected match changed; otherwise `false`
    public boolean searchPrevious() {
        return searchUi.selectPrevious(true);
    }

    String searchText() {
        return searchUi.text();
    }

    int searchMatchCount() {
        return searchUi.matchCount();
    }

    int selectedSearchMatchIndex() {
        return searchUi.selectedMatch();
    }

    private boolean scrollViewportByRows(long deltaRows) {
        var scrollbar = scrollbarInfo();
        if (!viewportScrollAvailable(scrollbar) || deltaRows == 0) {
            return false;
        }

        var nextOffset = Math.clamp(scrollbar.offset() + deltaRows, 0, scrollbar.scrollableRows());
        if (nextOffset == scrollbar.offset()) {
            return true;
        }

        scrollViewportTo(nextOffset);
        return true;
    }

    private boolean scrollViewportToPrompt(int direction) {
        var scrollbar = scrollbarInfo();
        if (scrollbar != null && scrollbar.visible() > 0) {
            var anchor = promptNavigationAnchor(direction, scrollbar);
            if (anchor < 0 && direction < 0) {
                return false;
            }

            var target = direction < 0
                    ? terminalSession.promptRowBefore(anchor)
                    : terminalSession.promptRowAfter(anchor);
            if (target < 0) {
                if (!terminalSession.isPromptRow(anchor)) {
                    return false;
                }
                target = Math.toIntExact(anchor);
            }

            if (!rowDisplayed(target, scrollbar) && scrollbar.scrollable()) {
                scrollViewportTo(target);
            }
            promptNavigationRow = target;
            showPromptNavigationHighlight(target);
            return true;
        }
        return false;
    }

    private long promptNavigationAnchor(int direction, TerminalSession.ScrollbarInfo scrollbar) {
        if (promptNavigationRow >= 0 && rowDisplayed(promptNavigationRow, scrollbar)) {
            return promptNavigationRow;
        }
        if (promptNavigationHighlightRow >= 0 && rowDisplayed(promptNavigationHighlightRow, scrollbar)) {
            return promptNavigationHighlightRow;
        }

        var cursorPromptRow = cursorPromptRow(scrollbar);
        if (cursorPromptRow < 0) {
            return initialPromptNavigationAnchor(direction, scrollbar);
        }
        return cursorPromptRow;
    }

    private long cursorPromptRow(TerminalSession.ScrollbarInfo scrollbar) {
        var cursorLocation = currentCursorLocation();
        if (cursorLocation == null) {
            return -1;
        }

        var row = scrollbar.offset() + cursorLocation.cellY();
        return terminalSession.isPromptRow(row) ? row : -1;
    }

    private static long initialPromptNavigationAnchor(int direction, TerminalSession.ScrollbarInfo scrollbar) {
        return direction < 0
                ? scrollbar.offset() + scrollbar.visible()
                : scrollbar.offset() - 1;
    }

    private static boolean rowDisplayed(long row, TerminalSession.ScrollbarInfo scrollbar) {
        return row >= scrollbar.offset() && row < scrollbar.offset() + scrollbar.visible();
    }

    private void showPromptNavigationHighlight(int row) {
        promptNavigationHighlightRow = row;
        updatePromptNavigationHighlightTimeline();
        redraw();
    }

    private void clearPromptNavigationHighlight() {
        if (promptNavigationHighlightRow < 0) {
            return;
        }
        promptNavigationHighlightRow = -1;
        redraw();
    }

    private boolean searchMatchesAffectViewport(List<Selection> matches) {
        var scrollbar = scrollbarInfo();
        if (scrollbar == null || scrollbar.visible() <= 0) {
            return !matches.isEmpty();
        }

        var viewportTop = scrollbar.offset();
        var viewportBottom = viewportTop + scrollbar.visible() - 1;
        for (var match : matches) {
            var normalized = match.normalized();
            if (normalized.from().y() <= viewportBottom && normalized.to().y() >= viewportTop) {
                return true;
            }
        }
        return false;
    }

    private void scrollSearchMatchIntoView(Selection match) {
        var scrollbar = scrollbarInfo();
        if (scrollbar == null || scrollbar.visible() <= 0) {
            return;
        }

        var row = match.normalized().from().y();
        var viewportTop = scrollbar.offset();
        var viewportBottom = viewportTop + scrollbar.visible() - 1;
        if (row < viewportTop) {
            scrollViewportTo(row);
        } else if (row > viewportBottom) {
            scrollViewportTo(row - scrollbar.visible() + 1);
        }
    }

    private boolean viewportScrollAvailable(TerminalSession.ScrollbarInfo scrollbar) {
        return scrollbar != null && scrollbar.scrollable();
    }

    private boolean adjustSelection(int adjustment) {
        if (!terminalSession.adjustSelection(adjustment)) {
            return false;
        }

        clearHover(false);
        scrollSelectionFocusIntoView();
        redraw();
        return true;
    }

    private void scrollSelectionFocusIntoView() {
        var nextFocus = terminalSession.selectionFocus();
        if (nextFocus == null) {
            return;
        }

        var scrollbar = scrollbarInfo();
        if (scrollbar == null || scrollbar.visible() <= 0) {
            return;
        }

        var viewportTop = scrollbar.offset();
        var viewportBottom = viewportTop + scrollbar.visible() - 1;
        if (nextFocus.y() < viewportTop) {
            scrollViewportTo(nextFocus.y());
        } else if (nextFocus.y() > viewportBottom) {
            scrollViewportTo(nextFocus.y() - scrollbar.visible() + 1);
        }
    }

    private void clearSelection() {
        if (terminalSession.clearSelection()) {
            clearHover(false);
            terminalSession.resetSelectionGesture();
            stopSelectionAutoscroll();
            redraw();
        }
    }

    private void applySearchTheme() {
        searchUi.applyTheme(getTheme());
    }

    private boolean applyTransition(KeyInput.Transition transition) {
        var previousKeyInputState = keyInputState;
        var hadSelection = terminalSession.hasSelection();
        keyInputState = transition.state();
        if (transition.clearSelection()) {
            clearSelection();
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
            resetCursorBlink();
            terminalSession.scrollViewportToBottom();
        }

        var redraw = transition.redraw()
                || wroteToPty
                || hadSelection != terminalSession.hasSelection()
                || !previousKeyInputState.preedit().equals(keyInputState.preedit());
        if (redraw) {
            redraw();
        }
        return wroteToPty || redraw || !previousKeyInputState.equals(keyInputState) || hadSelection != terminalSession.hasSelection();
    }

    private String selectedText() {
        return terminalSession.selectedText();
    }

    private KeyInput.KeySnapshot snapshot(KeyEvent event) {
        return new KeyInput.KeySnapshot(event.getCode(), event.isShiftDown(), event.isControlDown(), event.isAltDown(), event.isMetaDown());
    }

    private void redraw() {
        var event = JfrEvents.redraw();
        var width = getWidth();
        var height = getHeight();
        if (event != null) {
            event.width = (int) Math.round(width);
            event.height = (int) Math.round(height);
            event.begin();
        }
        try {
            if (width <= 0 || height <= 0) {
                return;
            }
            canvas.resize(width, height);

            blinkState = terminalSession.render(
                canvas.getGraphicsContext2D(),
                width,
                height,
                fontMetrics.get(),
                keyInputState.preedit(),
                hoveredLink == null ? Selection.empty() : hoveredLink.selection(),
                getLinkMatchers(),
                searchUi.visible() ? searchUi.result() : TerminalSession.SearchResult.empty(),
                searchUi.visible() ? searchUi.selectedMatch() : -1,
                isFocused(),
                getTheme(),
                cursorBlinkVisible,
                textBlinkVisible,
                mouseInputState.scrollbarHovered() || mouseInputState.scrollbarDragging(),
                    scrollbarReservedWidthPx(),
                    MIN_SCROLLBAR_HEIGHT_PX,
                    promptNavigationHighlightRow);
        } finally {
            if (event != null) {
                event.commit();
            }
        }
    }

    private void resetCursorBlink() {
        cursorBlinkVisible = true;
        updateCursorBlinkTimeline();
    }

    private void resetPromptNavigation() {
        promptNavigationRow = -1;
        promptNavigationHighlightRow = -1;
        promptNavigationHighlightTimeline.stop();
    }

    private void updateCursorBlinkTimeline() {
        if (getScene() != null && isCursorBlinking()) {
            cursorBlinkTimeline.playFromStart();
        } else {
            cursorBlinkTimeline.stop();
        }
    }

    private void updateBlinkTimelines() {
        if (getScene() == null) {
            cursorBlinkTimeline.stop();
            textBlinkTimeline.stop();
            return;
        }

        updateCursorBlinkTimeline();
        textBlinkTimeline.play();
    }

    private void updatePromptNavigationHighlightTimeline() {
        if (getScene() != null && promptNavigationHighlightRow >= 0) {
            promptNavigationHighlightTimeline.playFromStart();
        } else {
            promptNavigationHighlightTimeline.stop();
        }
    }

    private void tickCursorBlink() {
        if (!blinkState.cursor()) {
            if (!cursorBlinkVisible) {
                cursorBlinkVisible = true;
                redraw();
            }
            return;
        }

        cursorBlinkVisible = !cursorBlinkVisible;
        redraw();
    }

    private void tickTextBlink() {
        if (!blinkState.text()) {
            if (!textBlinkVisible) {
                textBlinkVisible = true;
                redraw();
            }
            return;
        }

        textBlinkVisible = !textBlinkVisible;
        redraw();
    }

    private CursorLocation currentCursorLocation() {
        return terminalSession.currentCursorLocation(fontMetrics.get());
    }

    private record Cleanup(TerminalSession terminalSession, PtySession ptySession) implements Runnable {
        @Override
        public void run() {
            try (terminalSession; ptySession) {
            }
        }
    }

    private static final class ProcessOutputDrain extends AnimationTimer {

        private final WeakReference<TerminalView> terminalRef;
        private final AtomicBoolean drainScheduled = new AtomicBoolean();
        private boolean redrawPending;
        private boolean closed;

        private ProcessOutputDrain(TerminalView terminal) {
            terminalRef = new WeakReference<>(terminal);
        }

        private void requestDrain(PtySession ptySession) {
            if (drainScheduled.compareAndSet(false, true)) {
                Platform.runLater(() -> drain(ptySession));
            }
        }

        private void drain(PtySession ptySession) {
            // Output arriving during this drain schedules one follow-up; everything already queued is drained below.
            drainScheduled.set(false);
            var terminal = terminalRef.get();
            if (terminal == null) {
                return;
            }

            var outputs = ptySession.pollProcessOutputs();
            if (outputs.isEmpty()) {
                return;
            }

            var totalBytes = 0;
            for (var output : outputs) {
                if (output instanceof PtySession.Chunk(var bytes)) {
                    totalBytes += bytes.length;
                }
            }

            if (totalBytes != 0) {
                var event = JfrEvents.ptyDrain();
                if (event != null) {
                    event.bytes = totalBytes;
                    event.chunks = outputs.size();
                    event.begin();
                }
                var bytes = new byte[totalBytes];
                var offset = 0;
                for (var output : outputs) {
                    if (output instanceof PtySession.Chunk(var chunk)) {
                        System.arraycopy(chunk, 0, bytes, offset, chunk.length);
                        offset += chunk.length;
                    }
                }
                terminal.resetCursorBlink();
                terminal.resetPromptNavigation();
                terminal.terminalSession.writeToTerminal(bytes);
                redrawPending = true;
                if (event != null) {
                    event.commit();
                }
            }

            if (outputs.getLast() instanceof PtySession.Closed(var state)) {
                terminal.terminalState.set(state);
                closed = true;
            }
        }

        @Override
        public void handle(long now) {
            var terminal = terminalRef.get();
            if (terminal == null) {
                stop();
                return;
            }

            if (redrawPending) {
                redrawPending = false;
                if (terminal.searchUi.visible()) {
                    terminal.searchUi.refresh();
                } else {
                    terminal.redraw();
                }
            }

            if (closed) {
                stop();
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

    private final class TerminalInputMethodRequests implements InputMethodRequests {

        @Override
        public Point2D getTextLocation(int offset) {
            var cursorLocation = currentCursorLocation();
            if (cursorLocation == null) {
                return new Point2D(0, 0);
            }

            var codePointCount = keyInputState.preedit().text().codePointCount(0, keyInputState.preedit().text().length());
            var clampedOffset = Math.clamp(offset, 0, codePointCount);
            var metrics = fontMetrics.get();
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
            return Math.clamp((int) Math.floor(dx / fontMetrics.get().cellWidthPx()), 0, codePointCount);
        }

        @Override
        public void cancelLatestCommittedText() {
        }

        @Override
        public String getSelectedText() {
            return selectedText();
        }
    }

    // A nested holder keeps installed-font enumeration lazy: the JVM initializes it only when a reported family
    // cannot be used and reverse lookup is first needed.
    private static final class FontIndex {

        private static final Map<String, List<String>> LOOKUP_FAMILIES_BY_FACE_NAME = build();

        private FontIndex() {
        }

        private static List<String> lookupFamilies(String faceName) {
            return LOOKUP_FAMILIES_BY_FACE_NAME.getOrDefault(faceName, List.of());
        }

        private static Map<String, List<String>> build() {
            var index = new HashMap<String, List<String>>();
            for (var family : Font.getFamilies()) {
                for (var faceName : Font.getFontNames(family)) {
                    index.computeIfAbsent(faceName, _ -> new ArrayList<>()).add(family);
                }
            }
            return index;
        }
    }

    static record FontMetrics(
            Font regular,
            Font bold,
            Font italic,
            Font boldItalic,
            int cellWidthPx,
            int cellHeightPx,
            int baselineOffsetPx) {

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

    static record CursorLocation(int cellX, int cellY, double pixelX, double pixelY) {

    }

    private record SelectionDrag(double x, double y, boolean rectangle) {

    }

    private static final class ResizableCanvas extends Canvas {

        private final DoubleSupplier prefWidth;
        private final DoubleSupplier prefHeight;

        private ResizableCanvas(DoubleSupplier prefWidth, DoubleSupplier prefHeight) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
        }

        @Override
        public boolean isResizable() {
            return true;
        }

        @Override
        public double prefWidth(double height) {
            return prefWidth.getAsDouble();
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
            return prefHeight.getAsDouble();
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

    }

    private boolean writeBytes(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        writeCommand(new PtySession.WriteInput(bytes));
        return true;
    }

}
