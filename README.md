# GhosttyFX

Generated per-platform `jextract` bindings for `libghostty-vt`.

## Layout

- `ghosttyfx`: shared Java module
- `ghosttyfx-linux-x86_64`
- `ghosttyfx-macos-x86_64`
- `ghosttyfx-macos-aarch64`
- `ghosttyfx-windows-x86_64`
- `ghostty`: pinned Git submodule for the upstream Ghostty source tree

## Local Build

Run:

`mvn clean test`

The Maven build invokes [scripts/GhosttyBuild.java](/C:/Users/Vlaaad/Projects/ghosttyfx/scripts/GhosttyBuild.java), which:

- ensures the `ghostty` submodule is initialized to the repo-pinned commit
- downloads and caches Zig in `.tools/zig`
- downloads and caches `jextract` in `.tools/jextract`
- builds `libghostty-vt` for the current host platform
- runs `jextract` with a shared Java package name
- writes generated sources under `target/generated-sources/jextract`
- writes generated resources under `target/generated-resources/ghosttyfx`
- writes CI/download artifacts under `target/ghosttyfx-artifact`

Artifacts contain:

- `src/`
- `resources/`

## CI

CI only needs to:

1. check out this repository with submodules
2. set up Java
3. run `mvn clean test`
4. upload `<platform-module>/target/ghosttyfx-artifact/`

## Notes

- Local generation is host-only.
- Cross-platform artifact sets come from CI running the same build on each target host.
- Local Windows builds still require Visual Studio Build Tools plus the Windows SDK.
- `ghostty-reference` is no longer part of the build path.
- No extra gitignore entry is needed for generated bindings because they live under `target/`.
