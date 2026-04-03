# GhosttyFX Core API Proposal

## Goals

- Define a generic Java 25 core API over `libghostty-vt`.
- Hide all `jextract` types, native memory, and platform layout differences.
- Keep native mutable state confined to one internal terminal thread.
- Expose a thread-safe wrapper to callers.
- Use records for immutable data and interfaces for public capabilities.
- Stay UI-agnostic. No JavaFX concerns in this layer.

## Design Summary

The core module should expose two public families:

- terminal state and control
- host-side input/output codecs

The central object is a thread-safe `TerminalSession`. Internally it owns a single-threaded native actor that serializes all interaction with `GhosttyTerminal`, `GhosttyKeyEncoder`, and `GhosttyMouseEncoder`. Public methods may be called from any thread. They marshal to the internal actor and return either:

- immediate immutable data
- completion after blocking the calling thread until the actor finishes the operation

The wrapper is intentionally blocking. The intended usage model is virtual threads at the application boundary, not `CompletableFuture` in the core API. The wrapper remains thread-safe; the implementation stays single-threaded internally.

Libghostty notifications should never invoke user code on the native actor thread. The wrapper should dispatch notifications onto an internal callback executor backed by virtual threads.

Host query callbacks are different: `TerminalQueries` are synchronous and run inline on the native actor thread because libghostty expects an immediate answer for enquiry, XTVERSION, size-report, and color-scheme requests.

That gives safe reentrancy for notifications: listeners may call back into `TerminalSession` without touching mutable native state directly. `TerminalQueries` must not call back into `TerminalSession` and should return quickly, ideally from already-available state.

## Module Shape

Suggested package root:

```java
io.github.vlaaad.ghostty
```

Suggested modules:

- `ghostty-vt-core`
- `ghostty-vt-linux-x64`
- `ghostty-vt-macos-x64`
- `ghostty-vt-macos-arm64`
- `ghostty-vt-windows-x64`

`ghostty-vt-core` contains only handwritten interfaces, records, enums, and shared wrapper logic contracts.
Platform modules are discovered internally by the facade and are not part of the public bootstrap API.

## Public Entry Point

```java
package io.github.vlaaad.ghostty;

public final class Ghostty {
    public static TerminalSession open(
        TerminalConfig config,
        PtyWriter ptyWriter,
        TerminalQueries queries,
        TerminalEvents events
    );

    public static KeyCodec keyCodec(KeyCodecConfig config);
    public static MouseCodec mouseCodec(MouseCodecConfig config);
    public static PasteCodec pasteCodec();
    public static FocusCodec focusCodec();
    public static SizeReportCodec sizeReportCodec();
    public static BuildInfo buildInfo();
    public static TypeSchema typeSchema();
}
```

Rationale:

- `Ghostty` is the only public bootstrap type.
- Platform service discovery happens inside `Ghostty`.
- Sessions and codecs remain public capabilities, but runtime/provider abstractions do not.

## Session API

```java
package io.github.vlaaad.ghostty;

import java.util.Optional;

public interface TerminalSession extends AutoCloseable {
    TerminalConfig config();
    TerminalSnapshot snapshot();

    void resize(TerminalSize size);
    void write(byte[] vt);
    void write(byte[] vt, int offset, int length);
    void reset();

    void setMode(TerminalMode mode, boolean enabled);
    Optional<Boolean> mode(TerminalMode mode);

    void setColorScheme(ColorScheme scheme);
    void setWindowTitle(String title);
    void setWorkingDirectory(String pwd);
    void setForeground(ColorValue color);
    void setBackground(ColorValue color);
    void setCursorColor(ColorValue color);
    void setPalette(ColorPalette palette);

    void scrollToTop();
    void scrollToBottom();
    void scrollBy(long delta);
    void scrollViewport(TerminalScrollViewport behavior);

    Optional<CellSnapshot> cell(Point point);
    Optional<RowSnapshot> row(long rowIndex, RowCoordinateSpace space);
    ScreenSnapshot screen(ScreenKind screen);
    Optional<DeviceAttributes> deviceAttributes();

    @Override
    void close();
}
```

Notes:

- Public API exposes immutable reads, not live native references.
- `snapshot()` is the main read model. Convenience reads like `cell`, `row`, and `screen` can be derived from a current snapshot internally.

