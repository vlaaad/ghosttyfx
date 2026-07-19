# GhosttyFX

Generated per-platform `jextract` bindings for `libghostty-vt`.

## Layout

- `ghosttyfx`: shared Java module
- `ghosttyfx-manual-app`: JavaFX launcher for manual testing
- `ghosttyfx-linux-x86_64`
- `ghosttyfx-linux-aarch64`
- `ghosttyfx-macos-x86_64`
- `ghosttyfx-macos-aarch64`
- `ghosttyfx-windows-x86_64`
- `ghostty`: pinned Git submodule for the upstream Ghostty source tree
- `ghostling`: pinned Git submodule for the upstream Ghostling reference app

## Licenses

GhosttyFX bundles shell integration scripts under `ghosttyfx/src/main/resources/shell`.
The Bash and Zsh integration scripts are copied from Ghostty's shell integration
and retain their GPLv3 license notices because they are derived from kitty's
GPLv3 shell integration. Other GhosttyFX code and scripts keep their original
licenses.

## Next steps

### v1
14. Semantic prompt / shell integration UI (osc 133): libghostty parses semantic prompt data, but the view does not expose prompt navigation, command regions, or similar UI behavior.
14.0.1. resize acceptance: with injected PowerShell/pwsh, prompt at 80 columns, resize to 6 columns, then back to 80; the cursor must return to the prompt end through the real PTY + libghostty path, not by a JavaFX-only workaround.
14.0.2. note: plain prompts without osc 133 markers reproduce libghostty cursor pin reflow behavior (`38,0 -> 5,0 -> 5,0`), so shell integration is required for prompt redraw after shrink/grow.

### v2
7. check out `_get_multi` for perf
8. explore backarrow key mode support

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

If the local Windows toolchain is unavailable, the build looks for a matching downloaded artifact cache under:

- `dist/<ghostty-commit-sha>/<artifactId>/`

If it is missing, the build automatically downloads the current platform artifact from a successful CI run.

Artifacts contain:

- `src/`
- `resources/`

## Downloaded Artifacts

Run:

`mvn -N -Pdownload-cross-platform-artifacts exec:exec@download-cross-platform-artifacts`

That command:

- first searches successful `build-lib.yml` workflow dispatches for the current branch and commit
- falls back to successful workflow runs from pushes to `main`, newest first
- downloads the newest complete retained artifact set compatible with the checked-out `ghostty` submodule
- downloads the produced artifact set into `dist/<ghostty-commit-sha>/`
- validates that each artifact metadata file matches the current `ghostty` submodule commit

This explicit command is only needed to download a complete cross-platform artifact set. Normal local builds download only the current platform when its native toolchain is unavailable.

## Perf App

Run the interactive throughput harness:

`mvn -pl ghosttyfx-perf-app -am -Pperf-app compile`

Useful scenarios:

- `-Dghosttyfx.perf.scenario=REPEAT_INPUT`
- `-Dghosttyfx.perf.scenario=SEARCH_FIELD`
- `-Dghosttyfx.perf.scenario=LINK_OUTPUT`
- `-Dghosttyfx.perf.scenario=LATENCY`
- `-Dghosttyfx.perf.scenario=THROUGHPUT -Dghosttyfx.perf.throughputBytes=10000000`

The perf app writes `summary.md`, `dispatch-samples.csv`, `pulse-samples.csv`, `recording.jfr`, and `jfr-events.csv` under `ghosttyfx-perf-app/target/perf-results` by default. Disable JFR with `-Dghosttyfx.perf.jfr=false`.

Compare two runs with:

`java scripts/PerfCompare.java baseline/summary.md candidate/summary.md comparison.md`

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
3. run `mvn "-Dghosttyfx.native.mode=build" clean test`
4. upload `<platform-module>/target/ghosttyfx-artifact/`

## Notes

- Local generation is host-only.
- `ghosttyfx.native.mode=build` disables artifact reuse and downloading, so CI builds from source or fails.
- Cross-platform artifact sets come from CI running the same build on each target host.
- Local Windows source builds still require Visual Studio Build Tools plus the Windows SDK.
- `ghostling` is for source reference only and is not part of the build path.
- No extra gitignore entry is needed for generated bindings because they live under `target/`.
