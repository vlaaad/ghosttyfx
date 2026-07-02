# GhosttyFX

[![Build](https://github.com/vlaaad/ghosttyfx/actions/workflows/build-lib.yml/badge.svg)](https://github.com/vlaaad/ghosttyfx/actions/workflows/build-lib.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.vlaaad/ghosttyfx.svg)](https://central.sonatype.com/artifact/io.github.vlaaad/ghosttyfx)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Sponsor](https://img.shields.io/badge/Sponsor-vlaaad-ea4aaa.svg?logo=github-sponsors)](https://github.com/sponsors/vlaaad)

GhosttyFX is a JavaFX terminal control backed by Ghostty's terminal emulator.
It renders terminal output in JavaFX and connects to a terminal backend supplied
by your application.

![](/image.png)

## Funding

If you use GhosttyFX in your project, please consider [sponsoring](https://github.com/sponsors/vlaaad)
its development. Your sponsorship helps keep the JavaFX integration and terminal
behavior work moving.

## Installation

GhosttyFX is published to Maven Central. Add the shared JavaFX control artifact:

```xml
<dependency>
    <groupId>io.github.vlaaad</groupId>
    <artifactId>ghosttyfx</artifactId>
    <version>...</version>
</dependency>
```

Use the [Maven Central artifact page](https://central.sonatype.com/artifact/io.github.vlaaad/ghosttyfx)
to find the latest version.

The `ghosttyfx` artifact depends on the native binding artifact for the current
platform. Supported platforms are Linux x86_64/aarch64, macOS x86_64/aarch64,
and Windows x86_64.

GhosttyFX requires Java 25 and JavaFX 25.

## Getting Started

GhosttyFX is pty-agnostic; this example uses [pty4j](https://github.com/JetBrains/pty4j/) as the PTY backend.

First, adapt pty4j to GhosttyFX's `Terminal` interface:

```java
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import io.github.vlaaad.ghosttyfx.Terminal;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class PtyTerminal implements Terminal {
    private final PtyProcess process;

    PtyTerminal(List<String> command, Path cwd, Map<String, String> environment, int columns, int rows) throws Exception {
        process = (PtyProcess) new PtyProcessBuilder()
                .setCommand(command.toArray(String[]::new))
                .setConsole(false)
                .setRedirectErrorStream(true)
                .setDirectory(cwd.toString())
                .setEnvironment(environment)
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .setUseWinConPty(true)
                .start();
    }

    @Override
    public InputStream output() {
        return process.getInputStream();
    }

    @Override
    public OutputStream input() {
        return process.getOutputStream();
    }

    @Override
    public void resize(int columns, int rows) {
        process.setWinSize(new WinSize(columns, rows));
    }

    @Override
    public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
        }
    }
}
```

Then create a `TerminalView` with a `TerminalFactory`. The factory is called on
a background thread with the initial terminal size.

```java
import io.github.vlaaad.ghosttyfx.Shell;
import io.github.vlaaad.ghosttyfx.TerminalView;
import java.nio.file.Path;
import java.util.List;

var command = List.of("pwsh");
var cwd = Path.of(System.getProperty("user.home"));

var view = new TerminalView((columns, rows) -> {
    var launcher = Shell.integrate(command, System.getenv());
    return new PtyTerminal(launcher.command(), cwd, launcher.environment(), columns, rows);
});
```

Shell integration is an optional adjustment to the launched shell that enables some
quality of life improvements like shortcuts for jumping between prompts.

Finally, use `TerminalView` as a JavaFX node. For example, put it in a tab and
bind the tab title to the terminal title:

```java
tab.textProperty().bind(view.titleProperty());
tab.setContent(view);
```

## Main Concepts

### TerminalView

`TerminalView` is the JavaFX control. It starts a terminal backend through a
`TerminalFactory`, renders terminal output with Ghostty's terminal emulator, and
sends keyboard, mouse, paste, and resize input back to the backend.

### Terminal lifecycle

`TerminalFactory.open(columns, rows)` is called on a background thread, so it
may start a process, open a PTY, or perform other blocking setup before
returning a `Terminal`.

GhosttyFX does not prescribe a PTY library. Your application can adapt pty4j,
JNA, a native backend, or another process/session implementation to `Terminal`.

`TerminalView` owns the backend lifecycle. Call `TerminalView.close()` when the
view is no longer needed; it can be called from the JavaFX UI thread, actual
backend cleanup happens on the background terminal task, and repeated calls are
harmless.

Use `terminalStateProperty()` to observe whether the backend is running, closed,
or failed.

### Shell integration

`Shell.integrate(command, environment)` applies Ghostty shell integration before
the shell starts. Integration lets the terminal observe shell lifecycle events,
including prompts and command boundaries, which enables prompt navigation and
better redraw behavior. Supported shells are Bash, Cmd, Fish, PowerShell, and
Zsh. Unknown commands are returned unchanged.

## The View

`TerminalView` extends `AnchorPane`, so use it anywhere a JavaFX node can be
used. Useful view APIs include:

- `titleProperty()`: terminal title reported by the running program
- `terminalStateProperty()`: running, closed, or failed backend state
- `terminalSizeProperty()`: current terminal grid size in character cells
- `copySelection()`, `pasteClipboard()`, `selectAll()`: common terminal actions
- `sendText(...)`, `sendEsc(...)`: send text or escape-prefixed input

## Configuration

### Font

Use `fontProperty()` or `setFont(...)` to configure the terminal font. The view
uses the font to measure terminal cells, so changing it can change the view's
preferred width and height. Bold, italic, and bold italic faces are derived from
the configured font family and size.

### Theme

Use `themeProperty()` or `setTheme(...)` to configure colors used by the
terminal, cursor, selection, inactive and active scrollbar thumbs, and search
highlight backgrounds and borders.

`TerminalTheme.defaults()` uses a black background, white foreground, and the
terminal emulator's default indexed palette. A custom palette may be empty, 16
colors, or 256 colors. `faintOpacity` must be between `0.0` and `1.0`.

### Cursor

Use `cursorBlinkingProperty()` or `setCursorBlinking(...)` to allow or suppress
cursor blinking. This controls whether blinking is allowed when the terminal
requests it; it does not force every cursor to blink.

### macOS Option key

Use `macOptionAsAltProperty()` or `setMacOptionAsAlt(...)` on macOS when Option
key combinations should be sent as terminal Alt input. The default is `false`,
which lets Option-produced text behave like normal text input.

### Search

Use `searchPromptTextProperty()` or `setSearchPromptText(...)` to customize the
placeholder shown in the search field. The search UI follows the terminal font
and theme.

Search can be controlled with `toggleSearch()`, `closeSearch()`, `searchNext()`,
and `searchPrevious()`.

### Shortcuts

Use `getTerminalShortcuts()` to customize keyboard shortcuts handled by the
view. The list is mutable, and shortcuts are tried in list order. A
`TerminalShortcut` action returns `true` when it handled the key press.

The default list includes copy, paste, select all, search, selection extension,
scrolling, and prompt navigation shortcuts. Custom shortcuts can call view
methods such as `sendText(...)`, `sendEsc(...)`, `copySelection()`, or
`toggleSearch()`.

### Links

Use `getLinkMatchers()` to customize clickable text patterns. The list is
mutable, and matchers are tried in list order. The default list includes a
built-in web URL matcher. OSC 8 hyperlinks from terminal output take
precedence over link matchers.

A `TerminalLinkMatcher` receives the regex `MatchResult`, so actions can use
capture groups.

### Bell

Use `setOnBell(...)` or `onBellProperty()` to run code when the terminal rings
the bell. The value may be `null` to disable custom bell handling.

## Development

Internal build and repository notes live in [DEVELOPMENT.md](DEVELOPMENT.md).