## Threading Contract

Public contract:

- all public interfaces are thread-safe unless documented otherwise
- listener callbacks are serialized per session
- listener callbacks never run on the internal native actor thread
- listeners may call `snapshot()` or other session methods

Internal contract:

- every `TerminalSession` owns one single-threaded native actor
- every mutable native handle is actor-confined
- immutable Java snapshots are copied before publication

## Construction Types

Use builder-style factories instead of telescoping constructors where null handling matters:

```java
package io.github.vlaaad.ghostty;

public record TerminalConfig(
    int columns,
    int rows,
    long maxScrollback
) {}
```

```java
package io.github.vlaaad.ghostty;

public interface PtyWriter {
    void writePty(byte[] data);
}
```

```java
package io.github.vlaaad.ghostty;

import java.util.Optional;

public interface TerminalQueries {
    default String enquiryReply() { return ""; }
    default String xtversionReply() { return ""; }
    default Optional<TerminalSize> sizeReportValue() { return Optional.empty(); }
    default Optional<ColorScheme> colorSchemeValue() { return Optional.empty(); }
    default Optional<DeviceAttributes> deviceAttributesValue() { return Optional.empty(); }
}
```

`TerminalQueries` contract:

- methods are synchronous and must return inline on the native actor thread
- implementations should be fast and non-blocking in practice, even though they are synchronous by contract
- implementations must not call back into `TerminalSession`
- if query data is produced asynchronously elsewhere, cache the latest value and return that cached value here

```java
package io.github.vlaaad.ghostty;

public interface TerminalEvents {
    default void bell(TerminalSession session) {}
    default void titleChanged(TerminalSession session, String title) {}
    default void stateChanged(TerminalSession session) {}
}
```

Design choice:

- the three callback categories stay separate because they have different semantics:
  terminal-to-host output, host-to-terminal queries, and wrapper notifications
- `titleChanged` carries the resolved immutable title string because libghostty only
  signals that the title changed; the wrapper reads the new title before dispatch

## Snapshot Model

```java
package io.github.vlaaad.ghostty;

public record TerminalSnapshot(
    TerminalSize size,
    CursorSnapshot cursor,
    ScreenKind activeScreen,
    boolean cursorVisible,
    boolean cursorPendingWrap,
    MouseTrackingMode mouseTracking,
    KittyKeyboardFlags kittyKeyboardFlags,
    TerminalScrollbar scrollbar,
    String title,
    String workingDirectory,
    long totalRows,
    long scrollbackRows,
    ThemeSnapshot theme,
    ScreenSnapshot primary,
    ScreenSnapshot alternate
) {}

public record CursorSnapshot(
    Point position,
    boolean visible,
    boolean pendingWrap,
    StyleSnapshot style
) {}
```

```java
package io.github.vlaaad.ghostty;

public record ScreenSnapshot(
    ScreenKind kind,
    int columns,
    int rows,
    java.util.List<RowSnapshot> visibleRows
) {}
```

```java
package io.github.vlaaad.ghostty;

public record RowSnapshot(
    long index,
    RowFlags flags,
    RowSemanticPrompt semanticPrompt,
    java.util.List<CellSnapshot> cells
) {}
```

```java
package io.github.vlaaad.ghostty;

public record CellSnapshot(
    int column,
    String text,
    int codePoint,
    CellContentTag contentTag,
    CellWidth width,
    StyleSnapshot style,
    Hyperlink hyperlink,
    CellSemantic semantic,
    boolean protectedCell
) {}
```

Important rule:

- snapshots are detached immutable values
- no public `GridRef`, native pointer, or cursor object leaks out

`Point` should remain generic and not expose libghostty unions:

```java
package io.github.vlaaad.ghostty;

public sealed interface Point permits ActivePoint, ViewportPoint, ScreenPoint, HistoryPoint {}

public record ActivePoint(int column, int row) implements Point {}
public record ViewportPoint(int column, int row) implements Point {}
public record ScreenPoint(int column, int row) implements Point {}
public record HistoryPoint(int column, long row) implements Point {}

public sealed interface TerminalScrollViewport permits 
    ScrollViewportTop, 
    ScrollViewportBottom, 
    ScrollViewportDelta {}

public record ScrollViewportTop() implements TerminalScrollViewport {}
public record ScrollViewportBottom() implements TerminalScrollViewport {}
public record ScrollViewportDelta(long delta) implements TerminalScrollViewport {}
```

