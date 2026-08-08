package io.github.vlaaad.ghosttyfx;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/// Utilities for preparing a shell launch command for GhosttyFX.
///
/// The launch environment identifies GhosttyFX's terminal capabilities. Shell
/// integration additionally augments supported interactive shells so the
/// terminal can observe lifecycle events such as prompts and command boundaries.
public final class Shell {
    private Shell() {}

    /// Prepares the terminal environment and applies shell integration when the
    /// shell is supported.
    ///
    /// The returned environment declares `xterm-256color` capabilities and true
    /// color support, and removes an inherited `VTE_VERSION` because GhosttyFX is
    /// not a VTE terminal. If the command is not recognized as a supported shell,
    /// the original command is returned with only these environment changes.
    ///
    /// Supported shells are Bash, Cmd, Fish, PowerShell, and Zsh.
    ///
    /// @param command the command used to start the shell
    /// @param environment the environment used to start the shell
    /// @return the command and environment to use when launching the shell
    /// @throws NullPointerException if `command` or `environment` is `null`
    /// @throws IllegalArgumentException if `command` is empty
    public static Launcher integrate(List<String> command, Map<String, String> environment) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environment, "environment");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        var terminalEnvironment = new LinkedHashMap<>(environment);
        terminalEnvironment.put("TERM", "xterm-256color");
        terminalEnvironment.put("COLORTERM", "truecolor");
        terminalEnvironment.remove("VTE_VERSION");

        var executable = command.getFirst();
        var separator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        var name = executable.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (name.equals("bash")) {
            return integrateBash(command, terminalEnvironment);
        }
        if (name.equals("cmd") || name.equals("cmd.exe")) {
            return integrateCmd(command, terminalEnvironment);
        }
        if (name.equals("fish")) {
            return integrateFish(command, terminalEnvironment);
        }
        if (name.equals("pwsh") || name.equals("pwsh.exe") || name.equals("powershell") || name.equals("powershell.exe")) {
            return integratePowershell(command, terminalEnvironment);
        }
        if (name.equals("zsh")) {
            return integrateZsh(command, terminalEnvironment);
        }

        return new Launcher(command, terminalEnvironment);
    }

    private static Launcher integrateBash(List<String> command, Map<String, String> environment) {
        var integratedCommand = new ArrayList<String>();
        integratedCommand.add(command.getFirst());
        integratedCommand.add("--posix");

        var inject = new StringBuilder("1");
        String rcfile = null;
        for (var i = 1; i < command.size(); i++) {
            var argument = command.get(i);
            if (argument.equals("--posix") || argument.startsWith("-") && !argument.startsWith("--") && argument.contains("c")) {
                return new Launcher(command, environment);
            }
            if (argument.equals("--norc") || argument.equals("--noprofile")) {
                inject.append(' ').append(argument);
                continue;
            }
            if (argument.equals("--rcfile") || argument.equals("--init-file")) {
                if (i + 1 == command.size()) {
                    return new Launcher(command, environment);
                }
                rcfile = command.get(++i);
                continue;
            }
            integratedCommand.add(argument);
            if (argument.equals("-") || argument.equals("--")) {
                integratedCommand.addAll(command.subList(i + 1, command.size()));
                break;
            }
        }

        var script = ResourceCache.extractZip(Shell.class, "/shell/bash.zip").resolve("ghostty.bash");
        var env = environment.get("ENV");
        if (env != null) {
            environment.put("GHOSTTY_BASH_ENV", env);
        }
        environment.put("ENV", script.toString());
        environment.put("GHOSTTY_BASH_INJECT", inject.toString());
        if (rcfile != null) {
            environment.put("GHOSTTY_BASH_RCFILE", rcfile);
        }
        if (!environment.containsKey("HISTFILE")) {
            environment.put("HISTFILE", System.getProperty("user.home") + "/.bash_history");
            environment.put("GHOSTTY_BASH_UNEXPORT_HISTFILE", "1");
        }
        return new Launcher(integratedCommand, environment);
    }

    private static Launcher integrateCmd(List<String> command, Map<String, String> environment) {
        var prompt = environment.getOrDefault("PROMPT", "$P$G");
        environment.put("PROMPT", "$e]133;D$e\\$e]133;A;redraw=last;cl=line$e\\$e]9;9;$P$e\\" + prompt + "$e]133;B$e\\");
        return new Launcher(command, environment);
    }

    private static Launcher integrateFish(List<String> command, Map<String, String> environment) {
        var resources = ResourceCache.extractZip(Shell.class, "/shell/fish.zip");
        environment.put("GHOSTTY_SHELL_INTEGRATION_XDG_DIR", resources.toString());
        environment.put("XDG_DATA_DIRS", resources + File.pathSeparator + environment.getOrDefault("XDG_DATA_DIRS", "/usr/local/share:/usr/share"));
        return new Launcher(command, environment);
    }

    private static Launcher integratePowershell(List<String> command, Map<String, String> environment) {
        try (var input = Shell.class.getResourceAsStream("/shell/pwsh/ghosttyfx.ps1")) {
            var script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            var integratedCommand = new ArrayList<>(command);
            integratedCommand.add("-NoExit");
            integratedCommand.add("-EncodedCommand");
            integratedCommand.add(encodedScript);
            return new Launcher(integratedCommand, environment);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Launcher integrateZsh(List<String> command, Map<String, String> environment) {
        var zdotdir = ResourceCache.extractZip(Shell.class, "/shell/zsh.zip");
        var existing = environment.get("ZDOTDIR");
        if (existing != null) {
            environment.put("GHOSTTY_ZSH_ZDOTDIR", existing);
        }
        environment.put("ZDOTDIR", zdotdir.toString());
        return new Launcher(command, environment);
    }

    /// A shell launch command and environment.
    ///
    /// @param command the command used to start the shell
    /// @param environment the environment used to start the shell
    public record Launcher(List<String> command, Map<String, String> environment) {}
}
