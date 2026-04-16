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
- `ghostty-reference` is no longer part of the build path.
- No extra gitignore entry is needed for generated bindings because they live under `target/`.