This is clearer than mirroring `GhosttyPointTag` plus a union record.

## Theme, Style, and String Types

```java
package io.github.vlaaad.ghostty;

public sealed interface ColorValue permits DefaultColor, PaletteColor, RgbColor {}

public record DefaultColor() implements ColorValue {}
public record PaletteColor(int index) implements ColorValue {}
public record RgbColor(int red, int green, int blue) implements ColorValue {}
```

```java
package io.github.vlaaad.ghostty;

public record TerminalScrollbar(
    long total,
    long offset,
    long length
) {}
```

```java
package io.github.vlaaad.ghostty;

public record ThemeSnapshot(
    ColorValue foreground,
    ColorValue background,
    ColorValue cursor,
    ColorPalette palette,
    ColorValue defaultForeground,
    ColorValue defaultBackground,
    ColorValue defaultCursor,
    ColorPalette defaultPalette
) {}
```

```java
package io.github.vlaaad.ghostty;

public record StyleSnapshot(
    ColorValue foreground,
    ColorValue background,
    boolean bold,
    boolean faint,
    boolean italic,
    boolean underline,
    boolean blink,
    boolean inverse,
    boolean invisible,
    boolean strikethrough
) {}
```

For strings originating from native `GhosttyString`, always convert to Java `String` or `byte[]` in the platform layer. Never surface pointer-backed string wrappers publicly.

## Input Codec APIs

The non-UI generic API should include standalone codecs because they are part of terminal semantics, not rendering.

### Key

```java
package io.github.vlaaad.ghostty;

public interface KeyCodec {
    byte[] encode(KeyEvent event);
}
```

```java
package io.github.vlaaad.ghostty;

public record KeyCodecConfig(
    boolean cursorKeyApplication,
    boolean keypadKeyApplication,
    boolean ignoreKeypadWithNumLock,
    boolean altEscPrefix,
    boolean modifyOtherKeysState2,
    KittyKeyboardFlags kittyFlags,
    OptionAsAlt optionAsAlt
) {}
```

```java
package io.github.vlaaad.ghostty;

public record KeyEvent(
    KeyAction action,
    Key key,
    KeyModifiers modifiers,
    KeyModifiers consumedModifiers,
    boolean composing,
    String utf8,
    int unshiftedCodePoint
) {}
```

### Mouse

```java
package io.github.vlaaad.ghostty;

public interface MouseCodec {
    byte[] encode(MouseEvent event, MouseEncodeContext context);
}
```

```java
package io.github.vlaaad.ghostty;

public record MouseEncodeContext(
    MouseFormat format,
    MouseTrackingMode trackingMode,
    TerminalSize size,
    boolean anyButtonPressed,
    boolean trackLastCell
) {}
```

```java
package io.github.vlaaad.ghostty;

public record MouseEvent(
    MouseAction action,
    MouseButton button,
    KeyModifiers modifiers,
    PixelPoint position
) {}
```

### Paste / Focus / Size Report

```java
package io.github.vlaaad.ghostty;

public interface PasteCodec {
    boolean isSafe(byte[] data);
    byte[] encode(byte[] data, boolean bracketed);
}

public interface FocusCodec {
    byte[] encode(FocusEvent event);
}

public record FocusEvent(
    FocusAction action
) {}

public enum FocusAction {
    IN,
    OUT
}

public interface SizeReportCodec {
    byte[] encode(SizeReportRequest request, SizeReport size);
}
```

## Small Value Types

```java
package io.github.vlaaad.ghostty;

public record TerminalSize(
    int columns,
    int rows,
    int cellWidthPx,
    int cellHeightPx
) {}

public record PixelPoint(int x, int y) {}

public record SizeReport(int widthPx, int heightPx, int columns, int rows) {}

public record Hyperlink(String uri) {}

public record BuildInfo(
    String version,
    int major,
    int minor,
    int patch,
    int build,
    String optimize,
    boolean simd,
    boolean kittyGraphics,
    boolean tmuxControlMode
) {}

public record TypeSchema(String json) {}
```

```java
package io.github.vlaaad.ghostty;

public enum ColorScheme {
    DARK,
    LIGHT
}
```

## Enums and Flags

