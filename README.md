# GhosttyFX

JavaFX/cljfx terminal emulator using libghostty with handwritten FFM bindings.

## CI Build

Builds libghostty-vt for Linux, macOS (arm64/x64), and Windows via GitHub Actions.
Each CI job uploads a per-platform artifact containing only the native library that the Java
wrapper loads at runtime.

### Windows Build Issue

Zig bug #25805 causes builds to fail on Windows when cwd and cache paths are on different drives.
The bug exists in Zig 0.12.x - 0.15.x and some 0.16.0-dev builds. It is fixed in Zig 0.16.0.

Ghostty's CI works because they use `namespace-profile-ghostty-windows` runners where the working
directory and temp path are on the same drive by default. Our `windows-latest` runners have them
on different drives (D: for temp, C: for workspace), triggering the bug.

Workaround: Set `ZIG_CACHE_DIR` to a path on the same drive as the runner's working directory.

## Setup

1. Install Java 25+
2. Install build tools: CMake, Zig
3. Clone libghostty: `git clone https://github.com/ghostty-org/ghostty`
4. Build libghostty-vt shared library
5. Create Java wrapper layer
6. Build JavaFX terminal component
7. Create cljfx handler

## Artifact Download

Run `python scripts/download_lib.py` to trigger the workflow and download:

- one downloaded artifact per platform into `dist/platforms/`
- each platform artifact contains `lib/`

## Binding Strategy

The binding layer is handwritten in `ghostty-core`, with the small subset of FFM calls and ABI
layouts the wrapper actually uses today.

The plan is:

- keep a handwritten common Java API in a core module
- keep the handwritten binding/runtime code in `ghostty-core`
- package platform-specific native libraries/modules for Linux, macOS arm64, macOS x64, and Windows x64
- keep the JavaFX/cljfx code talking only to the common handwritten API, not to generated classes directly


Then, we will use **Maven** for Java libs with runtime-scoped platform dependencies.

## Running Tests

Use `mvn clean test` when validating local changes.

## Resources

- [libghostty docs](https://libghostty.tip.ghostty.org/index.html)
- [Ghostling example](https://github.com/ghostty-org/ghostling)
