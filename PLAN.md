# Ghostty Wrapper Implementation Plan

Phase status legend: `🟢` completed, `🟡` in progress, `⚪` not started.

## Goal
- Deliver a handwritten Java wrapper over libghostty-vt that hides native/FFM details.
- End state: client code can launch a process, stream its output into a `TerminalSession`, and send encoded input back through a `PtyWriter`-style bridge.

## 🟢 Phase 1: Core factory wiring
- Extend `io.github.vlaaad.ghostty.impl.Provider` from metadata-only to full runtime factories.
- Make `Ghostty` delegate `open`, `keyCodec`, `mouseCodec`, `pasteCodec`, `focusCodec`, and `sizeReportCodec` through `Providers.provider()`.
- Keep `ghostty-core` as the only public API surface; platform modules stay internal adapters plus native resources.

## ⚪ Phase 2: Replace jextract with shared handwritten bindings
- Remove the runtime dependency on generated jextract classes and bind directly to packaged native libraries with handwritten FFM code.
- Add a shared internal binding layer for the small subset of `libghostty-vt` symbols the wrapper actually uses, expanding it only when higher phases need more surface.
- Model per-platform ABI differences explicitly, especially `size_t`, `GhosttyString`, layout-sensitive structs/unions, and callback descriptors.
- Keep platform modules focused on native library packaging, service registration, and minimal platform metadata rather than duplicated binding logic.

## 🟡 Phase 3: Standalone codecs
- Implement `KeyCodec` first, then `MouseCodec`, `PasteCodec`, `FocusCodec`, and `SizeReportCodec`.
- Build small native wrapper classes per codec: allocate native encoder/event objects, map Java enums/records to native values, encode to fresh `byte[]`, free native resources deterministically.
- Fill out placeholder Java config/types where needed, especially mouse codec configuration.
- Add codec tests with stable byte-sequence assertions for representative inputs and edge cases.

## ⚪ Phase 4: Terminal session runtime
- Implement a platform `TerminalSession` backed by one native `GhosttyTerminal` plus a single-threaded actor/executor.
- Route all mutating calls (`write`, `resize`, `reset`, mode/color/title/pwd/palette setters, scrolling, close`) onto the actor.
- Translate synchronous native effects into `PtyWriter` and `TerminalQueries`; translate async notifications into serialized `TerminalEvents` callbacks off the actor thread.
- Snapshot/read APIs must copy native state into detached Java records (`Terminal`, `Screen`, `Row`, `Cell`, etc.), never expose borrowed native memory.

## ⚪ Phase 5: Type mapping and shared adapter layer
- Add shared internal mappers/utilities for colors, styles, points, rows, screens, device attributes, modes, and native result handling.
- Prefer one adapter design implemented once, then extend it across Linux/macOS/Windows with only explicit ABI/layout differences where required.
- Keep native library loading, service registration, and provider selection exactly as the entry path for all runtime objects.

## ⚪ Phase 6: Process integration target
- Add a small higher-level integration example/helper showing `pty4j` + `Ghostty.open(...)`.
- Data flow: PTY-backed process output -> session `write(...)`; user key/mouse/focus/paste input -> codecs/session -> `PtyWriter` -> PTY master.
- Keep PTY/process ownership outside `ghostty-core`; the wrapper integrates with an external PTY layer rather than managing child processes itself.

## Validation
- Unit-test provider discovery and every codec.
- Add ABI-focused tests for handwritten bindings, especially `size_t` handling, `GhosttyString`, and any platform-specific layout descriptors.
- Add terminal-session tests for resize, VT write, title/pwd changes, mode queries, scrolling, snapshot consistency, and callback threading/reentrancy rules.
- Use `pty4j` for integration and end-to-end tests so interactive terminal behavior is exercised against a real PTY.
- Keep `pty4j` out of `ghostty-core`; PTY-backed tests should live in an integration-test layer or separate module.
- Add one end-to-end demo/test that launches a simple PTY-backed process, captures output in the terminal state, and sends input back through the writer bridge.