Use Java enums and records, not raw ints and shorts:

- `ScreenKind`
- `ColorScheme`
- `TerminalMode`
- `MouseTrackingMode`
- `MouseFormat`
- `MouseAction`
- `MouseButton`
- `KeyAction`
- `Key`
- `OptionAsAlt`
- `CellWidth`
- `CellSemantic`

Here are the enum values from the native bindings:

```java
// ScreenKind - Terminal screen identifier
public enum ScreenKind {
    PRIMARY,      // The primary (normal) screen
    ALTERNATE     // The alternate screen
}

// TerminalMode - Terminal modes (DEC private modes)
// Note: These correspond to packed 16-bit values from ghostty_mode_new()
// Bit 15: 1 = ANSI mode, 0 = DEC private mode
// Bits 0-14: mode value
public enum TerminalMode {
    // ANSI Modes (bit 15 = 1)
    KAM(2, true),                        // Keyboard action mode
    INSERT(4, true),                     // Insert mode
    SRM(12, true),                       // Send/receive mode
    LINEFEED(20, true),                  // Linefeed/new line mode
    
    // DEC Private Modes (bit 15 = 0)
    DECCKM(1, false),                    // Cursor keys
    COLUMNS_132(3, false),               // 132/80 column mode
    SMOOTH_SCROLL(4, false),             // Slow scroll
    REVERSE_VIDEO(5, false),             // Reverse video
    ORIGIN(6, false),                    // Origin mode
    AUTO_WRAP(7, false),                 // Auto-wrap mode
    AUTO_REPEAT(8, false),               // Auto-repeat keys
    X10_MOUSE(9, false),                 // X10 mouse reporting
    CURSOR_BLINKING(12, false),           // Cursor blink
    CURSOR_VISIBLE(25, false),           // Cursor visible (DECTCEM)
    ENABLE_MODE_3(40, false),            // Allow 132 column mode
    REVERSE_WRAP(45, false),             // Reverse wrap
    ALT_SCREEN_LEGACY(47, false),        // Alternate screen (legacy)
    KEYPAD_KEYS(66, false),              // Application keypad
    LEFT_RIGHT_MARGIN(69, false),        // Left/right margin mode
    NORMAL_MOUSE(1000, false),           // Normal mouse tracking
    BUTTON_MOUSE(1002, false),           // Button-event mouse tracking
    ANY_MOUSE(1003, false),              // Any-event mouse tracking
    FOCUS_EVENT(1004, false),            // Focus in/out events
    UTF8_MOUSE(1005, false),             // UTF-8 mouse format
    SGR_MOUSE(1006, false),              // SGR mouse format
    ALT_SCROLL(1007, false),             // Alternate scroll mode
    URXVT_MOUSE(1015, false),            // URxvt mouse format
    SGR_PIXELS_MOUSE(1016, false),       // SGR-Pixels mouse format
    NUMLOCK_KEYPAD(1035, false),          // Ignore keypad with NumLock
    ALT_ESC_PREFIX(1036, false),         // Alt key sends ESC prefix
    ALT_SENDS_ESC(1039, false),          // Alt sends escape
    REVERSE_WRAP_EXT(1045, false),       // Extended reverse wrap
    ALT_SCREEN(1047, false),             // Alternate screen
    SAVE_CURSOR(1048, false),            // Save cursor (DECSC)
    ALT_SCREEN_SAVE(1049, false),        // Alt screen + save cursor + clear
    BRACKETED_PASTE(2004, false),        // Bracketed paste mode
    SYNC_OUTPUT(2026, false),            // Synchronized output
    GRAPHEME_CLUSTER(2027, false),       // Grapheme cluster mode
    COLOR_SCHEME_REPORT(2031, false),    // Report color scheme
    IN_BAND_RESIZE(2048, false);         // In-band size reports
    
    private final int value;
    private final boolean isAnsi;
    
    TerminalMode(int value, boolean isAnsi) {
        this.value = value;
        this.isAnsi = isAnsi;
    }
    
    /** Get the packed 16-bit value for native calls */
    public short packedValue() {
        return (short)((value & 0x7FFF) | (isAnsi ? 0x8000 : 0));
    }
}

// Mode Report States (from native bindings)
// These are used for DECRPM (DEC Private Mode Report) responses
public enum ModeReportState {
    NOT_RECOGNIZED,    // Mode is not recognized
    SET,               // Mode is set (enabled)
    RESET,             // Mode is reset (disabled)
    PERMANENTLY_SET,   // Mode is permanently set
    PERMANENTLY_RESET // Mode is permanently reset
}

public enum ModifierSide {
    LEFT,
    RIGHT
}

// MouseTrackingMode - Mouse tracking modes
public enum MouseTrackingMode {
    NONE,       // Mouse reporting disabled
    X10,        // X10 mouse mode
    NORMAL,     // Normal mouse mode (button press/release only)
    BUTTON,     // Button-event tracking mode
    ANY         // Any-event tracking mode
}

// MouseFormat - Mouse output format
public enum MouseFormat {
    X10,        // X10 format
    UTF8,       // UTF-8 format
    SGR,        // SGR format
    URXVT,      // URXVT format
    SGR_PIXELS  // SGR pixel format
}

// MouseAction - Mouse event action type
public enum MouseAction {
    PRESS,      // Mouse button was pressed
    RELEASE,    // Mouse button was released
    MOTION      // Mouse moved
}

// MouseButton - Mouse button identity
public enum MouseButton {
    UNKNOWN,    // Unknown button
    LEFT,       // Left button
    RIGHT,      // Right button
    MIDDLE,     // Middle button
    FOUR,       // Button four
    FIVE,       // Button five
    SIX,        // Button six
    SEVEN,      // Button seven
    EIGHT,      // Button eight
    NINE,       // Button nine
    TEN,        // Button ten
    ELEVEN      // Button eleven
}

// KeyAction - Keyboard input event types
public enum KeyAction {
    RELEASE,    // Key was released
    PRESS,      // Key was pressed
    REPEAT      // Key is being repeated (held down)
}

// Key - Physical key codes (complete list)
public enum Key {
    UNIDENTIFIED,
    
    // Writing System Keys (W3C § 3.1.1)
    BACKQUOTE, BACKSLASH, BRACKET_LEFT, BRACKET_RIGHT, COMMA,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
    EQUAL, INTL_BACKSLASH, INTL_RO, INTL_YEN,
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    MINUS, PERIOD, QUOTE, SEMICOLON, SLASH,
    
    // Functional Keys (W3C § 3.1.2)
    ALT_LEFT, ALT_RIGHT, BACKSPACE, CAPS_LOCK, CONTEXT_MENU,
    CONTROL_LEFT, CONTROL_RIGHT, ENTER, META_LEFT, META_RIGHT,
    SHIFT_LEFT, SHIFT_RIGHT, SPACE, TAB,
    CONVERT, KANA_MODE, NON_CONVERT,
    
    // Control Pad Section (W3C § 3.2)
    DELETE, END, HELP, HOME, INSERT, PAGE_DOWN, PAGE_UP,
    
    // Arrow Pad Section (W3C § 3.3)
    ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, ARROW_UP,
    
    // Numpad Section (W3C § 3.4)
    NUM_LOCK, NUMPAD_0, NUMPAD_1, NUMPAD_2, NUMPAD_3, NUMPAD_4, NUMPAD_5, NUMPAD_6, NUMPAD_7, NUMPAD_8, NUMPAD_9,
    NUMPAD_ADD, NUMPAD_BACKSPACE, NUMPAD_CLEAR, NUMPAD_CLEAR_ENTRY, NUMPAD_COMMA, NUMPAD_DECIMAL, NUMPAD_DIVIDE,
    NUMPAD_ENTER, NUMPAD_EQUAL, NUMPAD_MEMORY_ADD, NUMPAD_MEMORY_CLEAR, NUMPAD_MEMORY_RECALL, NUMPAD_MEMORY_STORE,
    NUMPAD_MEMORY_SUBTRACT, NUMPAD_MULTIPLY, NUMPAD_PAREN_LEFT, NUMPAD_PAREN_RIGHT, NUMPAD_SUBTRACT,
    NUMPAD_SEPARATOR, NUMPAD_UP, NUMPAD_DOWN, NUMPAD_RIGHT, NUMPAD_LEFT, NUMPAD_BEGIN, NUMPAD_HOME, NUMPAD_END,
    NUMPAD_INSERT, NUMPAD_DELETE, NUMPAD_PAGE_UP, NUMPAD_PAGE_DOWN,
    
    // Function Section (W3C § 3.5)
    ESCAPE, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15, F16, F17, F18, F19, F20,
    F21, F22, F23, F24, F25, FN, FN_LOCK, PRINT_SCREEN, SCROLL_LOCK, PAUSE,
    
    // Media Keys (W3C § 3.6)
    BROWSER_BACK, BROWSER_FAVORITES, BROWSER_FORWARD, BROWSER_HOME, BROWSER_REFRESH, BROWSER_SEARCH, BROWSER_STOP,
    EJECT, LAUNCH_APP_1, LAUNCH_APP_2, LAUNCH_MAIL,
    MEDIA_PLAY_PAUSE, MEDIA_SELECT, MEDIA_STOP, MEDIA_TRACK_NEXT, MEDIA_TRACK_PREVIOUS,
    POWER, SLEEP, AUDIO_VOLUME_DOWN, AUDIO_VOLUME_MUTE, AUDIO_VOLUME_UP, WAKE_UP,
    
    // Legacy, Non-standard, and Special Keys (W3C § 3.7)
    COPY, CUT, PASTE
}

// OptionAsAlt - macOS option-as-alt setting
public enum OptionAsAlt {
    FALSE,      // Option key is not treated as alt
    TRUE,       // Option key is treated as alt
    LEFT,       // Only left option key is treated as alt
    RIGHT       // Only right option key is treated as alt
}

// CellWidth - Cell width property
public enum CellWidth {
    NARROW,         // Not a wide character, cell width 1
    WIDE,           // Wide character, cell width 2
    SPACER_TAIL,    // Spacer after wide character
    SPACER_HEAD     // Spacer at end of soft-wrapped line
}

// RowCoordinateSpace - Coordinate space for row operations
public enum RowCoordinateSpace {
    ACTIVE,        // Active area where the cursor can move
    VIEWPORT,      // Visible viewport (changes when scrolled)
    SCREEN         // Full screen including scrollback
}

// CellSemantic - Semantic content type of a cell
public enum CellSemantic {
    OUTPUT,        // Regular output content
    INPUT,         // Content that is part of user input
    PROMPT          // Content that is part of a shell prompt
}

// CellContentTag - Content type of a cell
public enum CellContentTag {
    CODEPOINT,             // A single codepoint (may be zero for empty)
    CODEPOINT_GRAPHEME,   // A codepoint that is part of a multi-codepoint grapheme cluster
    BG_COLOR_PALETTE,      // No text; background color from palette
    BG_COLOR_RGB           // No text; background color as RGB
}

// RowSemanticPrompt - Semantic prompt state of a row
public enum RowSemanticPrompt {
    NONE,                    // No prompt cells in this row
    PROMPT,                 // Prompt cells exist and this is a primary prompt line
    PROMPT_CONTINUATION      // Prompt cells exist and this is a continuation line
}
```

