# GhosttyFX

Generated per-platform `jextract` bindings for `libghostty-vt`.

## Layout

- `ghosttyfx-core`: shared Java implementation
- `ghosttyfx`: dependency-only current-platform convenience artifact
- `ghosttyfx-manual-app`: JavaFX launcher for manual testing
- `ghosttyfx-linux-x86_64`
- `ghosttyfx-linux-aarch64`
- `ghosttyfx-macos-x86_64`
- `ghosttyfx-macos-aarch64`
- `ghosttyfx-windows-x86_64`
- `ghostty`: pinned Git submodule for the upstream Ghostty source tree
- `ghostling`: pinned Git submodule for the upstream Ghostling reference app

## Licenses

GhosttyFX bundles shell integration scripts under `ghosttyfx-core/src/main/resources/shell`.
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

Run `./.cmd test`, or `./.cmd mvn clean test` for the equivalent Maven command.
The checked-in [dotcmd](https://github.com/dotcmdhq/dotcmd) launcher downloads
its pinned runtime and the toolchain on first use. No Java, Maven, Zig, or
jextract installation is needed. Tool versions and SHA-256 hashes live in
[.cmd.lua](.cmd.lua): Liberica JDK 25.0.2+12, Maven 3.9.16, Zig 0.16.0, and
jextract 25+2-4. Maven arguments are forwarded unchanged.

Native generation runs `git submodule sync --recursive` and
`git submodule update --init --recursive` before checking cached bundles.
This initializes missing submodules and checks out the commits pinned by the
parent repository, including nested submodules. It does not track upstream
branches or force-discard local source edits; conflicting changes fail the build.

On Windows, use `.\.cmd test` in PowerShell or `.cmd test` in cmd.exe.
On Unix the bootstrap needs curl or wget and the OS SHA-256 utility; Windows
uses PowerShell, or curl plus certutil. The runtime and downloaded tools are
cached in dotcmd's shared cache. No cache configuration is needed.

Maven invokes the internal `generate-platform` task during `generate-sources`.
It builds `libghostty-vt` and runs jextract using the same recipe locally and
in CI. Tools, native bundles, and Zig caches use dotcmd's shared cache outside
the checkout. Bundles are reused only when the platform,
Ghostty source (including local edits), and `.cmd.lua` recipe match.
Generated sources and resources remain under each module's `target/`.
There is no local GitHub artifact lookup or `gh` dependency.

Linux needs JavaFX's system libraries (including GTK), `strip`, and a display.
When a test or app command needs a display and `DISPLAY` is unset, `.cmd`
launches Maven through `xvfb-run -a`, which owns virtual-display cleanup.
Headless hosts therefore need Xvfb and xauth installed. Windows native builds
need Visual Studio Build Tools and the Windows SDK; macOS native builds use
the installed Apple SDK. These OS dependencies are not downloaded by `.cmd`.

## Manual App

The repository includes a JavaFX manual app in
[GhosttyFxManualApp.java](ghosttyfx-manual-app/src/main/java/io/github/vlaaad/ghosttyfx/manualapp/GhosttyFxManualApp.java).

From the repository root, launch it with:

`./.cmd manual-app`

Launch the Nord app with `./.cmd nord-app`. Both aliases accept extra Maven
arguments and use the same `mvn` task. The full manual-app invocation remains:

`./.cmd mvn -pl ghosttyfx-manual-app -am -Pmanual-app compile`

The app:

- starts with an empty `TabPane`
- auto-detects available terminal executables
- lets you choose a working directory before opening a tab
- creates each tab with `GhosttyFx.create(command, cwd, System.getenv())`

Close tabs or the window to tear down their PTY processes.

## CI

Every native-host job checks out submodules and runs `./.cmd test -B`.
The Linux display wrapper is inside `.cmd`, and CI supplies no separate
Java/Maven versions. The macOS Intel job also passes `-Dprism.order=sw`.

Native jobs upload `<platform-module>/target/ghosttyfx-artifact/`, containing
`src/` and `resources/`. The publishing job transfers those bundles into
`dist/<ghostty-commit>/<artifactId>/` and runs `./.cmd mvn -Prelease ...`.
Non-host modules require a matching bundle produced by the same recipe on
its native host; local native generation remains host-only.

The Maven validation phase also checks the public `.cmd` entrypoints with
controlled tool downloads and child processes.

The `java`, `jar`, and `javap` tasks expose the pinned JDK tools for inspection
and release checks. Signing still uses GPG and publishing credentials.
`ghostling` is source reference only and is not required by the build.
