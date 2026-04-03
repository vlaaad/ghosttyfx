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

Suggested package root: `io.github.vlaaad.ghostty`

Suggested modules:

- `ghostty-vt-core`
- `ghostty-vt-linux-x86_64`
- `ghostty-vt-macos-x86_64`
- `ghostty-vt-macos-aarch64`
- `ghostty-vt-windows-x86_64`

`ghostty-vt-core` contains only handwritten interfaces, records, enums, and shared wrapper logic contracts.
Platform modules are discovered internally by the facade and are not part of the public bootstrap API.

## Public Entry Point

The `Ghostty` class provides factory methods for creating terminal sessions and codecs:

- `open()` - Creates a new terminal session
- `keyCodec()` - Creates a key codec
- `mouseCodec()` - Creates a mouse codec  
- `pasteCodec()` - Creates a paste codec
- `focusCodec()` - Creates a focus codec
- `sizeReportCodec()` - Creates a size report codec
- `buildInfo()` - Gets build information
- `typeSchema()` - Gets type schema

Rationale:

- `Ghostty` is the only public bootstrap type.
- Platform service discovery happens inside `Ghostty`.
- Sessions and codecs remain public capabilities, but runtime/provider abstractions do not.

## Session API

The `TerminalSession` interface provides methods for interacting with a terminal session:

- `config()` - Gets terminal configuration
- `snapshot()` - Gets current terminal snapshot
- `resize()` - Resizes terminal
- `write()` - Writes VT sequences
- `reset()` - Resets terminal
- `setMode()` - Sets terminal mode
- `mode()` - Gets terminal mode state
- `setColorScheme()` - Sets color scheme
- `setWindowTitle()` - Sets window title
- `setWorkingDirectory()` - Sets working directory
- `setForeground()` - Sets foreground color
- `setBackground()` - Sets background color
- `setCursorColor()` - Sets cursor color
- `setPalette()` - Sets color palette
- `scrollToTop()` - Scrolls to top
- `scrollToBottom()` - Scrolls to bottom
- `scrollBy()` - Scrolls by delta
- `scrollViewport()` - Scrolls viewport
- `cell()` - Gets cell at point
- `row()` - Gets row at index
- `screen()` - Gets screen snapshot
- `deviceAttributes()` - Gets device attributes
- `close()` - Closes session

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

Key construction types include:

- `TerminalConfig` record with columns, rows, and maxScrollback
- `PtyWriter` interface with writePty method
- `TerminalQueries` interface with query callback methods

`TerminalQueries` contract:

- methods are synchronous and must return inline on the native actor thread
- implementations should be fast and non-blocking in practice, even though they are synchronous by contract
- implementations must not call back into `TerminalSession`
- if query data is produced asynchronously elsewhere, cache the latest value and return that cached value here

The `TerminalEvents` interface provides callback methods:

- `bell()` - Called when bell occurs
- `titleChanged()` - Called when title changes
- `stateChanged()` - Called when state changes

Design choice:

- the three callback categories stay separate because they have different semantics:
  terminal-to-host output, host-to-terminal queries, and wrapper notifications
- `titleChanged` carries the resolved immutable title string because libghostty only
  signals that the title changed; the wrapper reads the new title before dispatch

## Snapshot Model

Key snapshot records include:

- `TerminalSnapshot` - Complete terminal state snapshot
- `CursorSnapshot` - Cursor state with position and style
- `ScreenSnapshot` - Screen state with dimensions and visible rows
- `RowSnapshot` - Row state with index, flags, and cells
- `CellSnapshot` - Cell state with text, style, and metadata

Important rule:

- snapshots are detached immutable values
- no public `GridRef`, native pointer, or cursor object leaks out

`Point` should remain generic and not expose libghostty unions. The sealed interface includes:

- `ActivePoint`, `ViewportPoint`, `ScreenPoint`, `HistoryPoint` implementations
- `TerminalScrollViewport` with `ScrollViewportTop`, `ScrollViewportBottom`, `ScrollViewportDelta`

This is clearer than mirroring `GhosttyPointTag` plus a union record.

## Theme, Style, and String Types

Key theme and style records include:

- `ColorValue` sealed interface with `DefaultColor`, `PaletteColor`, `RgbColor` implementations
- `TerminalScrollbar` with total, offset, and length
- `ThemeSnapshot` with foreground, background, cursor colors and palettes
- `StyleSnapshot` with text styling attributes

For strings originating from native `GhosttyString`, always convert to Java `String` or `byte[]` in the platform layer. Never surface pointer-backed string wrappers publicly.

## Input Codec APIs

The non-UI generic API should include standalone codecs because they are part of terminal semantics, not rendering.

### Key

- `KeyCodec` interface with `encode(KeyEvent)` method
- `KeyCodecConfig` record with keyboard configuration options
- `KeyEvent` record with key action, modifiers, and text

### Mouse

- `MouseCodec` interface with `encode(MouseEvent, MouseEncodeContext)` method
- `MouseEncodeContext` record with mouse encoding context
- `MouseEvent` record with mouse action, button, and position

### Paste / Focus / Size Report

- `PasteCodec` interface with paste encoding methods
- `FocusCodec` interface with focus event encoding
- `FocusEvent` record with focus action
- `SizeReportCodec` interface with size report encoding

## Small Value Types

Key value types include:

- `TerminalSize` with columns, rows, and pixel dimensions
- `PixelPoint` with x,y coordinates
- `SizeReport` with width/height in pixels and columns/rows
- `Hyperlink` with URI
- `BuildInfo` with version and feature flags
- `TypeSchema` with JSON schema
- `ColorScheme` enum with DARK and LIGHT values

## Enums and Flags

Key enums include:

- `ScreenKind` (PRIMARY, ALTERNATE)
- `TerminalMode` (comprehensive list with packed value support)
- `ModeReportState` (NOT_RECOGNIZED, SET, RESET, etc.)
- `MouseTrackingMode`, `MouseFormat`, `MouseAction`, `MouseButton`
- `KeyAction`, `Key` (complete list of physical keys)
- `OptionAsAlt`, `CellWidth`, `CellSemantic`, `CellContentTag`, `RowSemanticPrompt`
- `ModifierSide` (LEFT, RIGHT)

Key bitflag records include:

- `KeyModifiers` with shift/ctrl/alt/super states and modifier sides
- `KittyKeyboardFlags` with kitty keyboard feature flags

This is slower than exposing raw masks, but the public API becomes readable and stable. Platform adapters can still pack/unpack bitmasks internally.

## Error Model

Key error handling components:

- `GhosttyException` sealed class with specific subclasses
- `OutOfMemoryException`, `InvalidValueException`, `OutOfSpaceException`, `NoValueException`
- `DeviceAttributes` with nested records for primary, secondary, and tertiary attributes

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