Use records for bitflags:

```java
package io.github.vlaaad.ghostty;

public record KeyModifiers(
    boolean shift,
    boolean ctrl,
    boolean alt,
    boolean superKey,
    boolean capsLock,
    boolean numLock,
    ModifierSide shiftSide,
    ModifierSide ctrlSide,
    ModifierSide altSide,
    ModifierSide superSide
) {}

public enum ModifierSide {
    LEFT,
    RIGHT
}

public record KittyKeyboardFlags(
    boolean disambiguate,
    boolean reportEvents,
    boolean reportAlternates,
    boolean reportAll,
    boolean reportAssociated
) {}
```

This is slower than exposing raw masks, but the public API becomes readable and stable. Platform adapters can still pack/unpack bitmasks internally.

## Error Model

Map libghostty results to exceptions at the wrapper boundary:

```java
package io.github.vlaaad.ghostty;

public sealed class GhosttyException extends RuntimeException
    permits OutOfMemoryException, InvalidValueException, OutOfSpaceException, NoValueException {}

public final class OutOfMemoryException extends GhosttyException {}
public final class InvalidValueException extends GhosttyException {}
public final class OutOfSpaceException extends GhosttyException {}
public final class NoValueException extends GhosttyException {}

public record DeviceAttributes(
    DeviceAttributesPrimary primary,
    DeviceAttributesSecondary secondary,
    DeviceAttributesTertiary tertiary
) {}

public record DeviceAttributesPrimary(
    String version,
    int pngLevel,
    boolean truecolor,
    boolean sixel,
    boolean iterm,
    boolean kittyGraphics,
    boolean tmuxControlMode,
    boolean titleStack,
    boolean colorStack,
    boolean graphemeCluster,
    boolean syncOutput,
    boolean dynamicColors,
    boolean colorPalette,
    boolean ansiColor,
    boolean columnRegions,
    boolean ansiTextLocator,
    boolean hexdumpLocator,
    boolean rectangleLocator,
    boolean horizontalPositioning,
    boolean verticalPositioning,
    boolean unitTerminal,
    boolean geometricShapes,
    boolean nationalReplacementCharacterSets,
    boolean technicalCharacters,
    boolean userDefinedCharacters,
    boolean activePositionReporting,
    boolean printer,
    boolean userWindows,
    boolean nationalReplacementCharacterSets2,
    boolean technicalCharacters2,
    boolean g0G1CharacterSets,
    boolean g2G3CharacterSets,
    boolean additionalCharacterSets,
    boolean macroDefinitions,
    boolean terminalStateInterrogation,
    boolean multipleSessionManagement,
    boolean defaultFontSelection
) {}

public record DeviceAttributesSecondary(
    boolean utf8,
    boolean sixelGraphics,
    boolean softTerminalReset,
    boolean selectiveErase,
    boolean urxvtMouse,
    boolean sgrMouse,
    boolean alternateScroll,
    boolean proportionalSpacing
) {}

public record DeviceAttributesTertiary(
    boolean transparency,
    boolean italics,
    boolean blinkingText,
    boolean coloredUnderline,
    boolean ideogramSupport,
    boolean ideogramUnderline,
    boolean ideogramDoubleUnderline,
    boolean ideogramOverline
) {}
```

