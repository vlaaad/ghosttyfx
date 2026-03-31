# GhosttyFX

JavaFX/cljfx terminal emulator using libghostty with JExtract bindings.

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
