# GhosttyFX

Generated per-platform `jextract` bindings for `libghostty-vt`.

## Layout

- `ghosttyfx`: shared Java module
- `ghosttyfx-manual-app`: JavaFX launcher for manual testing
- `ghosttyfx-linux-x86_64`
- `ghosttyfx-macos-x86_64`
- `ghosttyfx-macos-aarch64`
- `ghosttyfx-windows-x86_64`
- `ghostty`: pinned Git submodule for the upstream Ghostty source tree
- `ghostling`: pinned Git submodule for the upstream Ghostling reference app

## Next steps

### v1
- More shortcuts:
  - shortcut injection: map shortcuts to PTY sequences; default macOS `Alt+Left/Right` -> `Esc+b/f`, `Meta+Left/Right/Backspace` -> `Ctrl+A/E/U`
11. Blinking: cursor visibility is respected, but there is no blink timer for blinking cursor or blinking text attributes.
15. Search UI:
  - find/highlight navigation in scrollback
  - search selection
  - next/previous result shortcuts: macOS `Cmd+G`, `Cmd+Shift+G`
  - end search shortcut: `Esc`; macOS also `Cmd+Shift+F`
16. Rich font rendering: grapheme handling exists, but JavaFX fillText per cell means no full terminal-grade shaping/ligatures/fallback behavior like Ghostty’s renderer.
17. URL handling:
  - regex URL matching
  - copy URL under cursor

### v2
6. kitty graphics
14. Semantic prompt / shell integration UI (osc 133): libghostty parses semantic prompt data, but the canvas does not expose prompt navigation, command regions, or similar UI behavior.
14.0. blocker: we need to implement shell injections first to make terminals emit osc 133/633 markers
14.1. prompt-aware navigation (ctrl+up/down) to jump to prev/next prompts.
14.2. region selection (quadruple-click to select region)
14.3. prompt marks in left edge

## Local Build

Run:

`mvn clean test`

The Maven build invokes [scripts/GhosttyBuild.java](/C:/Users/Vlaaad/Projects/ghosttyfx/scripts/GhosttyBuild.java), which:

- ensures the `ghostty` and `ghostling` submodules are initialized to their repo-pinned commits
- downloads and caches Zig in `.tools/zig`
- downloads and caches `jextract` in `.tools/jextract`
- builds `libghostty-vt` for the current host platform
- runs `jextract` with a shared Java package name
- writes generated sources under `target/generated-sources/jextract`
- writes generated resources under `target/generated-resources/ghosttyfx`
- writes CI/download artifacts under `target/ghosttyfx-artifact`

If the local Windows toolchain is unavailable, the build will also look for a matching downloaded artifact cache under:

- `dist/<ghostty-commit-sha>/<artifactId>/`

Artifacts contain:

- `src/`
- `resources/`

## Downloaded Artifacts

Run:

`mvn -N -Pdownload-cross-platform-artifacts exec:exec@download-cross-platform-artifacts`

That command:

- requires a clean checkout synced to `origin/main`
- triggers `build-lib.yml` on CI
- downloads the produced artifact set into `dist/<ghostty-commit-sha>/`
- validates that each artifact metadata file matches the current `ghostty` submodule commit

After that, on Windows machines without MSVC Build Tools + Windows SDK, `mvn clean test` can reuse the downloaded artifact for the current host platform.

## Manual App

The repository includes a JavaFX manual app in
[GhosttyFxManualApp.java](/C:/Users/Vlaaad/Projects/ghosttyfx/ghosttyfx-manual-app/src/main/java/io/github/vlaaad/ghosttyfx/manualapp/GhosttyFxManualApp.java).

From the repository root, launch it with:

`mvn -pl ghosttyfx-manual-app -am -Pmanual-app compile`

The app:

- starts with an empty `TabPane`
- auto-detects available terminal executables
- lets you choose a working directory before opening a tab
- creates each tab with `GhosttyFx.create(command, cwd, System.getenv())`

Close tabs or the window to tear down their PTY processes.

## CI

CI only needs to:

1. check out this repository with submodules
2. set up Java
3. run `mvn clean test`
4. upload `<platform-module>/target/ghosttyfx-artifact/`

## Notes

- Local generation is host-only.
- Cross-platform artifact sets come from CI running the same build on each target host.
- Local Windows source builds still require Visual Studio Build Tools plus the Windows SDK.
- `ghostling` is for source reference only and is not part of the build path.
- No extra gitignore entry is needed for generated bindings because they live under `target/`.
