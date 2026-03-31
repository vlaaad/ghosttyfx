# GhosttyFX

JavaFX/cljfx terminal emulator using libghostty with JExtract bindings.

## CI Build

Builds libghostty-vt for Linux, macOS (arm64/x64), and Windows via GitHub Actions.

### Windows Build Issue

Zig bug #25805 causes builds to fail on Windows when cwd and cache paths are on different drives.
The bug exists in Zig 0.12.x - 0.15.x and some 0.16.0-dev builds. It is fixed in Zig 0.16.0.

Ghostty's CI works because they use `namespace-profile-ghostty-windows` runners where the working
directory and temp path are on the same drive by default. Our `windows-latest` runners have them
on different drives (D: for temp, C: for workspace), triggering the bug.

Workaround: Set `ZIG_CACHE_DIR` to a path on the same drive as the runner's working directory.

## Setup

1. Install Java 25+ with jextract
2. Install build tools: CMake, Zig
3. Clone libghostty: `git clone https://github.com/ghostty-org/ghostty`
4. Build libghostty-vt shared library
5. Run jextract on headers
6. Create Java wrapper layer
7. Build JavaFX terminal component
8. Create cljfx handler

## Resources

- [libghostty docs](https://libghostty.tip.ghostty.org/index.html)
- [JExtract (JEP 469)](https://openjdk.org/jeps/469)
- [Ghostling example](https://github.com/ghostty-org/ghostling)