Rules:

- methods like `cell(...)` or `mode(...)` should use `Optional` when absence is normal
- configuration or state violations should throw mapped exceptions
- unexpected native failures should throw a plain `GhosttyException`

## Platform Adapter Responsibilities

Each platform module should:

- load the correct native library
- own all `Arena` and `MemorySegment` lifetimes
- translate records/enums to generated structs, unions, and callbacks
- materialize immutable Java snapshots before returning
- normalize layout differences such as `size_t`, `intptr_t`, and support structs
- define Java constants for C macros (like terminal modes) that jextract doesn't generate

The core module should not mention:

- `MemorySegment`
- `Arena`
- `SegmentAllocator`
- generated binding classes
- platform names beyond internal platform loading

## Implementation Notes

### Terminal Modes

The native library defines terminal modes as C macros using `ghostty_mode_new(value, ansi_flag)`
which creates packed 16-bit values. Since jextract doesn't generate Java constants for C macros,
the Java wrapper must manually define these constants as shown in the `TerminalMode` enum.

The `packedValue()` method converts the Java enum to the native packed format:
- Bits 0-14: mode value (0-32767)
- Bit 15: ANSI flag (1 = ANSI mode, 0 = DEC private mode)

### Mode Report States

The native bindings include `GHOSTTY_MODE_REPORT_*` constants for DECRPM responses.
These are exposed as the `ModeReportState` enum for use with mode reporting functionality.

## Internal Implementation Shape

Suggested internal split:

- `TerminalSession` public interface
- `NativeTerminalSession` internal class
- `TerminalActor` internal single-thread serializer
- `SnapshotMapper` internal native-to-record mapper
- `BindingAdapter` per-platform bridge

The actor should be the only owner of:

- native terminal handle
- native encoder handles
- upcall stubs and native arenas

## Why This Shape

This proposal deliberately avoids two bad extremes:

- exposing raw `jextract` structs and leaking platform ABI differences
- over-abstracting libghostty until important VT capabilities disappear

It keeps the public API:

- generic
- Java-native
- thread-safe
- close to libghostty semantics

while still leaving room for a later JavaFX renderer layer on top.

## Defaults Chosen

- callbacks are dispatched via an internal executor
- default callback dispatch should use virtual threads
- `Ghostty` performs platform lookup internally
- screen reads use immutable detached snapshots
- payload-heavy notifications are query-based rather than push-based
- bitflags become records/enums publicly, raw masks internally
